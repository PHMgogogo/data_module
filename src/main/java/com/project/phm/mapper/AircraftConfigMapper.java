package com.project.phm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.project.phm.entity.Aircraft;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AircraftConfigMapper extends BaseMapper<Aircraft> {

    @Select("SELECT * FROM aircraft_config WHERE aircraft_number = #{aircraftNumber} FOR UPDATE")
    Aircraft selectByAircraftNumberForUpdate(@Param("aircraftNumber") String aircraftNumber);
}
