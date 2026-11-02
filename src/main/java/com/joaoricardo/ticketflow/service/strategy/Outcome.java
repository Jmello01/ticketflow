package com.joaoricardo.ticketflow.service.strategy;

public enum Outcome {
    WON, LOST, ERROR;

    public String code() {
        return name().toLowerCase();
    }
}
