package com.petplatform.verification.biz.infrastructure.persistence;
import javax.sql.DataSource;
import org.mybatis.spring.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import com.petplatform.verification.biz.infrastructure.persistence.mapper.CredentialMapper;
public final class CredentialStore {
 public static CredentialMapper mapper(DataSource source){try{
  var bean=new SqlSessionFactoryBean();bean.setDataSource(source);bean.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/CredentialMapper.xml"));
  var factory=bean.getObject();factory.getConfiguration().setMapUnderscoreToCamelCase(true);factory.getConfiguration().setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
  return new SqlSessionTemplate(factory).getMapper(CredentialMapper.class);
 }catch(Exception failure){throw new IllegalStateException("Credential persistence unavailable");}}
}
