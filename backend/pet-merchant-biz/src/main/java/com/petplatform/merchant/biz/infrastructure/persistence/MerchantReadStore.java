package com.petplatform.merchant.biz.infrastructure.persistence;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantReadMapper;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Executes every merchant read unit in one read-only MySQL repeatable-read snapshot. */
public final class MerchantReadStore {
    private final SqlSessionTemplate template;
    private final TransactionTemplate transaction;

    public MerchantReadStore(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource is required");
        this.template = MerchantMybatis.template(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        transaction.setTimeout(10);
    }

    public <T> T read(Function<MerchantReadMapper, T> work) {
        Objects.requireNonNull(work, "work is required");
        try {
            return transaction.execute(status -> work.apply(template.getMapper(MerchantReadMapper.class)));
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "merchant read dependency unavailable");
        }
    }
}
