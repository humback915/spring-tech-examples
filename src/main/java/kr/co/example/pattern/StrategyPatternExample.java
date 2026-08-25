package kr.co.example.pattern;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ========================================================================
 * [9-A] Strategy Pattern (전략 패턴)
 * ========================================================================
 *
 * ── 개념 ──
 *
 * 동일한 인터페이스를 구현한 여러 전략(알고리즘)을 런타임에 교체하여 사용.
 * if-else / switch 분기를 제거하고, 새로운 전략 추가 시 기존 코드 수정 불필요.
 *
 * ── 구조 ──
 * ┌────────────────────────────────────────────┐
 * │ PaymentStrategy (인터페이스)                │
 * │   ├─ CardPaymentStrategy (카드 결제)       │
 * │   ├─ BankTransferStrategy (계좌이체)       │
 * │   └─ MobilePaymentStrategy (모바일 결제)   │
 * │                                            │
 * │ PaymentService (컨텍스트)                   │
 * │   → Map<String, PaymentStrategy>로 전략 주입│
 * │   → paymentType에 따라 전략 선택 후 실행    │
 * └────────────────────────────────────────────┘
 *
 * ── Spring에서의 활용 ──
 *
 * Spring의 DI 컨테이너가 인터페이스의 모든 구현체를 자동으로 수집.
 * List<PaymentStrategy> 또는 Map<String, PaymentStrategy>로 주입 가능.
 * → new 키워드 없이 전략 교체 가능
 *
 * ── 사용 특성 ──
 *
 * 장점:
 * - OCP(개방-폐쇄 원칙) 준수: 새 전략 추가 시 기존 코드 수정 없음
 * - 테스트 용이: 각 전략을 독립적으로 단위 테스트 가능
 * - 런타임에 동적으로 전략 교체 가능
 *
 * 주의점:
 * - 전략이 적은 경우 오버 엔지니어링이 될 수 있음
 * - 클라이언트가 전략의 차이를 알아야 함
 */

// ── 전략 인터페이스 ──
interface PaymentStrategy {

    /** 이 전략이 처리하는 결제 타입 (식별 키) */
    String getType();

    /** 결제 실행 */
    PaymentResult pay(long amount, Map<String, String> params);
}

/** 결제 결과 DTO */
record PaymentResult(boolean success, String transactionId, String message) {}

// ── 전략 구현체 1: 카드 결제 ──
@Slf4j
@Component  // Spring 빈으로 등록 → 자동 수집 대상
class CardPaymentStrategy implements PaymentStrategy {

    @Override
    public String getType() {
        return "CARD";
    }

    @Override
    public PaymentResult pay(long amount, Map<String, String> params) {
        log.info("[Card] 카드 결제 처리 - amount={}, cardNo={}",
                amount, params.get("cardNumber"));
        // PG사 API 호출 로직
        return new PaymentResult(true, "CARD-TXN-001", "카드 결제 성공");
    }
}

// ── 전략 구현체 2: 계좌이체 ──
@Slf4j
@Component
class BankTransferStrategy implements PaymentStrategy {

    @Override
    public String getType() {
        return "BANK";
    }

    @Override
    public PaymentResult pay(long amount, Map<String, String> params) {
        log.info("[Bank] 계좌이체 처리 - amount={}, bankCode={}",
                amount, params.get("bankCode"));
        return new PaymentResult(true, "BANK-TXN-001", "계좌이체 성공");
    }
}

// ── 전략 구현체 3: 모바일 결제 ──
@Slf4j
@Component
class MobilePaymentStrategy implements PaymentStrategy {

    @Override
    public String getType() {
        return "MOBILE";
    }

    @Override
    public PaymentResult pay(long amount, Map<String, String> params) {
        log.info("[Mobile] 모바일 결제 처리 - amount={}", amount);
        return new PaymentResult(true, "MOBILE-TXN-001", "모바일 결제 성공");
    }
}

// ── 컨텍스트: 전략을 선택하여 실행 ──
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyPatternExample {

    /**
     * Spring이 PaymentStrategy 인터페이스의 모든 구현체를 자동 주입.
     *
     * List<PaymentStrategy>로 주입받은 후
     * Map<String, PaymentStrategy>로 변환하여 O(1) 조회.
     *
     * → if-else 분기 없이 paymentType으로 전략 선택
     */
    private final Map<String, PaymentStrategy> strategyMap;

    /**
     * 생성자에서 List → Map 변환
     *
     * Spring이 List<PaymentStrategy>로 모든 구현체를 주입하면,
     * getType()을 키로 하는 Map으로 변환.
     */
    public StrategyPatternExample(List<PaymentStrategy> strategies) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(PaymentStrategy::getType, Function.identity()));
        log.info("[Strategy] 등록된 전략: {}", strategyMap.keySet());
    }

    /**
     * 결제 실행 - 타입에 따라 전략 선택
     *
     * if-else 대신 Map.get()으로 전략 선택.
     * 새로운 결제 수단 추가 시 PaymentStrategy 구현체만 추가하면 됨.
     */
    public PaymentResult processPayment(String paymentType, long amount, Map<String, String> params) {
        PaymentStrategy strategy = strategyMap.get(paymentType);

        if (strategy == null) {
            throw new IllegalArgumentException("지원하지 않는 결제 타입: " + paymentType);
        }

        log.info("[Strategy] 결제 처리 - type={}, amount={}", paymentType, amount);
        return strategy.pay(amount, params);
    }
}
