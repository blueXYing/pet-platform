package com.petplatform.user.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.user.biz.infrastructure.persistence.CommandIdempotencyStore;
import com.petplatform.user.biz.infrastructure.persistence.SessionControl;
import com.petplatform.user.biz.infrastructure.persistence.UserAuthStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GET/PUT /api/v1/c/profile (HTTP10 §3.2.1): masked phone only, updates idempotent per
 * supplement 23 §5 through the same binding store the pet commands use, USER_FROZEN rejection
 * for writes. No phone rebinding here — that lives with the auth slice.
 */
public final class UserProfileService {

    /** Response fields exactly per §3.2.1. */
    public record ProfileView(
            String userId, String nickname, String avatarUrl,
            String phoneMasked, boolean passwordEnabled) {}

    private final UserAuthStore store;
    private final CommandIdempotencyStore idempotency;
    private final SessionControl sessionControl;
    private final TransactionTemplate execution;

    public UserProfileService(DataSource dataSource, SnowflakeIdGenerator ids) {
        this.store = new UserAuthStore(dataSource);
        this.idempotency = new CommandIdempotencyStore(dataSource, ids);
        this.sessionControl = new SessionControl(dataSource);
        this.execution = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        execution.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        execution.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        execution.setTimeout(10);
    }

    public ProfileView getProfile(String userIdText) {
        long userId = numeric(userIdText);
        UserAuthStore.AccountRow account = store.findAccount(userId, false);
        if (account == null) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        return toView(account);
    }

    public ProfileView updateProfile(CommandContext context, String nickname, String avatarUrl) {
        CommandContext checked = trustedUserContext(context);
        if ((nickname == null || nickname.isEmpty()) && (avatarUrl == null || avatarUrl.isEmpty())) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
        }
        PetValidator.text(nickname == null || nickname.isEmpty() ? "x" : nickname, 64, "nickname");
        if (avatarUrl != null && !avatarUrl.isEmpty()) PetValidator.avatar(avatarUrl);
        long userId = numeric(checked.operatorId());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("avatarUrl", avatarUrl);
        params.put("nickname", nickname);
        return withIdempotency("profile.update", checked, params, ignored -> {
            UserAuthStore.AccountRow account = store.findAccount(userId, true);
            if (account == null) {
                throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
            }
            requireActiveUser(account);
            store.updateProfile(userId,
                    nickname == null || nickname.isEmpty() ? null : nickname,
                    avatarUrl == null || avatarUrl.isEmpty() ? null : avatarUrl);
            return toView(store.findAccount(userId, false));
        }, updated -> {
            UserAuthStore.AccountRow account = store.findAccount(userId, false);
            if (account == null) {
                throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
            }
            requireActiveUser(account);
            return null;
        });
    }

    // ------------------------------------------------------------------ frame

    /** Same supplement 23 §5 frame the pet commands use, scoped to this namespace. */
    private ProfileView withIdempotency(String namespace, CommandContext context,
                                        Map<String, Object> params,
                                        Function<Void, ProfileView> body,
                                        Function<ProfileView, Void> replayRevalidation) {
        String requestKey = namespace + "|USER|" + context.operatorId() + "|USER_SELF|" + context.requestId();
        CanonicalParams.Canonical canonical = CanonicalParams.of(params);
        Optional<String> replay;
        try {
            replay = idempotency.admit(requestKey, canonical);
        } catch (CommandIdempotencyStore.ParamsConflict conflict) {
            throw new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, "相同 requestId 对应不同参数");
        }
        if (replay.isPresent()) {
            ProfileView receipt = deserialize(replay.orElseThrow());
            replayRevalidation.apply(receipt);
            return receipt;
        }
        return execution.execute(status -> {
            sessionControl.applyExecutionDefaults();
            idempotency.lockForExecution(requestKey);
            ProfileView result = body.apply(null);
            idempotency.succeed(requestKey, serialize(result));
            return result;
        });
    }

    private static CommandContext trustedUserContext(CommandContext context) {
        CommandContext checked = PublicContractChecks.requireCommandRequestId(context);
        if (checked.operatorType() != OperatorType.USER) {
            throw new IllegalArgumentException("Profile commands require a USER operator");
        }
        return checked;
    }

    private static void requireActiveUser(UserAuthStore.AccountRow account) {
        if (!"ACTIVE".equals(account.status())) {
            throw new ApiException("USER_FROZEN", "账号不可用，拒绝写入");
        }
    }

    private static long numeric(String value) {
        try {
            return PetService.numericId(value, "userId");
        } catch (RuntimeException e) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
    }

    private static ProfileView toView(UserAuthStore.AccountRow account) {
        return new ProfileView(Long.toUnsignedString(account.id()), account.nickname(),
                account.avatarUrl(), UserAuthService.mask(account.phone()), account.passwordEnabled());
    }

    private String serialize(ProfileView value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Receipt serialization failed; transaction rolled back", e);
        }
    }

    private ProfileView deserialize(String receiptJson) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(receiptJson, ProfileView.class);
        } catch (Exception e) {
            throw new IllegalStateException("Stored receipt is not readable", e);
        }
    }
}
