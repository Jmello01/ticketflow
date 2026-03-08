package com.joaoricardo.ticketflow.domain.controller;

import com.joaoricardo.ticketflow.domain.dto.SimulationResult;
import com.joaoricardo.ticketflow.domain.entity.Event;
import com.joaoricardo.ticketflow.domain.repository.EventRepository;
import com.joaoricardo.ticketflow.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate; // IMPORTANTE
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/simulation")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;
    private final EventRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    @PostMapping("/run")
    public SimulationResult runSimulation(@RequestParam int threads, @RequestParam int tickets) throws InterruptedException {
        // Setup do Evento no Banco
        Event event = new Event();
        event.setName("Simulação Concorrente");
        event.setAvailableTickets(tickets);
        event = repository.save(event);

        // Pegamos o ID primeiro, depois mandamos pro Redis
        Long eventId = event.getId();
        redisTemplate.opsForValue().set("event:" + eventId + ":stock", String.valueOf(tickets));

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        List<String> logs = Collections.synchronizedList(new ArrayList<>());

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < threads; i++) {
            int threadId = i;
            executor.execute(() -> {
                try {
                    latch.await();

                    // 1. Valida no Redis (Rápido)
                    Long remaining = redisTemplate.opsForValue().decrement("event:" + eventId + ":stock");

                    if (remaining != null && remaining >= 0) {
                        // 2. Envia para a Fila (Assíncrono)
                        rabbitTemplate.convertAndSend("tickets.queue", eventId);
                        successCount.incrementAndGet();
                        logs.add("Pedido enviado para a fila!");
                    } else {
                        logs.add("Esgotado no Redis");
                    }
                } catch (Exception e) {
                    logs.add("Erro: " + e.getMessage());
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        long endTime = System.currentTimeMillis();

        return new SimulationResult(
                threads,
                successCount.get(),
                threads - successCount.get(),
                (endTime - startTime),
                logs
        );
    }
}