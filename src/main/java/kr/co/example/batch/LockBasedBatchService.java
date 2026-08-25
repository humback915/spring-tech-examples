package kr.co.example.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ========================================================================
 * 락(Lock) 기반 배치 처리 예제
 * ========================================================================
 *
 * ── 왜 배치에 락이 필요한가? ──
 *
 * 단일 서버 환경:
 * - @Scheduled가 한 인스턴스에서만 실행되므로 문제 없음
 *
 * 다중 서버(Scale-Out) 환경:
 * - 3대의 서버에서 같은 @Scheduled 배치가 동시에 실행
 * - 같은 데이터를 3번 처리 → 중복 처리, 데이터 정합성 깨짐
 * - 환불 배치가 3번 실행되면 3번 환불 → 재무 손실
 *
 * ┌─────────────────────────────────────────────────────────┐
 * │ 다중 인스턴스 환경에서 배치 중복 실행 문제                  │
 * │                                                          │
 * │ Server-1 ─── @Scheduled(04:00) ─── 환불 배치 실행 ✓       │
 * │ Server-2 ─── @Scheduled(04:00) ─── 환불 배치 실행 ✗ (중복!)│
 * │ Server-3 ─── @Scheduled(04:00) ─── 환불 배치 실행 ✗ (중복!)│
 * │                                                          │
 * │ → 분산 락으로 Server-1만 실행, 나머지는 skip              │
 * └─────────────────────────────────────────────────────────┘
 *
 * ── 3가지 락 전략 비교 ──
 *
 * ┌──────────────────┬────────────────────┬──────────────────────┬─────────────────────┐
 * │ 전략              │ AtomicBoolean       │ MySQL GET_LOCK        │ Redisson RLock       │
 * ├──────────────────┼────────────────────┼──────────────────────┼─────────────────────┤
 * │ 범위              │ 단일 JVM            │ 같은 DB 사용 인스턴스  │ 모든 인스턴스         │
 * │ 인프라 의존        │ 없음                │ MySQL 필요             │ Redis 필요           │
 * │ 성능              │ 매우 빠름 (ns)       │ 빠름 (DB 호출 1회)     │ 빠름 (Redis 호출 1회)│
 * │ 장애 시           │ JVM 종료 시 자동 해제│ 커넥션 종료 시 자동 해제│ Watchdog 자동 갱신   │
 * │ 적합한 환경       │ 단일 서버            │ DB 기반 멀티 서버      │ 대규모 클러스터      │
 * └──────────────────┴────────────────────┴──────────────────────┴─────────────────────┘
 *
 * ── 실무 권장: 2계층 락 구조 ──
 *
 * 1계층: AtomicBoolean (같은 JVM 내 중복 방지 - 비용 제로)
 * 2계층: 분산 락 (다른 서버 간 중복 방지 - 네트워크 비용 1회)
 *
 * AtomicBoolean을 먼저 확인하면 불필요한 Redis/DB 호출을 줄일 수 있음.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LockBasedBatchService {

    private final JdbcTemplate jdbcTemplate;
    private final RedissonClient redissonClient;

    // ================================================================
    // [1] AtomicBoolean - 단일 JVM 내 중복 실행 방지
    // ================================================================

    /**
     * AtomicBoolean: CAS(Compare-And-Swap) 기반 원자적 플래그
     *
     * compareAndSet(false, true):
     * - 현재 값이 false이면 → true로 변경하고 true 반환 (락 획득)
     * - 현재 값이 true이면 → 변경 없이 false 반환 (락 획득 실패)
     * - 원자적 연산이므로 동시 호출에도 하나만 성공
     *
     * 용도: 같은 JVM에서 @Scheduled가 겹치는 경우 방지
     * - fixedRate 사용 시 이전 실행이 완료되기 전에 다음 실행이 시작될 수 있음
     * - AtomicBoolean으로 "이미 실행 중이면 건너뛰기" 구현
     *
     * 한계: 다른 서버의 실행은 감지 불가
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * AtomicBoolean 기반 중복 실행 방지 배치
     *
     * fixedRate: 이전 시작 시점 기준 5분마다 실행
     * → 이전 배치가 5분 이상 걸리면 다음 실행과 겹칠 수 있음
     * → AtomicBoolean으로 동시 실행 방지
     */
    @Scheduled(fixedRate = 300_000)  // 5분마다
    public void localGuardedBatch() {
        // CAS: false → true 변경 시도 (원자적)
        if (!running.compareAndSet(false, true)) {
            log.info("[LocalLock] 이전 배치 실행 중 - 건너뜀");
            return;
        }

        try {
            log.info("[LocalLock] 배치 시작");
            processBatchWork();
            log.info("[LocalLock] 배치 완료");
        } catch (Exception e) {
            log.error("[LocalLock] 배치 실패", e);
        } finally {
            // 반드시 finally에서 플래그 해제
            // 예외 발생 시에도 다음 실행이 가능하도록
            running.set(false);
        }
    }

    // ================================================================
    // [2] MySQL GET_LOCK - DB 기반 분산 락
    // ================================================================

    /**
     * MySQL GET_LOCK을 이용한 분산 락 배치
     *
     * MySQL GET_LOCK(name, timeout):
     * - name: 락 이름 (문자열, 네임스페이스 역할)
     * - timeout: 락 획득 대기 시간 (초), 0이면 즉시 반환
     * - 반환값: 1(획득 성공), 0(타임아웃), NULL(에러)
     *
     * GET_LOCK 특성:
     * - 커넥션 레벨 락: 같은 커넥션에서만 RELEASE 가능
     * - 커넥션 종료 시 자동 해제 → 데드락 없음
     * - 서버 재시작/크래시 시에도 자동 해제
     * - 테이블/행 락과 독립적 (Advisory Lock)
     *
     * ┌──────────────────────────────────────────────────┐
     * │ Server-1: GET_LOCK('batch:settlement', 0) → 1    │
     * │           → 배치 실행                              │
     * │           → RELEASE_LOCK('batch:settlement')      │
     * │                                                    │
     * │ Server-2: GET_LOCK('batch:settlement', 0) → 0    │
     * │           → 이미 실행 중 → 건너뜀                  │
     * └──────────────────────────────────────────────────┘
     *
     * 주의:
     * - JdbcTemplate 사용 시 매번 커넥션이 달라질 수 있음
     *   → 같은 커넥션에서 GET_LOCK/RELEASE_LOCK 호출해야 함
     *   → 실무에서는 DataSource.getConnection() 직접 사용 권장
     * - MySQL 5.7+: 같은 세션에서 여러 명명 락 동시 보유 가능
     */
    @Scheduled(cron = "0 30 4 * * ?")  // 매일 04:30
    public void mysqlLockBatch() {
        // 1계층: 로컬 AtomicBoolean (같은 JVM 중복 방지)
        if (!running.compareAndSet(false, true)) {
            log.info("[MySQLLock] 로컬 중복 - 건너뜀");
            return;
        }

        try {
            // 2계층: MySQL 분산 락 (다른 서버 중복 방지)
            if (!tryMysqlLock("batch:settlement", 0)) {
                log.info("[MySQLLock] 다른 인스턴스 실행 중 - 건너뜀");
                return;
            }

            try {
                log.info("[MySQLLock] 배치 시작 - 정산 처리");
                processBatchWork();
                log.info("[MySQLLock] 배치 완료");
            } finally {
                releaseMysqlLock("batch:settlement");
            }
        } catch (Exception e) {
            log.error("[MySQLLock] 배치 실패", e);
        } finally {
            running.set(false);
        }
    }

    /**
     * MySQL GET_LOCK 획득
     *
     * @param lockName 락 이름
     * @param timeout  대기 시간 (초), 0이면 즉시 반환
     * @return 획득 성공 여부
     */
    private boolean tryMysqlLock(String lockName, int timeout) {
        Integer result = jdbcTemplate.queryForObject(
                "SELECT GET_LOCK(?, ?)",
                Integer.class,
                lockName, timeout
        );
        boolean acquired = Integer.valueOf(1).equals(result);
        log.debug("[MySQLLock] 락 획득 시도 - name={}, result={}", lockName, acquired);
        return acquired;
    }

    /**
     * MySQL RELEASE_LOCK 해제
     *
     * @param lockName 해제할 락 이름
     */
    private void releaseMysqlLock(String lockName) {
        jdbcTemplate.queryForObject(
                "SELECT RELEASE_LOCK(?)",
                Integer.class,
                lockName
        );
        log.debug("[MySQLLock] 락 해제 - name={}", lockName);
    }

    // ================================================================
    // [3] Redisson RLock - Redis 기반 분산 락
    // ================================================================

    /**
     * Redisson RLock을 이용한 분산 락 배치
     *
     * tryLock() 파라미터:
     * - waitTime: 락 획득 최대 대기 시간
     *   → 0: 즉시 반환 (다른 인스턴스 실행 중이면 skip)
     *   → N초: N초까지 대기 후 실패
     *
     * - leaseTime: 락 자동 해제 시간
     *   → 배치가 이 시간 내에 완료되지 않으면 자동 해제
     *   → 데드락 방지 (서버 크래시 시에도 자동 해제)
     *   → -1: Watchdog 모드 (30초마다 자동 갱신, 작업 완료까지 유지)
     *
     * ── Watchdog(워치독) 메커니즘 ──
     *
     * leaseTime을 지정하지 않으면(-1) Watchdog이 활성화:
     * - 30초마다 락 TTL을 자동 갱신
     * - 작업이 얼마나 걸리든 완료될 때까지 락 유지
     * - 서버 크래시 시 Watchdog도 종료 → 30초 후 락 자동 해제
     *
     * ┌────────────────────────────────────────────────────┐
     * │ leaseTime 지정 vs Watchdog 비교                     │
     * ├────────────────────┬───────────────────────────────┤
     * │ leaseTime=300초     │ Watchdog (-1)                 │
     * │ 300초 후 강제 해제   │ 작업 완료까지 자동 갱신        │
     * │ 배치 지연 시 위험     │ 크래시 시 30초 후 안전 해제    │
     * │ 예측 가능한 작업에 적합│ 가변적 작업 시간에 적합        │
     * └────────────────────┴───────────────────────────────┘
     */
    @Scheduled(cron = "0 10 1 * * ?")  // 매일 01:10
    public void redisLockBatch() {
        String lockKey = "batch:daily-cleanup";
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // tryLock: 즉시 시도(waitTime=0), Watchdog 모드(leaseTime=-1)
            boolean acquired = lock.tryLock(0, -1, TimeUnit.SECONDS);

            if (!acquired) {
                log.info("[RedisLock] 다른 인스턴스 실행 중 - 건너뜀");
                return;
            }

            try {
                log.info("[RedisLock] 배치 시작 - 일일 정리");
                processBatchWork();
                log.info("[RedisLock] 배치 완료");
            } finally {
                // 현재 스레드가 보유한 경우에만 해제
                // 다른 스레드가 해제하면 IllegalMonitorStateException 발생
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                    log.debug("[RedisLock] 락 해제 - key={}", lockKey);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[RedisLock] 인터럽트 발생", e);
        }
    }

    // ================================================================
    // [4] 재시도 로직이 포함된 락 배치
    // ================================================================

    /**
     * 재시도 전략이 포함된 배치 실행
     *
     * 배치 실패 시 재시도 패턴:
     * - 최대 재시도 횟수 제한 (무한 루프 방지)
     * - 재시도 간 대기 시간 (DB/외부 시스템 복구 대기)
     * - 재시도 간격을 점진적으로 늘리는 방식도 가능 (지수 백오프)
     *
     * ┌──────────────────────────────────────────┐
     * │ 실행 → 실패 → 2초 대기 → 재시도 1회       │
     * │                  → 실패 → 4초 대기 → 재시도 2회 │
     * │                           → 실패 → 최종 실패 기록 │
     * └──────────────────────────────────────────┘
     *
     * @param maxAttempts 최대 시도 횟수
     * @param delayMs     재시도 간 대기 시간 (밀리초)
     */
    public void executeWithRetry(int maxAttempts, long delayMs) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("[Retry] 배치 시도 {}/{}", attempt, maxAttempts);
                processBatchWork();
                log.info("[Retry] 배치 성공 - 시도 횟수={}", attempt);
                return;  // 성공 시 즉시 종료
            } catch (Exception e) {
                log.warn("[Retry] 배치 실패 - 시도 {}/{}, error={}",
                        attempt, maxAttempts, e.getMessage());

                if (attempt < maxAttempts) {
                    try {
                        // 재시도 전 대기 (DB 복구, 네트워크 안정화 대기)
                        Thread.sleep(delayMs * attempt);  // 점진적 대기 (2초, 4초, 6초...)
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        log.error("[Retry] 배치 최종 실패 - 모든 시도 소진");
        // 실무: 알림 발송, 모니터링 시스템 트리거
    }

    // ================================================================
    // 내부 헬퍼 메서드
    // ================================================================

    /**
     * 배치 작업 시뮬레이션
     */
    private void processBatchWork() {
        log.info("[Batch] 배치 작업 처리 중...");
        // 실무: 데이터 조회 → 변환 → 저장
    }
}
