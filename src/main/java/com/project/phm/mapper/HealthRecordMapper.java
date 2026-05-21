package com.project.phm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.project.phm.entity.HealthRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface HealthRecordMapper extends BaseMapper<HealthRecord> {
}
