package com.joaoricardo.ticketflow.service;

import com.joaoricardo.ticketflow.domain.exception.TicketStockException;
import com.joaoricardo.ticketflow.domain.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TicketService {
    private final EventRepository repository;
    private final StringRedisTemplate redisTemplate; // Injeção do Redis

    @Transactional
    public void purchase(Long eventId) {
        String redisKey = "event:" + eventId + ":stock";

        //  fast check (No Redis/RAM)
        // O decrement é atômico. Se o valor cair abaixo de 0, o ingresso já acabou.
        Long remaining = redisTemplate.opsForValue().decrement(redisKey);

        if (remaining != null && remaining < 0) {
            // Se "furou" o estoque no Redis, devolvemos o valor para não ficar negativo
            redisTemplate.opsForValue().increment(redisKey);
            throw new TicketStockException("Ingressos esgotados (Check by Redis)");
        }

        // persistence (No Banco/Disco)
        // Aqui entra a sua lógica de Optimistic Locking que já funciona!
        try {
            var event = repository.findById(eventId).orElseThrow();
            event.setAvailableTickets(event.getAvailableTickets() - 1);
            repository.save(event);
        } catch (Exception e) {
            // Se o banco falhar (concorrência), precisamos devolver o ticket para o Redis
            redisTemplate.opsForValue().increment(redisKey);
            throw e;
        }
    }
}
