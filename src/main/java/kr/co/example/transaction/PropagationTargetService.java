package kr.co.example.transaction;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ========================================================================
 * 트랜잭션 전파 대상 서비스
 * ========================================================================
 *
 * PropagationExampleService에서 호출되는 대상 메서드 모음.
 *
 * ── 왜 별도 클래스인가? ──
 *
 * @Transactional은 Spring AOP 프록시 기반으로 동작.
 * 같은 클래스 내부 호출(this.method())은 프록시를 거치지 않아
 * 전파 설정이 무시됨.
 *
 * 따라서 전파가 올바르게 동작하려면
 * 반드시 다른 빈(클래스)에서 호출해야 함.
 *
 * ┌──────────────────────────────────────────────┐
 * │ 잘못된 예 (self-invocation):                  │
 * │ class A {                                     │
 * │   @Transactional                              │
 * │   void outer() {                              │
 * │     this.inner(); // ← 프록시 미경유!         │
 * │   }                                           │
 * │   @Transactional(propagation = REQUIRES_NEW)  │
 * │   void inner() { ... } // 전파 설정 무시됨    │
 * │ }                                             │
 * │                                               │
 * │ 올바른 예 (별도 빈 호출):                     │
 * │ class A {                                     │
 * │   @Autowired B b;                             │
 * │   @Transactional                              │
 * │   void outer() {                              │
 * │     b.inner(); // ← 프록시 경유! 전파 동작    │
 * │   }                                           │
 * │ }                                             │
 * │ class B {                                     │
 * │   @Transactional(propagation = REQUIRES_NEW)  │
 * │   void inner() { ... } // 새 TX 생성됨        │
 * │ }                                             │
 * └──────────────────────────────────────────────┘
 */
@Slf4j
@Service
public class PropagationTargetService {

    // ================================================================
    // REQUIRED - 기존 TX 참여 (기본값)
    // ================================================================

