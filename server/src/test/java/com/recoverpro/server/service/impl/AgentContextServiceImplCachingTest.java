package com.recoverpro.server.service.impl;

import com.recoverpro.server.dto.response.AgentContextDto;
import com.recoverpro.server.repository.AllocationRepository;
import com.recoverpro.server.repository.AssignmentRepository;
import com.recoverpro.server.repository.CollectionRepository;
import com.recoverpro.server.repository.PtpRepository;
import com.recoverpro.server.service.AgentContextService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the real Spring caching proxy so the {@code @Cacheable} on
 * buildContext is actually verified: SYSTEM_DESIGN.md 6.5 requires Lucien's
 * per-agent context be "assembled once per session, cached in Redis" instead
 * of rebuilt on every chat turn.
 */
class AgentContextServiceImplCachingTest {

    @Configuration
    @EnableCaching
    static class CachingTestConfig {
        @Bean
        AssignmentRepository assignmentRepository() {
            return mock(AssignmentRepository.class);
        }

        @Bean
        CollectionRepository collectionRepository() {
            return mock(CollectionRepository.class);
        }

        @Bean
        PtpRepository ptpRepository() {
            return mock(PtpRepository.class);
        }

        @Bean
        AllocationRepository allocationRepository() {
            return mock(AllocationRepository.class);
        }

        @Bean
        org.springframework.cache.CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("lucienContext");
        }

        @Bean
        AgentContextServiceImpl agentContextServiceImpl(AssignmentRepository a, CollectionRepository c,
                                                          PtpRepository p, AllocationRepository al) {
            return new AgentContextServiceImpl(a, c, p, al);
        }
    }

    private AnnotationConfigApplicationContext ctx;
    private AssignmentRepository assignmentRepository;
    private AgentContextService service;

    @BeforeEach
    void setUp() {
        ctx = new AnnotationConfigApplicationContext(CachingTestConfig.class);
        assignmentRepository = ctx.getBean(AssignmentRepository.class);
        CollectionRepository collectionRepository = ctx.getBean(CollectionRepository.class);
        PtpRepository ptpRepository = ctx.getBean(PtpRepository.class);
        AllocationRepository allocationRepository = ctx.getBean(AllocationRepository.class);
        service = ctx.getBean(AgentContextService.class);

        lenient().when(assignmentRepository.findAllByAgentAndDate(any(), any())).thenReturn(List.of());
        lenient().when(ptpRepository.countByAgentIdAndStatus(any(), any())).thenReturn(0L);
        lenient().when(ptpRepository.countByAgentStatusAndDateRange(any(), any(), any(), any())).thenReturn(0L);
        lenient().when(collectionRepository.sumCollectionByAgentBetweenDates(any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        lenient().when(collectionRepository.countCollectionsByAgentBetweenDates(any(), any(), any()))
                .thenReturn(0L);
        lenient().when(allocationRepository.sumOutstandingByAgentId(any())).thenReturn(BigDecimal.ZERO);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    @Test
    void buildContext_secondCallForSameSession_skipsRepositories() {
        UUID agentId = UUID.randomUUID();

        AgentContextDto first = service.buildContext("session-1", agentId, "Ann");
        AgentContextDto second = service.buildContext("session-1", agentId, "Ann");

        assertThat(first).isSameAs(second);
        verify(assignmentRepository, times(1)).findAllByAgentAndDate(any(), any());
    }

    @Test
    void buildContext_differentSessions_rebuildsIndependently() {
        UUID agentId = UUID.randomUUID();

        service.buildContext("session-1", agentId, "Ann");
        service.buildContext("session-2", agentId, "Ann");

        verify(assignmentRepository, times(2)).findAllByAgentAndDate(any(), any());
    }

    /**
     * SYSTEM-PLAN 15.3: buildContext is TASK 15.3.b's own "expensive entry" example (several
     * repository queries per call). Before this task, {@code @Cacheable} on this method had no
     * {@code sync = true}, so N requests racing on the same never-before-seen sessionId would each
     * independently miss the cache and run the full query set. This proves that's no longer true --
     * a burst of concurrent callers for one cold session key produces exactly one backing
     * computation, matching TASK 15.3's literal acceptance criterion.
     */
    @Test
    void buildContext_concurrentColdSession_collapsesToOneComputation() throws Exception {
        UUID agentId = UUID.randomUUID();
        int threads = 10;

        when(assignmentRepository.findAllByAgentAndDate(any(), any())).thenAnswer(inv -> {
            Thread.sleep(50); // widen the race window so concurrent misses are actually likely
            return List.<com.recoverpro.server.entity.Assignment>of();
        });

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<AgentContextDto>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return service.buildContext("cold-session", agentId, "Ann");
            }));
        }
        ready.await();
        go.countDown();

        List<AgentContextDto> results = new ArrayList<>();
        for (Future<AgentContextDto> f : futures) {
            results.add(f.get(5, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(results).allMatch(r -> r == results.get(0));
        verify(assignmentRepository, times(1)).findAllByAgentAndDate(any(), any());
    }
}
