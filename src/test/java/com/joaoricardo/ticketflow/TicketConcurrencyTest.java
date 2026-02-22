package com.joaoricardo.ticketflow;

import com.joaoricardo.ticketflow.domain.entity.Event;
import com.joaoricardo.ticketflow.domain.repository.EventRepository;
import com.joaoricardo.ticketflow.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
public class TicketConcurrencyTest {
    @Autowired private TicketService ticketService;
    @Autowired private EventRepository repository;

    @Test
    void testConcurrencyWithRetry() throws InterruptedException {
        //  criando evento com 10 ingressos
        Event event = new Event();
        event.setName("Rock in CC");
        event.setAvailableTickets(10);
        event = repository.save(event);
        Long eventId = event.getId();

        // simulando 50 pessoas tentando comprar os 10 ingressos ao mesmo tempo
        int threads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            executor.execute(() -> {
                try {
                    latch.await(); // Todas esperam o sinal
                    ticketService.purchase(eventId);
                    successCount.incrementAndGet();
                } catch (Exception ignored) {
                    // Aqui caem os erros de estoque esgotado após os retries
                }
            });
        }

        latch.countDown(); // aqui dá a largada
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // 3. Verificação
        Event finalEvent = repository.findById(eventId).orElseThrow();
        System.out.println("Vendas confirmadas: " + successCount.get());
        System.out.println("Estoque no Banco: " + finalEvent.getAvailableTickets());

        assertEquals(10, successCount.get(), "Deveria vender exatamente 10 ingressos");
        assertEquals(0, finalEvent.getAvailableTickets(), "O estoque deveria estar zerado");
    }
}
