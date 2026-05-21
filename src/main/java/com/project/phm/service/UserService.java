package com.project.phm.service;

import com.project.phm.entity.User;
import com.project.phm.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class UserService {
    @Autowired
    private UserMapper userMapper;

    public List<User> listUsers() {
        return userMapper.selectList(null);
    }
}
