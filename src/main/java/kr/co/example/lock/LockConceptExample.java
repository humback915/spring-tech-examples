package kr.co.example.lock;

import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 락(Lock) 개념 종합 예제 — 애플리케이션 락, DB 락, 트랜잭션 격리 수준.
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  왜 락이 필요한가?                                                    │
 * ├──────────────────────────────────────────────────────────────────────┤
 * │  여러 스레드/프로세스가 동시에 같은 데이터에 접근하면:                   │
 * │  - 데이터 불일치 (Lost Update, Dirty Read 등)                         │
 * │  - 재고 마이너스 (동시 주문 시 재고 0 이하 감소)                       │
 * │  - 중복 처리 (같은 요청을 여러 번 처리)                                │
 * │  → 동시성 제어(Concurrency Control)가 필요 → 락 사용                  │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  락 종류 분류                                                         │
 * ├──────────────────┬───────────────────────────────────────────────────┤
 * │  [1] 애플리케이션 │ JVM 내부 — synchronized, ReentrantLock, Atomic    │
 * │      락          │ 단일 서버에서만 유효, 서버 간 공유 불가              │
 * ├──────────────────┼───────────────────────────────────────────────────┤
 * │  [2] DB 락       │ 데이터베이스 제공 — Row Lock, Table Lock           │
 * │                  │ 비관적 락: SELECT FOR UPDATE                       │
 * │                  │ 낙관적 락: @Version                                │
 * │                  │ Advisory Lock: GET_LOCK (MySQL)                   │
 * ├──────────────────┼───────────────────────────────────────────────────┤
 * │  [3] 트랜잭션    │ 격리 수준(Isolation Level)에 따른 암묵적 락         │
 * │      격리 수준   │ READ_COMMITTED, REPEATABLE_READ, SERIALIZABLE     │
 * ├──────────────────┼───────────────────────────────────────────────────┤
 * │  [4] 분산 락     │ 서버 간 공유 — Redis(Redisson), ZooKeeper         │
 * │                  │ MSA/클러스터 환경에서 필수                          │
 * │                  │ → redis/RedisStockService.java 참고                │
 * │                  │ → batch/LockBasedBatchService.java 참고            │
 * └──────────────────┴───────────────────────────────────────────────────┘
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LockConceptExample {

    private final JdbcTemplate jdbcTemplate;

    // ====================================================================
    // [1] 애플리케이션 락 — JVM 내부 (단일 서버)
    // ====================================================================

    /**
     * synchronized — Java 기본 동기화.
     *
     * <pre>
     * ┌─────────────────────────────────────────────────────────────────┐
     * │  synchronized 특징                                               │
     * ├─────────────────────────────────────────────────────────────────┤
     * │  - JVM 모니터 락 기반 (intrinsic lock)                           │
     * │  - 한 번에 하나의 스레드만 진입 가능                               │
     * │  - 메서드 또는 블록 단위로 적용                                    │
     * │  - 단일 JVM에서만 유효 (서버 2대 이상이면 무의미)                  │
     * │  - 타임아웃 설정 불가 → 데드락 위험                                │
     * └─────────────────────────────────────────────────────────────────┘
     * </pre>
     */
    private int stock = 100;

    /** synchronized 메서드 — 메서드 전체에 락 적용 */
    public synchronized void decreaseStockSync() {
        if (stock > 0) {
            stock--;
            log.info("재고 감소: {}", stock);
        }
    }

    /** synchronized 블록 — 특정 구간에만 락 적용 (더 세밀한 제어) */
    private final Object stockLock = new Object();

    public void decreaseStockBlock() {
        // 락이 필요 없는 작업 (검증 등)
        log.debug("재고 감소 요청");

        synchronized (stockLock) { // 이 블록만 동기화
            if (stock > 0) {
                stock--;
            }
        }
        // 락이 필요 없는 후처리
    }

    /**
     * ReentrantLock — synchronized보다 유연한 락.
     *
     * <pre>
     * ┌─────────────────────────────────────────────────────────────────┐
     * │  ReentrantLock vs synchronized                                   │
     * ├──────────────────┬──────────────────────────────────────────────┤
     * │                  │  synchronized      │  ReentrantLock          │
     * ├──────────────────┼────────────────────┼────────────────────────┤
     * │  타임아웃        │  불가              │  tryLock(timeout) 가능  │
     * │  공정성          │  비공정            │  fair 옵션 지원         │
     * │  인터럽트        │  불가              │  lockInterruptibly()    │
     * │  try-finally     │  자동 해제         │  수동 unlock() 필수     │
     * │  Condition       │  wait/notify       │  여러 Condition 생성    │
     * └──────────────────┴────────────────────┴────────────────────────┘
     * </pre>
     */
    private final ReentrantLock reentrantLock = new ReentrantLock();

    public void decreaseStockWithReentrantLock() {
        // tryLock: 타임아웃 설정 가능 → 데드락 방지
        boolean acquired = false;
        try {
            acquired = reentrantLock.tryLock(3, TimeUnit.SECONDS);
            if (acquired) {
                if (stock > 0) {
                    stock--;
                }
            } else {
                log.warn("락 획득 실패 (타임아웃)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (acquired) {
                reentrantLock.unlock(); // 반드시 finally에서 해제
            }
        }
    }

    /**
     * AtomicInteger — CAS(Compare-And-Swap) 기반 원자적 연산.
     *
     * <pre>
     * 락 없이 원자적 연산 수행 — Lock-Free 알고리즘.
     * 내부적으로 CPU의 CAS 명령어 사용 → 매우 빠름.
     *
     * 동작 원리:
     * 1. 현재값 읽기 (expected = 100)
     * 2. 새 값 계산 (new = 99)
     * 3. CAS: if (현재값 == expected) { 현재값 = new; return true; }
     * 4. 실패 시 재시도 (다른 스레드가 먼저 변경한 경우)
     *
     * 적합: 단순 카운터, 플래그 — 복잡한 비즈니스 로직에는 부적합
     * </pre>
     */
    private final AtomicInteger atomicStock = new AtomicInteger(100);

    public void decreaseStockAtomic() {
        int remaining = atomicStock.decrementAndGet(); // 원자적 감소
        if (remaining < 0) {
            atomicStock.incrementAndGet(); // 롤백
            throw new RuntimeException("재고 부족");
        }
    }

    /**
     * ConcurrentHashMap — 키 단위 세밀한 락.
     *
     * <pre>
     * 상품별로 독립적인 재고 관리 시.
     * ConcurrentHashMap은 내부적으로 세그먼트별 락 → 높은 동시성.
     * </pre>
     */
    private final ConcurrentHashMap<Long, AtomicInteger> stockMap = new ConcurrentHashMap<>();

    public void decreaseStockByProduct(Long productId) {
        AtomicInteger productStock = stockMap.computeIfAbsent(productId,
                k -> new AtomicInteger(100));

        int remaining = productStock.decrementAndGet();
        if (remaining < 0) {
            productStock.incrementAndGet();
            throw new RuntimeException("상품 " + productId + " 재고 부족");
        }
    }

    // ====================================================================
    // [2] DB 락 — 비관적 락 (Pessimistic Lock)
    // ====================================================================

    /**
     * 비관적 락 — SELECT FOR UPDATE.
     *
     * <pre>
     * ┌─────────────────────────────────────────────────────────────────┐
     * │  비관적 락 (Pessimistic Lock)                                    │
     * ├─────────────────────────────────────────────────────────────────┤
     * │  "충돌이 반드시 발생할 것이다" → 미리 락을 잡고 시작                │
     * │                                                                 │
     * │  SELECT * FROM products WHERE id = 1 FOR UPDATE;                │
     * │  → 해당 행(row)에 배타적 락(X Lock) 걸림                          │
     * │  → 다른 트랜잭션은 이 행에 UPDATE/DELETE 불가                     │
     * │  → 읽기(SELECT)도 락 모드에 따라 대기 가능                        │
     * │  → 트랜잭션 종료(COMMIT/ROLLBACK) 시 락 자동 해제                 │
     * │                                                                 │
     * │  적합: 재고 차감, 좌석 예매 등 충돌이 빈번한 경우                   │
     * │  주의: 데드락 위험, 동시성 처리량 감소                              │
     * └─────────────────────────────────────────────────────────────────┘
     *
     * JPA 비관적 락 모드:
     * ┌──────────────────────────┬──────────────────────────────────────┐
     * │  PESSIMISTIC_WRITE       │ SELECT FOR UPDATE (배타적 락)        │
     * │                          │ 읽기/쓰기 모두 블로킹                 │
     * ├──────────────────────────┼──────────────────────────────────────┤
     * │  PESSIMISTIC_READ        │ SELECT FOR SHARE (공유 락)           │
     * │                          │ 다른 트랜잭션 읽기 가능, 쓰기 블로킹   │
     * ├──────────────────────────┼──────────────────────────────────────┤
     * │  PESSIMISTIC_FORCE_      │ 낙관적 + 비관적 결합                 │
     * │  INCREMENT               │ 버전 증가 + FOR UPDATE              │
     * └──────────────────────────┴──────────────────────────────────────┘
     * </pre>
     */
    // Repository 인터페이스에서 사용 예시:
    // @Lock(LockModeType.PESSIMISTIC_WRITE)
    // @Query("SELECT p FROM Product p WHERE p.id = :id")
    // Optional<Product> findByIdForUpdate(@Param("id") Long id);

    /**
     * SELECT FOR UPDATE SKIP LOCKED — 대기 없이 락 가능한 행만 처리.
     *
     * <pre>
     * 일반 FOR UPDATE: 락이 걸린 행에서 대기 (블로킹)
     * SKIP LOCKED:     락이 걸린 행을 건너뛰고 다음 행 처리
     *
     * 활용: 배치 처리, 작업 큐 (여러 워커가 동시에 작업 가져오기)
     * → batch/VersionedBatchService.java에서 활용 중
     * </pre>
     */

    // ====================================================================
    // [3] DB 락 — 낙관적 락 (Optimistic Lock)
    // ====================================================================

    /**
     * 낙관적 락 — @Version 필드 기반.
     *
     * <pre>
     * ┌─────────────────────────────────────────────────────────────────┐
     * │  낙관적 락 (Optimistic Lock)                                     │
     * ├─────────────────────────────────────────────────────────────────┤
     * │  "충돌은 드물 것이다" → 락 없이 진행, 커밋 시 충돌 감지            │
     * │                                                                 │
     * │  동작 원리:                                                      │
     * │  1. 조회: SELECT * FROM products WHERE id=1; → version = 3      │
     * │  2. 수정: UPDATE products SET stock=99, version=4               │
     * │           WHERE id=1 AND version=3;                              │
     * │  3. 성공: affected rows = 1 → 정상                               │
     * │  4. 실패: affected rows = 0 → OptimisticLockException 발생       │
     * │     (다른 트랜잭션이 먼저 version을 변경)                          │
     * │                                                                 │
     * │  적합: 충돌이 드문 경우 (읽기 > 쓰기)                             │
     * │  장점: 락을 잡지 않으므로 동시성 처리량 높음                        │
     * │  단점: 충돌 시 재시도 로직 필요                                    │
     * └─────────────────────────────────────────────────────────────────┘
     *
     * JPA에서 사용:
     * @Version
     * private Integer version;
     * → BaseEntity.java에서 정의
     *
     * 충돌 발생 시:
     * try {
     *     repository.save(entity);
     * } catch (OptimisticLockException e) {
     *     // 재시도 또는 에러 반환
     * }
     * </pre>
     */

    // ====================================================================
    // [4] DB 락 — Advisory Lock (MySQL GET_LOCK)
    // ====================================================================

    /**
     * MySQL Advisory Lock — 애플리케이션 레벨 분산 락.
     *
     * <pre>
     * ┌─────────────────────────────────────────────────────────────────┐
     * │  Advisory Lock vs Row Lock vs 분산 락                            │
     * ├──────────────────┬──────────────────────────────────────────────┤
     * │  Row Lock        │ 특정 행 잠금 (SELECT FOR UPDATE)              │
     * │  (FOR UPDATE)    │ 트랜잭션 종료 시 자동 해제                     │
     * │                  │ 데이터가 있어야 락 가능                        │
     * ├──────────────────┼──────────────────────────────────────────────┤
     * │  Advisory Lock   │ 이름 기반 잠금 (GET_LOCK('key', timeout))     │
     * │  (GET_LOCK)      │ 데이터 존재 불필요 (논리적 자원 잠금)           │
     * │                  │ 명시적 해제 필요 (RELEASE_LOCK)               │
     * │                  │ MySQL 전용                                    │
     * ├──────────────────┼──────────────────────────────────────────────┤
     * │  분산 락         │ Redis, ZooKeeper 등 외부 시스템 기반           │
     * │  (Redis RLock)   │ DB 의존 없음, MSA에서 범용적                   │
     * │                  │ Watchdog으로 자동 연장/해제                    │
     * │                  │ → redis/RedisStockService.java 참고           │
     * └──────────────────┴──────────────────────────────────────────────┘
     * </pre>
     */
    public boolean executeWithAdvisoryLock(String lockName, int timeoutSeconds, Runnable task) {
        try {
            // 락 획득 시도 (timeout초 대기)
            Integer result = jdbcTemplate.queryForObject(
                    "SELECT GET_LOCK(?, ?)", Integer.class, lockName, timeoutSeconds);

            if (result != null && result == 1) {
                try {
                    task.run();
                    return true;
                } finally {
                    // 명시적 락 해제 (반드시 finally에서)
                    jdbcTemplate.queryForObject(
                            "SELECT RELEASE_LOCK(?)", Integer.class, lockName);
                }
            } else {
                log.warn("Advisory Lock 획득 실패: {}", lockName);
                return false;
            }
        } catch (Exception e) {
            log.error("Advisory Lock 실행 중 에러: {}", lockName, e);
            return false;
        }
    }

    // ====================================================================
    // [5] 트랜잭션 격리 수준 (Isolation Level) — 암묵적 락
    // ====================================================================

    /**
     * 트랜잭션 격리 수준에 따른 데이터 일관성 문제.
     *
     * <pre>
     * ┌──────────────────────────────────────────────────────────────────┐
     * │  동시성 문제 종류                                                  │
     * ├──────────────────┬───────────────────────────────────────────────┤
     * │  Dirty Read      │ 커밋되지 않은 데이터를 읽음                    │
     * │                  │ → 롤백되면 읽은 데이터가 무효                   │
     * ├──────────────────┼───────────────────────────────────────────────┤
     * │  Non-Repeatable  │ 같은 데이터를 두 번 읽었는데 값이 달라짐       │
     * │  Read            │ → 다른 트랜잭션이 중간에 UPDATE + COMMIT       │
     * ├──────────────────┼───────────────────────────────────────────────┤
     * │  Phantom Read    │ 같은 조건으로 두 번 조회했는데 행 수가 달라짐   │
     * │                  │ → 다른 트랜잭션이 중간에 INSERT + COMMIT       │
     * └──────────────────┴───────────────────────────────────────────────┘
     *
     * ┌──────────────────────────────────────────────────────────────────┐
     * │  격리 수준별 허용되는 문제                                         │
     * ├─────────────────────┬───────────┬──────────────┬────────────────┤
     * │  격리 수준          │ Dirty Read│ Non-Repeat   │ Phantom Read   │
     * ├─────────────────────┼───────────┼──────────────┼────────────────┤
     * │  READ_UNCOMMITTED   │  허용     │  허용        │  허용          │
     * │  READ_COMMITTED     │  방지     │  허용        │  허용          │
     * │  (MySQL/PostgreSQL  │           │              │                │
     * │   기본값)           │           │              │                │
     * ├─────────────────────┼───────────┼──────────────┼────────────────┤
     * │  REPEATABLE_READ    │  방지     │  방지        │  허용          │
     * │  (MySQL InnoDB      │           │              │ (MySQL은 Gap   │
     * │   기본값)           │           │              │  Lock으로 방지) │
     * ├─────────────────────┼───────────┼──────────────┼────────────────┤
     * │  SERIALIZABLE       │  방지     │  방지        │  방지          │
     * │  (가장 엄격, 느림)  │           │              │                │
     * └─────────────────────┴───────────┴──────────────┴────────────────┘
     *
     * 선택 기준:
     * - 대부분의 경우: DB 기본값 (READ_COMMITTED / REPEATABLE_READ)
     * - 금융/결제: SERIALIZABLE 또는 비관적 락
     * - 읽기 전용 조회: READ_COMMITTED (성능 우선)
     * </pre>
     */

    /** READ_COMMITTED — 커밋된 데이터만 읽기 (기본값) */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void readCommittedExample() {
        // 다른 트랜잭션이 커밋하지 않은 데이터는 보이지 않음 (Dirty Read 방지)
        // 하지만 같은 데이터를 두 번 읽으면 값이 달라질 수 있음 (Non-Repeatable Read)
    }

    /** REPEATABLE_READ — 같은 트랜잭션 내 같은 데이터는 항상 같은 값 */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void repeatableReadExample() {
        // 트랜잭션 시작 시점의 스냅샷을 기준으로 읽기
        // 같은 SELECT를 여러 번 실행해도 같은 결과 보장
    }

    /** SERIALIZABLE — 가장 엄격, 동시성 최소 */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void serializableExample() {
        // 모든 SELECT에 공유 락(LOCK IN SHARE MODE)이 걸림
        // Phantom Read까지 완벽 방지
        // 성능이 가장 낮음 — 꼭 필요한 경우에만 사용
    }

    // ====================================================================
    // [6] 락 선택 가이드
    // ====================================================================

    /**
     * 상황별 락 선택 가이드.
     *
     * <pre>
     * ┌────────────────────────────────────────────────────────────────────┐
     * │  상황                        │  권장 락 방식                       │
     * ├──────────────────────────────┼──────────────────────────────────┤
     * │  단순 카운터 (조회수 등)      │  AtomicInteger / AtomicLong      │
     * │  단일 서버 재고 차감          │  synchronized / ReentrantLock     │
     * │  DB 데이터 동시 수정 방지     │  @Version (낙관적 락)             │
     * │  재고/좌석 등 충돌 빈번       │  SELECT FOR UPDATE (비관적 락)    │
     * │  배치 작업 중복 방지          │  Advisory Lock (GET_LOCK)         │
     * │  MSA 분산 환경               │  Redis RLock (Redisson)           │
     * │  강력한 일관성 필요 (결제)    │  비관적 락 + SERIALIZABLE         │
     * └──────────────────────────────┴──────────────────────────────────┘
     * </pre>
     */
}
