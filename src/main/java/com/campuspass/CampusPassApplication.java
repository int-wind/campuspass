package com.campuspass;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@MapperScan("com.campuspass")
@ConfigurationPropertiesScan
public class CampusPassApplication {
    public static void main(String[] args) {
        SpringApplication.run(CampusPassApplication.class, args);
    }
}
