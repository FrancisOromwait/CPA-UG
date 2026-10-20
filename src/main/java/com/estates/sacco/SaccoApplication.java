package com.estates.sacco;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SaccoApplication {
    public static void main(String[] args) {
        SpringApplication.run(SaccoApplication.class, args);
    }
}
