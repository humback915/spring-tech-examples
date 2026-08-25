package kr.co.example.httpclient;

import feign.Logger;
import lombok.Builder;
import lombok.Getter;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * OpenFeign 예제 — 선언적 인터페이스 기반 HTTP 클라이언트.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  OpenFeign — Spring Cloud OpenFeign                                  │
 * │  의존성: spring-cloud-starter-openfeign                              │
 * │  BOM: org.springframework.cloud:spring-cloud-dependencies            │
 * │       Boot 3.3.x → Cloud 2023.0.x                                   │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  Netflix에서 개발 → Spring Cloud에서 인수                             │
 * │  인터페이스에 어노테이션만 선언하면 구현체 자동 생성 (프록시)            │
 * │  MSA 서비스 간 통신에 가장 많이 사용                                   │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  OpenFeign 설정 순서                                                 │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  1. build.gradle에 의존성 추가                                       │
 * │     implementation 'org.springframework.cloud:spring-cloud-starter-openfeign'│
 * │  2. Spring Cloud BOM 추가 (dependencyManagement)                     │
 * │  3. @EnableFeignClients 어노테이션 추가 (Config 또는 Application 클래스)│
 * │  4. @FeignClient 인터페이스 정의                                      │
 * │  5. 서비스에서 인터페이스 주입(@Autowired)하여 사용                     │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  OpenFeign vs RestClient vs WebClient                                │
 * ├───────────────┬────────────────┬────────────────┬──────────────────┤
 * │               │  OpenFeign     │  RestClient    │  WebClient       │
 * ├───────────────┼────────────────┼────────────────┼──────────────────┤
 * │  선언 방식    │  인터페이스     │  Fluent API    │  Fluent API      │
 * │  코드량       │  매우 적음     │  보통           │  보통             │
 * │  동기/비동기  │  동기          │  동기           │  비동기(+동기)    │
 * │  로드밸런싱   │  내장 지원     │  수동 설정      │  수동 설정        │
 * │  MSA 적합성   │  최적          │  보통           │  보통             │
 * │  Spring Cloud │  필요          │  불필요         │  불필요           │
 * │  커스터마이징 │  제한적        │  높음           │  높음             │
 * └───────────────┴────────────────┴────────────────┴──────────────────┘
 * </pre>
 *
 * concert-msa-project 실사용:
 * - OrderServiceClient (payment→order 서비스 호출)
 * - TossPaymentsClient (외부 결제 API 호출)
 */
public class FeignClientExample {

    // ====================================================================
    // [1] 기본 Feign Client — 내부 서비스 간 통신 (MSA)
    // ====================================================================

    /**
     * 주문 서비스 Feign Client — payment-service → order-service 호출.
     *
     * <pre>
     * @FeignClient 속성:
     * - name:   클라이언트 이름 (로깅, Bean 이름에 사용)
     * - url:    호출 대상 URL (직접 지정 또는 프로퍼티 참조)
     *           Service Discovery(Eureka) 사용 시 url 생략 → name으로 자동 라우팅
     * - configuration: Feign 전용 설정 클래스 (로그 레벨, 인터셉터 등)
     *
     * 프로퍼티 참조: ${service.order-url} → application.yml에서 관리
     * → 환경별(dev/prod) URL을 쉽게 변경 가능
     * </pre>
     */
    @FeignClient(
            name = "order-service",
            url = "${service.order-url:http://localhost:8083}", // 기본값 지정 (: 뒤)
            configuration = FeignDefaultConfig.class
    )
    public interface OrderServiceClient {

        /**
         * 주문 단건 조회 — Spring MVC 어노테이션 그대로 사용.
         * Feign이 인터페이스를 프록시로 구현 → 실제 HTTP GET 요청 실행.
         */
        @GetMapping("/api/orders/{orderId}")
        ResponseEntity<Map<String, Object>> getOrder(@PathVariable("orderId") Long orderId);

        /**
         * 주문 상태 변경 (PATCH).
         * @RequestBody로 JSON 본문 전송.
         */
        @PatchMapping("/api/orders/{orderId}/status")
        ResponseEntity<Void> updateOrderStatus(
                @PathVariable("orderId") Long orderId,
                @RequestBody Map<String, String> statusRequest);

        /** 사용자의 주문 목록 조회 — 쿼리 파라미터 전달 */
        @GetMapping("/api/orders")
        ResponseEntity<List<Map<String, Object>>> getOrdersByUser(
                @RequestParam("userId") Long userId,
                @RequestParam(value = "page", defaultValue = "0") int page,
                @RequestParam(value = "size", defaultValue = "10") int size);
    }

