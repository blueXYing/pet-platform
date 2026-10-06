package com.petplatform.merchant.biz.infrastructure.persistence;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantStaffIdentityMapper;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Read-only member/grant resolution over the merchant-owned datasource. The action gate joins
 * the caller's store-guarded transaction; facts and membership reads run in their own
 * repeatable-read transaction. No binding writer exists in this slice (supplement 27 storage §2).
 */
public final class MerchantStaffIdentityStore {
    private final SqlSessionTemplate template;
    private final TransactionTemplate read;
    private final MerchantAgreementStore agreements;
    private final DataSource source;

    public MerchantStaffIdentityStore(DataSource source) {
        this.source = Objects.requireNonNull(source, "source is required");
        this.template = MerchantMybatis.joiningTemplate(source);
        this.agreements = new MerchantAgreementStore(source);
        this.read = new TransactionTemplate(new DataSourceTransactionManager(source));
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setReadOnly(true);
        read.setTimeout(10);
    }

    public DataSource source() {
        return source;
    }

    /** Joins the caller's active transaction; used by the guard-held action gate. */
    public MerchantStaffIdentityMapper joining() {
        return template.getMapper(MerchantStaffIdentityMapper.class);
    }

    public MerchantAgreementStore agreements() {
        return agreements;
    }

    public <T> T read(Function<MerchantStaffIdentityMapper, T> work) {
        try {
            return read.execute(status -> work.apply(template.getMapper(MerchantStaffIdentityMapper.class)));
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "merchant staff identity facts are unavailable");
        }
    }

    public <T> T read(BiFunction<MerchantStaffIdentityMapper, MerchantAgreementStore, T> work) {
        return read(mapper -> work.apply(mapper, agreements));
    }
}
