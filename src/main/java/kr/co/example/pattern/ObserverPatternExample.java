package kr.co.example.pattern;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ========================================================================
 * [9-C] Observer Pattern (옵저버 패턴) - Spring Event
 * ========================================================================
 *
 * ── 개념 ──
 *
 * 이벤트 발생 시 등록된 리스너(관찰자)들에게 자동으로 통지.
 * 발행자(Publisher)와 구독자(Listener)가 느슨하게 결합.
 *
 * Spring에서는 ApplicationEventPublisher + @EventListener로 구현.
 *
 * ── 구조 ──
 * ┌────────────────────────────────────────────────────┐
 * │ Publisher (이벤트 발행)                              │
 * │   → applicationEventPublisher.publishEvent(event)  │
 * │                                                     │
 * │ @EventListener (동기 리스너)                        │
 * │   → 이벤트 발행 스레드에서 즉시 실행                │
 * │   → 리스너 예외 시 발행자에게 전파                  │
 * │                                                     │
 * │ @TransactionalEventListener (트랜잭션 리스너)       │
 * │   → 트랜잭션 커밋 후 실행 (AFTER_COMMIT)           │
 * │   → 트랜잭션 성공 시에만 실행 보장                  │
 * │                                                     │
 * │ @Async + @EventListener (비동기 리스너)             │
 * │   → 별도 스레드에서 실행                            │
 * │   → 발행자 성능에 영향 없음                         │
 * └────────────────────────────────────────────────────┘
 *
 * ── @EventListener vs @TransactionalEventListener ──
 *
 * ┌───────────────────────┬──────────────────────────────┐
 * │ @EventListener         │ 즉시 실행 (동기)             │
 * │                        │ 발행 시점에 트랜잭션 참여     │
 * │                        │ 리스너 예외 → 트랜잭션 롤백  │
 * ├───────────────────────┼──────────────────────────────┤
 * │ @TransactionalEvent    │ 트랜잭션 커밋 후 실행        │
 * │ Listener               │ DB 저장이 확실한 후 동작     │
 * │ (AFTER_COMMIT)         │ 알림 발송 등에 적합          │
 * ├───────────────────────┼──────────────────────────────┤
 * │ @TransactionalEvent    │ 트랜잭션 롤백 후 실행        │
 * │ Listener               │ 실패 알림, 보상 로직에 적합  │
 * │ (AFTER_ROLLBACK)       │                              │
 * └───────────────────────┴──────────────────────────────┘
 *
 * ── 사용 특성 ──
 *
 * 장점:
 * - 발행자와 리스너의 완전한 분리 (느슨한 결합)
 * - 새 리스너 추가 시 발행자 코드 수정 불필요
 * - 트랜잭션 기반 이벤트로 데이터 일관성 보장
 *
 * 주의점:
 * - 동기 @EventListener는 발행자의 응답 시간에 포함됨
 * - @TransactionalEventListener는 트랜잭션이 없으면 실행되지 않음
 * - 이벤트 순서 보장 안 됨 (여러 리스너 간)
 */

// ── 이벤트 클래스 ──
@Getter
class OrderCompletedEvent extends ApplicationEvent {

    /** 주문 ID */
    private final Long orderId;

    /** 결제 금액 */
    private final long amount;

    /** 고객 ID */
    private final Long userId;

    /**
     * ApplicationEvent 상속:
     * source는 이벤트 발행 주체 (보통 this 또는 서비스 인스턴스)
     */
    public OrderCompletedEvent(Object source, Long orderId, long amount, Long userId) {
        super(source);
        this.orderId = orderId;
        this.amount = amount;
        this.userId = userId;
    }
}

// ── 이벤트 발행자 ──
@Slf4j
@Service
@RequiredArgsConstructor
public class ObserverPatternExample {

    /**
     * ApplicationEventPublisher:
     * Spring이 자동 주입하는 이벤트 발행기.
     * publishEvent()로 이벤트를 발행하면
     * 모든 등록된 @EventListener가 자동으로 호출됨.
     */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 주문 완료 처리 + 이벤트 발행
     *
     * 핵심 비즈니스 로직(주문 저장) 후 이벤트를 발행.
     * 부가 로직(알림, 포인트, 로그)은 리스너에서 처리.
     * → 핵심 로직과 부가 로직의 분리
     */
    public void completeOrder(Long orderId, long amount, Long userId) {
        log.info("[Publisher] 주문 완료 처리 - orderId={}", orderId);

        // 핵심 로직: 주문 저장
        // orderRepository.save(order);

        // 이벤트 발행 → 등록된 모든 리스너에게 통지
        eventPublisher.publishEvent(new OrderCompletedEvent(this, orderId, amount, userId));

        log.info("[Publisher] 이벤트 발행 완료");
    }
}

// ── 리스너 1: 동기 이벤트 리스너 (재고 확정) ──
@Slf4j
@Component
class StockEventListener {

    /**
     * @EventListener: 이벤트 발행 시 동기적으로 실행.
     * 발행자의 스레드에서 즉시 실행되며,
     * 여기서 예외가 발생하면 발행자에게 전파됨.
     */
    @EventListener
    public void onOrderCompleted(OrderCompletedEvent event) {
        log.info("[Listener-동기] 재고 확정 - orderId={}", event.getOrderId());
        // 재고 차감 로직
    }
}

// ── 리스너 2: 트랜잭션 커밋 후 리스너 (알림 발송) ──
@Slf4j
@Component
class NotificationEventListener {

    /**
     * @TransactionalEventListener(phase = AFTER_COMMIT):
     * 트랜잭션이 성공적으로 커밋된 후에만 실행.
     *
     * 사용 이유:
     * DB에 데이터가 확실히 저장된 후에 알림을 보내야 함.
     * 만약 트랜잭션이 롤백되면 이 리스너는 실행되지 않음.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCompleted(OrderCompletedEvent event) {
        log.info("[Listener-TX커밋후] 알림 발송 - userId={}, orderId={}",
                event.getUserId(), event.getOrderId());
        // 푸시 알림, SMS, 이메일 발송
    }
}

// ── 리스너 3: 비동기 리스너 (포인트 적립) ──
@Slf4j
@Component
class PointEventListener {

    /**
     * @Async + @EventListener: 별도 스레드에서 비동기 실행.
     * 발행자의 응답 시간에 영향을 주지 않음.
     *
     * 주의: @Async는 반드시 @EnableAsync 설정이 필요하며,
     * 같은 클래스 내부 호출 시 동작하지 않음 (AOP 프록시 특성).
     */
    @Async("taskExecutor")
    @EventListener
    public void onOrderCompleted(OrderCompletedEvent event) {
        log.info("[Listener-비동기] 포인트 적립 시작 - userId={}, amount={}, thread={}",
                event.getUserId(), event.getAmount(), Thread.currentThread().getName());

        // 포인트 적립 로직 (시간이 걸릴 수 있음)
        long points = event.getAmount() / 100; // 1% 적립
        log.info("[Listener-비동기] 포인트 적립 완료 - {}P", points);
    }
}
