package com.joaoricardo.ticketflow.domain.dto;

import java.util.List;

public record ComparisonResult(int threads, int tickets, List<StrategyResult> strategies) {}
