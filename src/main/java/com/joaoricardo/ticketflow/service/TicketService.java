package com.joaoricardo.ticketflow.service;

import com.joaoricardo.ticketflow.domain.exception.TicketStockException;
import com.joaoricardo.ticketflow.domain.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TicketService {
    private final EventRepository repository;

    @Transactional
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 10,
            backoff = @Backoff(delay = 100)
    )
    public void purchase(Long id) {
        var event = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Evento não encontrado"));

        // Se não tem ingresso, lança erro!
        if (event.getAvailableTickets() <= 0) {
            throw new TicketStockException("Ingressos esgotados!");
        }

        event.setAvailableTickets(event.getAvailableTickets() - 1);
        repository.save(event);
    }
}
