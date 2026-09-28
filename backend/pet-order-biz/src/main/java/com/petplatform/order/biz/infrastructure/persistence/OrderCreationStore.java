package com.petplatform.order.biz.infrastructure.persistence;

import com.petplatform.order.biz.infrastructure.persistence.entity.OrderCreationBinding;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderCreationMapper;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** SQL38 ORDER-owned write adapter. No other domain's persistence is accessed here. */
public final class OrderCreationStore {
    private final OrderCreationMapper mapper;

    public OrderCreationStore(DataSource source) {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(Objects.requireNonNull(source));
        try {
            bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/*.xml"));
            SqlSessionFactory factory = bean.getObject();
            factory.getConfiguration().setMapUnderscoreToCamelCase(true);
            mapper = new SqlSessionTemplate(factory).getMapper(OrderCreationMapper.class);
        } catch (Exception failure) {
            throw new IllegalStateException("ORDER MyBatis initialization failed", failure);
        }
    }

    public void sessionDefaults() { mapper.setUtcTimeZone(); mapper.setLockWaitTimeout(); }
    public void insertBinding(Map<String, Object> values) { mapper.insertBinding(values); }
    public OrderCreationBinding lockBinding(byte[] key) { return mapper.selectBindingForUpdate(key); }
    public void succeed(byte[] key, String receipt) {
        if (mapper.succeedBinding(key, receipt) != 1) {
            throw new IllegalStateException("ORDER success receipt and binding state diverged");
        }
    }
    public Long owner(long orderId) { return mapper.selectOrderOwner(orderId); }
    public void order(Map<String, Object> values) { mapper.insertOrder(values); }
    public void serviceSnapshot(Map<String, Object> values) { mapper.insertServiceSnapshot(values); }
    public void petSnapshot(Map<String, Object> values) { mapper.insertPetSnapshot(values); }
    public void inputSnapshot(Map<String, Object> values) { mapper.insertInputSnapshot(values); }
    public void statusLog(Map<String, Object> values) { mapper.insertStatusLog(values); }
    public void creationAudit(Map<String, Object> values) { mapper.insertCreationAudit(values); }
}
