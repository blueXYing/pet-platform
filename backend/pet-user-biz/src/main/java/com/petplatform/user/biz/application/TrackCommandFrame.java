package com.petplatform.user.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.user.biz.infrastructure.persistence.CommandIdempotencyStore;
import com.petplatform.user.biz.infrastructure.persistence.SessionControl;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Supplement 23 §5 idempotent-write frame shared by the Contract 58 favorite/footprint
 * services (the same admit → lock → execute → succeed protocol PetService uses): the first
 * success receipt is bound to the requestId, a same-params retry replays it, and the same
 * requestId with different protected parameters is IDEMPOTENCY_KEY_CONFLICT.
 */
final class TrackCommandFrame {
    private static final ObjectMapper CODEC = new ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final CommandIdempotencyStore idempotency;
    private final SessionControl sessionControl;
    private final TransactionTemplate execution;

    TrackCommandFrame(DataSource dataSource, com.petplatform.common.SnowflakeIdGenerator ids) {
        this.idempotency = new CommandIdempotencyStore(dataSource, ids);
        this.sessionControl = new SessionControl(dataSource);
        this.execution = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        execution.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        execution.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        execution.setTimeout(10);
    }

    record Framed<R>(R receipt, boolean replayed) {}

    <R> Framed<R> execute(String namespace, CommandContext context, Map<String, Object> params,
            Class<R> receiptType, Function<Void, R> body, Function<R, Void> replayRevalidation) {
        CommandContext checked = PublicContractChecks.requireCommandRequestId(context);
        if (checked.operatorId() == null || checked.operatorId().isEmpty()) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        String requestKey = namespace + "|USER|" + checked.operatorId() + "|USER_SELF|"
                + checked.requestId();
        CanonicalParams.Canonical canonical = CanonicalParams.of(params);
        Optional<String> replay;
        try {
            replay = idempotency.admit(requestKey, canonical);
        } catch (CommandIdempotencyStore.ParamsConflict conflict) {
            throw new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, "相同 requestId 对应不同参数");
        }
        if (replay.isPresent()) {
            R receipt = deserialize(replay.orElseThrow(), receiptType);
            replayRevalidation.apply(receipt);
            return new Framed<>(receipt, true);
        }
        R result = execution.execute(status -> {
            sessionControl.applyExecutionDefaults();
            idempotency.lockForExecution(requestKey);
            R value = body.apply(null);
            idempotency.succeed(requestKey, serialize(value));
            return value;
        });
        return new Framed<>(result, false);
    }

    private String serialize(Object value) {
        try {
            return CODEC.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Receipt serialization failed; transaction rolled back", error);
        }
    }

    private <R> R deserialize(String receiptJson, Class<R> receiptType) {
        try {
            return CODEC.readValue(receiptJson, receiptType);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Stored receipt is not readable", error);
        }
    }

    static <T> T require(T value) {
        return Objects.requireNonNull(value);
    }
}
