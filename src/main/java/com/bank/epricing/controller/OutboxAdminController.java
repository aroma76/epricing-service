package com.bank.epricing.controller;

import com.bank.epricing.entity.OutboxEvent;
import com.bank.epricing.repository.OutboxEventRepository;
import com.bank.epricing.service.OutboxRelayService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operator / SRE endpoint for inspecting outbox backlog, replaying dead-lettered events,
 * and manually flushing pending Kafka notifications after broker recovery.
 */
@RestController
@RequestMapping("/admin/outbox")
@PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
public class OutboxAdminController {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxRelayService outboxRelayService;

    public OutboxAdminController(OutboxEventRepository outboxEventRepository,
                                 OutboxRelayService outboxRelayService) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxRelayService = outboxRelayService;
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("pending", outboxEventRepository.countByStatus(OutboxEvent.OutboxStatus.PENDING));
        summary.put("processing", outboxEventRepository.countByStatus(OutboxEvent.OutboxStatus.PROCESSING));
        summary.put("published", outboxEventRepository.countByStatus(OutboxEvent.OutboxStatus.PUBLISHED));
        summary.put("dead_letter", outboxEventRepository.countByStatus(OutboxEvent.OutboxStatus.DEAD_LETTER));
        List<OutboxEvent> recentDeadLetters = outboxEventRepository.findByStatusOrderByCreatedAtDesc(
                OutboxEvent.OutboxStatus.DEAD_LETTER, PageRequest.of(0, 20));
        summary.put("recent_dead_letters", recentDeadLetters.stream().map(e -> Map.of(
                "id", e.getId(),
                "aggregate_id", e.getAggregateId(),
                "event_type", e.getEventType(),
                "retry_count", e.getRetryCount(),
                "last_error", e.getLastError() != null ? e.getLastError() : "",
                "created_at", e.getCreatedAt() != null ? e.getCreatedAt().toString() : ""
        )).toList());
        return ResponseEntity.ok(summary);
    }

    @PostMapping("/dead-letter/replay")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> replayDeadLetters(
            @RequestParam(defaultValue = "50") int limit) {
        int requeued = outboxRelayService.replayDeadLetters(limit);
        return ResponseEntity.ok(Map.of(
                "requeued_count", requeued,
                "message", "Dead-letter outbox events moved back to PENDING for delivery"
        ));
    }

    @PostMapping("/flush")
    public ResponseEntity<Map<String, Object>> flushPending() {
        int published = outboxRelayService.relayPendingEvents();
        return ResponseEntity.ok(Map.of(
                "published_count", published,
                "message", "Outbox relay cycle completed"
        ));
    }
}
