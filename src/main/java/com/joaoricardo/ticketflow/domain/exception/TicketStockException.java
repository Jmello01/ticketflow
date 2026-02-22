package com.joaoricardo.ticketflow.domain.exception;

public class TicketStockException extends RuntimeException {
    public TicketStockException(String message) {
        super(message);
    }
}