package kr.co.example.thread;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * ========================================================================
 * [6] 스레드 풀 설정 - ThreadPoolTaskExecutor
 * ========================================================================
 *
 * ── 핵심 개념 ──
 *
 * 1. 스레드 풀이 필요한 이유
 *    - 스레드 생성/소멸 비용이 비쌈
 *    - 무한 스레드 생성 → 메모리 고갈
 *    - 풀로 스레드를 재사용하여 성능 최적화
 *
 * 2. ThreadPoolTaskExecutor 동작 흐름
 *    ┌───────────────────────────────────────────────────────┐
 *    │ 작업 제출                                             │
 *    │    ↓                                                  │
 *    │ corePoolSize 미만? → 새 스레드 생성하여 실행           │
 *    │    ↓ (코어 풀 가득 참)                                │
 *    │ queueCapacity 미만? → 큐에 대기                       │
 *    │    ↓ (큐도 가득 참)                                   │
 *    │ maxPoolSize 미만? → 추가 스레드 생성하여 실행          │
 *    │    ↓ (최대 풀도 가득 참)                               │
 *    │ RejectedExecutionHandler 실행                         │
 *    └───────────────────────────────────────────────────────┘
 *
 * 3. RejectedExecutionHandler (거부 정책)
 *    ┌──────────────────┬─────────────────────────────────────┐
 *    │ CallerRunsPolicy  │ 호출자 스레드에서 직접 실행          │
 *    │                   │ → 작업 유실 없음, 호출자 지연 발생  │
 *    ├──────────────────┼─────────────────────────────────────┤
 *    │ AbortPolicy       │ RejectedExecutionException 발생    │
 *    │ (기본값)          │ → 작업 유실, 예외 처리 필요         │
 *    ├──────────────────┼─────────────────────────────────────┤
 *    │ DiscardPolicy     │ 조용히 버림 (예외 없음)             │
 *    │                   │ → 작업 유실, 로그 없음              │
 *    ├──────────────────┼─────────────────────────────────────┤
 *    │ DiscardOldest     │ 큐에서 가장 오래된 작업 버리고 추가 │
 *    │                   │ → 최신 작업 우선                    │
 *    └──────────────────┴─────────────────────────────────────┘
 *
 * 4. 풀 사이징 가이드
 *    - CPU 바운드: corePoolSize ≈ CPU 코어 수
 *    - I/O 바운드: corePoolSize ≈ CPU 코어 수 × (1 + 대기/실행 비율)
 *    - 예: 4코어, I/O 대기 80% → 4 × (1 + 0.8/0.2) = 20
 *
 * ── 사용 특성 ──
 *
 * 장점:
 * - 용도별 스레드 풀 분리 → 장애 격리
 * - 환경별 설정 (@Value) → dev/prod 튜닝
 * - Spring 관리 빈 → 종료 시 안전한 shutdown
 *
 * 주의점:
 * - 풀 사이즈가 너무 크면 컨텍스트 스위칭 오버헤드
 * - 풀 사이즈가 너무 작으면 작업 대기 시간 증가
 * - 큐가 무제한이면 메모리 고갈 위험 (반드시 제한 설정)
 */
@Slf4j
@Configuration
public class ThreadPoolConfig {

    /**
     * 범용 비동기 작업 Executor
     *
     * corePoolSize(10): 항상 유지되는 기본 스레드 수
     * maxPoolSize(50): 큐가 가득 찼을 때 최대 확장 스레드 수
     * queueCapacity(100): 코어 스레드가 모두 바쁠 때 대기할 작업 수
     * threadNamePrefix: 로그에서 스레드 식별용
     */
    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);                 // 기본 스레드 수
        executor.setMaxPoolSize(50);                  // 최대 스레드 수
        executor.setQueueCapacity(100);               // 대기 큐 크기
        executor.setThreadNamePrefix("async-");       // 스레드 이름 접두사
        executor.initialize();

        log.info("[ThreadPool] taskExecutor - core=10, max=50, queue=100");
        return executor;
    }

    /**
     * 외부 API 호출 전용 Executor (환경별 설정)
     *
     * I/O 바운드 작업이므로 코어 풀을 크게 설정.
     * application.yml에서 환경별 값을 주입받아 dev/prod 튜닝.
     *
     * CallerRunsPolicy: 풀 + 큐가 모두 가득 차면
     * 작업을 요청한 스레드(호출자)에서 직접 실행.
     * → 작업 유실 없음 + 자연스러운 백프레셔 효과
     *
     * keepAliveSeconds(60): 코어 초과 스레드의 유휴 유지 시간
     * → 60초간 유휴 상태이면 해당 스레드 종료
     */
    @Bean(name = "externalApiExecutor")
    public ThreadPoolTaskExecutor externalApiExecutor(
            @Value("${executor.refund.core-pool-size:10}") int corePoolSize,
            @Value("${executor.refund.max-pool-size:15}") int maxPoolSize,
            @Value("${executor.refund.queue-capacity:200}") int queueCapacity) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);       // 환경별 코어 스레드
        executor.setMaxPoolSize(maxPoolSize);         // 환경별 최대 스레드
        executor.setQueueCapacity(queueCapacity);     // 환경별 큐 크기
        executor.setThreadNamePrefix("ext-api-");     // 스레드 이름
        executor.setKeepAliveSeconds(60);             // 유휴 스레드 유지 시간

        // 거부 정책: 호출자 스레드에서 직접 실행 (작업 유실 방지)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();

        log.info("[ThreadPool] externalApiExecutor - core={}, max={}, queue={}",
                corePoolSize, maxPoolSize, queueCapacity);
        return executor;
    }

    /**
     * 소규모 비동기 디스패치 Executor
     *
     * 소수의 비동기 작업을 디스패치하는 용도.
     * 비동기 작업 자체가 오래 걸리지 않는 경우에 적합.
     * (예: 알림 발송 트리거, 이벤트 발행)
     */
    @Bean(name = "dispatchExecutor")
    public ThreadPoolTaskExecutor dispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);                  // 최소 스레드
        executor.setMaxPoolSize(4);                   // 최대 스레드
        executor.setQueueCapacity(5);                 // 소규모 큐
        executor.setThreadNamePrefix("dispatch-");
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();

        log.info("[ThreadPool] dispatchExecutor - core=2, max=4, queue=5");
        return executor;
    }

    /**
     * I/O 바운드 배치 Executor
     *
     * 외부 HTTP 호출을 병렬로 처리하는 용도.
     * I/O 대기 시간이 길기 때문에 코어보다 큰 풀 사이즈 적용.
     */
    @Bean(name = "batchExecutor")
    public ThreadPoolTaskExecutor batchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);                  // I/O 바운드 → 코어 수보다 큼
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("batch-");
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();

        log.info("[ThreadPool] batchExecutor - core=4, max=8, queue=10");
        return executor;
    }
}
