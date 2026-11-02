package com.joaoricardo.ticketflow.service.strategy;

/**
 * Decide se uma thread leva o ingresso. Cada implementação é uma forma diferente
 * (certa ou errada) de proteger o estoque contra concorrência.
 */
public interface StockStrategy {

    String id();

    String label();

    /** true quando quem decide é o Redis e a venda segue para a fila; false quando a própria estratégia grava no banco. */
    boolean usesQueue();

    Outcome tryBuy(Long eventId);
}
