package com.joaoricardo.ticketflow.service.strategy;

import com.joaoricardo.ticketflow.domain.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Decide direto no banco com @Version. Quem perde a corrida de versão relê e tenta de novo,
 * então nunca vende além do estoque, mas paga o custo das tentativas.
 */
@Component
@RequiredArgsConstructor
public class OptimisticLockStrategy implements StockStrategy {

    private static final int MAX_ATTEMPTS = 30;

    private final EventRepository repository;
    private final TransactionTemplate transactions;

    @Override
    public String id() {
        return "optimistic";
    }

    @Override
    public String label() {
        return "Lock otimista no banco";
    }

    @Override
    public boolean usesQueue() {
        return false;
    }

    @Override
    public Outcome tryBuy(Long eventId) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return transactions.execute(status -> {
                    var event = repository.findById(eventId).orElseThrow();
                    if (event.getAvailableTickets() <= 0) {
                        return Outcome.LOST;
                    }
                    event.setAvailableTickets(event.getAvailableTickets() - 1);
                    repository.saveAndFlush(event);
                    return Outcome.WON;
                });
            } catch (ObjectOptimisticLockingFailureException | CannotAcquireLockException conflict) {
                pause();
            }
        }
        return Outcome.ERROR;
    }

    private void pause() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(2, 25));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
