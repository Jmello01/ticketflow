package com.joaoricardo.ticketflow.infrastructure;

import com.joaoricardo.ticketflow.service.TicketPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class TicketConsumer {

    private final TicketPersistenceService persistenceService;
    private final io.micrometer.core.instrument.MeterRegistry registry;

    @RabbitListener(queues = RabbitConfig.QUEUE_NAME)
    public void handleTicketPurchase(Long eventId) {
       try {
           persistenceService.persistPurchase(eventId);
           registry.counter("tickets.sold.database").increment();
           log.info("[DATABASE] Ingresso processado com sucesso!");
       } catch (Exception e) {
           registry.counter("tickets,failed.database").increment();
           log.info("Falha ao processar venda do evento {}: {}", eventId, e.getMessage());
       }
    }
}
