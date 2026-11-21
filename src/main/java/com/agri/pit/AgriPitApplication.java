package com.agri.pit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AgriPitApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgriPitApplication.class, args);
    }
}
