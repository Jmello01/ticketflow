package com.joaoricardo.ticketflow.service.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Lê o estoque, confere e grava de volta em passos separados. Entre a leitura e a
 * escrita outras threads leem o mesmo valor, então várias passam na conferência.
 */
@Component
@RequiredArgsConstructor
public class UnprotectedStrategy implements StockStrategy {

    private final StringRedisTemplate redis;

    @Override
    public String id() {
        return "unprotected";
    }

    @Override
    public String label() {
        return "Sem proteção";
    }

    @Override
    public boolean usesQueue() {
        return true;
    }

    @Override
    public Outcome tryBuy(Long eventId) {
        String key = RedisAtomicStrategy.stockKey(eventId);
        String current = redis.opsForValue().get(key);
        int stock = current == null ? 0 : Integer.parseInt(current);
        if (stock <= 0) {
            return Outcome.LOST;
        }
        redis.opsForValue().set(key, String.valueOf(stock - 1));
        return Outcome.WON;
    }
}
