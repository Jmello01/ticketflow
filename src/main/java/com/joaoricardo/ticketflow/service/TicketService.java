package com.joaoricardo.ticketflow.service;

import com.joaoricardo.ticketflow.domain.exception.TicketStockException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketService {
    private final StringRedisTemplate redisTemplate;
    private final TicketPersistenceService persistenceService;

    public void purchase(Long eventId) {
        String redisKey = "event:" + eventId + ":stock";

        Long remaining = redisTemplate.opsForValue().decrement(redisKey);

        if (remaining != null && remaining < 0) {
            redisTemplate.opsForValue().increment(redisKey);
            throw new TicketStockException("Ingressos esgotados (Check by Redis)");
        }

        try {
            persistenceService.persistPurchase(eventId);
        } catch (Exception e) {
            redisTemplate.opsForValue().increment(redisKey);
            throw e;
        }
    }
}
