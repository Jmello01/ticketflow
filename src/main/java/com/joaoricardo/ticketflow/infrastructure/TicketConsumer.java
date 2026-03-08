package com.joaoricardo.ticketflow.infrastructure;

import com.joaoricardo.ticketflow.domain.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class TicketConsumer {

    private final EventRepository repository;

    @RabbitListener(queues = "tickets.queue")
    @Transactional
    public void processPurchase(Long eventId) {
        try {
            var event = repository.findById(eventId).orElseThrow();
            event.setAvailableTickets(event.getAvailableTickets() - 1);
            repository.save(event);
            log.info("[BANCO] Venda processada com sucesso para o evento {}", eventId);
        } catch (Exception e) {
            log.error("Erro ao processar venda no banco: {}", e.getMessage());
        }
    }
}
