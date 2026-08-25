package kr.co.example.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * ========================================================================
 * 버저닝(Versioning) 고려 배치 처리 예제
 * ========================================================================
 *
 * ── 배치에서 동시성 문제란? ──
 *
 * 배치가 대량 데이터를 UPDATE하는 동안, 실시간 API도 같은 데이터를 수정할 수 있음.
 *
 * ┌─────────────────────────────────────────────────────────────┐
 * │ 시간 순서:                                                   │
 * │                                                              │
 * │ T1: 배치가 주문 #100 읽기 (status=PENDING, version=1)         │
 * │ T2: API가 주문 #100을 CONFIRMED로 변경 (version=1→2)          │
 * │ T3: 배치가 주문 #100을 PROCESSING으로 변경 시도               │
 * │     → version=1 기준이므로 API 변경분 덮어쓰기! (Lost Update)  │
 * │                                                              │
 * │ 해결: version 체크로 T3에서 충돌 감지                         │
 * └─────────────────────────────────────────────────────────────┘
 *
 * ── 낙관적 락(Optimistic Lock) vs 비관적 락(Pessimistic Lock) ──
 *
 * ┌────────────────┬─────────────────────────┬────────────────────────────┐
 * │ 항목            │ 낙관적 락                │ 비관적 락                   │
 * ├────────────────┼─────────────────────────┼────────────────────────────┤
 * │ 방식            │ UPDATE 시 version 체크   │ SELECT 시점에 행 잠금       │
 * │ 락 점유 시간    │ 없음 (락을 잡지 않음)     │ TX 종료까지 점유            │
 * │ 충돌 처리       │ 충돌 시 재시도            │ 대기 또는 타임아웃           │
 * │ 성능            │ 충돌 적으면 빠름          │ 락 대기로 처리량 저하        │
 * │ 데드락          │ 없음                     │ 발생 가능                   │
 * │ 적합한 상황     │ 읽기 많고 충돌 적음       │ 쓰기 많고 충돌 빈번          │
 * │ 배치 적합도     │ 적합 (대량 처리)          │ 부적합 (락 점유 오래됨)      │
 * └────────────────┴─────────────────────────┴────────────────────────────┘
 *
 * ── @Version과 JDBC 배치의 관계 ──
 *
 * JPA @Version 사용 시:
 * - Hibernate가 UPDATE SET ... version=version+1 WHERE version=? 자동 생성
 * - 배치 UPDATE 시 각 행의 version이 다르면 JDBC 배치가 깨질 수 있음
 * - hibernate.jdbc.batch_versioned_data=true 설정 필요 (application.yml)
 *
 * JdbcTemplate 사용 시:
 * - SQL에 직접 WHERE version=? AND version+1 작성
 * - 반환된 affected rows로 충돌 감지
 * - 더 세밀한 제어 가능
 *
 * ── 배치에서 낙관적 락이 적합한 이유 ──
 *
 * 1. 락 점유 없음: 대량 데이터 처리 중 다른 TX를 블로킹하지 않음
 * 2. 데드락 없음: 비관적 락처럼 교차 락으로 인한 데드락 불가
 * 3. 충돌 감지: affected rows = 0이면 충돌 → 재시도 또는 건너뛰기
 * 4. 처리량: 락 대기 없으므로 전체 처리 시간 단축
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VersionedBatchService {

    private final JdbcTemplate jdbcTemplate;

    // ================================================================
    // [1] 낙관적 락 기반 배치 UPDATE
    // ================================================================

    /**
     * 버전 체크 기반 배치 UPDATE (낙관적 락)
     *
     * SQL 동작:
     * UPDATE orders
     * SET status = ?, version = version + 1
     * WHERE id = ? AND version = ?
     *
     * - version이 일치하면: UPDATE 성공 (affected=1), version 증가
     * - version이 불일치하면: UPDATE 실패 (affected=0), 다른 TX가 먼저 수정함
     *
     * 충돌 시 전략:
     * ┌──────────────────────┬────────────────────────────────────┐
     * │ 전략                  │ 설명                               │
     * ├──────────────────────┼────────────────────────────────────┤
     * │ 건너뛰기 (Skip)       │ 실시간 API가 이미 처리함 → 무시    │
     * │ 재시도 (Retry)        │ 최신 version 다시 읽고 재시도       │
     * │ 실패 기록 (Log)       │ 실패 목록에 추가하여 나중에 처리    │
     * │ 예외 발생 (Throw)     │ 전체 배치 중단 (데이터 정합성 최우선)│
     * └──────────────────────┴────────────────────────────────────┘
     *
     * @param updates 업데이트할 데이터 (id, version 포함)
     * @return 처리 결과
     */
    @Transactional
    public VersionedBatchResult batchUpdateWithVersionCheck(List<VersionedRecord> updates) {
        String sql = """
                UPDATE orders
                SET status = ?, updated_at = NOW(), version = version + 1
                WHERE id = ? AND version = ?
                """;

        int successCount = 0;
        List<VersionedRecord> conflicted = new ArrayList<>();

        for (VersionedRecord record : updates) {
            int affected = jdbcTemplate.update(sql,
                    record.newStatus(),
                    record.id(),
                    record.expectedVersion()
            );

            if (affected == 1) {
                // UPDATE 성공: version이 일치하여 변경됨
                successCount++;
            } else {
                // affected=0: version 불일치 (다른 TX가 먼저 수정)
                log.warn("[VersionBatch] 충돌 감지 - id={}, expectedVersion={}",
                        record.id(), record.expectedVersion());
                conflicted.add(record);
            }
        }

        log.info("[VersionBatch] 처리 완료 - 성공={}, 충돌={}",
                successCount, conflicted.size());
        return new VersionedBatchResult(successCount, conflicted);
    }

    // ================================================================
    // [2] 충돌 시 재시도 배치
    // ================================================================

    /**
     * 충돌 발생 시 최신 데이터를 다시 읽고 재시도하는 배치
     *
     * 재시도 흐름:
     * ┌──────────────────────────────────────────────────────────┐
     * │ 1차 시도: UPDATE WHERE version=1 → affected=0 (충돌!)     │
     * │      ↓                                                    │
     * │ 최신 version 조회: SELECT version FROM orders WHERE id=?  │
     * │      ↓ version=3 (API가 2번 수정함)                       │
     * │                                                           │
     * │ 비즈니스 검증: 현재 상태에서 배치 처리가 여전히 유효한가?   │
     * │      ↓ 유효하면                                           │
     * │                                                           │
     * │ 2차 시도: UPDATE WHERE version=3 → affected=1 (성공!)     │
     * └──────────────────────────────────────────────────────────┘
     *
     * 주의:
     * - 무한 재시도 방지를 위해 maxRetries 제한
     * - 재시도마다 SELECT 쿼리 발생 → 대량 충돌 시 DB 부하
     * - 충돌이 빈번하면 배치 실행 시간을 조정하는 것이 근본 해결
     *
     * @param record 업데이트할 레코드
     * @param maxRetries 최대 재시도 횟수
     * @return 성공 여부
     */
    @Transactional
    public boolean updateWithRetryOnConflict(VersionedRecord record, int maxRetries) {
        String updateSql = """
                UPDATE orders
                SET status = ?, version = version + 1
                WHERE id = ? AND version = ?
                """;

        String selectVersionSql = "SELECT version FROM orders WHERE id = ?";

        int currentVersion = record.expectedVersion();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            int affected = jdbcTemplate.update(updateSql,
                    record.newStatus(), record.id(), currentVersion);

            if (affected == 1) {
                log.info("[VersionRetry] 성공 - id={}, attempt={}", record.id(), attempt);
                return true;
            }

            // 충돌: 최신 version 다시 조회
            Integer latestVersion = jdbcTemplate.queryForObject(
                    selectVersionSql, Integer.class, record.id());

            if (latestVersion == null) {
                log.warn("[VersionRetry] 레코드 삭제됨 - id={}", record.id());
                return false;
            }

            log.info("[VersionRetry] 충돌 - id={}, attempt={}, oldVersion={}, newVersion={}",
                    record.id(), attempt, currentVersion, latestVersion);
            currentVersion = latestVersion;
        }

        log.error("[VersionRetry] 최종 실패 - id={}, 재시도 소진", record.id());
        return false;
    }

    // ================================================================
    // [3] 비관적 락 기반 배치 (SELECT FOR UPDATE)
    // ================================================================

    /**
     * SELECT FOR UPDATE를 이용한 비관적 락 배치
     *
     * SELECT ... FOR UPDATE:
     * - 조회 시점에 해당 행에 배타적 락(X-Lock)을 설정
     * - 다른 TX가 같은 행을 UPDATE/DELETE/SELECT FOR UPDATE 하면 대기
     * - 현재 TX가 COMMIT/ROLLBACK 할 때 락 해제
     *
     * 배치에서 비관적 락 사용 시 주의:
     *
     * ┌──────────────────────────────────────────────────────────┐
     * │ 문제: 대량 행 잠금                                       │
     * │                                                          │
     * │ 배치가 1만 건 SELECT FOR UPDATE → 1만 행 잠금             │
     * │ → 실시간 API가 해당 행 접근 시 대기                       │
     * │ → 배치 완료까지 API 응답 지연 → 서비스 장애               │
     * │                                                          │
     * │ 해결: 소량씩 처리 (LIMIT 사용)                            │
     * │ → 100건씩 SELECT FOR UPDATE + UPDATE + COMMIT             │
     * │ → 각 chunk 사이에 락 해제되어 API 접근 가능               │
     * └──────────────────────────────────────────────────────────┘
     *
     * SKIP LOCKED (MySQL 8.0+, PostgreSQL 9.5+):
     * - 이미 잠긴 행을 건너뛰고 잠기지 않은 행만 조회
     * - 여러 배치 워커가 동시에 작업 분담 가능 (경쟁 없음)
     *
     * NOWAIT:
     * - 이미 잠긴 행이 있으면 즉시 예외 발생 (대기하지 않음)
     * - 타임아웃 대신 빠른 실패를 원할 때 사용
     *
     * @param batchSize 한 번에 잠글 행 수 (작을수록 API 영향 적음)
     * @param newStatus 변경할 상태
     * @return 처리된 행 수
     */
    @Transactional
    public int pessimisticLockBatch(int batchSize, String newStatus) {
        // SKIP LOCKED: 이미 잠긴 행은 건너뛰고, 잠기지 않은 행만 조회
        String selectSql = """
                SELECT id FROM orders
                WHERE status = 'PENDING'
                ORDER BY created_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """;

        String updateSql = "UPDATE orders SET status = ?, version = version + 1 WHERE id = ?";

        // 잠금 가능한 행 조회 (이미 다른 워커가 잠근 행은 제외)
        List<Long> ids = jdbcTemplate.queryForList(selectSql, Long.class, batchSize);

        if (ids.isEmpty()) {
            log.info("[PessimisticBatch] 처리할 데이터 없음");
            return 0;
        }

        // 조회된 행은 이미 잠겨있으므로 안전하게 UPDATE 가능
        int totalUpdated = 0;
        for (Long id : ids) {
            int affected = jdbcTemplate.update(updateSql, newStatus, id);
            totalUpdated += affected;
        }

        log.info("[PessimisticBatch] 처리 완료 - {}건", totalUpdated);
        return totalUpdated;
    }

    // ================================================================
    // [4] CAS(Compare-And-Swap) 패턴 배치
    // ================================================================

    /**
     * 상태 기반 CAS 패턴 배치
     *
     * version 컬럼 없이도 동시성을 제어하는 방법.
     * 현재 상태(status)를 조건으로 사용하여 상태 전이가 원자적으로 이루어지도록 보장.
     *
     * UPDATE WHERE status='PENDING' → SET status='PROCESSING'
     *
     * 동시 실행 시:
     * ┌──────────────────────────────────────────────────────┐
     * │ 배치 TX-A: UPDATE SET status='PROCESSING'            │
     * │            WHERE id=100 AND status='PENDING'         │
     * │            → affected=1 (성공, status 변경됨)         │
     * │                                                       │
     * │ 배치 TX-B: UPDATE SET status='PROCESSING'            │
     * │            WHERE id=100 AND status='PENDING'          │
     * │            → affected=0 (실패, 이미 PROCESSING)       │
     * └──────────────────────────────────────────────────────┘
     *
     * 장점:
     * - version 컬럼 불필요 (기존 스키마 변경 없이 적용)
     * - 상태 전이 로직이 SQL에 명시적으로 표현됨
     *
     * 한계:
     * - 상태가 같은 값으로 UPDATE하는 경우 감지 불가
     * - 복잡한 필드 변경은 version 기반이 더 안전
     *
     * @param batchSize 한 번에 처리할 건수
     * @return 처리된 건수
     */
    @Transactional
    public int casStatusBatch(int batchSize) {
        // 원자적 상태 전이: PENDING → PROCESSING
        String sql = """
                UPDATE orders
                SET status = 'PROCESSING', updated_at = NOW()
                WHERE status = 'PENDING'
                ORDER BY created_at
                LIMIT ?
                """;

        int affected = jdbcTemplate.update(sql, batchSize);
        log.info("[CAS Batch] 상태 전이 완료 - PENDING→PROCESSING {}건", affected);
        return affected;
    }

    // ================================================================
    // DTO 정의
    // ================================================================

    /** 버전 정보가 포함된 업데이트 레코드 */
    public record VersionedRecord(
            Long id,               // 대상 레코드 PK
            int expectedVersion,   // 읽기 시점의 version (충돌 감지용)
            String newStatus       // 변경할 상태
    ) {}

    /** 버전 기반 배치 처리 결과 */
    public record VersionedBatchResult(
            int successCount,                    // 성공 건수
            List<VersionedRecord> conflicted     // 충돌 발생 레코드 목록
    ) {}
}
