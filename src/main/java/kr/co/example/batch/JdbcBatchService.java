package kr.co.example.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * ========================================================================
 * JdbcTemplate 배치 처리 예제
 * ========================================================================
 *
 * ── 왜 JdbcTemplate 배치를 사용하는가? ──
 *
 * JPA/Hibernate 배치의 한계:
 * 1. 영속성 컨텍스트 메모리: 수만 건 save() 시 1차 캐시에 엔티티 누적 → OOM
 * 2. 더티 체킹 오버헤드: flush 시 모든 엔티티 변경 감지 → CPU 부하
 * 3. @Version(낙관적 락) 간섭: 배치 UPDATE 시 버전 체크로 배치 불가
 * 4. @GeneratedValue(IDENTITY): MySQL auto_increment 사용 시 JDBC 배치 비활성화
 *
 * JdbcTemplate 배치 장점:
 * - 영속성 컨텍스트 우회 → 메모리 사용 최소
 * - SQL 직접 제어 → 최적화 가능
 * - executeBatch()로 네트워크 라운드트립 최소화
 * - Hibernate batch_size 설정과 독립적
 *
 * ── 성능 비교 (10만 건 INSERT 기준) ──
 *
 * ┌─────────────────────────┬──────────────┬──────────────────┐
 * │ 방식                     │ 소요 시간     │ 메모리 사용       │
 * ├─────────────────────────┼──────────────┼──────────────────┤
 * │ JPA saveAll() (배치 X)  │ ~60초         │ 높음 (1차 캐시)   │
 * │ JPA saveAll() (배치 O)  │ ~20초         │ 높음 (1차 캐시)   │
 * │ JdbcTemplate batchUpdate│ ~5초          │ 낮음              │
 * │ JDBC executeBatch()     │ ~3초          │ 매우 낮음         │
 * └─────────────────────────┴──────────────┴──────────────────┘
 *
 * ── MySQL rewriteBatchedStatements 옵션 ──
 *
 * MySQL JDBC URL에 rewriteBatchedStatements=true 추가 시:
 * 개별 INSERT를 멀티 VALUES INSERT로 재작성하여 대폭 성능 향상.
 *
 * Before: INSERT INTO t VALUES (1); INSERT INTO t VALUES (2); ...
 * After:  INSERT INTO t VALUES (1), (2), (3), ...
 *
 * 적용: jdbc:mysql://host:3306/db?rewriteBatchedStatements=true
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JdbcBatchService {

    /** Spring이 주입하는 JdbcTemplate (DataSource 기반) */
    private final JdbcTemplate jdbcTemplate;

    // ================================================================
    // [1] batchUpdate - 가장 기본적인 JDBC 배치
    // ================================================================

    /**
     * JdbcTemplate.batchUpdate() - BatchPreparedStatementSetter 방식
     *
     * 동작 원리:
     * 1. PreparedStatement를 1개 생성
     * 2. setValues()로 파라미터만 바꿔가며 addBatch()
     * 3. 전체 리스트 완료 후 executeBatch() 호출
     * → N개의 SQL이 1번의 네트워크 왕복으로 DB에 전달
     *
     * 성능:
     * - 개별 INSERT 대비 10~50배 빠름
     * - 네트워크 라운드트립이 N번 → 1번으로 감소
     *
     * 제한:
     * - 단일 SQL 패턴만 가능 (같은 INSERT를 반복)
     * - 다른 테이블에 동시 INSERT 불가
     *
     * @param records 삽입할 데이터 리스트
     * @return 각 행의 영향 받은 행 수 배열
     */
    @Transactional
    public int[] batchInsert(List<BatchRecord> records) {
        String sql = "INSERT INTO batch_data (name, value, status) VALUES (?, ?, ?)";

        return jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                BatchRecord record = records.get(i);
                ps.setString(1, record.name());      // 첫 번째 ? 바인딩
                ps.setString(2, record.value());      // 두 번째 ? 바인딩
                ps.setString(3, record.status());     // 세 번째 ? 바인딩
            }

            @Override
            public int getBatchSize() {
                return records.size();  // 전체 리스트 크기
            }
        });
    }

    // ================================================================
    // [2] 청크 단위 배치 - 메모리 제어
    // ================================================================

    /**
     * 대량 데이터를 청크(chunk) 단위로 분할하여 배치 처리
     *
     * ── 왜 청크 단위로 나누는가? ──
     *
     * batchUpdate()에 10만 건을 한 번에 전달하면:
     * 1. PreparedStatement에 10만 건이 메모리에 누적
     * 2. DB 서버에도 10만 건의 SQL이 한 번에 전달
     * 3. 실패 시 10만 건 전체 롤백
     *
     * 청크 단위 처리 시:
     * 1. 500건씩 executeBatch() → 메모리 사용 일정
     * 2. DB 부하 분산
     * 3. 실패 시 해당 청크만 재처리 가능
     *
     * ── 청크 크기 선택 가이드 ──
     *
     * ┌──────────────┬────────────────────────────────────────────┐
     * │ 크기          │ 적합한 상황                                 │
     * ├──────────────┼────────────────────────────────────────────┤
     * │ 100~500      │ 네트워크 지연 큰 환경, 행(row) 크기 클 때    │
     * │ 500~1000     │ 일반적인 배치 INSERT (권장 범위)              │
     * │ 1000~5000    │ 네트워크 빠르고, 행 크기 작을 때              │
     * └──────────────┴────────────────────────────────────────────┘
     *
     * MySQL max_allowed_packet 설정도 고려해야 함.
     * 기본 64MB이지만 행 크기가 크면 청크를 줄여야 함.
     *
     * @param records  삽입할 전체 데이터
     * @param chunkSize 한 번에 처리할 청크 크기
     * @return 총 처리된 행 수
     */
    @Transactional
    public int batchInsertInChunks(List<BatchRecord> records, int chunkSize) {
        String sql = "INSERT INTO batch_data (name, value, status) VALUES (?, ?, ?)";
        int totalAffected = 0;

        // 전체 데이터를 chunkSize 단위로 분할
        for (int i = 0; i < records.size(); i += chunkSize) {
            // subList: 원본 리스트의 뷰 (복사 없음, 메모리 효율적)
            List<BatchRecord> chunk = records.subList(
                    i, Math.min(i + chunkSize, records.size())
            );

            int[] results = jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int idx) throws SQLException {
                    BatchRecord record = chunk.get(idx);
                    ps.setString(1, record.name());
                    ps.setString(2, record.value());
                    ps.setString(3, record.status());
                }

                @Override
                public int getBatchSize() {
                    return chunk.size();
                }
            });

            totalAffected += results.length;
            log.info("[JdbcBatch] 청크 처리 완료 - chunk={}/{}, 처리={}건",
                    (i / chunkSize) + 1,
                    (records.size() + chunkSize - 1) / chunkSize,
                    chunk.size());
        }

        log.info("[JdbcBatch] 전체 배치 완료 - 총 {}건", totalAffected);
        return totalAffected;
    }

    // ================================================================
    // [3] 독립 트랜잭션 배치 - 레코드별 실패 격리
    // ================================================================

    /**
     * 레코드별 독립 처리 + 실패 격리 배치
     *
     * 모든 레코드를 하나의 트랜잭션으로 묶으면:
     * - 1건 실패 → 전체 롤백 → 성공한 999건도 취소
     *
     * 레코드별 독립 처리 시:
     * - 1건 실패 → 해당 건만 실패 기록 → 나머지 999건 정상 처리
     *
     * 주의:
     * - 개별 INSERT이므로 batchUpdate보다 느림
     * - 전체 원자성이 필요한 경우에는 부적합
     * - 실패한 레코드를 별도 리스트로 수집하여 재처리 가능
     *
     * @param records 처리할 데이터 리스트
     * @return 처리 결과 (성공 수, 실패 수, 실패 목록)
     */
    public BatchResult batchInsertWithFailureIsolation(List<BatchRecord> records) {
        String sql = "INSERT INTO batch_data (name, value, status) VALUES (?, ?, ?)";
        int successCount = 0;
        List<FailedRecord> failedRecords = new ArrayList<>();

        for (int i = 0; i < records.size(); i++) {
            BatchRecord record = records.get(i);
            try {
                jdbcTemplate.update(sql, record.name(), record.value(), record.status());
                successCount++;
            } catch (Exception e) {
                log.warn("[JdbcBatch] 레코드 실패 - index={}, name={}, error={}",
                        i, record.name(), e.getMessage());
                failedRecords.add(new FailedRecord(i, record, e.getMessage()));
            }
        }

        log.info("[JdbcBatch] 격리 배치 완료 - 성공={}, 실패={}",
                successCount, failedRecords.size());
        return new BatchResult(successCount, failedRecords.size(), failedRecords);
    }

    // ================================================================
    // [4] UPSERT 배치 - 존재하면 UPDATE, 없으면 INSERT
    // ================================================================

    /**
     * UPSERT 배치 처리 (MySQL ON DUPLICATE KEY UPDATE)
     *
     * 데이터 동기화/이관 배치에서 자주 사용.
     * - 신규 데이터: INSERT
     * - 기존 데이터: UPDATE (값 갱신)
     *
     * MySQL: ON DUPLICATE KEY UPDATE
     * PostgreSQL: ON CONFLICT DO UPDATE
     * H2: MERGE INTO
     *
     * 주의:
     * - UNIQUE KEY 또는 PRIMARY KEY가 존재해야 동작
     * - 대량 UPSERT는 INSERT보다 느림 (존재 여부 확인 비용)
     *
     * @param records UPSERT할 데이터
     * @return 영향 받은 행 수 (INSERT=1, UPDATE=2 기준, MySQL 특성)
     */
    @Transactional
    public int[] batchUpsert(List<BatchRecord> records) {
        // MySQL 전용 UPSERT 구문
        String sql = """
                INSERT INTO batch_data (name, value, status)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    value = VALUES(value),
                    status = VALUES(status)
                """;

        return jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                BatchRecord record = records.get(i);
                ps.setString(1, record.name());
                ps.setString(2, record.value());
                ps.setString(3, record.status());
            }

            @Override
            public int getBatchSize() {
                return records.size();
            }
        });
    }

    // ================================================================
    // DTO 정의
    // ================================================================

    /** 배치 처리 대상 레코드 */
    public record BatchRecord(String name, String value, String status) {}

    /** 실패한 레코드 정보 */
    public record FailedRecord(int index, BatchRecord record, String errorMessage) {}

    /** 배치 처리 결과 */
    public record BatchResult(int successCount, int failureCount, List<FailedRecord> failedRecords) {}
}
