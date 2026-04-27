package com.project.phm.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Select;

import com.project.phm.entity.User;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper {
    @Select("SELECT id, username FROM users")
    List<User> listUsers();
}