    /**
     * REQUIRED: 호출자의 TX에 참여
     *
     * 호출자에 TX가 있으면 → 해당 TX에 참여 (같이 커밋/롤백)
     * 호출자에 TX가 없으면 → 새 TX 생성
     *
     * 이 메서드에서 예외가 발생하면:
     * - 참여한 전체 TX가 롤백 마킹됨
     * - 호출자가 예외를 catch해도 TX는 이미 rollback-only 상태
     * - 최종적으로 전체 TX가 롤백됨
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void innerRequired() {
        log.info("[REQUIRED] 내부 메서드 실행 - 호출자 TX에 참여");
        // repository.save(entity);
    }

    // ================================================================
    // REQUIRES_NEW - 독립 트랜잭션 생성
    // ================================================================

    /**
     * REQUIRES_NEW: 항상 새 TX 생성 (호출자 TX와 독립)
     *
     * 호출자의 TX가 있으면 → 호출자 TX를 일시 중단 (suspend)
     * 새로운 TX 시작 → 이 메서드 완료 시 독립적으로 커밋/롤백
     * 호출자 TX 재개 (resume)
     *
     * 호출자가 롤백되어도 이 메서드의 커밋은 유지됨.
     * → 감사 로그, 이력 저장, 시퀀스 채번 등에 활용
     *
     * 주의:
     * - 새 TX를 위해 새 DB 커넥션을 사용 → 커넥션 풀 고갈 주의
     * - 호출자 TX가 오래 걸리면 suspend 시간이 길어짐
     *
     * @param message  감사 로그 메시지
     * @param action   감사 로그 액션 타입 (CREATE, UPDATE, DELETE 등)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAuditLog(String message, String action) {
        log.info("[REQUIRES_NEW] 감사 로그 저장 (독립 TX) - action={}, message={}", action, message);
        // auditLogRepository.save(new AuditLog(action, message, LocalDateTime.now()));
        // → 호출자 TX가 롤백되어도 이 저장은 유지됨
    }

    // ================================================================
    // SUPPORTS - TX 있으면 참여, 없으면 TX 없이 실행
    // ================================================================

    /**
     * SUPPORTS: TX 유무에 따라 유연하게 동작
     *
     * TX가 있는 상태에서 호출:
     * → 해당 TX에 참여 (Hibernate 영속성 컨텍스트 활용 가능)
     *
     * TX가 없는 상태에서 호출:
     * → TX 없이 실행 (auto-commit 모드)
     * → 각 SQL이 독립적으로 커밋됨
     *
     * 활용:
     * - 조회 전용 유틸리티 메서드 (TX 유무에 관계없이 동작해야 할 때)
     * - TX가 있으면 1차 캐시 활용, 없어도 직접 DB 조회
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public String supportsMethod() {
        log.info("[SUPPORTS] 메서드 실행 - TX 있으면 참여, 없으면 TX 없이 실행");
        // return repository.findById(id);
        return "supports-result";
    }

    // ================================================================
    // NOT_SUPPORTED - TX 없이 실행 (기존 TX 일시 중단)
    // ================================================================

    /**
     * NOT_SUPPORTED: TX 밖에서 실행 (기존 TX 일시 중단)
     *
     * 호출자의 TX가 있으면 → 일시 중단 (suspend)
     * 이 메서드는 TX 없이 실행 → 완료 후 호출자 TX 재개
     *
     * 활용:
     * - 외부 API 호출: TX 내에서 API 응답을 기다리면 DB 커넥션을 불필요하게 점유
     * - 파일 I/O: TX와 무관한 작업을 TX 범위에서 분리
     * - 오래 걸리는 연산: TX 범위를 최소화하여 DB 커넥션 효율 향상
     *
     * @param data 외부 API에 전송할 데이터
     * @return 외부 API 응답 결과
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String callExternalApi(String data) {
        log.info("[NOT_SUPPORTED] 외부 API 호출 (TX 없이) - data={}", data);
        // WebClient 호출, RestTemplate 호출 등
        // 이 동안 호출자의 TX는 일시 중단 → DB 커넥션 점유 방지
        return "api-response-" + data;
    }

    // ================================================================
    // MANDATORY - 반드시 기존 TX 필요
    // ================================================================

    /**
     * MANDATORY: 기존 TX가 반드시 있어야 함
     *
     * TX가 있는 상태에서 호출 → 정상 동작 (TX에 참여)
     * TX가 없는 상태에서 호출 → IllegalTransactionStateException 발생
     *
     * 활용:
     * - 핵심 도메인 로직이 반드시 TX 내에서만 실행되어야 할 때
     * - 잘못된 호출을 컴파일 타임이 아닌 런타임에 감지 (Fail-Fast)
     * - 데이터 정합성이 TX 없이 깨질 수 있는 중요한 연산
     *
     * 예: 잔액 차감, 재고 감소 등 원자적 실행이 보장되어야 하는 로직
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void mandatoryMethod() {
        log.info("[MANDATORY] 실행 - 반드시 TX 내에서 호출되어야 함");
        // 핵심 도메인 로직 (TX 없이 실행되면 안 되는 코드)
        // balance.subtract(amount);
    }

    // ================================================================
    // NESTED - 세이브포인트 기반 중첩 TX
    // ================================================================

    /**
     * NESTED: 세이브포인트 기반 중첩 트랜잭션
     *
     * 호출자 TX 내에 세이브포인트를 생성.
     * 이 메서드에서 예외 발생 시 → 세이브포인트까지만 롤백 (부분 롤백)
     * 호출자 TX는 그대로 유효 → 나머지 작업 계속 진행 가능
     *
     * NESTED 특성:
     * - 중첩 TX 성공 → 외부 TX 커밋 시 함께 커밋
     * - 중첩 TX 실패 → 세이브포인트까지만 롤백, 외부 TX는 유효
     * - 외부 TX 실패 → 중첩 TX도 함께 롤백 (외부에 종속)
     *
     * 활용:
     * - 옵션/부가 기능 처리 (실패해도 메인 로직 진행)
     * - 포인트 적립: 적립 실패 시에도 주문은 정상 처리
     * - 쿠폰 적용: 쿠폰 적용 실패 시에도 결제는 진행
     *
     * 제한사항:
     * - JPA(Hibernate) 단독으로는 미지원 (내부적으로 REQUIRES_NEW처럼 동작할 수 있음)
     * - JDBC DataSourceTransactionManager에서 완전하게 지원
     * - 사용 시 TX 매니저 구현체 확인 필요
     */
    @Transactional(propagation = Propagation.NESTED)
    public void nestedMethod() {
        log.info("[NESTED] 중첩 TX 실행 - 세이브포인트 생성");
        // 부가 기능 로직 (실패해도 외부 TX 유지)
        // pointService.addPoints(userId, 100);
    }
}
