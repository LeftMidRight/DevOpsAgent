package org.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * SSE 流式任务专用线程池，由 Spring 管理生命周期。
 */
@Configuration
public class StreamingExecutorConfig {

    @Bean(name = "agentStreamingTaskExecutor")
    public TaskExecutor agentStreamingTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("agent-sse-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(256);
        executor.initialize();
        return executor;
    }
}
