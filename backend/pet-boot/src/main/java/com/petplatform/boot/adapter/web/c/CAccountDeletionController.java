package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CHttpModels.Request;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.user.biz.application.AccountDeletionService;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/c/account deletion face (contract 66 via HTTP10 §3.17): the precheck read projects
 * the kernel's blocker catalog verbatim (ARCH-005 — no adapter-side derivation), the deletion
 * write opens the v0.2 cooling period (account stays ACTIVE, the durable task executes the
 * CANCELED flip at expiry), the status read drives the "可撤销" prompt and the cancel write
 * CASes the cooling-period row back. Writes are 201 on first execution and 200 on same-key
 * idempotent replay (supplement 23 §5). This adapter never flips account state itself;
 * CANCELED sessions die through the auth slice's existing resolveSession check.
 */
@RestController
@RequestMapping("/api/v1/c/account")
@ConditionalOnProperty(prefix = "pet",
        name = {"auth.c.enabled", "usersettings.http.enabled", "merchant.application.enabled"},
        havingValue = "true")
public class CAccountDeletionController {

    private static final Set<String> DELETION_FIELDS = Set.of("acknowledged");
    private static final Set<String> CANCEL_FIELDS = Set.of();

    private final AccountDeletionService deletions;

    public CAccountDeletionController(AccountDeletionService deletions) {
        this.deletions = deletions;
    }

    private static MiniSessionView session(HttpServletRequest req) {
        Object attribute = req.getAttribute(CBearerSessionFilter.VIEW);
        if (!(attribute instanceof MiniSessionView view)) {
            throw new IllegalArgumentException("Session view missing");
        }
        return view;
    }

    private static CommandContext context(HttpServletRequest req, MiniSessionView view) {
        return new CommandContext(
                CShared.requestId(req), CShared.trace(req), OperatorType.USER,
                view.userId(), "C_MINIAPP");
    }

    @GetMapping("/deletion-precheck")
    public ApiResponse<Map<String, Object>> precheck(HttpServletRequest req) {
        MiniSessionView view = session(req);
        AccountDeletionService.PrecheckView precheck = deletions.precheck(view.userId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("blocked", precheck.blocked());
        data.put("blockers", precheck.blockers().stream()
                .map(CAccountDeletionController::toBody).toList());
        return ApiResponse.success(data, CShared.trace(req));
    }

    @PostMapping("/deletion")
    public ApiResponse<Map<String, Object>> delete(
            @RequestBody Request body, HttpServletRequest req, HttpServletResponse response) {
        body.require(DELETION_FIELDS, "acknowledged");
        Boolean acknowledged = body.bool("acknowledged");
        if (!Boolean.TRUE.equals(acknowledged)) {
            throw new IllegalArgumentException("acknowledged must be true");
        }
        MiniSessionView view = session(req);
        AccountDeletionService.DeletionOutcome outcome = deletions.deleteAccount(
                new AccountDeletionService.DeleteAccountCommand(context(req, view), true));
        response.setStatus(outcome.replayed() ? 200 : 201);
        AccountDeletionService.DeletionReceipt receipt = outcome.receipt();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", receipt.userId());
        data.put("status", receipt.status());
        data.put("requestedAt", receipt.requestedAt());
        data.put("effectiveAt", receipt.effectiveAt());
        data.put("cooldownDays", receipt.cooldownDays());
        return ApiResponse.success(data, CShared.trace(req));
    }

    @GetMapping("/deletion-status")
    public ApiResponse<Map<String, Object>> status(HttpServletRequest req) {
        MiniSessionView view = session(req);
        AccountDeletionService.DeletionStatusView status = deletions.deletionStatus(view.userId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status.status());
        if ("PENDING".equals(status.status())) {
            data.put("requestedAt", status.requestedAt());
            data.put("effectiveAt", status.effectiveAt());
            data.put("cooldownDays", status.cooldownDays());
        }
        return ApiResponse.success(data, CShared.trace(req));
    }

    @PostMapping("/deletion-cancel")
    public ApiResponse<Map<String, Object>> cancel(
            @RequestBody(required = false) Request body, HttpServletRequest req,
            HttpServletResponse response) {
        if (body != null) body.require(CANCEL_FIELDS);
        MiniSessionView view = session(req);
        AccountDeletionService.CancelOutcome outcome = deletions.cancelDeletion(
                new AccountDeletionService.CancelDeletionCommand(context(req, view)));
        response.setStatus(outcome.replayed() ? 200 : 201);
        AccountDeletionService.CancelReceipt receipt = outcome.receipt();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", receipt.userId());
        data.put("status", receipt.status());
        data.put("canceledAt", receipt.canceledAt());
        return ApiResponse.success(data, CShared.trace(req));
    }

    private static Map<String, Object> toBody(AccountDeletionService.Blocker blocker) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", blocker.code());
        body.put("label", blocker.label());
        body.put("detail", blocker.detail());
        return body;
    }
}
