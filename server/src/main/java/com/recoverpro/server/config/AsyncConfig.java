package com.recoverpro.server.config;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;

@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Value("${app.async.file-executor.core-pool-size:5}")
    private int fileCore;
    @Value("${app.async.file-executor.max-pool-size:20}")
    private int fileMax;
    @Value("${app.async.file-executor.queue-capacity:500}")
    private int fileQueue;

    @Value("${app.async.agent-executor.core-pool-size:4}")
    private int agentCore;
    @Value("${app.async.agent-executor.max-pool-size:10}")
    private int agentMax;
    @Value("${app.async.agent-executor.queue-capacity:200}")
    private int agentQueue;

    @Value("${app.async.report-executor.core-pool-size:4}")
    private int reportCore;
    @Value("${app.async.report-executor.max-pool-size:8}")
    private int reportMax;
    @Value("${app.async.report-executor.queue-capacity:50}")
    private int reportQueue;

    @Bean(name = "taskExecutor")
    @Override
    public Executor getAsyncExecutor() {
        // Lucien chat/tool-loop work is conversational, not batch -- worth finishing an
        // in-flight tool call on shutdown, but not worth making SIGTERM wait as long as the
        // file/report executors below.
        return build("agent-", agentCore, agentMax, agentQueue, 30);
    }

    @Bean(name = "fileProcessingExecutor")
    public Executor fileProcessingExecutor() {
        // SYSTEM 05 TASK 5.3: an in-flight file import/parse should be allowed to finish on
        // SIGTERM rather than being truncated mid-write.
        return build("file-proc-", fileCore, fileMax, fileQueue, 60);
    }

    @Bean(name = "reportingTaskExecutor")
    public Executor reportingTaskExecutor() {
        return build("report-", reportCore, reportMax, reportQueue, 60);
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("Uncaught exception in @Async method {}.{}(): {}",
                        method.getDeclaringClass().getSimpleName(),
                        method.getName(), ex.getMessage(), ex);
    }

    private Executor build(String prefix, int core, int max, int queue, int awaitTerminationSeconds) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix(prefix);
        executor.setTaskDecorator(mdcPropagatingDecorator());
        executor.setRejectedExecutionHandler(loggingAbortPolicy(prefix));
        // SYSTEM 05 TASK 5.3: without these two, Spring's shutdown just abandons in-flight
        // tasks on this executor immediately instead of waiting for them to finish -- the
        // in-flight-file-import-gets-truncated-on-SIGTERM gap the task calls out.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        executor.initialize();
        return executor;
    }

    static TaskDecorator mdcPropagatingDecorator() {
        return runnable -> {
            Map<String, String> captured = MDC.getCopyOfContextMap();
            java.util.UUID capturedOrgId = com.recoverpro.server.security.RlsOrgIdHolder.get();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (captured != null) MDC.setContextMap(captured); else MDC.clear();
                com.recoverpro.server.security.RlsOrgIdHolder.set(capturedOrgId);
                try {
                    runnable.run();
                } finally {
                    com.recoverpro.server.security.RlsOrgIdHolder.clear();
                    if (previous != null) MDC.setContextMap(previous); else MDC.clear();
                }
            };
        };
    }

    private RejectedExecutionHandler loggingAbortPolicy(String prefix) {
        return (r, pool) -> {
            log.error("Executor [{}] queue full (active={}, queue={}/{}), task rejected",
                    prefix, pool.getActiveCount(),
                    pool.getQueue().size(), pool.getQueue().remainingCapacity());
            throw new java.util.concurrent.RejectedExecutionException(
                    "Executor " + prefix + " is at capacity");
        };
    }
}
