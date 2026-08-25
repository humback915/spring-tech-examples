package kr.co.example.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * ========================================================================
 * Kafka Producer - 이벤트 발행
 * ========================================================================
 *
 * ── Producer의 역할 ──
 *
 * Kafka 토픽에 메시지를 발행하는 컴포넌트.
 * KafkaTemplate을 통해 메시지를 직렬화하고 브로커에 전송한다.
 *
 * ── 전송 보장 수준 (acks 설정, application.yml) ──
 *
 * ┌──────────┬──────────────────────────────┬──────────────────┐
 * │ 설정      │ 동작                          │ 특성              │
 * ├──────────┼──────────────────────────────┼──────────────────┤
 * │ acks=0   │ 브로커 확인 없이 즉시 반환      │ 가장 빠름, 유실 가능│
 * │ acks=1   │ Leader 브로커만 기록 확인       │ 기본값, 적절한 균형 │
 * │ acks=all │ 모든 ISR(In-Sync Replica) 확인 │ 가장 안전, 느림    │
 * └──────────┴──────────────────────────────┴──────────────────┘
 *
 * ── 비동기 vs 동기 발행 ──
 *
 * 비동기 (publishEvent):
 * - send() 호출 후 즉시 반환, 결과는 whenComplete 콜백에서 처리
 * - 호출자가 전송 완료를 기다리지 않으므로 처리량(throughput) 높음
 * - 전송 실패 시 콜백에서 로깅/재처리 수행
 *
 * 동기 (publishSync):
 * - send().get()으로 브로커 응답까지 블로킹 대기
 * - 전송 성공이 보장된 후에야 다음 로직 진행
 * - 순서가 중요하거나 전송 실패 시 즉시 예외 처리가 필요한 경우 사용
 *
 * ── 파티션 키(Key)의 역할 ──
 *
 * key를 지정하면 동일 key의 메시지는 항상 같은 파티션으로 전송.
 * → 같은 주문 ID의 이벤트가 순서대로 처리됨을 보장
 *
 * key가 null이면 라운드 로빈으로 파티션 분배.
 * → 순서 무관한 메시지에 적합 (부하 균등 분산)
 *
 * ── Spring Boot 3.x 변경사항 ──
 *
 * Spring Boot 2.x: ListenableFuture 반환
 * Spring Boot 3.x: CompletableFuture 반환
 * → whenComplete, thenApply 등 표준 API 사용 가능
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventProducer {

    /** Kafka 메시지 전송을 위한 Template (Spring이 주입) */
    private final KafkaTemplate<String, String> kafkaTemplate;

    /** 이벤트 페이로드(Map)를 JSON 문자열로 직렬화하기 위한 ObjectMapper */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 비동기 이벤트 발행 (Non-blocking)
     *
     * send() 호출 후 즉시 반환. 전송 결과는 whenComplete 콜백에서 비동기로 처리.
     * 호출자는 전송 완료를 기다리지 않으므로 처리량(throughput)이 높다.
     *
     * 메시지 구조:
     * - eventId: 고유 식별자 (UUID) - 멱등성 검사에 활용
     * - key: 파티션 분배 키
     * - payload: 실제 이벤트 데이터
     * - timestamp: 발행 시각 (epoch millis)
     *
     * 전송 실패 시:
     * - whenComplete 콜백에서 에러 로그 기록
     * - 실무에서는 재시도 큐 또는 Outbox 테이블에 저장하여 재발행
     *
     * @param topic   메시지를 발행할 Kafka 토픽 이름 (EventTopic 상수 참조)
     * @param key     파티션 분배 키 (같은 key → 같은 파티션 → 순서 보장)
     * @param payload 이벤트 데이터 (Map → JSON 직렬화 후 전송)
     */
    public void publishEvent(String topic, String key, Map<String, Object> payload) {
        String eventId = UUID.randomUUID().toString();

        try {
            String message = objectMapper.writeValueAsString(Map.of(
                    "eventId", eventId,
                    "key", key,
                    "payload", payload,
                    "timestamp", System.currentTimeMillis()
            ));

            // Spring Boot 3.x: CompletableFuture 반환
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(topic, key, message);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("[Kafka] 발행 성공 - topic={}, partition={}, offset={}",
                            result.getRecordMetadata().topic(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                } else {
                    log.error("[Kafka] 발행 실패 - topic={}, key={}", topic, key, ex);
                }
            });

        } catch (JsonProcessingException e) {
            log.error("[Kafka] 직렬화 실패 - key={}", key, e);
        }
    }

    /**
     * 동기 이벤트 발행 (Blocking)
     *
     * send().get()으로 브로커 응답이 올 때까지 블로킹 대기.
     * 전송 성공이 확인된 후에야 다음 로직으로 진행.
     *
     * 비동기(publishEvent)와의 차이:
     * ┌───────────────┬──────────────────────────────────┐
     * │ publishEvent  │ 즉시 반환, 결과는 콜백에서 처리     │
     * │ publishSync   │ 전송 완료까지 대기 후 반환          │
     * └───────────────┴──────────────────────────────────┘
     *
     * 사용 시기:
     * - 메시지 전송 성공 여부에 따라 후속 로직 분기가 필요할 때
     * - 트랜잭션 내에서 Kafka 발행 보장이 필요할 때
     * - 순서가 중요하여 이전 메시지 전송 완료 확인 후 다음 메시지를 보내야 할 때
     *
     * 주의:
     * - get()은 블로킹이므로 응답 지연 시 호출 스레드도 함께 대기
     * - 대량 발행 시 처리량 저하 → 비동기 방식 권장
     *
     * @param topic   메시지를 발행할 Kafka 토픽 이름
     * @param key     파티션 분배 키 (같은 key → 같은 파티션)
     * @param message 전송할 메시지 문자열 (이미 직렬화된 상태)
     * @return 전송 결과 (토픽, 파티션, 오프셋 정보 포함)
     * @throws RuntimeException Kafka 전송 실패 시 (InterruptedException, ExecutionException 래핑)
     */
    public SendResult<String, String> publishSync(String topic, String key, String message) {
        try {
            // get(): CompletableFuture의 결과를 블로킹 대기
            return kafkaTemplate.send(topic, key, message).get();
        } catch (Exception e) {
            throw new RuntimeException("Kafka 전송 실패", e);
        }
    }
}
