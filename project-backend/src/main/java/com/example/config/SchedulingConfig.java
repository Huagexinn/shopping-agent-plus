package com.example.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 启用定时任务，供超时订单关单任务使用。 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
