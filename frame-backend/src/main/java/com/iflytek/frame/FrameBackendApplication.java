package com.iflytek.frame;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Full-stack template — Spring Boot backend entry point.
 *
 * <p>Run with {@code mvn spring-boot:run} (requires JDK 21 + Maven + MariaDB).
 *
 * <p>Base package: {@code com.iflytek.frame}. Mappers live under {@code com.iflytek.frame.mapper}
 * and are picked up by {@link MapperScan}.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@MapperScan("com.iflytek.frame.mapper")
public class FrameBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(FrameBackendApplication.class, args);
    }
}
