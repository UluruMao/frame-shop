package com.iflytek.frame;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

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

    /**
     * Multi-threaded scheduler — the default {@code @EnableScheduling} uses a single thread,
     * which means a long-running {@code drain()} blocks {@code reclaimStalled()} and vice versa.
     * This pool allows concurrent scheduled tasks.
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("frame-scheduler-");
        return scheduler;
    }

    public static void main(String[] args) {
        SpringApplication.run(FrameBackendApplication.class, args);
    }
}
