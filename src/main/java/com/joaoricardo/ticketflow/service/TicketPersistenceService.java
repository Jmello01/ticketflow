package com.joaoricardo.ticketflow.service;

import com.joaoricardo.ticketflow.domain.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TicketPersistenceService {
    private final EventRepository repository;

    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 8,
            backoff = @Backoff(delay = 50, multiplier = 2, random = true)
    )
    @Transactional
    public void persistPurchase(Long eventId) {
        var event = repository.findById(eventId).orElseThrow();
        event.setAvailableTickets(event.getAvailableTickets() - 1);
        repository.save(event);
    }
}
