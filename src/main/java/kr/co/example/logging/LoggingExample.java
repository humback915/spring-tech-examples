package kr.co.example.logging;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 로깅 예제 — SLF4J + Logback 활용 패턴.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  SLF4J — Simple Logging Facade for Java                             │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  로깅 퍼사드(인터페이스) — 실제 구현체(Logback, Log4j2)와 분리.       │
 * │  Spring Boot 기본: SLF4J + Logback                                  │
 * │                                                                     │
 * │  @Slf4j (Lombok): 아래 코드를 자동 생성                               │
 * │  private static final Logger log =                                   │
 * │      LoggerFactory.getLogger(LoggingExample.class);                  │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  로그 레벨 (심각도 순서)                                               │
 * ├──────────┬──────────────────────────────────────────────────────────┤
 * │  TRACE   │ 가장 상세 — 변수값 추적, 디버깅 (운영에서 사용 안 함)      │
 * │  DEBUG   │ 디버깅용 — 개발 환경에서 주로 사용                          │
 * │  INFO    │ 일반 정보 — 앱 시작, 주요 이벤트, 비즈니스 흐름              │
 * │  WARN    │ 경고 — 잠재적 문제, 예상된 예외 (비즈니스 예외 등)          │
 * │  ERROR   │ 에러 — 예상치 못한 오류, 즉시 조치 필요                    │
 * └──────────┴──────────────────────────────────────────────────────────┘
 *
 * 운영 환경 권장 레벨: INFO (DEBUG 이하는 출력 안 함)
 * 개발 환경 권장 레벨: DEBUG
 *
 * application.yml:
 *   logging:
 *     level:
 *       root: INFO
 *       kr.co.example: DEBUG     # 패키지별 레벨 설정
 *       org.hibernate.SQL: DEBUG # SQL 로그 출력
 * </pre>
 */
@Slf4j
@Service
public class LoggingExample {

    // ────────────────────────────────────────
    // [1] 기본 로깅 — 레벨별 사용법
    // ────────────────────────────────────────

    /**
     * 로그 레벨별 사용법.
     *
     * <pre>
     * 중요: 문자열 연결(+) 대신 플레이스홀더({}) 사용
     *
     * ✗ log.info("User: " + user.getName() + ", age: " + user.getAge());
     *   → 로그 레벨이 INFO 미만이어도 문자열 연결 실행 (성능 낭비)
     *
     * ✓ log.info("User: {}, age: {}", user.getName(), user.getAge());
     *   → 로그 레벨이 INFO 이상일 때만 문자열 조합 (Lazy)
     * </pre>
     */
    public void loggingLevels() {
        String userId = "123";
        String userName = "홍길동";

        // TRACE — 매우 상세한 추적 (변수값, 루프 내부 등)
        log.trace("메서드 진입: userId={}", userId);

        // DEBUG — 디버깅 정보 (개발 중 확인 필요한 것)
        log.debug("사용자 조회 시작: userId={}", userId);

        // INFO — 주요 비즈니스 이벤트 (앱 상태 추적)
        log.info("사용자 로그인 성공: userId={}, userName={}", userId, userName);

        // WARN — 경고 (잠재적 문제, 예상된 예외)
        log.warn("사용자 로그인 시도 횟수 초과: userId={}, attempts={}", userId, 5);

        // ERROR — 에러 (예상치 못한 오류, 스택 트레이스 포함)
        try {
            throw new RuntimeException("DB 연결 실패");
        } catch (Exception e) {
            // 예외 객체를 마지막 파라미터로 전달 → 스택 트레이스 자동 출력
            log.error("사용자 조회 실패: userId={}", userId, e);
        }
    }

    // ────────────────────────────────────────
    // [2] MDC (Mapped Diagnostic Context) — 요청 추적
    // ────────────────────────────────────────

    /**
     * MDC — 요청별 고유 ID를 로그에 자동 포함.
     *
     * <pre>
     * MSA에서 하나의 요청이 여러 서비스를 거칠 때,
     * 같은 traceId로 전체 흐름을 추적할 수 있음.
     *
     * logback-spring.xml 패턴 설정:
     * %d{yyyy-MM-dd HH:mm:ss} [%X{traceId}] [%thread] %-5level %logger{36} - %msg%n
     *
     * 출력 예시:
     * 2024-01-15 10:30:00 [abc-123] [http-nio-8080-exec-1] INFO  LoggingExample - 주문 생성
     * 2024-01-15 10:30:01 [abc-123] [http-nio-8080-exec-1] INFO  LoggingExample - 결제 요청
     * → traceId로 같은 요청의 모든 로그를 묶어서 조회 가능
     *
     * 보통 Interceptor나 Filter에서 요청 시작 시 설정, 종료 시 제거.
     * </pre>
     */
    public void mdcExample() {
        // 요청 시작 시 — traceId 설정
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("traceId", traceId);

        try {
            log.info("주문 생성 시작");
            log.info("재고 확인 완료");
            log.info("결제 요청");
            // 모든 로그에 [traceId] 자동 포함
        } finally {
            // 요청 종료 시 — 반드시 제거 (메모리 누수 방지)
            // 스레드 풀에서 재사용되므로 이전 요청의 MDC가 남을 수 있음
            MDC.clear();
        }
    }

    // ────────────────────────────────────────
    // [3] 실무 로깅 패턴
    // ────────────────────────────────────────

    /**
     * 서비스 메서드 로깅 — 실무 패턴.
     *
     * <pre>
     * 로깅 원칙:
     * 1. 메서드 시작/끝에 로그 → 실행 흐름 추적
     * 2. 외부 시스템 호출 전후에 로그 → 장애 추적
     * 3. 중요 비즈니스 이벤트에 로그 → 감사 추적
     * 4. catch 블록에서 에러 로그 + 스택 트레이스
     * 5. 민감 정보(비밀번호, 카드번호) 마스킹
     *
     * 하지 말 것:
     * - 루프 내부에서 대량 로깅 (성능 저하)
     * - System.out.println() 사용 (SLF4J 사용)
     * - 문자열 연결(+) 사용 ({} 플레이스홀더 사용)
     * </pre>
     */
    public void serviceMethodPattern(Long orderId, Long userId) {
        log.info("주문 처리 시작: orderId={}, userId={}", orderId, userId);

        try {
            // 비즈니스 로직
            log.debug("재고 확인: orderId={}", orderId);

            // 외부 API 호출
            long startTime = System.currentTimeMillis();
            // paymentService.processPayment(...)
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("결제 API 응답: orderId={}, 소요시간={}ms", orderId, elapsed);

            log.info("주문 처리 완료: orderId={}", orderId);

        } catch (Exception e) {
            // 에러 로그: 예외 객체를 마지막 파라미터로 전달 → 스택 트레이스 출력
            log.error("주문 처리 실패: orderId={}, userId={}", orderId, userId, e);
            throw e;
        }
    }

    /**
     * 민감 정보 마스킹 예시.
     */
    public void maskingExample() {
        String email = "user@example.com";
        String phone = "010-1234-5678";

        // 이메일 마스킹: u***@example.com
        String maskedEmail = email.charAt(0)
                + "***"
                + email.substring(email.indexOf("@"));

        // 전화번호 마스킹: 010-****-5678
        String maskedPhone = phone.substring(0, 4)
                + "****"
                + phone.substring(8);

        log.info("사용자 정보: email={}, phone={}", maskedEmail, maskedPhone);
    }
}
