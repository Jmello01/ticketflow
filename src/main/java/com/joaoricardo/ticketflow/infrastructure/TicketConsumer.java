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
    private final io.micrometer.core.instrument.MeterRegistry registry;

    @RabbitListener(queues = RabbitConfig.QUEUE_NAME)
    @Transactional
    public void handleTicketPurchase(Long eventId) {
        try {
            // 1. BUSCA o evento no banco usando o ID que veio na mensagem
            // Isso cria a variável 'event' que o compilador não estava achando
            var event = repository.findById(eventId)
                    .orElseThrow(() -> new RuntimeException("Evento não encontrado"));

            if (event.getAvailableTickets() > 0) {
                event.setAvailableTickets(event.getAvailableTickets() - 1);

                // 2. AGORA o 'repository.save(event)' vai funcionar!
                repository.save(event);

                registry.counter("tickets.sold.database").increment();
                log.info("[DATABASE] Ingresso processado com sucesso!");
            }
        } catch (Exception e) {
            log.error("Erro ao processar venda: {}", e.getMessage());
        }
    }
}
