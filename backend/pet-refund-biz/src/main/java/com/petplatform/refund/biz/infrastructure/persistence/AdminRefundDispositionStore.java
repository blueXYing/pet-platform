package com.petplatform.refund.biz.infrastructure.persistence;

import com.petplatform.refund.biz.infrastructure.persistence.mapper.AdminRefundDispositionMapper;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * REFUND-owned SQL for the contract-63 v0.2 disposal actions. Joins the caller's transaction on
 * the same DataSource (no independent transaction): the audit row and the durable-task trigger
 * commit atomically or not at all.
 */
public final class AdminRefundDispositionStore {
    private final SqlSessionTemplate sql;

    public AdminRefundDispositionStore(DataSource source) {
        Objects.requireNonNull(source, "source is required");
        try {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            Configuration configuration = new Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/AdminRefundDispositionMapper.xml"));
            sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        } catch (Exception failure) {
            throw new IllegalStateException("admin refund disposition mapper initialization failed", failure);
        }
    }

    /** Plain committed read used to locate the store before the store guard can be acquired. */
    public String locateStore(long refundOrderId) {
        return sql.getMapper(AdminRefundDispositionMapper.class).locateStore(refundOrderId);
    }

    public ActionRow byRequest(String requestId) {
        var rows = sql.getMapper(AdminRefundDispositionMapper.class).selectByRequest(requestId);
        if (rows.size() > 1) throw new IllegalStateException("duplicate refund admin action request");
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        return new ActionRow(row.id, row.refundOrderId, row.orderId, row.actionType, row.taskKey,
                row.taskState, row.refundStatus, row.actedByOperatorId, row.actedAt,
                row.authzVersion, row.requestId, row.traceId);
    }

    public void insert(long id, long refundOrderId, long orderId, String actionType, String taskKey,
            String taskState, String refundStatus, long operatorId, LocalDateTime actedAt,
            String authzVersion, String requestId, String traceId) {
        if (sql.getMapper(AdminRefundDispositionMapper.class).insertAction(id, refundOrderId, orderId,
                actionType, taskKey, taskState, refundStatus, operatorId, actedAt, authzVersion,
                requestId, traceId, actedAt) != 1) {
            throw new IllegalStateException("refund admin action insert failed");
        }
    }

    public record ActionRow(
            long id, long refundOrderId, long orderId, String actionType, String taskKey,
            String taskState, String refundStatus, long actedByOperatorId, LocalDateTime actedAt,
            String authzVersion, String requestId, String traceId) {}
}
