package kr.co.example.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * ========================================================================
 * [2] Spring Kafka 설정 - 이벤트 기반 아키텍처
 * ========================================================================
 *
 * ── 핵심 개념 ──
 *
 * 1. Kafka란?
 *    - 분산 이벤트 스트리밍 플랫폼
 *    - Producer → Topic → Consumer 구조
 *    - 파티션 기반 분산 처리로 높은 처리량
 *    - 메시지 영속성 (디스크 기반 저장)
 *
 * 2. 에러 처리 전략
 *    ┌──────────────────────────────────────────────┐
 *    │ Consumer 메시지 처리 실패                      │
 *    │    ↓                                         │
 *    │ DefaultErrorHandler (재시도)                   │
 *    │    ↓ 3회 재시도 (1초 간격)                     │
 *    │ 재시도 실패                                    │
 *    │    ↓                                         │
 *    │ DeadLetterPublishingRecoverer                  │
 *    │    ↓ {원본토픽}.DLT 토픽으로 이동              │
 *    │ DLT 모니터링 후 수동/자동 재처리               │
 *    └──────────────────────────────────────────────┘
 *
 * 3. Idempotent Producer
 *    - enable.idempotence=true → 중복 전송 방지
 *    - acks=all → 모든 ISR 기록 확인 (가장 안전)
 *
 * 4. Transactional Outbox Pattern
 *    - DB 트랜잭션과 이벤트 발행의 원자성 보장
 *    - Outbox 테이블에 이벤트 저장 → 별도 프로세스가 Kafka 발행
 *
 * ── yml 설정 vs Bean 설정 ──
 *
 * Spring Boot의 Kafka 자동 구성(Auto-Configuration)은 application.yml 속성으로
 * ProducerFactory, ConsumerFactory, KafkaTemplate 등을 자동 생성한다.
 * 단순 속성(주소, 직렬화, acks 등)은 yml로 충분하지만,
 * 커스텀 로직(에러 핸들러, DLT 라우팅 등)이 필요하면 Bean을 직접 정의한다.
 *
 * ┌────────────────────────────────────────────────────────────────────┐
 * │ 설정 방식            │ 대상                                       │
 * ├──────────────────────┼────────────────────────────────────────────┤
 * │ application.yml      │ 단순 속성값 (문자열, 숫자, boolean)         │
 * │ (자동 구성)          │ bootstrap-servers, group-id                │
 * │                      │ acks, retries, compression-type            │
 * │                      │ key/value serializer/deserializer          │
 * │                      │ auto-offset-reset, max-poll-records        │
 * │                      │ enable.idempotence (properties.* 하위)     │
 * │                      │ listener.concurrency                       │
 * ├──────────────────────┼────────────────────────────────────────────┤
 * │ @Bean (Java 코드)    │ 커스텀 로직이 필요한 설정                    │
 * │ (수동 구성)          │ DefaultErrorHandler (재시도 전략)            │
 * │                      │ DeadLetterPublishingRecoverer (DLT 라우팅)  │
 * │                      │ ConcurrentKafkaListenerContainerFactory    │
 * │                      │ 예외별 재시도/즉시실패 분류                  │
 * │                      │ 인터셉터, 필터, 파티션 전략                  │
 * │                      │ 커스텀 Serializer/Deserializer              │
 * └──────────────────────┴────────────────────────────────────────────┘
 *
 * 주의: Bean을 직접 정의하면 해당 타입의 자동 구성이 비활성화된다.
 * 예) ConcurrentKafkaListenerContainerFactory를 @Bean으로 등록하면
 *     Spring Boot의 자동 Factory 생성이 무시되므로,
 *     ConsumerFactory 주입과 에러 핸들러 설정을 직접 해야 한다.
 *
 * ── 사용 특성 ──
 *
 * 장점:
 * - 시스템 간 느슨한 결합
 * - 비동기 처리로 응답 시간 단축
 * - 재시도/DLT로 메시지 유실 방지
 * - 수평 확장 용이
 *
 * 주의점:
 * - 메시지 순서 보장은 파티션 단위로만 가능
 * - Consumer Group 리밸런싱 주의
 * - DLT 모니터링 체계 필수
 * - Consumer 멱등성 처리 필요
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class KafkaConfig {

    private static final long RETRY_INTERVAL_MS = 1000L;
    private static final long MAX_RETRY_COUNT = 3L;

    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * Dead-Letter Topic 퍼블리셔
     *
     * 재시도 소진된 메시지를 {원본토픽}.DLT로 이동.
     */
    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer() {
        return new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> {
                    log.error("[Kafka DLT] 메시지 이동 - topic={}, key={}, error={}",
                            record.topic(), record.key(), ex.getMessage());
                    return new TopicPartition(record.topic() + ".DLT", record.partition());
                }
        );
    }

    /**
     * Kafka Listener Container Factory
     *
     * Spring Boot 3.x에서는 DefaultErrorHandler 사용
     * (SeekToCurrentErrorHandler는 deprecated)
     *
     * - FixedBackOff(1000, 3): 1초 간격, 최대 3회 재시도
     * - addNotRetryableExceptions: 즉시 DLT로 이동할 예외 지정
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                deadLetterPublishingRecoverer(),
                new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRY_COUNT)
        );

        // 재시도 불필요한 예외 → 즉시 DLT
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
        errorHandler.addNotRetryableExceptions(IllegalStateException.class);

        factory.setCommonErrorHandler(errorHandler);

        log.info("[Kafka] Factory 초기화 - retry={}ms x {}회, DLT 활성",
                RETRY_INTERVAL_MS, MAX_RETRY_COUNT);

        return factory;
    }
}
