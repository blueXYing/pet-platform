package com.petplatform.refund.biz.infrastructure.persistence;

import com.petplatform.refund.biz.infrastructure.persistence.mapper.RefundExecutionMapper;
import com.petplatform.refund.biz.infrastructure.persistence.mapper.RefundMapperRows;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** REFUND-owned SQL. MyBatis joins the caller's transaction on this same DataSource. */
public final class RefundExecutionStore {
    private final RefundExecutionMapper mapper;

    public RefundExecutionStore(DataSource source) {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(Objects.requireNonNull(source, "source is required"));
        try {
            bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/RefundExecutionMapper.xml"));
            SqlSessionFactory factory = Objects.requireNonNull(bean.getObject());
            factory.getConfiguration().setMapUnderscoreToCamelCase(true);
            factory.getConfiguration().setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
            mapper = new SqlSessionTemplate(factory).getMapper(RefundExecutionMapper.class);
        } catch (Exception failure) {
            throw new IllegalStateException("REFUND MyBatis initialization failed", failure);
        }
    }

    public void sessionDefaults() { mapper.setUtcTimeZone(); mapper.setLockWaitTimeout(); }
    public LocalDateTime databaseNow() { return mapper.databaseNow(); }
    public void insertRefundOrder(long refundId, long refundNo, long orderId,
            BigDecimal amount, LocalDateTime now) {
        mapper.insertRefundOrder(refundId, refundNo, orderId, amount, now);
    }
    public void insertExecution(long refundId, long refundNo, long orderId, long paymentId,
            long paymentNo, long storeId, long merchantId, long userId,
            long paymentSuccessEventId, long lateEventId, String channelTradeNo,
            BigDecimal amount, LocalDateTime paidAt, String requestId, long createdEventId,
            LocalDateTime now) {
        mapper.insertExecution(refundId, refundNo, orderId, paymentId, paymentNo, storeId,
                merchantId, userId, paymentSuccessEventId, lateEventId, channelTradeNo,
                amount, paidAt, requestId, createdEventId, now);
    }
    public Integer successProofCount(long refundId, String receipt, String refundNo) {
        return mapper.successProofCount(refundId, receipt, refundNo);
    }
    public List<RefundMapperRows.Binding> lockById(long refundId) { return mapper.lockById(refundId); }
    public List<RefundMapperRows.Binding> lockByOrder(long orderId) { return mapper.lockByOrder(orderId); }
    public int markUnknown(long refundId) { return mapper.markUnknown(refundId); }
    public int setNextQuery(long refundId, LocalDateTime due) { return mapper.setNextQuery(refundId, due); }
    public LocalDateTime firstQueryAt(long refundId, LocalDateTime due) {
        return mapper.firstQueryAt(refundId, due);
    }
    public int setFirstQuery(long refundId, LocalDateTime first) {
        return mapper.setFirstQuery(refundId, first);
    }
    public int markRefundSucceeded(long refundId, String channelRefundNo, LocalDateTime at) {
        return mapper.markRefundSucceeded(refundId, channelRefundNo, at);
    }
    public int markExecutionSucceeded(long refundId, long eventId, String receipt) {
        return mapper.markExecutionSucceeded(refundId, eventId, receipt);
    }
    public int insertSuccessTransaction(long id, long refundId, String receipt,
            String action, String refundNo) {
        return mapper.insertSuccessTransaction(id, refundId, receipt, action, refundNo);
    }
    public int resolveIssue(long refundId) { return mapper.resolveIssue(refundId); }
    public int insertIssue(long refundId, String code) { return mapper.insertIssue(refundId, code); }
    public List<RefundMapperRows.Candidate> scanOpen(long after) { return mapper.scanOpen(after); }
}
