package com.joaoricardo.ticketflow.domain.dto;

import java.util.List;

public record StrategyResult(
        String id,
        String label,
        boolean usesQueue,
        int sold,
        int oversold,
        int rejected,
        int errors,
        long decisionMs,
        boolean drained,
        int databaseStock,
        List<String> outcomes
) {}
