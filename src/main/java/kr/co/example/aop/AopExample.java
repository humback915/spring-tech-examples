package kr.co.example.aop;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * AOP + Interceptor 예제 — 횡단 관심사(Cross-Cutting Concerns) 처리.
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  AOP (Aspect-Oriented Programming) 핵심 용어                         │
 * ├──────────────────┬───────────────────────────────────────────────────┤
 * │  Aspect          │ 횡단 관심사를 모듈화한 클래스 (@Aspect)            │
 * │  Advice          │ 실행할 로직 (Before, After, Around 등)            │
 * │  JoinPoint       │ Advice가 적용될 수 있는 지점 (메서드 실행)         │
 * │  Pointcut        │ JoinPoint 중 실제 Advice를 적용할 대상 선택        │
 * │  Weaving         │ Aspect를 대상에 연결하는 과정 (Spring은 런타임 프록시) │
 * └──────────────────┴───────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  AOP vs Interceptor vs Filter 비교                                   │
 * ├───────────────┬─────────────┬─────────────────┬─────────────────────┤
 * │               │  Filter     │  Interceptor    │  AOP                │
 * ├───────────────┼─────────────┼─────────────────┼─────────────────────┤
 * │  영역         │  서블릿     │  Spring MVC     │  Spring Bean 전체    │
 * │  적용 대상    │  HTTP 요청  │  컨트롤러 전후  │  모든 Bean 메서드     │
 * │  실행 순서    │  가장 먼저  │  컨트롤러 직전  │  메서드 호출 시       │
 * │  Spring DI    │  제한적     │  가능           │  가능                 │
 * │  적합한 용도  │  인코딩,보안│  인증,로깅,권한 │  트랜잭션,로깅,캐시    │
 * └───────────────┴─────────────┴─────────────────┴─────────────────────┘
 *
 * 실행 순서: Filter → Interceptor(preHandle) → AOP → Controller
 *           → AOP → Interceptor(postHandle) → Filter
 * </pre>
 */
public class AopExample {

    // ====================================================================
    // [1] @Aspect — AOP 예제
    // ====================================================================

    /**
     * 로깅 Aspect — 서비스 메서드 실행 전후 로그 기록.
     *
     * <pre>
     * Advice 종류:
     * ┌──────────────────┬───────────────────────────────────────────┐
     * │  @Before          │ 메서드 실행 전 (파라미터 검증, 로깅)      │
     * │  @AfterReturning  │ 메서드 정상 완료 후 (결과 로깅)           │
     * │  @AfterThrowing   │ 예외 발생 시 (에러 로깅)                  │
     * │  @After           │ 메서드 종료 후 (성공/실패 무관, finally)   │
     * │  @Around          │ 메서드 전후 모두 (가장 강력, 실행 제어)    │
     * └──────────────────┴───────────────────────────────────────────┘
     *
     * Pointcut 표현식 문법:
     * execution(수식어? 리턴타입 패키지.클래스.메서드(파라미터))
     *
     * 예시:
     * execution(* kr.co.example..*.*(..))
     *   → kr.co.example 하위 모든 패키지, 모든 클래스, 모든 메서드
     *
     * execution(public * kr.co.example..*Service.*(..))
     *   → *Service로 끝나는 클래스의 public 메서드
     *
     * @annotation(org.springframework.transaction.annotation.Transactional)
     *   → @Transactional이 붙은 메서드
     * </pre>
     */
    @Slf4j
    @Aspect
    @Component
    public static class LoggingAspect {

        /**
         * @Pointcut — 재사용 가능한 포인트컷 정의.
         * 서비스 계층의 모든 public 메서드에 적용.
         */
        @Pointcut("execution(* kr.co.example..*Service.*(..))")
        public void serviceLayer() {
        }

        /**
         * @Before — 메서드 실행 전 로깅.
         * JoinPoint: 실행 중인 메서드 정보 (이름, 파라미터 등)
         */
        @Before("serviceLayer()")
        public void logBefore(JoinPoint joinPoint) {
            log.info("[AOP] 메서드 호출: {}.{}() | 파라미터: {}",
                    joinPoint.getTarget().getClass().getSimpleName(),
                    joinPoint.getSignature().getName(),
                    Arrays.toString(joinPoint.getArgs()));
        }

        /**
         * @AfterReturning — 정상 완료 후 결과 로깅.
         * returning: 반환값을 파라미터로 받음.
         */
        @AfterReturning(pointcut = "serviceLayer()", returning = "result")
        public void logAfterReturning(JoinPoint joinPoint, Object result) {
            log.info("[AOP] 메서드 완료: {}.{}() | 결과: {}",
                    joinPoint.getTarget().getClass().getSimpleName(),
                    joinPoint.getSignature().getName(),
                    result);
        }

