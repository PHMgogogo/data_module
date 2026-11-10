package com.project.phm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.project.phm.entity.AircraftConfigItemLife;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AircraftConfigItemLifeMapper extends BaseMapper<AircraftConfigItemLife> {

    @Select("SELECT * FROM aircraft_config_item_life "
            + "WHERE aircraft_number = #{aircraftNumber} ORDER BY item_id FOR UPDATE")
    List<AircraftConfigItemLife> selectByAircraftNumberForUpdate(
            @Param("aircraftNumber") String aircraftNumber);

    @Select("SELECT * FROM aircraft_config_item_life WHERE life_id = #{lifeId} FOR UPDATE")
    AircraftConfigItemLife selectByLifeIdForUpdate(@Param("lifeId") Long lifeId);
}
