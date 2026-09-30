package com.petplatform.thirdparty.biz.infrastructure.persistence;

import com.petplatform.thirdparty.biz.infrastructure.persistence.mapper.*;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.mybatis.spring.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class AfterSaleAssetStore {
    public record Tx(PrivateAssetMapper assets,AfterSaleAssetMapper grants) {}
    private final SqlSessionTemplate sessions;
    private final TransactionTemplate transactions;
    public AfterSaleAssetStore(DataSource source) {
        Objects.requireNonNull(source);
        try {
            var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
            var config=new org.apache.ibatis.session.Configuration();config.setMapUnderscoreToCamelCase(true);factory.setConfiguration(config);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
            sessions=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        } catch(Exception e){throw new IllegalStateException("AFS private-asset persistence unavailable",e);}
        transactions=new TransactionTemplate(new DataSourceTransactionManager(source));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);transactions.setTimeout(15);
    }
    public <T>T transaction(Function<Tx,T> work){return transactions.execute(status->read(work));}
    public <T>T read(Function<Tx,T> work){
        var assets=sessions.getMapper(PrivateAssetMapper.class);assets.setSessionTimeZoneUtc();
        return work.apply(new Tx(assets,sessions.getMapper(AfterSaleAssetMapper.class)));
    }
}
