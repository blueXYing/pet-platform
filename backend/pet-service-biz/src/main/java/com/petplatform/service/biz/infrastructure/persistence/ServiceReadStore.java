package com.petplatform.service.biz.infrastructure.persistence;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.service.biz.infrastructure.persistence.mapper.ServiceReadMapper;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes every service read unit in one read-only MySQL repeatable-read snapshot. Merchant
 * display-eligibility reads join this transaction (joining template on the merchant side), so
 * service, merchant, store, application and agreement facts come from one snapshot. After
 * visibility passes, cover signing separately locks the current private-asset facts.
 */
public final class ServiceReadStore {
    private final SqlSessionTemplate template;
    private final TransactionTemplate transaction;

    public ServiceReadStore(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource is required");
        this.template = ServiceMybatis.template(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        transaction.setTimeout(10);
    }

    public <T> T read(Function<ServiceReadMapper, T> work) {
        Objects.requireNonNull(work, "work is required");
        try {
            return transaction.execute(status -> work.apply(template.getMapper(ServiceReadMapper.class)));
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "service read dependency unavailable");
        }
    }
}
