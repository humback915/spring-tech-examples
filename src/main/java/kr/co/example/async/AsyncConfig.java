package kr.co.example.async;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * ========================================================================
 * [7] 비동기 처리 설정 - @EnableAsync, @Async
 * ========================================================================
 *
 * ── 핵심 개념 ──
 *
 * 1. @EnableAsync
 *    - Spring의 비동기 메서드 실행 인프라를 활성화
 *    - @Async 어노테이션이 붙은 메서드를 별도 스레드에서 실행
 *    - 내부적으로 AOP 프록시를 생성하여 스레드 풀에 위임
 *
 * 2. @Async 동작 원리
 *    ┌─────────────────────────────────────────────────────┐
 *    │ 호출자 → AOP 프록시 → TaskExecutor.submit(메서드)    │
 *    │                     ↓                               │
 *    │ 호출자는 즉시 반환 (비동기)                          │
 *    │ 별도 스레드에서 실제 메서드 실행                      │
 *    └─────────────────────────────────────────────────────┘
 *
 * 3. 반환 타입
 *    - void: 결과 불필요, fire-and-forget
 *    - Future<T>: 비동기 결과를 나중에 get()으로 조회
 *    - CompletableFuture<T>: 체이닝, 조합 가능한 비동기 결과
 *
 * 4. AsyncConfigurer 인터페이스
 *    - getAsyncExecutor(): @Async의 기본 실행 Executor 지정
 *    - getAsyncUncaughtExceptionHandler(): void 반환 @Async의 예외 처리
 *
 * ── 주의점 ──
 *
 * - self-invocation 문제: 같은 클래스 내부에서 @Async 메서드 호출 시
 *   프록시를 거치지 않아 동기로 실행됨
 *   → 반드시 외부 빈에서 호출해야 비동기 동작
 *
 * - SecurityContext 전파: 비동기 스레드는 호출자의 SecurityContext를 상속받지 않음
 *   → DelegatingSecurityContextExecutor 사용으로 해결
 *
 * - Transaction 전파: @Async 메서드는 호출자의 트랜잭션에 참여하지 않음
 *   → 비동기 메서드 내에서 별도 트랜잭션 관리 필요
 */
@Slf4j
@Configuration
@EnableAsync  // @Async 활성화
public class AsyncConfig implements AsyncConfigurer {

    /**
     * @Async의 기본 Executor 지정
     *
     * @Async에 executor 이름을 지정하지 않으면 이 Executor 사용.
     * 명시적으로 @Async("taskExecutor") 등으로 지정하면 해당 빈 사용.
     *
     * CachedThreadPool: 필요할 때 스레드 생성, 60초 유휴 시 제거.
     * → 요청량이 불규칙한 경우에 적합
     * → 주의: 무제한 스레드 생성 가능 → 대량 요청 시 메모리 주의
     */
    @Override
    public Executor getAsyncExecutor() {
        return Executors.newCachedThreadPool();
    }

    /**
     * void 반환 @Async 메서드의 예외 처리기
     *
     * void 메서드는 예외가 호출자에게 전파되지 않으므로
     * 여기서 로깅/알림 등의 예외 처리를 수행.
     *
     * Future/CompletableFuture 반환 메서드는 get() 시 예외 전파.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (Throwable ex, Method method, Object... params) -> {
            log.error("[Async] 비동기 예외 발생 - method={}, error={}",
                    method.getName(), ex.getMessage(), ex);
            // 알림 발송, 모니터링 시스템 전달 등
        };
    }
}
