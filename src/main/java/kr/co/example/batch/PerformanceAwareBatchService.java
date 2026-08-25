package kr.co.example.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * ========================================================================
 * 성능 고려 배치 처리 예제 (메모리 / CPU / I/O 최적화)
 * ========================================================================
 *
 * ── 배치 성능 병목 3대 요소 ──
 *
 * ┌──────────────────────────────────────────────────────────────┐
 * │ [1] 메모리 (Memory)                                          │
 * │   - 대량 데이터를 한 번에 로딩 → OutOfMemoryError              │
 * │   - JPA 영속성 컨텍스트에 엔티티 누적                          │
 * │   - 해결: 페이징/커서, 영속성 컨텍스트 초기화, JdbcTemplate     │
 * │                                                               │
 * │ [2] CPU                                                       │
 * │   - 복잡한 변환/검증 로직 (암호화, JSON 파싱 등)                │
 * │   - Hibernate 더티 체킹 (flush 시 모든 엔티티 비교)             │
 * │   - 해결: 병렬 처리, 변환 로직 최적화, JdbcTemplate             │
 * │                                                               │
 * │ [3] I/O (DB, 네트워크)                                        │
 * │   - 건별 INSERT/UPDATE (N번 라운드트립)                         │
 * │   - N+1 쿼리 (연관 엔티티 Lazy Loading)                        │
 * │   - 해결: 배치 INSERT, JOIN FETCH, IN 쿼리                     │
 * └──────────────────────────────────────────────────────────────┘
 *
 * ── 메모리 사용량 추정 공식 ──
 *
 * JPA 엔티티 1건당 메모리 ≈ 필드 크기 + 200~500 bytes (프록시, 메타데이터)
 *
 * 예시:
 * - Order 엔티티 (10개 필드, 평균 50bytes) ≈ 700 bytes/건
 * - 10만 건 로딩 시 ≈ 70MB (영속성 컨텍스트)
 * - 100만 건 로딩 시 ≈ 700MB → OOM 위험
 *
 * JdbcTemplate (RowMapper):
 * - DTO 1건당 ≈ 필드 크기만큼 (프록시/메타데이터 없음)
 * - 같은 10만 건 ≈ 5~10MB
 *
 * ── 배치 처리 방식별 성능 비교 ──
 *
 * ┌──────────────────────────┬──────────┬──────────┬──────────┬──────────┐
 * │ 방식                      │ 메모리    │ CPU      │ I/O      │ 총 성능   │
 * ├──────────────────────────┼──────────┼──────────┼──────────┼──────────┤
 * │ JPA findAll() + saveAll()│ 매우 높음│ 높음     │ 보통     │ 느림     │
 * │ JPA Paging + flush/clear│ 보통     │ 보통     │ 보통     │ 보통     │
 * │ JdbcTemplate 청크 배치    │ 낮음     │ 낮음     │ 빠름     │ 빠름     │
 * │ JDBC + 병렬 처리         │ 낮음     │ 높음(코어)│ 매우 빠름│ 매우 빠름│
 * │ DB 프로시저/CTAS         │ 최소     │ DB 측    │ 최소     │ 가장 빠름│
 * └──────────────────────────┴──────────┴──────────┴──────────┴──────────┘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceAwareBatchService {

    private final JdbcTemplate jdbcTemplate;
    private final Executor batchExecutor;

    // ================================================================
    // [1] 페이징 기반 배치 - 메모리 제어
    // ================================================================

    /**
     * 페이징(Paging) 기반 배치 처리
     *
     * 전체 데이터를 한 번에 로딩하지 않고, 페이지 단위로 조회 → 처리 → 해제.
     * 메모리 사용량이 pageSize에 비례하여 일정하게 유지됨.
     *
     * ── 동작 흐름 ──
     *
     * ┌──────────────────────────────────────────────────────┐
     * │ while (true):                                         │
     * │   1. SELECT ... LIMIT pageSize OFFSET page*pageSize  │
     * │   2. 결과가 비어있으면 종료                            │
     * │   3. 각 행 처리 (변환, 저장 등)                        │
     * │   4. GC가 이전 페이지 데이터 회수 가능                 │
     * │   5. 다음 페이지로 이동                                │
     * └──────────────────────────────────────────────────────┘
     *
     * ── OFFSET 방식의 한계와 대안 ──
     *
     * OFFSET이 커지면 DB가 앞의 행을 건너뛰기 위해 스캔해야 함:
     * - OFFSET 0: 0건 스캔 후 1000건 반환
     * - OFFSET 100,000: 10만 건 스캔 후 1000건 반환 → 느려짐
     *
     * 대안 1: Keyset Pagination (커서 기반)
     *   WHERE id > :lastId ORDER BY id LIMIT :pageSize
     *   → OFFSET 없이 인덱스로 직접 접근 (항상 빠름)
     *
     * 대안 2: 상태 기반 (처리 완료 마킹)
     *   WHERE status = 'PENDING' LIMIT :pageSize
     *   → 처리 후 status 변경 → 다음 쿼리에서 자동 제외
     *
     * @param pageSize 페이지당 조회 건수
     * @return 총 처리 건수
     */
    @Transactional
    public int pagingBatch(int pageSize) {
        int totalProcessed = 0;
        long lastId = 0;  // Keyset Pagination용

        while (true) {
            // Keyset Pagination: OFFSET 없이 id 기반 조회
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM orders WHERE id > ? AND status = 'PENDING' ORDER BY id LIMIT ?",
                    Long.class,
                    lastId, pageSize
            );

            if (ids.isEmpty()) {
                break;  // 더 이상 처리할 데이터 없음
            }

            // 배치 UPDATE
            for (Long id : ids) {
                jdbcTemplate.update(
                        "UPDATE orders SET status = 'PROCESSED' WHERE id = ?", id);
            }

            // 다음 페이지의 시작점
            lastId = ids.getLast();
            totalProcessed += ids.size();

            log.info("[PagingBatch] 페이지 처리 - lastId={}, 누적={}건", lastId, totalProcessed);

            // 메모리 모니터링 (GC 힌트)
            logMemoryUsage();
        }

        log.info("[PagingBatch] 완료 - 총 {}건", totalProcessed);
        return totalProcessed;
    }

    // ================================================================
    // [2] 병렬 배치 - CPU 활용 극대화
    // ================================================================

    /**
     * 데이터를 파티션으로 분할하여 병렬 처리하는 배치
     *
     * ── 병렬 처리 전략 ──
     *
     * 1. ID 범위 기반 파티셔닝:
     *    - 전체 ID 범위를 N등분 → 각 스레드가 독립적으로 처리
     *    - 데이터 겹침 없음 → 락 불필요
     *    - 데이터 분포가 균등하지 않으면 스레드 간 부하 불균형
     *
     * 2. 모듈러(Modulo) 기반 파티셔닝:
     *    - WHERE id % N = threadIndex
     *    - 균등 분배 보장
     *    - 인덱스 활용 불가 (Full Scan)
     *
     * 3. 해시 기반 파티셔닝:
     *    - WHERE HASH(key) % N = threadIndex
     *    - 특정 키의 처리를 항상 같은 스레드가 담당
     *
     * ── 병렬 처리 시 주의사항 ──
     *
     * ┌──────────────────────────────────────────────────────────┐
     * │ 1. DB 커넥션 풀 고갈                                      │
     * │    - 스레드 수 ≤ 커넥션 풀 크기 여야 함                    │
     * │    - 4개 스레드 × 배치 쿼리 → 최소 4개 커넥션 필요         │
     * │                                                           │
     * │ 2. 트랜잭션 격리                                          │
     * │    - 각 스레드가 독립 TX → 하나 실패해도 다른 스레드 영향 없음│
     * │    - 전체 원자성이 필요하면 병렬 처리 부적합                │
     * │                                                           │
     * │ 3. 로깅 오버헤드                                          │
     * │    - 건별 로깅 시 I/O 병목 → 청크 단위 로깅 권장           │
     * │                                                           │
     * │ 4. CPU 코어 수 고려                                       │
     * │    - CPU 바운드: 스레드 수 ≈ 코어 수                       │
     * │    - I/O 바운드: 스레드 수 ≈ 코어 수 × 2~4                │
     * └──────────────────────────────────────────────────────────┘
     *
     * @param partitionCount 파티션(스레드) 수
     * @param minId          처리 대상 최소 ID
     * @param maxId          처리 대상 최대 ID
     * @return 총 처리 건수
     */
    public int parallelPartitionBatch(int partitionCount, long minId, long maxId) {
        long rangeSize = (maxId - minId + 1) / partitionCount;
        AtomicInteger totalProcessed = new AtomicInteger(0);

        // 각 파티션을 CompletableFuture로 병렬 실행
        List<CompletableFuture<Void>> futures = IntStream.range(0, partitionCount)
                .mapToObj(partition -> {
                    long partStart = minId + (partition * rangeSize);
                    long partEnd = (partition == partitionCount - 1)
                            ? maxId
                            : partStart + rangeSize - 1;

                    return CompletableFuture.runAsync(() -> {
                        int processed = processPartition(partition, partStart, partEnd);
                        totalProcessed.addAndGet(processed);
                    }, batchExecutor);
                })
                .toList();

        // 모든 파티션 완료 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        log.info("[ParallelBatch] 전체 완료 - 파티션={}, 총 처리={}건",
                partitionCount, totalProcessed.get());
        return totalProcessed.get();
    }

    /**
     * 개별 파티션 처리
     *
     * 각 스레드가 독립적으로 자신의 ID 범위를 처리.
     * 범위가 겹치지 않으므로 락 없이 안전하게 실행.
     */
    private int processPartition(int partitionIndex, long startId, long endId) {
        log.info("[Partition-{}] 시작 - range=[{}, {}]", partitionIndex, startId, endId);

        int affected = jdbcTemplate.update(
                "UPDATE orders SET status = 'PROCESSED' WHERE id BETWEEN ? AND ? AND status = 'PENDING'",
                startId, endId
        );

        log.info("[Partition-{}] 완료 - {}건 처리", partitionIndex, affected);
        return affected;
    }

    // ================================================================
    // [3] 스트리밍 배치 - 대용량 메모리 절약
    // ================================================================

    /**
     * JdbcTemplate queryForStream() - 대용량 조회 시 메모리 절약
     *
     * ── 일반 query() vs queryForStream() ──
     *
     * query():
     * - ResultSet 전체를 List로 변환 후 반환
     * - 100만 건 조회 → 100만 건이 메모리에 적재
     *
     * queryForStream():
     * - ResultSet을 Stream으로 래핑
     * - 1건씩 처리 후 GC 대상 → 메모리 사용 최소
     * - try-with-resources로 Stream 닫기 필수 (커넥션 반환)
     *
     * ┌──────────────────────────────────────────────────────┐
     * │ query()       : [전체 로딩] → List → 처리             │
     * │ 메모리: ████████████████████████ (전체 데이터)         │
     * │                                                       │
     * │ queryForStream(): [1건씩] → Stream → 처리 → GC        │
     * │ 메모리: ██ (현재 처리 중인 건만)                       │
     * └──────────────────────────────────────────────────────┘
     *
     * 주의:
     * - Stream이 열려있는 동안 DB 커넥션 점유
     * - 반드시 try-with-resources 또는 close() 호출
     * - 처리 시간이 길면 커넥션 풀 고갈 위험
     * - MySQL: useCursorFetch=true, fetchSize 설정 필요
     *
     * @return 처리된 건수
     */
    @Transactional(readOnly = true)
    public int streamingBatch() {
        AtomicInteger processed = new AtomicInteger(0);

        // try-with-resources: Stream 종료 시 커넥션 반환
        try (var stream = jdbcTemplate.queryForStream(
                "SELECT id, name, status FROM orders WHERE status = 'PENDING'",
                (rs, rowNum) -> new SimpleRecord(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("status")
                )
        )) {
            stream.forEach(record -> {
                // 1건씩 처리 (메모리에 1건만 유지)
                processRecord(record);
                processed.incrementAndGet();

                // 진행 상황 로깅 (건별 로깅은 I/O 부하 → 1000건 단위)
                if (processed.get() % 1000 == 0) {
                    log.info("[StreamBatch] 진행 - {}건 처리", processed.get());
                }
            });
        }

        log.info("[StreamBatch] 완료 - 총 {}건", processed.get());
        return processed.get();
    }

    // ================================================================
    // [4] 메모리 모니터링
    // ================================================================

    /**
     * 배치 실행 중 JVM 메모리 사용량 로깅
     *
     * 배치 처리 중 메모리 사용량을 모니터링하여:
     * - 메모리 누수 감지
     * - 청크 크기 최적화
     * - OOM 발생 전 경고
     *
     * ── JVM 메모리 구조 ──
     *
     * ┌──────────────────────────────────────────────┐
     * │ maxMemory   : JVM이 사용할 수 있는 최대 메모리 │
     * │ totalMemory : 현재 할당된 메모리               │
     * │ freeMemory  : 할당된 메모리 중 사용 가능       │
     * │ usedMemory  : totalMemory - freeMemory        │
     * │                                                │
     * │ ┌──────────────────────────────────────────┐  │
     * │ │ totalMemory                              │  │
     * │ │ ┌──────────────┬───────────────────────┐│  │
     * │ │ │ usedMemory   │ freeMemory            ││  │
     * │ │ └──────────────┴───────────────────────┘│  │
     * │ └──────────────────────────────────────────┘  │
     * │ ← ─ ─ ─ ─ ─ maxMemory ─ ─ ─ ─ ─ ─ ─ ─ → │
     * └──────────────────────────────────────────────┘
     *
     * 운영 환경 권장:
     * - usedMemory / maxMemory > 80% → 경고 로그
     * - 배치 시작/종료 시점의 메모리 차이 → 누수 여부 판단
     * - Micrometer + Prometheus로 실시간 모니터링
     */
    private void logMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / (1024 * 1024);          // MB
        long totalMemory = runtime.totalMemory() / (1024 * 1024);      // MB
        long freeMemory = runtime.freeMemory() / (1024 * 1024);        // MB
        long usedMemory = totalMemory - freeMemory;                    // MB
        double usagePercent = (double) usedMemory / maxMemory * 100;

        log.debug("[Memory] used={}MB, free={}MB, total={}MB, max={}MB, usage={:.1f}%",
                usedMemory, freeMemory, totalMemory, maxMemory, usagePercent);

        // 80% 초과 시 경고
        if (usagePercent > 80) {
            log.warn("[Memory] 메모리 사용량 높음! {:.1f}% - GC 또는 청크 크기 축소 고려",
                    usagePercent);
        }
    }

    // ================================================================
    // 내부 헬퍼
    // ================================================================

    private void processRecord(SimpleRecord record) {
        // 실무: 변환, 외부 API 호출, 다른 테이블 저장 등
    }

    /** 스트리밍 조회용 DTO */
    record SimpleRecord(Long id, String name, String status) {}
}
