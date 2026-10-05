package com.petplatform.merchant.biz.infrastructure.persistence;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.biz.application.MerchantCanonicalParams;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantAgreementMapper;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantStaffMemberMapper;
import java.util.Objects;
import java.util.function.BiFunction;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Merchant-owned persistence for contract 54 binding writes. Supplement-23 bindings share the
 * merchant_command_idempotency table via MerchantAgreementStore; execution transactions are
 * READ_COMMITTED so the store guard and FOR UPDATE re-verification order like the schedule
 * write commands (binding CCR D4 approved mechanism).
 */
public final class MerchantStaffMemberStore {
    private final MerchantAgreementStore bindings;
    private final SqlSessionTemplate template;
    private final TransactionTemplate read;
    private final SnowflakeIdGenerator ids;

    public MerchantStaffMemberStore(DataSource source, SnowflakeIdGenerator ids) {
        this.bindings = new MerchantAgreementStore(source, ids);
        this.template = MerchantMybatis.joiningTemplate(source);
        this.ids = Objects.requireNonNull(ids);
        this.read = new TransactionTemplate(new DataSourceTransactionManager(source));
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setReadOnly(true);
        read.setTimeout(10);
    }

    public long nextId() {
        long value = ids.nextId();
        if (value <= 0) throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "ID provider failed");
        return value;
    }

    public MerchantAgreementStore.Binding admit(byte[] key,
            MerchantCanonicalParams.Canonical canonical, String traceId) {
        return bindings.admit(key, canonical, traceId);
    }

    public <T> T read(BiFunction<MerchantStaffMemberMapper, MerchantAgreementMapper, T> work) {
        try {
            return read.execute(status -> work.apply(template.getMapper(MerchantStaffMemberMapper.class),
                    template.getMapper(MerchantAgreementMapper.class)));
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "merchant staff member read unavailable");
        }
    }

    public <T> T execute(BiFunction<MerchantStaffMemberMapper, MerchantAgreementMapper, T> work) {
        return bindings.execute(agreements ->
                work.apply(template.getMapper(MerchantStaffMemberMapper.class), agreements));
    }
}
