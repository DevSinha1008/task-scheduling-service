package com.scheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. @SpringBootApplication bundles three annotations:
 * @Configuration (this class can define beans), @EnableAutoConfiguration
 * (configure Tomcat, Hibernate, Jackson etc. from what is on the classpath),
 * and @ComponentScan (find @Component/@Service/@RestController below this
 * package — which is why every class lives under com.scheduler).
 */
@SpringBootApplication
public class SchedulerApplication {
    public static void main(String[] args) {
        SpringApplication.run(SchedulerApplication.class, args);
    }
}
