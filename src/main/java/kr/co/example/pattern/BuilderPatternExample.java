package kr.co.example.pattern;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ========================================================================
 * [9-E] Builder Pattern (빌더 패턴)
 * ========================================================================
 *
 * ── 개념 ──
 *
 * 복잡한 객체를 단계별로 생성하는 패턴.
 * 생성자 파라미터가 많거나 선택적 파라미터가 있을 때 가독성을 높임.
 *
 * ── 생성 방식 비교 ──
 *
 * 1. 텔레스코핑 생성자 (Telescoping Constructor)
 *    new Order(1L, "김철수", 50000, "CARD", null, null, null);
 *    → 파라미터 순서가 헷갈리고, null이 많음
 *
 * 2. JavaBeans (Setter)
 *    Order order = new Order();
 *    order.setUserId(1L);
 *    order.setUserName("김철수");
 *    → 불완전한 상태의 객체가 존재할 수 있음
 *
 * 3. Builder
 *    Order order = Order.builder()
 *        .userId(1L)
 *        .userName("김철수")
 *        .amount(50000)
 *        .build();
 *    → 명확하고, 불변 객체 생성 가능
 *
 * ── Lombok @Builder ──
 *
 * Lombok이 자동으로 Builder 클래스를 생성.
 * - @Builder: 클래스 또는 생성자에 적용
 * - @Builder.Default: 필드 기본값 지정
 * - .builder(): Builder 인스턴스 생성
 * - .build(): 최종 객체 생성
 *
 * ── 사용 특성 ──
 *
 * 장점:
 * - 가독성: 어떤 값이 어떤 필드인지 명확
 * - 불변성: build() 후 수정 불가능한 객체 생성 가능
 * - 유연성: 선택적 파라미터를 자연스럽게 처리
 *
 * 주의점:
 * - 단순한 객체에는 오버킬 (필드 2~3개면 생성자로 충분)
 * - Lombok @Builder 사용 시 @AllArgsConstructor가 함께 생성됨
 */
public class BuilderPatternExample {

    /**
     * DTO에 Lombok @Builder 적용 예제
     */
    @Getter
    @Builder
    @ToString
    public static class NotificationRequest {

        /** 수신자 ID */
        private final Long userId;

        /** 알림 제목 */
        private final String title;

        /** 알림 본문 */
        private final String body;

        /** 알림 타입 (PUSH, SMS, EMAIL) */
        @Builder.Default  // Builder 사용 시 기본값 적용
        private final String type = "PUSH";

        /** 알림 데이터 (추가 정보) */
        private final String data;

        /** 발송 예약 시간 (null이면 즉시 발송) */
        private final LocalDateTime scheduledAt;
    }

    /**
     * 수동 Builder 구현 예제 (Lombok 없이)
     *
     * Lombok @Builder의 내부 동작을 이해하기 위한 수동 구현.
     */
    @Getter
    @ToString
    public static class ApiResponse<T> {

        private final int code;
        private final String message;
        private final T data;
        private final List<String> errors;
        private final LocalDateTime timestamp;

        /** private 생성자: Builder를 통해서만 생성 가능 */
        private ApiResponse(ApiResponseBuilder<T> builder) {
            this.code = builder.code;
            this.message = builder.message;
            this.data = builder.data;
            this.errors = builder.errors;
            this.timestamp = LocalDateTime.now();
        }

        /** Builder 진입점 */
        public static <T> ApiResponseBuilder<T> builder() {
            return new ApiResponseBuilder<>();
        }

        /** Builder 클래스 */
        public static class ApiResponseBuilder<T> {
            private int code = 200;
            private String message = "성공";
            private T data;
            private List<String> errors;

            public ApiResponseBuilder<T> code(int code) {
                this.code = code;
                return this; // 메서드 체이닝을 위해 this 반환
            }

            public ApiResponseBuilder<T> message(String message) {
                this.message = message;
                return this;
            }

            public ApiResponseBuilder<T> data(T data) {
                this.data = data;
                return this;
            }

            public ApiResponseBuilder<T> errors(List<String> errors) {
                this.errors = errors;
                return this;
            }

            /** 최종 객체 생성 */
            public ApiResponse<T> build() {
                return new ApiResponse<>(this);
            }
        }
    }

    // ── 사용 예제 ──
    public static void main(String[] args) {

        // Lombok Builder 사용
        NotificationRequest request = NotificationRequest.builder()
                .userId(1L)
                .title("주문 완료")
                .body("주문이 정상적으로 처리되었습니다.")
                .type("PUSH")       // 선택적 (기본값: "PUSH")
                .scheduledAt(null)  // 선택적 (즉시 발송)
                .build();

        System.out.println("Notification: " + request);

        // 수동 Builder 사용
        ApiResponse<String> response = ApiResponse.<String>builder()
                .code(200)
                .message("조회 성공")
                .data("sample-data")
                .build();

        System.out.println("Response: " + response);
    }
}
