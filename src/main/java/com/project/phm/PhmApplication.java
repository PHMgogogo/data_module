package com.project.phm;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@MapperScan("com.project.phm.mapper")
public class PhmApplication {

    public static void main(String[] args) {
        SpringApplication.run(PhmApplication.class, args);
    }
}
