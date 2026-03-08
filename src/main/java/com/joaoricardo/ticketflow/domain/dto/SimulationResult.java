package com.joaoricardo.ticketflow.domain.dto;
import java.util.List;

public record SimulationResult(
        int totalAttempts,
        int successfulPurchases,
        int failures,
        long executionTimeMs,
        List<String> logs
) {}