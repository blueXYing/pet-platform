package com.petplatform.verification.biz.infrastructure.persistence;
import javax.sql.DataSource;
import org.mybatis.spring.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import com.petplatform.verification.biz.infrastructure.persistence.mapper.VerificationCompletionMapper;
public final class VerificationCompletionStore {
 public static VerificationCompletionMapper mapper(DataSource source){try{
  var b=new SqlSessionFactoryBean();b.setDataSource(source);b.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/VerificationCompletionMapper.xml"));
  var f=b.getObject();f.getConfiguration().setMapUnderscoreToCamelCase(true);f.getConfiguration().setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
  return new SqlSessionTemplate(f).getMapper(VerificationCompletionMapper.class);
 }catch(Exception e){throw new IllegalStateException("Verification persistence unavailable");}}
}
