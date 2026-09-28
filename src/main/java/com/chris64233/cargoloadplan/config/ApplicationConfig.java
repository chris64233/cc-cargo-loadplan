package com.chris64233.cargoloadplan.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 通用基础设施 Bean。 */
@Configuration
public class ApplicationConfig {

    /** 统一时钟，便于版本时间戳生成，测试中可替换为固定时钟。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
