package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CHttpModels.Request;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.user.api.command.AddressCommands.CreateAddress;
import com.petplatform.user.api.command.AddressCommands.DeleteAddress;
import com.petplatform.user.api.command.AddressCommands.SetDefaultAddress;
import com.petplatform.user.api.command.AddressCommands.UpdateAddress;
import com.petplatform.user.api.dto.AddressView;
import com.petplatform.user.biz.application.AddressService;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/c/addresses CRUD + set-default (contract 59 via HTTP10 §3.2.2): the AddressService
 * kernel unchanged — this adapter only maps the session principal into CommandContext(USER)
 * and the wire fields into commands. POST create is 201 on first execution and 200 on
 * same-key idempotent replay (supplement 23 §5); set-default is a bodyless decision route.
 */
@RestController
@RequestMapping("/api/v1/c/addresses")
@ConditionalOnProperty(prefix = "pet.auth.c", name = "enabled", havingValue = "true")
public class CAddressController {

    private static final Set<String> UPSERT_FIELDS = Set.of(
            "contactName", "contactPhone", "region", "detail", "isDefault");

    private final AddressService addresses;

    public CAddressController(AddressService addresses) {
        this.addresses = addresses;
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

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest req) {
        MiniSessionView view = session(req);
        List<Map<String, Object>> items = addresses.listActiveAddresses(view.userId()).stream()
                .map(CAddressController::toBody).toList();
        return ApiResponse.success(items, CShared.trace(req));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(
            @RequestBody Request body, HttpServletRequest req, HttpServletResponse response) {
        body.require(UPSERT_FIELDS, "contactName", "contactPhone", "region", "detail");
        MiniSessionView view = session(req);
        CreateAddress command = new CreateAddress(
                context(req, view),
                body.string("contactName"), body.string("contactPhone"), body.string("region"),
                body.string("detail"), Boolean.TRUE.equals(body.bool("isDefault")));
        AddressService.CommandOutcome<AddressView> outcome = addresses.createAddressOutcome(command);
        response.setStatus(outcome.replayed() ? 200 : 201);
        return ApiResponse.success(toBody(outcome.receipt()), CShared.trace(req));
    }

    @GetMapping("/{addressId}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String addressId, HttpServletRequest req) {
        MiniSessionView view = session(req);
        return ApiResponse.success(toBody(addresses.getAddress(addressId, view.userId())), CShared.trace(req));
    }

    @PutMapping("/{addressId}")
    public ApiResponse<Map<String, Object>> update(
            @PathVariable String addressId, @RequestBody Request body, HttpServletRequest req) {
        body.require(UPSERT_FIELDS, "contactName", "contactPhone", "region", "detail");
        MiniSessionView view = session(req);
        UpdateAddress command = new UpdateAddress(
                context(req, view),
                addressId,
                body.string("contactName"), body.string("contactPhone"), body.string("region"),
                body.string("detail"), body.bool("isDefault"));
        return ApiResponse.success(toBody(addresses.updateAddress(command)), CShared.trace(req));
    }

    @DeleteMapping("/{addressId}")
    public ApiResponse<Map<String, Object>> delete(
            @PathVariable String addressId, HttpServletRequest req) {
        MiniSessionView view = session(req);
        return ApiResponse.success(
                receipt(addresses.deleteAddress(new DeleteAddress(context(req, view), addressId))),
                CShared.trace(req));
    }

    @PostMapping("/{addressId}/set-default")
    public ApiResponse<Map<String, Object>> setDefault(
            @PathVariable String addressId, HttpServletRequest req) {
        MiniSessionView view = session(req);
        return ApiResponse.success(
                toBody(addresses.setDefaultAddress(new SetDefaultAddress(context(req, view), addressId))),
                CShared.trace(req));
    }

    // ------------------------------------------------------------------ mapping

    /** Wire form per contract 59: IDs as decimal String, owner-scoped plaintext projection. */
    private static Map<String, Object> toBody(AddressView address) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("addressId", address.addressId());
        body.put("contactName", address.contactName());
        body.put("contactPhone", address.contactPhone());
        body.put("region", address.region());
        body.put("detail", address.detail());
        body.put("isDefault", address.isDefault());
        body.put("status", address.status());
        return body;
    }

    private static Map<String, Object> receipt(com.petplatform.user.api.command.AddressCommands.AddressReceipt address) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("addressId", address.addressId());
        body.put("status", address.status());
        return body;
    }
}
