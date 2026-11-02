package com.joaoricardo.ticketflow.domain.controller;

import com.joaoricardo.ticketflow.domain.dto.ComparisonResult;
import com.joaoricardo.ticketflow.service.ComparisonService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/simulation")
@RequiredArgsConstructor
public class ComparisonController {

    private static final int MAX_THREADS = 20_000;

    private final ComparisonService service;

    @PostMapping("/compare")
    public ResponseEntity<ComparisonResult> compare(@RequestParam int threads, @RequestParam int tickets)
            throws InterruptedException {
        if (threads < 1 || threads > MAX_THREADS || tickets < 1) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(service.compare(threads, tickets));
    }
}
