package com.joaoricardo.ticketflow;

import com.joaoricardo.ticketflow.domain.dto.ComparisonResult;
import com.joaoricardo.ticketflow.domain.dto.StrategyResult;
import com.joaoricardo.ticketflow.service.ComparisonService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ComparisonServiceTest {

    private static final int THREADS = 300;
    private static final int TICKETS = 10;

    @Autowired private ComparisonService service;

    private static ComparisonResult result;

    private StrategyResult strategy(String id) throws InterruptedException {
        if (result == null) {
            result = service.compare(THREADS, TICKETS);
        }
        return result.strategies().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void redisAtomicoVendeExatamenteOEstoqueEOBancoFechaEmZero() throws InterruptedException {
        StrategyResult redis = strategy("redis");

        assertEquals(TICKETS, redis.sold());
        assertEquals(0, redis.oversold());
        assertTrue(redis.drained(), "o consumer deveria ter esvaziado a fila");
        assertEquals(0, redis.databaseStock());
    }

    @Test
    void lockOtimistaNuncaVendeAlemDoEstoque() throws InterruptedException {
        StrategyResult optimistic = strategy("optimistic");

        assertTrue(optimistic.sold() <= TICKETS, "vendeu " + optimistic.sold());
        assertEquals(0, optimistic.oversold());
        assertEquals(TICKETS - optimistic.sold(), optimistic.databaseStock());
    }

    @Test
    void semProtecaoNuncaVendeMenosQueOEstoqueEOBancoRefleteAsVendas() throws InterruptedException {
        StrategyResult unprotected = strategy("unprotected");

        assertTrue(unprotected.sold() >= TICKETS, "vendeu " + unprotected.sold());
        assertEquals(unprotected.sold() - TICKETS, unprotected.oversold());
        assertTrue(unprotected.drained(), "o consumer deveria ter esvaziado a fila");
        assertEquals(TICKETS - unprotected.sold(), unprotected.databaseStock());
    }

    @Test
    void todasAsEstrategiasRegistramUmaChegadaPorThread() throws InterruptedException {
        strategy("redis");
        for (StrategyResult s : result.strategies()) {
            assertEquals(THREADS, s.outcomes().size(), s.id());
        }
    }
}
