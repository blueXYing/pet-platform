package com.petplatform.order.biz.infrastructure.persistence;
import javax.sql.DataSource;
import org.mybatis.spring.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderAfterSaleMapper;
public final class OrderAfterSaleStore {
 public static OrderAfterSaleMapper mapper(DataSource s){try{var b=new SqlSessionFactoryBean();b.setDataSource(s);b.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/OrderAfterSaleMapper.xml"));var f=b.getObject();f.getConfiguration().setMapUnderscoreToCamelCase(true);f.getConfiguration().setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);return new SqlSessionTemplate(f).getMapper(OrderAfterSaleMapper.class);}catch(Exception e){throw new IllegalStateException("ORDER AFS persistence unavailable",e);}}
}
