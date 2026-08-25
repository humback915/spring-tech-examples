package kr.co.example.exception;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 예외처리 AOP 예제 — 통합 예외처리 vs 개별 예외처리.
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  Spring 예외처리 방식 3가지                                            │
 * ├──────────────────────────────────────────────────────────────────────┤
 * │                                                                      │
 * │  [1] @RestControllerAdvice (통합 예외처리 — 가장 일반적)              │
 * │      → GlobalExceptionHandler.java 참고                               │
 * │      → 모든 컨트롤러의 예외를 한 곳에서 처리                            │
 * │                                                                      │
 * │  [2] 컨트롤러 내부 @ExceptionHandler (개별 예외처리)                   │
 * │      → 특정 컨트롤러에서만 동작하는 예외 핸들러                         │
 * │      → 해당 컨트롤러의 예외만 처리                                     │
 * │                                                                      │
 * │  [3] AOP @AfterThrowing / @Around (횡단 예외처리)                     │
 * │      → 서비스 계층 예외를 공통으로 로깅/변환                            │
 * │      → 컨트롤러뿐 아니라 모든 Bean에 적용 가능                         │
 * │                                                                      │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  예외처리 우선순위 (같은 예외 타입일 때)                                │
 * ├──────────────────────────────────────────────────────────────────────┤
 * │  1. 컨트롤러 내부 @ExceptionHandler (개별)  ← 가장 높음               │
 * │  2. @RestControllerAdvice @ExceptionHandler (통합)                    │
 * │  3. AOP는 컨트롤러 예외처리와 독립적으로 동작 (before/after)           │
 * │                                                                      │
 * │  개별 핸들러가 있으면 통합 핸들러는 호출되지 않음                       │
 * └──────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class ExceptionAopExample {

    // ====================================================================
    // [1] AOP @AfterThrowing — 예외 발생 후 로깅
    // ====================================================================

    /**
     * 서비스 예외 로깅 AOP — @AfterThrowing.
     *
     * <pre>
     * @AfterThrowing:
     * - 대상 메서드에서 예외가 발생한 "후"에 실행
     * - 예외를 잡지 않음 → 예외는 그대로 전파됨
     * - 순수 로깅/모니터링 목적
     *
     * @Around와의 차이:
     * - @AfterThrowing: 예외 후 로깅만 (예외 전파 변경 불가)
     * - @Around: 예외를 잡아서 다른 예외로 변환하거나 기본값 반환 가능
     * </pre>
     */
    @Slf4j
    @Aspect
    @Component
    public static class ExceptionLoggingAspect {

        /**
         * 서비스 계층 예외 자동 로깅.
         * throwing = "ex" → 발생한 예외 객체를 파라미터로 받음.
         */
        @AfterThrowing(
                pointcut = "execution(* kr.co.example..*Service.*(..))",
                throwing = "ex"
        )
        public void logServiceException(Exception ex) {
            log.error("[AOP] 서비스 예외 발생: type={}, message={}",
                    ex.getClass().getSimpleName(), ex.getMessage());

            // 모니터링 시스템에 알림 전송 (Slack, PagerDuty 등)
            // alertService.sendAlert(ex);
        }

        /**
         * 특정 예외 타입만 처리.
         * DomainException만 캐치하여 비즈니스 예외 통계 수집.
         */
        @AfterThrowing(
                pointcut = "execution(* kr.co.example..*Service.*(..))",
                throwing = "ex"
        )
        public void logDomainException(CustomException.DomainException ex) {
            log.warn("[AOP] 비즈니스 예외: code={}, status={}, message={}",
                    ex.getCode(), ex.getHttpStatus(), ex.getMessage());
            // 메트릭 수집: 에러 코드별 발생 횟수 등
        }
    }

    // ====================================================================
    // [2] AOP @Around — 예외 변환/재시도
    // ====================================================================

    /**
     * 예외 변환 AOP — 외부 API 예외를 내부 예외로 래핑.
     *
     * <pre>
     * @Around의 예외 처리 패턴:
     * 1. 예외 변환: 외부 라이브러리 예외 → 도메인 예외로 변환
     * 2. 재시도:    일시적 장애 시 자동 재시도
     * 3. 폴백:      예외 시 기본값 반환
     * 4. 로깅+전파: 예외를 로깅하고 그대로 던지기
     * </pre>
     */
    @Slf4j
    @Aspect
    @Component
    public static class ExceptionTranslationAspect {

        /**
         * 외부 API 호출 예외 → DomainException 변환.
         *
         * <pre>
         * 외부 API 라이브러리의 예외(HttpClientErrorException 등)를
         * 내부 도메인 예외로 변환하여 상위 계층에서 일관되게 처리.
         * </pre>
         */
        @Around("execution(* kr.co.example.httpclient..*.*(..))")
        public Object translateExternalException(ProceedingJoinPoint joinPoint) throws Throwable {
            try {
                return joinPoint.proceed();
            } catch (Exception e) {
                log.error("[AOP] 외부 API 호출 실패: method={}, error={}",
                        joinPoint.getSignature().getName(), e.getMessage());

                // 외부 예외 → 도메인 예외 변환
                throw new CustomException.DomainException(
                        CustomException.DomainExceptionCode.EXTERNAL_API_ERROR);
            }
        }

        /**
         * 재시도 AOP — 일시적 장애 시 자동 재시도.
         *
         * <pre>
         * 데이터베이스 데드락, 네트워크 일시 오류 등에서 활용.
         * 재시도 횟수와 간격을 지정하여 복원력 향상.
         * </pre>
         */
        // @Around("@annotation(kr.co.example.annotation.Retryable)")
        public Object retryOnFailure(ProceedingJoinPoint joinPoint) throws Throwable {
            int maxRetries = 3;
            int retryCount = 0;
            Exception lastException = null;

            while (retryCount < maxRetries) {
                try {
                    return joinPoint.proceed();
                } catch (Exception e) {
                    lastException = e;
                    retryCount++;
                    log.warn("[AOP] 재시도 {}/{}: method={}, error={}",
                            retryCount, maxRetries,
                            joinPoint.getSignature().getName(),
                            e.getMessage());

                    if (retryCount < maxRetries) {
                        Thread.sleep(1000L * retryCount); // 점진적 대기
                    }
                }
            }
            throw lastException;
        }
    }

    // ====================================================================
    // [3] 컨트롤러 내부 @ExceptionHandler (개별 예외처리)
    // ====================================================================

    /**
     * 개별 컨트롤러 예외처리 — 해당 컨트롤러에서만 동작.
     *
     * <pre>
     * 사용 시기:
     * - 특정 컨트롤러에서만 다른 형태의 에러 응답이 필요한 경우
     * - 글로벌 핸들러의 동작을 해당 컨트롤러에서 오버라이드하고 싶은 경우
     * - 특정 API의 에러 응답 형식이 다른 API와 다른 경우
     *
     * 우선순위: 컨트롤러 내부 @ExceptionHandler > @RestControllerAdvice
     * → 컨트롤러 내부에 핸들러가 있으면 글로벌 핸들러는 호출되지 않음
     * </pre>
     */
    @RestController
    public static class SpecificController {

        /**
         * 이 컨트롤러에서만 동작하는 예외 핸들러.
         * 같은 예외 타입이 GlobalExceptionHandler에도 있으면
         * 이 핸들러가 우선 실행됨.
         */
        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
            // 이 컨트롤러 전용 에러 응답 형식
            ErrorResponse response = ErrorResponse.of(
                    400, "SPECIFIC_VALIDATION_ERROR", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    // ====================================================================
    // [4] 특정 패키지에만 적용되는 @RestControllerAdvice
    // ====================================================================

    /**
     * 패키지별 예외처리 분리 — basePackages 또는 assignableTypes 사용.
     *
     * <pre>
     * 대규모 프로젝트에서 모듈별로 다른 예외 처리가 필요할 때.
     *
     * @RestControllerAdvice(basePackages = "kr.co.example.admin")
     * → admin 패키지의 컨트롤러에서만 동작
     *
     * @RestControllerAdvice(assignableTypes = {UserController.class, OrderController.class})
     * → 특정 컨트롤러에서만 동작
     *
     * @RestControllerAdvice(annotations = RestController.class)
     * → @RestController가 붙은 모든 컨트롤러에서 동작 (기본)
     * </pre>
     */
    @Slf4j
    @RestControllerAdvice(basePackages = "kr.co.example.rest")
    public static class RestModuleExceptionHandler {

        @ExceptionHandler(IllegalStateException.class)
        public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
            log.warn("[REST 모듈] IllegalStateException: {}", e.getMessage());
            ErrorResponse response = ErrorResponse.of(
                    409, "INVALID_STATE", e.getMessage());
            return ResponseEntity.status(409).body(response);
        }
    }

    // ====================================================================
    // [5] 예외처리 전략 정리
    // ====================================================================

    /**
     * 예외처리 전략 선택 가이드.
     *
     * <pre>
     * ┌───────────────────────────────────────────────────────────────────┐
     * │  방식                    │  적용 범위     │  용도                  │
     * ├──────────────────────────┼────────────────┼────────────────────────┤
     * │  @RestControllerAdvice   │  전체 컨트롤러 │  통합 에러 응답 (기본)  │
     * │  (GlobalExceptionHandler)│                │  모든 API 공통 처리     │
     * ├──────────────────────────┼────────────────┼────────────────────────┤
     * │  @RestControllerAdvice   │  특정 패키지   │  모듈별 다른 에러 형식  │
     * │  (basePackages)          │                │  admin vs api 분리      │
     * ├──────────────────────────┼────────────────┼────────────────────────┤
     * │  컨트롤러 내부           │  해당 컨트롤러 │  특정 API 전용 처리     │
     * │  @ExceptionHandler       │  만             │  글로벌 핸들러 오버라이드│
     * ├──────────────────────────┼────────────────┼────────────────────────┤
     * │  AOP @AfterThrowing      │  모든 Bean     │  예외 로깅/모니터링     │
     * │                          │                │  예외를 잡지 않고 전파  │
     * ├──────────────────────────┼────────────────┼────────────────────────┤
     * │  AOP @Around             │  모든 Bean     │  예외 변환/재시도/폴백  │
     * │                          │                │  예외를 잡아서 처리     │
     * └──────────────────────────┴────────────────┴────────────────────────┘
     *
     * 권장 조합:
     * 1. GlobalExceptionHandler (통합) — 모든 API 공통 에러 응답
     * 2. AOP @AfterThrowing — 서비스 예외 자동 로깅/모니터링
     * 3. 필요시 컨트롤러 개별 핸들러로 오버라이드
     * </pre>
     */
}