    // ====================================================================
    // [2] 외부 API Feign Client — 결제 게이트웨이 등
    // ====================================================================

    /**
     * Toss Payments Feign Client — 외부 결제 API 호출.
     * concert-msa-project의 TossPaymentsClient 패턴 참고.
     *
     * <pre>
     * 외부 API 호출 시 주의사항:
     * 1. URL을 환경변수/프로퍼티로 관리 (하드코딩 금지)
     * 2. 인증 정보(API Key)는 환경변수로 관리
     * 3. 전용 Configuration 클래스로 인증 헤더 자동 추가
     * 4. 타임아웃 설정 필수 (외부 서비스 장애 전파 방지)
     * 5. 에러 디코더(ErrorDecoder)로 외부 에러 → 내부 예외 변환
     * </pre>
     */
    @FeignClient(
            name = "toss-payments",
            url = "${external.toss.base-url:https://api.tosspayments.com}",
            configuration = TossFeignConfig.class
    )
    public interface TossPaymentsClient {

        /** 결제 승인 요청 */
        @PostMapping("/v1/payments/confirm")
        ResponseEntity<Map<String, Object>> confirmPayment(
                @RequestBody Map<String, Object> confirmRequest);

        /** 결제 취소(환불) 요청 */
        @PostMapping("/v1/payments/{paymentKey}/cancel")
        ResponseEntity<Map<String, Object>> cancelPayment(
                @PathVariable("paymentKey") String paymentKey,
                @RequestBody Map<String, Object> cancelRequest);
    }

    // ====================================================================
    // [3] Feign Configuration — 로그, 인터셉터, 에러 디코더
    // ====================================================================

    /**
     * 기본 Feign 설정 — 내부 서비스용.
     *
     * <pre>
     * Feign Logger Level:
     * - NONE:    로그 없음 (운영 권장)
     * - BASIC:   요청 메서드, URL, 응답 상태코드, 실행 시간
     * - HEADERS: BASIC + 요청/응답 헤더
     * - FULL:    HEADERS + 요청/응답 본문 (개발용, 운영 시 성능/보안 주의)
     * </pre>
     */
    public static class FeignDefaultConfig {
        @Bean
        public Logger.Level feignLoggerLevel() {
            return Logger.Level.BASIC; // 요청 URL + 응답 상태코드 + 실행 시간
        }
    }

    /**
     * Toss Payments 전용 Feign 설정 — 인증 헤더 자동 추가.
     *
     * <pre>
     * RequestInterceptor로 모든 요청에 공통 헤더를 자동 추가.
     * API Key를 환경변수에서 읽어 Authorization 헤더에 설정.
     *
     * Toss Payments는 Basic 인증 사용:
     * Authorization: Basic {Base64(secretKey + ":")}
     * </pre>
     */
    public static class TossFeignConfig {
        @Bean
        public Logger.Level feignLoggerLevel() {
            return Logger.Level.FULL; // 외부 API 디버깅용
        }

        /**
         * RequestInterceptor — 모든 요청에 인증 헤더 자동 추가.
         */
        @Bean
        public feign.RequestInterceptor authInterceptor() {
            return template -> {
                // ⚠️ 예제 전용 — 실제 프로덕션에서는 절대 시크릿 키를 하드코딩하지 않음
                // 실무에서는 환경변수 또는 @ConfigurationProperties에서 읽음:
                // String secretKey = environment.getProperty("TOSS_SECRET_KEY");
                String secretKey = "test_secret_key"; // 예제용 더미 값
                String encoded = java.util.Base64.getEncoder()
                        .encodeToString((secretKey + ":").getBytes());
                template.header("Authorization", "Basic " + encoded);
                template.header("Content-Type", "application/json");
            };
        }
    }

    // ====================================================================
    // [4] Feign 사용 예시 (서비스 계층)
    // ====================================================================

    /**
     * Feign Client 사용법 — 일반 인터페이스처럼 메서드 호출.
     *
     * <pre>
     * Feign의 핵심: 인터페이스 선언만으로 HTTP 호출이 됨
     * → 마치 로컬 메서드를 호출하는 것처럼 사용
     * → 실제로는 내부에서 HTTP 요청을 보내고 응답을 역직렬화
     *
     * @Autowired
     * private OrderServiceClient orderClient;
     *
     * // 마치 로컬 메서드 호출처럼 사용
     * ResponseEntity&lt;Map&gt; order = orderClient.getOrder(123L);
     * </pre>
     */
    public void usageExample() {
        // OrderServiceClient를 @Autowired로 주입받아 사용
    }
}
