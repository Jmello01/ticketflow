package com.joaoricardo.ticketflow.service.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Decremento atômico no Redis: conferir e descontar acontecem em uma única operação. */
@Component
@RequiredArgsConstructor
public class RedisAtomicStrategy implements StockStrategy {

    private final StringRedisTemplate redis;

    public static String stockKey(Long eventId) {
        return "event:" + eventId + ":stock";
    }

    @Override
    public String id() {
        return "redis";
    }

    @Override
    public String label() {
        return "Redis atômico";
    }

    @Override
    public boolean usesQueue() {
        return true;
    }

    @Override
    public Outcome tryBuy(Long eventId) {
        Long remaining = redis.opsForValue().decrement(stockKey(eventId));
        return remaining != null && remaining >= 0 ? Outcome.WON : Outcome.LOST;
    }
}
