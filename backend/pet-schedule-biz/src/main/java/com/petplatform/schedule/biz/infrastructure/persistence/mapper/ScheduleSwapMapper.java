package com.petplatform.schedule.biz.infrastructure.persistence.mapper;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
public interface ScheduleSwapMapper {
 Map<String,Object> parent(@Param("id") long id);
 String snapshot(@Param("change") long change,@Param("order") long order,@Param("reservation") long reservation,@Param("store") long store,@Param("version") long version);
 int swap(Map<String,Object> values);
 int claim(Map<String,Object> values);
 int history(Map<String,Object> values);
}
