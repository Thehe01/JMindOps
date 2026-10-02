package com.kama.jmindops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class JMindOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(JMindOpsApplication.class, args);
    }

}
