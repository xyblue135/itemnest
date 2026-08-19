package com.xyblue.itemnest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class ItemNestApplication {
    public static void main(String[] args) {
        SpringApplication.run(ItemNestApplication.class, args);
    }
}
