package kr.co.example.javacore;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.function.Function;

/**
 * Enum 활용 예제 — 상태 관리, 전략 패턴, 코드 테이블.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  Enum은 단순 상수가 아니다                                            │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  Java Enum = 클래스 → 필드, 메서드, 생성자, 인터페이스 구현 가능       │
 * │                                                                     │
 * │  활용:                                                               │
 * │  1. 상태/타입 정의 (OrderStatus, UserRole)                            │
 * │  2. 코드 테이블 (코드 → 한글 변환)                                    │
 * │  3. 전략 패턴 (Enum별 다른 비즈니스 로직)                              │
 * │  4. 상태 전이 규칙 (유효한 상태 변경만 허용)                           │
 * │  5. 싱글턴 패턴 (Enum 인스턴스는 JVM에서 단 하나)                     │
 * └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class EnumExample {

    // ====================================================================
    // [1] 기본 Enum — 코드 + 한글명 매핑
    // ====================================================================

    /**
     * 주문 상태 Enum — 코드와 한글명을 함께 관리.
     *
     * <pre>
     * JPA에서 사용 시:
     * @Enumerated(EnumType.STRING) → DB에 "PENDING", "PAID" 등 문자열 저장
     * @Enumerated(EnumType.ORDINAL) → DB에 0, 1, 2 등 순서 저장 (위험 — 사용 금지)
     * </pre>
     */
    @Getter
    @RequiredArgsConstructor
    public enum OrderStatus {
        PENDING("대기", false),
        PAID("결제완료", false),
        SHIPPED("배송중", false),
        DELIVERED("배송완료", true),
        CANCELLED("취소", true);

        /** 한글 상태명 — 프론트엔드 표시용 */
        private final String description;

        /** 최종 상태 여부 — true면 더 이상 상태 변경 불가 */
        private final boolean terminal;

        /**
         * 상태 전이 검증 — 유효한 전이만 허용.
         *
         * <pre>
         * PENDING → PAID, CANCELLED
         * PAID    → SHIPPED, CANCELLED
         * SHIPPED → DELIVERED
         * DELIVERED, CANCELLED → 변경 불가 (최종 상태)
         * </pre>
         */
        public boolean canTransitionTo(OrderStatus target) {
            return switch (this) {
                case PENDING -> target == PAID || target == CANCELLED;
                case PAID -> target == SHIPPED || target == CANCELLED;
                case SHIPPED -> target == DELIVERED;
                case DELIVERED, CANCELLED -> false; // 최종 상태
            };
        }

        /**
         * 코드 → Enum 변환 (안전한 방식).
         * valueOf()는 일치하는 값이 없으면 IllegalArgumentException 발생
         * → 이 메서드로 안전하게 변환.
         */
        public static OrderStatus fromDescription(String description) {
            return Arrays.stream(values())
                    .filter(status -> status.description.equals(description))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "알 수 없는 주문 상태: " + description));
        }
    }

    // ====================================================================
    // [2] 전략 Enum — Enum별 다른 비즈니스 로직
    // ====================================================================

    /**
     * 할인 정책 Enum — 각 Enum이 자체 계산 로직을 가짐.
     *
     * <pre>
     * if-else/switch 대신 Enum 메서드로 로직 분기.
     *
     * 기존:
     * if (type == "FIXED") price -= 1000;
     * else if (type == "PERCENT") price *= 0.9;
     *
     * 개선:
     * price = DiscountType.PERCENT.apply(price, 10);
     * </pre>
     */
    @Getter
    @RequiredArgsConstructor
    public enum DiscountType {
        /** 정액 할인 */
        FIXED("정액할인", (price, value) -> Math.max(0, price - value)),

        /** 정률 할인 */
        PERCENT("정률할인", (price, value) -> price * (100 - value) / 100),

        /** 할인 없음 */
        NONE("할인없음", (price, value) -> price);

        private final String description;

        /** 할인 계산 함수 — 각 Enum이 자체 로직을 가짐 */
        private final DiscountFunction discountFunction;

        /** 할인 적용 */
        public long apply(long originalPrice, long discountValue) {
            return discountFunction.apply(originalPrice, discountValue);
        }

        @FunctionalInterface
        interface DiscountFunction {
            long apply(long price, long value);
        }
    }

    // ====================================================================
    // [3] 그룹핑 Enum — 여러 Enum을 그룹으로 묶기
    // ====================================================================

    /**
     * 결제 수단 — 그룹화 활용.
     */
    @Getter
    @RequiredArgsConstructor
    public enum PaymentMethod {
        CREDIT_CARD("신용카드", PaymentGroup.CARD),
        DEBIT_CARD("체크카드", PaymentGroup.CARD),
        BANK_TRANSFER("계좌이체", PaymentGroup.BANK),
        KAKAO_PAY("카카오페이", PaymentGroup.EASY_PAY),
        NAVER_PAY("네이버페이", PaymentGroup.EASY_PAY),
        TOSS_PAY("토스페이", PaymentGroup.EASY_PAY);

        private final String description;
        private final PaymentGroup group;
    }

    @Getter
    @RequiredArgsConstructor
    public enum PaymentGroup {
        CARD("카드"),
        BANK("은행"),
        EASY_PAY("간편결제");

        private final String description;
    }

    // ====================================================================
    // [4] 사용 예시
    // ====================================================================

    public void usage() {
        // 상태 전이 검증
        OrderStatus current = OrderStatus.PENDING;
        if (current.canTransitionTo(OrderStatus.PAID)) {
            // 상태 변경 가능
        }

        // 전략 패턴 — if-else 없이 할인 계산
        long price = DiscountType.PERCENT.apply(10000, 15); // 8500

        // 코드 → Enum 변환
        OrderStatus status = OrderStatus.fromDescription("결제완료"); // PAID

        // 그룹핑
        PaymentMethod method = PaymentMethod.KAKAO_PAY;
        String group = method.getGroup().getDescription(); // "간편결제"
    }
}
