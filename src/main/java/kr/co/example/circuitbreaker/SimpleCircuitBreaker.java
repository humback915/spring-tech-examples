package kr.co.example.circuitbreaker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * ========================================================================
 * [11] Circuit Breaker (서킷 브레이커) - 수동 구현
 * ========================================================================
 *
 * ── 핵심 개념 ──
 *
 * 외부 서비스 호출 실패가 반복될 때,
 * 추가 호출을 차단하여 시스템 전체의 연쇄 장애를 방지하는 패턴.
 *
 * "전기 회로의 차단기"와 같은 개념:
 * - 정상: 전류 흐름 (요청 전달)
 * - 과부하: 차단기 동작 (요청 차단)
 * - 복구: 차단기 해제 (요청 재개)
 *
 * ── 상태 전이 ──
 * ┌──────────────────────────────────────────────────────┐
 * │                                                      │
 * │  CLOSED ──(failureThreshold 도달)──→ OPEN            │
 * │  (정상)                               (차단)         │
 * │    ↑                                    │            │
 * │    │                          timeout 경과           │
 * │    │                                    ↓            │
 * │    └───(성공)──── HALF_OPEN ───(실패)──→ OPEN        │
 * │                  (시험 허용)                          │
 * │                                                      │
 * └──────────────────────────────────────────────────────┘
 *
 * CLOSED (닫힘 - 정상):
 *   → 모든 요청을 통과시킴
 *   → 실패 횟수를 카운트
 *   → 연속 실패가 threshold에 도달하면 OPEN으로 전환
 *
 * OPEN (열림 - 차단):
 *   → 모든 요청을 즉시 거부 (외부 호출 없이 즉시 실패)
 *   → timeout(냉각 시간) 경과 후 HALF_OPEN으로 전환
 *   → 불필요한 외부 호출을 방지하여 시스템 보호
 *
 * HALF_OPEN (반열림 - 시험):
 *   → 제한된 요청만 통과시켜 복구 여부 확인
 *   → 성공하면 CLOSED로 복귀 (정상화)
 *   → 실패하면 다시 OPEN으로 전환
 *
 * ── 사용 특성 ──
 *
 * 장점:
 * - 연쇄 장애(Cascading Failure) 방지
 * - 장애 서비스에 대한 불필요한 대기 시간 제거
 * - 빠른 실패(Fail-Fast)로 사용자 경험 향상
 * - 장애 서비스의 복구 시간 확보
 *
 * 주의점:
 * - threshold, timeout 값을 적절히 튜닝해야 함
 * - 너무 민감하면 일시적 오류에도 차단됨
 * - 너무 둔감하면 장애 전파 방지 효과 감소
 * - 실무에서는 Resilience4j, Hystrix 등 라이브러리 사용 권장
 */
@Slf4j
@Component
public class SimpleCircuitBreaker {

    /** 서킷 브레이커 상태 열거형 */
    private enum State {
        CLOSED,    // 정상 (요청 통과)
        OPEN,      // 차단 (요청 거부)
        HALF_OPEN  // 시험 (제한된 요청 허용)
    }

    /** 현재 상태 (AtomicReference: 스레드 안전) */
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);

    /** 연속 실패 횟수 (AtomicInteger: 스레드 안전한 카운터) */
    private final AtomicInteger failureCount = new AtomicInteger(0);

    /** OPEN 상태가 된 시점 */
    private volatile Instant lastFailureTime = Instant.now();

    /** OPEN으로 전환되는 연속 실패 임계값 */
    private static final int FAILURE_THRESHOLD = 5;

    /** OPEN → HALF_OPEN 전환 대기 시간(초) */
    private static final long TIMEOUT_SECONDS = 30;

    /**
     * 서킷 브레이커를 통한 외부 호출 실행
     *
     * @param action   실행할 외부 호출 로직
     * @param fallback 서킷이 열려있을 때 실행할 폴백 로직
     * @param <T>      반환 타입
     * @return         호출 결과 또는 폴백 결과
     */
    public <T> T execute(Supplier<T> action, Supplier<T> fallback) {

        // ── OPEN 상태 처리 ──
        if (state.get() == State.OPEN) {
            // timeout 경과 여부 확인
            if (Instant.now().isAfter(lastFailureTime.plusSeconds(TIMEOUT_SECONDS))) {
                // timeout 경과 → HALF_OPEN으로 전환 (시험 허용)
                state.set(State.HALF_OPEN);
                log.info("[CircuitBreaker] OPEN → HALF_OPEN (timeout 경과, 시험 허용)");
            } else {
                // timeout 미경과 → 즉시 폴백 반환 (외부 호출 안 함)
                log.warn("[CircuitBreaker] OPEN 상태 - 요청 차단, 폴백 실행");
                return fallback.get();
            }
        }

        // ── CLOSED 또는 HALF_OPEN: 실제 호출 시도 ──
        try {
            T result = action.get();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure();
            log.error("[CircuitBreaker] 호출 실패 - failureCount={}, error={}",
                    failureCount.get(), e.getMessage());
            return fallback.get();
        }
    }

    /**
     * 호출 성공 시 처리
     *
     * HALF_OPEN 상태에서 성공하면 CLOSED로 복귀 (정상화).
     * 실패 카운트 초기화.
     */
    private void onSuccess() {
        if (state.get() == State.HALF_OPEN) {
            state.set(State.CLOSED);
            log.info("[CircuitBreaker] HALF_OPEN → CLOSED (복구 확인)");
        }
        failureCount.set(0);
    }

    /**
     * 호출 실패 시 처리
     *
     * 실패 카운트를 증가시키고, 임계값에 도달하면 OPEN으로 전환.
     * HALF_OPEN 상태에서 실패하면 즉시 OPEN으로 전환.
     */
    private void onFailure() {
        int count = failureCount.incrementAndGet();

        if (state.get() == State.HALF_OPEN) {
            // HALF_OPEN에서 실패 → 즉시 OPEN
            state.set(State.OPEN);
            lastFailureTime = Instant.now();
            log.warn("[CircuitBreaker] HALF_OPEN → OPEN (시험 실패)");
        } else if (count >= FAILURE_THRESHOLD) {
            // 연속 실패가 임계값 도달 → OPEN
            state.set(State.OPEN);
            lastFailureTime = Instant.now();
            log.warn("[CircuitBreaker] CLOSED → OPEN (연속 실패 {} 회)", count);
        }
    }

    /**
     * 현재 상태 조회 (모니터링용)
     */
    public String getState() {
        return state.get().name();
    }

    /**
     * 연속 실패 횟수 조회 (모니터링용)
     */
    public int getFailureCount() {
        return failureCount.get();
    }
}
