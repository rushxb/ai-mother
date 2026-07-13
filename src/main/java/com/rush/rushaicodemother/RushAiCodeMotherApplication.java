package com.rush.rushaicodemother;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
@SpringBootApplication
@MapperScan("com.rush.rushaicodemother.mapper")
public class RushAiCodeMotherApplication {

    public static void main(String[] args) {
        SpringApplication.run(RushAiCodeMotherApplication.class, args);
    }

}
