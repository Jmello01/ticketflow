package com.joaoricardo.ticketflow.service;

import com.joaoricardo.ticketflow.domain.dto.ComparisonResult;
import com.joaoricardo.ticketflow.domain.dto.StrategyResult;
import com.joaoricardo.ticketflow.domain.entity.Event;
import com.joaoricardo.ticketflow.domain.repository.EventRepository;
import com.joaoricardo.ticketflow.infrastructure.RabbitConfig;
import com.joaoricardo.ticketflow.service.strategy.OptimisticLockStrategy;
import com.joaoricardo.ticketflow.service.strategy.Outcome;
import com.joaoricardo.ticketflow.service.strategy.RedisAtomicStrategy;
import com.joaoricardo.ticketflow.service.strategy.StockStrategy;
import com.joaoricardo.ticketflow.service.strategy.UnprotectedStrategy;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Roda a mesma disputa em cada estratégia, cada uma com seu próprio evento e estoque,
 * para que uma não interfira na outra.
 */
@Service
@Slf4j
public class ComparisonService {

    private static final long DRAIN_TIMEOUT_MS = 15_000;

    private final List<StockStrategy> strategies;
    private final EventRepository repository;
    private final StringRedisTemplate redis;
    private final RabbitTemplate rabbit;
    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;

    public ComparisonService(UnprotectedStrategy unprotected,
                             OptimisticLockStrategy optimistic,
                             RedisAtomicStrategy atomic,
                             EventRepository repository,
                             StringRedisTemplate redis,
                             RabbitTemplate rabbit,
                             MeterRegistry registry,
                             JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.strategies = List.of(unprotected, optimistic, atomic);
        this.repository = repository;
        this.redis = redis;
        this.rabbit = rabbit;
        this.registry = registry;
    }

    public ComparisonResult compare(int threads, int tickets) throws InterruptedException {
        List<StrategyResult> results = new ArrayList<>();
        for (StockStrategy strategy : strategies) {
            results.add(run(strategy, threads, tickets));
        }
        return new ComparisonResult(threads, tickets, results);
    }

    private StrategyResult run(StockStrategy strategy, int threads, int tickets) throws InterruptedException {
        Event event = new Event();
        event.setName("Comparação: " + strategy.label());
        event.setAvailableTickets(tickets);
        Long eventId = repository.save(event).getId();
        redis.opsForValue().set(RedisAtomicStrategy.stockKey(eventId), String.valueOf(tickets));

        double processedBefore = processedByConsumer();
        ConcurrentLinkedQueue<Outcome> arrivals = new ConcurrentLinkedQueue<>();
        AtomicInteger published = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        long begin;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                executor.execute(() -> {
                    Outcome outcome;
                    try {
                        start.await();
                        outcome = strategy.tryBuy(eventId);
                        if (outcome == Outcome.WON && strategy.usesQueue()) {
                            rabbit.convertAndSend(RabbitConfig.QUEUE_NAME, eventId);
                            published.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        outcome = Outcome.ERROR;
                    } catch (Exception e) {
                        log.warn("[COMPARACAO] {} falhou: {}", strategy.id(), e.getMessage());
                        outcome = Outcome.ERROR;
                    }
                    arrivals.add(outcome);
                });
            }
            begin = System.nanoTime();
            start.countDown();
        } // o close() espera todas as threads terminarem
        long decisionMs = (System.nanoTime() - begin) / 1_000_000;

        boolean drained = awaitConsumer(processedBefore + published.get());
        // SQL direto: o findById devolveria a entidade em cache do contexto de persistência da requisição
        int databaseStock = jdbc.queryForObject(
                "select available_tickets from event where id = ?", Integer.class, eventId);

        List<String> outcomes = arrivals.stream().map(Outcome::code).toList();
        int sold = (int) arrivals.stream().filter(o -> o == Outcome.WON).count();
        int errors = (int) arrivals.stream().filter(o -> o == Outcome.ERROR).count();
        int rejected = outcomes.size() - sold - errors;

        return new StrategyResult(strategy.id(), strategy.label(), strategy.usesQueue(),
                sold, Math.max(0, sold - tickets), rejected, errors,
                decisionMs, drained, databaseStock, outcomes);
    }

    private double processedByConsumer() {
        return registry.counter("tickets.sold.database").count()
                + registry.counter("tickets.failed.database").count();
    }

    private boolean awaitConsumer(double target) throws InterruptedException {
        long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
        while (processedByConsumer() < target) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            Thread.sleep(20);
        }
        return true;
    }
}
