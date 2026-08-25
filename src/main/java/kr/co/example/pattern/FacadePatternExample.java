package kr.co.example.pattern;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * ========================================================================
 * [9-D] Facade Pattern (퍼사드 패턴)
 * ========================================================================
 *
 * ── 개념 ──
 *
 * 복잡한 하위 시스템들의 인터페이스를 단순화하는 상위 인터페이스 제공.
 * 클라이언트는 퍼사드만 알면 되고, 내부 구현 세부사항을 몰라도 됨.
 *
 * ── 구조 ──
 * ┌─────────────────────────────────────────────┐
 * │ Controller (클라이언트)                       │
 * │   ↓                                          │
 * │ OrderFacade (퍼사드)                          │
 * │   ├─ InventoryService (재고 확인/차감)       │
 * │   ├─ PaymentService (결제 처리)              │
 * │   ├─ OrderRepository (주문 저장)             │
 * │   └─ NotificationService (알림 발송)         │
 * │                                              │
 * │ Controller는 OrderFacade.placeOrder()만 호출 │
 * │ 내부에서 여러 서비스를 조합/조율               │
 * └─────────────────────────────────────────────┘
 *
 * ── Spring에서의 활용 ──
 *
 * Spring의 Service 레이어가 자연스럽게 퍼사드 역할을 수행.
 * 여러 Repository, 외부 서비스, 유틸리티를 조합하여
 * Controller에 단일 진입점 제공.
 *
 * ── 사용 특성 ──
 *
 * 장점:
 * - 클라이언트 코드 단순화 (복잡한 호출 순서를 몰라도 됨)
 * - 하위 시스템 간의 결합도 감소 (퍼사드를 통해서만 접근)
 * - 하위 시스템 변경 시 퍼사드만 수정하면 됨
 *
 * 주의점:
 * - 퍼사드가 너무 많은 기능을 포함하면 God Object가 될 수 있음
 * - 적절한 책임 분리 필요 (퍼사드는 조율만, 로직은 하위 서비스에)
 * - 단계별 실패 시 보상 로직(Saga) 고려 필요
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FacadePatternExample {

    /*
     * 실무에서는 아래처럼 각각 별도 서비스 클래스를 주입받음:
     *
     * private final InventoryService inventoryService;
     * private final PaymentService paymentService;
     * private final OrderRepository orderRepository;
     * private final NotificationService notificationService;
     *
     * 이 예제에서는 학습 목적으로 private 메서드로 시뮬레이션.
     */

    /**
     * 퍼사드 메서드: 주문 처리
     *
     * Controller는 이 메서드 하나만 호출하면 됨.
     * 내부에서 여러 하위 서비스를 순서대로 호출하여 복잡한 비즈니스 흐름을 조율.
     *
     * 실행 흐름:
     * 1. 재고 확인 및 예약 (재고 부족 시 예외)
     * 2. 결제 처리 (PG사 API 호출)
     * 3. 주문 저장 (DB 영속화)
     * 4. 알림 발송 (비동기 가능)
     *
     * 실패 시나리오:
     * - Step 1 실패: 재고 부족 → 예외 발생, 이후 단계 미실행
     * - Step 2 실패: 결제 실패 → 예약된 재고 복원 필요 (보상 트랜잭션)
     * - Step 3 실패: 주문 저장 실패 → 결제 취소 필요 (보상 트랜잭션)
     * - Step 4 실패: 알림 실패 → 주문 자체는 정상 (비동기 재시도)
     *
     * @param userId   주문자 ID
     * @param itemId   상품 ID
     * @param quantity 주문 수량
     * @return 주문 결과 Map (orderId, transactionId, status)
     * @throws RuntimeException 재고 부족 시
     */
    public Map<String, Object> placeOrder(Long userId, Long itemId, int quantity) {
        log.info("[Facade] 주문 처리 시작 - userId={}, itemId={}, qty={}", userId, itemId, quantity);

        // Step 1: 재고 확인 및 예약
        // 재고가 부족하면 이후 단계를 실행하지 않고 즉시 예외
        boolean stockAvailable = checkAndReserveStock(itemId, quantity);
        if (!stockAvailable) {
            throw new RuntimeException("재고 부족 - itemId=" + itemId);
        }

        // Step 2: 결제 처리
        // 단가 1,000원 × 수량으로 결제 금액 계산 (예제용 고정 단가)
        long totalAmount = quantity * 1000L;
        String transactionId = processPayment(userId, totalAmount);

        // Step 3: 주문 저장
        Long orderId = saveOrder(userId, itemId, quantity, transactionId);

        // Step 4: 알림 발송
        // 실무에서는 @Async 또는 이벤트 기반으로 비동기 처리
        // 알림 실패가 주문 전체를 실패시키지 않도록 분리
        sendNotification(userId, orderId);

        log.info("[Facade] 주문 처리 완료 - orderId={}", orderId);

        return Map.of(
                "orderId", orderId,
                "transactionId", transactionId,
                "status", "COMPLETED"
        );
    }

    // ── 하위 시스템 메서드들 (실무에서는 별도 서비스 클래스로 분리) ──

    /**
     * 재고 확인 및 예약
     *
     * 실무에서는 inventoryService.checkAndReserve(itemId, quantity) 호출.
     * Redis 재고 차감 → 실패 시 false 반환.
     *
     * @param itemId   상품 ID
     * @param quantity 요청 수량
     * @return 재고 확보 성공 여부
     */
    private boolean checkAndReserveStock(Long itemId, int quantity) {
        log.info("[Facade-재고] 재고 확인 및 예약 - itemId={}, qty={}", itemId, quantity);
        return true; // 시뮬레이션: 항상 성공
    }

    /**
     * 결제 처리
     *
     * 실무에서는 paymentService.charge(userId, amount) 호출.
     * PG사 API를 통해 실제 결제 수행 후 트랜잭션 ID 반환.
     *
     * @param userId 결제자 ID
     * @param amount 결제 금액 (원)
     * @return PG사 트랜잭션 ID
     */
    private String processPayment(Long userId, long amount) {
        log.info("[Facade-결제] 결제 처리 - userId={}, amount={}원", userId, amount);
        return "TXN-" + System.currentTimeMillis(); // 시뮬레이션
    }

    /**
     * 주문 저장
     *
     * 실무에서는 orderRepository.save(Order.of(...)) 호출.
     * DB에 주문 엔티티를 영속화하고 생성된 주문 ID 반환.
     *
     * @param userId        주문자 ID
     * @param itemId        상품 ID
     * @param quantity      주문 수량
     * @param transactionId 결제 트랜잭션 ID
     * @return 생성된 주문 ID
     */
    private Long saveOrder(Long userId, Long itemId, int quantity, String transactionId) {
        log.info("[Facade-주문] 주문 저장 - userId={}, transactionId={}", userId, transactionId);
        return 1L; // 시뮬레이션
    }

    /**
     * 알림 발송
     *
     * 실무에서는 notificationService.send(userId, orderId) 호출.
     * 푸시 알림, SMS, 이메일 등을 비동기로 발송.
     * 알림 실패가 주문 프로세스에 영향을 주지 않도록 try-catch 또는 @Async 처리.
     *
     * @param userId  수신자 ID
     * @param orderId 주문 ID (알림 내용에 포함)
     */
    private void sendNotification(Long userId, Long orderId) {
        log.info("[Facade-알림] 알림 발송 - userId={}, orderId={}", userId, orderId);
    }
}