        /**
         * @AfterThrowing — 예외 발생 시 로깅.
         * throwing: 발생한 예외를 파라미터로 받음.
         */
        @AfterThrowing(pointcut = "serviceLayer()", throwing = "ex")
        public void logAfterThrowing(JoinPoint joinPoint, Exception ex) {
            log.error("[AOP] 예외 발생: {}.{}() | 예외: {}",
                    joinPoint.getTarget().getClass().getSimpleName(),
                    joinPoint.getSignature().getName(),
                    ex.getMessage());
        }
    }

    /**
     * 실행 시간 측정 Aspect — @Around 사용.
     */
    @Slf4j
    @Aspect
    @Component
    public static class PerformanceAspect {

        /**
         * @Around — 메서드 전후를 모두 감싸는 가장 강력한 Advice.
         *
         * <pre>
         * ProceedingJoinPoint:
         * - JoinPoint를 상속, proceed() 메서드 추가
         * - proceed()를 호출해야 대상 메서드가 실행됨
         * - proceed()를 호출하지 않으면 대상 메서드 실행 안 됨 (차단 가능)
         * - 반환값을 변경하거나 예외를 잡아서 다르게 처리 가능
         *
         * @Around는 강력하지만 proceed() 호출을 빠뜨리면 위험
         * → 단순 로깅은 @Before/@After 사용 권장
         * → 실행 시간 측정, 캐싱, 재시도 등에 @Around 사용
         * </pre>
         */
        @Around("execution(* kr.co.example..*Controller.*(..))")
        public Object measureExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
            long start = System.currentTimeMillis();

            try {
                Object result = joinPoint.proceed(); // ← 대상 메서드 실행
                return result;
            } finally {
                long elapsed = System.currentTimeMillis() - start;
                log.info("[Performance] {}.{}() — {}ms",
                        joinPoint.getTarget().getClass().getSimpleName(),
                        joinPoint.getSignature().getName(),
                        elapsed);

                // 느린 API 경고 (3초 이상)
                if (elapsed > 3000) {
                    log.warn("[Performance] 느린 API 감지: {}.{}() — {}ms",
                            joinPoint.getTarget().getClass().getSimpleName(),
                            joinPoint.getSignature().getName(),
                            elapsed);
                }
            }
        }
    }

    // ====================================================================
    // [2] HandlerInterceptor — Spring MVC 인터셉터
    // ====================================================================

    /**
     * 요청/응답 인터셉터 — 컨트롤러 전후 처리.
     *
     * <pre>
     * Interceptor 메서드 실행 순서:
     * 1. preHandle()      → 컨트롤러 호출 전 (false 반환 시 요청 중단)
     * 2. Controller 실행
     * 3. postHandle()     → 컨트롤러 정상 완료 후 (예외 시 호출 안 됨)
     * 4. afterCompletion()→ 요청 완료 후 (예외 발생 여부 무관, finally)
     *
     * 활용 예시:
     * - 요청 로깅 (IP, URI, 실행 시간)
     * - 인증/인가 검사
     * - API 호출 횟수 제한 (Rate Limiting)
     * - MDC(Mapped Diagnostic Context) 설정 (로그 추적 ID)
     * </pre>
     */
    @Slf4j
    @Component
    public static class RequestLoggingInterceptor implements HandlerInterceptor {

        /** 요청 시작 시각 저장용 attribute 키 */
        private static final String START_TIME = "requestStartTime";

        @Override
        public boolean preHandle(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Object handler) {
            request.setAttribute(START_TIME, System.currentTimeMillis());

            log.info("[Interceptor] 요청: {} {} | IP: {}",
                    request.getMethod(),
                    request.getRequestURI(),
                    request.getRemoteAddr());

            return true; // true: 계속 진행, false: 요청 중단
        }

        @Override
        public void afterCompletion(HttpServletRequest request,
                                    HttpServletResponse response,
                                    Object handler,
                                    Exception ex) {
            long startTime = (Long) request.getAttribute(START_TIME);
            long elapsed = System.currentTimeMillis() - startTime;

            log.info("[Interceptor] 응답: {} {} | status={} | {}ms",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    elapsed);
        }
    }

    // ====================================================================
    // [3] Interceptor 등록 — WebMvcConfigurer
    // ====================================================================

    /**
     * 인터셉터 등록 설정.
     * addPathPatterns: 적용할 URL 패턴
     * excludePathPatterns: 제외할 URL 패턴
     */
    @Configuration
    @RequiredArgsConstructor
    public static class WebMvcConfig implements WebMvcConfigurer {

        private final RequestLoggingInterceptor requestLoggingInterceptor;

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(requestLoggingInterceptor)
                    .addPathPatterns("/api/**")         // /api/** 경로에만 적용
                    .excludePathPatterns(               // 제외 경로
                            "/api/auth/**",             // 인증 관련
                            "/swagger-ui/**",           // Swagger
                            "/actuator/**"              // Actuator
                    );
        }
    }
}
