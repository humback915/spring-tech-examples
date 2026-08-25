package kr.co.example.transaction;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * ========================================================================
 * [5] 트랜잭션 관리 - 선언적(@Transactional) + 프로그래밍 방식(TransactionTemplate)
 * ========================================================================
 *
 * ── 핵심 개념 ──
 *
 * 1. @Transactional (선언적 트랜잭션)
 *    - AOP 프록시로 트랜잭션 경계 자동 관리
 *    - 메서드 시작 시 트랜잭션 시작, 정상 종료 시 커밋, 예외 시 롤백
 *    - RuntimeException만 기본 롤백 (checked exception은 커밋)
 *
 * 2. TransactionTemplate (프로그래밍 방식)
 *    - 코드 레벨에서 트랜잭션 경계를 직접 제어
 *    - 한 메서드 내에서 여러 개의 독립 트랜잭션 실행 가능
 *    - 부분 커밋/롤백이 필요한 복잡한 시나리오에 적합
 *
 * 3. 전파 수준 (Propagation)
 *    ┌────────────────────┬───────────────────────────────────────┐
 *    │ REQUIRED (기본)     │ 기존 TX 있으면 참여, 없으면 새로 생성  │
 *    │                     │ → 가장 일반적인 사용                  │
 *    ├────────────────────┼───────────────────────────────────────┤
 *    │ REQUIRES_NEW        │ 항상 새 TX 생성 (기존 TX 일시 중단)   │
 *    │                     │ → 로그 저장 등 독립 커밋 필요 시      │
 *    ├────────────────────┼───────────────────────────────────────┤
 *    │ NOT_SUPPORTED       │ TX 없이 실행 (기존 TX 일시 중단)      │
 *    │                     │ → 비동기 작업 전에 TX 분리 시         │
 *    ├────────────────────┼───────────────────────────────────────┤
 *    │ NESTED              │ 기존 TX 내에 중첩 TX (세이브포인트)   │
 *    │                     │ → 부분 롤백 필요 시                   │
 *    └────────────────────┴───────────────────────────────────────┘
 *
 * 4. readOnly 최적화
 *    - @Transactional(readOnly = true)
 *    - Hibernate 더티체킹 비활성화 → 성능 향상
 *    - DB에 읽기 전용 힌트 전달 → 레플리카 라우팅 가능
 *
 * ── 사용 특성 ──
 *
 * 선언적(@Transactional):
 * - 간편하지만 프록시 기반이므로 같은 클래스 내부 호출 시 동작 안 함
 * - self-invocation 문제: this.method() 호출 시 AOP 프록시를 거치지 않음
 *
 * 프로그래밍(TransactionTemplate):
 * - 코드가 복잡해지지만 세밀한 제어 가능
 * - self-invocation 문제 없음
 * - 한 메서드 내에서 여러 독립 트랜잭션 실행 가능
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionExampleService {

    /**
     * PlatformTransactionManager:
     * Spring의 트랜잭션 추상화 인터페이스.
     * DataSourceTransactionManager(JDBC), JpaTransactionManager(JPA) 등의 구현체가 있음.
     * TransactionTemplate을 생성할 때 사용.
     */
    private final PlatformTransactionManager transactionManager;

    // ================================================================
    // [1] 선언적 트랜잭션 - @Transactional
    // ================================================================

    /**
     * 기본 트랜잭션 (REQUIRED)
     *
     * 기존 TX가 있으면 참여, 없으면 새로 생성.
     * RuntimeException 발생 시 자동 롤백.
     */
    @Transactional
    public void basicTransaction(String data) {
        log.info("[TX] 기본 트랜잭션 시작");
        // DB 저장 로직
        // repository.save(entity);
        log.info("[TX] 기본 트랜잭션 종료 → 커밋");
    }

    /**
     * 읽기 전용 트랜잭션
     *
     * readOnly = true:
     * - Hibernate 더티체킹 비활성화 (flush 안 함)
     * - DB에 읽기 전용 힌트 전달
     * - 레플리카 DB로 라우팅 가능 (DataSource Routing 설정 시)
     * - 쿼리 성능 최적화
     */
    @Transactional(readOnly = true)
    public String readOnlyTransaction(Long id) {
        log.info("[TX] 읽기 전용 트랜잭션 - id={}", id);
        // return repository.findById(id);
        return "data-" + id;
    }

    /**
     * REQUIRES_NEW - 독립 트랜잭션
     *
     * 호출자의 트랜잭션과 무관하게 새 트랜잭션 생성.
     * 이 메서드의 커밋/롤백은 호출자에게 영향 없음.
     *
     * 사용 예: 감사 로그 저장 (비즈니스 로직 롤백 시에도 로그는 유지)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void independentTransaction(String auditLog) {
        log.info("[TX] 독립 트랜잭션 - 감사 로그 저장: {}", auditLog);
        // auditRepository.save(new AuditLog(auditLog));
        // → 호출자가 롤백되어도 이 저장은 유지됨
    }

    // ================================================================
    // [2] 프로그래밍 방식 트랜잭션 - TransactionTemplate
    // ================================================================

    /**
     * TransactionTemplate 기본 사용
     *
     * @Transactional 대신 코드로 직접 트랜잭션 경계 설정.
     * execute() 블록 내부가 하나의 트랜잭션.
     * - 정상 반환 → 커밋
     * - 예외 발생 → 롤백
     * - status.setRollbackOnly() → 명시적 롤백 마킹
     */
    public String programmaticTransaction(String data) {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        // execute() 내부가 하나의 트랜잭션
        String result = txTemplate.execute(status -> {
            log.info("[TX] 프로그래밍 트랜잭션 시작");

            // DB 작업
            // Entity saved = repository.save(entity);

            // 조건부 롤백
            if ("INVALID".equals(data)) {
                status.setRollbackOnly();  // 명시적 롤백 마킹
                return null;
            }

            log.info("[TX] 프로그래밍 트랜잭션 완료 → 커밋");
            return "result-" + data;
        });

        return result;
    }

    /**
     * 2단계 트랜잭션 분리 - 비동기 작업 전 TX 분리
     *
     * 실무 시나리오:
     * Phase 1: DB에 데이터 준비 (트랜잭션 내)
     * Phase 2: 외부 API 호출 (트랜잭션 밖 - 비동기)
     *
     * @Transactional(propagation = NOT_SUPPORTED)로 기존 TX를 끊고,
     * TransactionTemplate으로 Phase 1만 별도 TX 실행.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String twoPhaseProcess(String requestData) {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        // Phase 1: DB 작업 (독립 트랜잭션)
        String preparedData = txTemplate.execute(status -> {
            log.info("[Phase 1] DB 준비 작업 (TX 내부)");
            // 데이터 검증, 저장, 상태 변경
            return "prepared-" + requestData;
        });

        // Phase 2: 외부 호출 (TX 밖) - 비동기 가능
        log.info("[Phase 2] 외부 API 호출 (TX 외부) - data={}", preparedData);
        // asyncExecutor.execute(() -> externalApi.call(preparedData));

        return preparedData;
    }

    /**
     * 레코드별 독립 트랜잭션 - 배치 처리
     *
     * 다수의 레코드를 처리할 때 각 레코드를 독립 트랜잭션으로 처리.
     * 하나의 레코드 실패가 다른 레코드에 영향을 주지 않음.
     *
     * ┌────────────────────────────────────────────┐
     * │ Record-1: TX 시작 → 처리 → 커밋 ✓         │
     * │ Record-2: TX 시작 → 처리 → 에러 → 롤백 ✗  │
     * │ Record-3: TX 시작 → 처리 → 커밋 ✓         │
     * │ → Record-2만 롤백, 나머지는 정상 커밋      │
     * └────────────────────────────────────────────┘
     */
    public int batchProcessWithIndependentTx(List<String> records) {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        int successCount = 0;

        for (String record : records) {
            try {
                // 각 레코드를 독립 트랜잭션으로 처리
                txTemplate.execute(status -> {
                    log.info("[Batch TX] 레코드 처리 - record={}", record);
                    // processRecord(record);
                    return null;
                });
                successCount++;
            } catch (Exception e) {
                // 실패한 레코드만 롤백, 다음 레코드 계속 처리
                log.error("[Batch TX] 실패 - record={}, error={}", record, e.getMessage());
            }
        }

        log.info("[Batch TX] 완료 - 성공={}/{}", successCount, records.size());
        return successCount;
    }
}
