package kr.co.example.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * ========================================================================
 * Transactional Outbox Pattern
 * ========================================================================
 *
 * ── 문제 상황 ──
 * ┌────────────────────────────────────────────┐
 * │ @Transactional                              │
 * │ void process() {                            │
 * │     repository.save(entity);  // DB 커밋 OK │
 * │     kafka.send(event);        // 네트워크 실패│
 * │ }                                           │
 * │ → DB는 저장됐지만 이벤트는 유실!             │
 * └────────────────────────────────────────────┘
 *
 * ── Outbox Pattern 해결 ──
 * ┌────────────────────────────────────────────┐
 * │ @Transactional                              │
 * │ void process() {                            │
 * │     repository.save(entity);   // DB 저장   │
 * │     outbox.save(event);        // 같은 TX   │
 * │ }                                           │
 * │                                             │
 * │ 별도 스케줄러:                               │
 * │   outbox 폴링 → Kafka 발행 → 상태 갱신      │
 * └────────────────────────────────────────────┘
 *
 * 핵심: DB 트랜잭션과 이벤트 저장의 원자성 보장
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final KafkaTemplate<String, String> kafkaTemplate;

    // 실무에서는 JPA Entity + Repository
    private final Queue<OutboxEntry> outboxStore = new ConcurrentLinkedQueue<>();

    public record OutboxEntry(
            String id,
            String topic,
            String key,
            String payload,
            LocalDateTime createdAt,
            String status // PENDING, PUBLISHED, FAILED
    ) {
        public OutboxEntry withStatus(String newStatus) {
            return new OutboxEntry(id, topic, key, payload, createdAt, newStatus);
        }
    }

    /**
     * Outbox에 이벤트 저장 (@Transactional 내에서 호출)
     */
    public void save(String topic, String key, String payload) {
        outboxStore.add(new OutboxEntry(
                UUID.randomUUID().toString(), topic, key, payload,
                LocalDateTime.now(), "PENDING"
        ));
        log.info("[Outbox] 저장 - topic={}, key={}", topic, key);
    }

    /**
     * PENDING 이벤트를 Kafka로 재발행 (5초 간격)
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional(readOnly = true)
    public void republishPending() {
        List<OutboxEntry> pending = outboxStore.stream()
                .filter(e -> "PENDING".equals(e.status()))
                .toList();

        if (pending.isEmpty()) return;

        log.info("[Outbox] 재발행 대상 {}건", pending.size());

        for (OutboxEntry entry : pending) {
            try {
                kafkaTemplate.send(entry.topic(), entry.key(), entry.payload());
                outboxStore.remove(entry);
                log.info("[Outbox] 재발행 성공 - key={}", entry.key());
            } catch (Exception e) {
                log.error("[Outbox] 재발행 실패 - key={}", entry.key(), e);
            }
        }
    }
}
