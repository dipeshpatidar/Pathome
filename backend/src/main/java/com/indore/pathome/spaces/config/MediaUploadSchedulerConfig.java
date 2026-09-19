package com.indore.pathome.spaces.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class MediaUploadSchedulerConfig {

    @Bean(name = "mediaClaimTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler mediaClaimTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("media-claim-heartbeat-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        scheduler.initialize();
        return scheduler;
    }
}
