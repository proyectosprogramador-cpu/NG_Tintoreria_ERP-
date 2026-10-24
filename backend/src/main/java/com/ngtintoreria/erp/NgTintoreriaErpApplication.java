package com.ngtintoreria.erp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NgTintoreriaErpApplication {
    public static void main(String[] args) {
        SpringApplication.run(NgTintoreriaErpApplication.class, args);
    }
}
