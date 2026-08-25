package kr.co.example.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ========================================================================
 * Kafka Consumer - 이벤트 소비
 * ========================================================================
 *
 * ── @KafkaListener 핵심 속성 ──
 * - topics: 구독할 토픽
 * - groupId: Consumer Group (같은 그룹 내 파티션 분배)
 * - concurrency: Listener 스레드 수
 *
 * ── Consumer Group 동작 ──
 * ┌────────────────────────────────────────┐
 * │ Topic: order.paid (3 파티션)           │
 * │                                        │
 * │ Group: example-group                   │
 * │   Consumer-1 → Partition-0             │
 * │   Consumer-2 → Partition-1             │
 * │   Consumer-3 → Partition-2             │
 * │                                        │
 * │ 같은 Group → 파티션 분배               │
 * │ 다른 Group → 동일 메시지 각각 수신      │
 * └────────────────────────────────────────┘
 *
 * ── 멱등성(Idempotency) ──
 * - 동일 메시지가 재전달될 수 있음 (네트워크 장애)
 * - eventId로 중복 체크하여 재처리 방지
 * - 실무에서는 Redis 또는 DB로 관리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventConsumer {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 멱등성 체크 (실무: Redis/DB 사용)
    private final Set<String> processedEvents = ConcurrentHashMap.newKeySet();

    @KafkaListener(topics = EventTopic.ORDER_PAID, groupId = "example-group")
    public void onOrderPaid(ConsumerRecord<String, String> record) {
        log.info("[Consumer] 수신 - topic={}, partition={}, offset={}, key={}",
                record.topic(), record.partition(), record.offset(), record.key());

        try {
            JsonNode event = objectMapper.readTree(record.value());
            String eventId = event.get("eventId").asText();

            // [1] 멱등성 체크
            if (processedEvents.contains(eventId)) {
                log.warn("[Consumer] 중복 이벤트 무시 - eventId={}", eventId);
                return;
            }

            // [2] 비즈니스 로직 처리
            log.info("[Consumer] 처리 시작 - eventId={}", eventId);

            // ... 실제 비즈니스 로직 ...

            processedEvents.add(eventId);
            log.info("[Consumer] 처리 완료 - eventId={}", eventId);

        } catch (Exception e) {
            // 예외 throw → DefaultErrorHandler가 재시도 처리
            log.error("[Consumer] 처리 실패 - offset={}", record.offset(), e);
            throw new RuntimeException("이벤트 처리 실패", e);
        }
    }
}
