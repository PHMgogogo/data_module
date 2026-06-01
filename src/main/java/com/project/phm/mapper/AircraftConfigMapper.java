package com.project.phm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.project.phm.entity.Aircraft;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AircraftConfigMapper extends BaseMapper<Aircraft> {
}
