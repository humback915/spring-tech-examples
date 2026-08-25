package kr.co.example.transaction;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ========================================================================
 * [5-B] 트랜잭션 전파(Propagation) 예제
 * ========================================================================
 *
 * ── 트랜잭션 전파란? ──
 *
 * 이미 진행 중인 트랜잭션이 있을 때,
 * 새로 호출되는 @Transactional 메서드가 기존 트랜잭션에
 * "참여할지 / 새로 만들지 / 무시할지"를 결정하는 정책.
 *
 * ── 전파 수준 전체 목록 ──
 *
 * ┌──────────────────┬────────────────────────────────────────────────────┐
 * │ REQUIRED (기본)   │ 기존 TX 있으면 참여, 없으면 새로 생성               │
 * │                   │ → 대부분의 서비스 메서드에서 사용                   │
 * │                   │ → 호출자와 같이 커밋/롤백됨                        │
 * ├──────────────────┼────────────────────────────────────────────────────┤
 * │ REQUIRES_NEW      │ 항상 새 TX 생성 (기존 TX는 일시 중단)              │
 * │                   │ → 호출자와 독립적으로 커밋/롤백                    │
 * │                   │ → 감사 로그, 이력 저장 등에 활용                   │
 * ├──────────────────┼────────────────────────────────────────────────────┤
 * │ SUPPORTS          │ 기존 TX 있으면 참여, 없으면 TX 없이 실행           │
 * │                   │ → 읽기 전용 로직에서 유연하게 사용                 │
 * │                   │ → TX가 있으면 더티체킹 혜택, 없어도 동작           │
 * ├──────────────────┼────────────────────────────────────────────────────┤
 * │ NOT_SUPPORTED     │ TX 없이 실행 (기존 TX가 있으면 일시 중단)          │
 * │                   │ → 외부 API 호출 등 TX가 불필요한 작업에 사용       │
 * │                   │ → TX 내에서 오래 걸리는 I/O를 분리할 때            │
 * ├──────────────────┼────────────────────────────────────────────────────┤
 * │ MANDATORY         │ 반드시 기존 TX가 있어야 함 (없으면 예외 발생)       │
 * │                   │ → TX 컨텍스트가 보장되어야 하는 도메인 로직에 사용  │
 * │                   │ → 실수로 TX 없이 호출하는 것을 방지                │
 * ├──────────────────┼────────────────────────────────────────────────────┤
 * │ NEVER             │ TX가 있으면 예외 발생 (반드시 TX 없이 실행)         │
 * │                   │ → TX 컨텍스트에서 절대 호출되면 안 되는 로직        │
 * │                   │ → 거의 사용하지 않음                              │
 * ├──────────────────┼────────────────────────────────────────────────────┤
 * │ NESTED            │ 기존 TX 내에 세이브포인트 기반 중첩 TX 생성        │
 * │                   │ → 중첩 TX 롤백 시 세이브포인트까지만 롤백          │
 * │                   │ → 외부 TX는 유지됨 (부분 롤백 가능)               │
 * │                   │ → JPA에서는 지원 제한적 (JDBC에서 주로 사용)       │
 * └──────────────────┴────────────────────────────────────────────────────┘
 *
 * ── 주의: self-invocation ──
 *
 * @Transactional은 AOP 프록시 기반으로 동작하므로,
 * 같은 클래스 내부에서 호출하면 프록시를 거치지 않아 전파 설정이 무시됨.
 *
 * 해결 방법:
 * 1. 다른 빈(클래스)에서 호출
 * 2. TransactionTemplate으로 프로그래밍 방식 사용
 * 3. self-injection (@Lazy 또는 ObjectProvider)
 *
 * ── 이 예제의 구조 ──
 *
 * PropagationExampleService (외부 호출자 역할)
 *   └─ PropagationTargetService (전파 대상 메서드)
 *
 * @Transactional 전파가 동작하려면 반드시 다른 빈에서 호출해야 하므로,
 * 두 개의 서비스 클래스로 분리하여 각 전파 수준의 동작을 시연.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PropagationExampleService {

    /** 전파 대상 메서드가 정의된 별도 서비스 빈 */
    private final PropagationTargetService targetService;

    // ================================================================
    // [1] REQUIRED (기본) - 기존 TX 참여 / 없으면 새로 생성
    // ================================================================

    /**
     * REQUIRED 전파 시연
     *
     * 호출 흐름:
     * ┌──────────────────────────────────────────────────┐
     * │ outerRequired() - TX-A 시작                      │
     * │   ├─ save("outer-data")                          │
     * │   └─ targetService.innerRequired()               │
     * │        └─ TX-A에 참여 (동일 트랜잭션)             │
     * │           save("inner-data")                     │
     * │                                                  │
     * │ 결과: 둘 다 TX-A에서 실행                         │
     * │ inner에서 예외 → 전체(outer + inner) 롤백         │
     * └──────────────────────────────────────────────────┘
     *
     * REQUIRED가 기본인 이유:
     * - 호출자의 TX에 자연스럽게 참여하여 원자성 보장
     * - 중첩된 서비스 호출이 모두 하나의 TX로 묶임
     * - 어디서 예외가 발생해도 전체가 일관되게 롤백
     */
    @Transactional
    public void outerRequired() {
        log.info("[REQUIRED] 외부 TX 시작");

        // 외부 서비스의 DB 작업
        // repository.save(new Entity("outer-data"));

        // 내부 서비스 호출 → 같은 TX에 참여 (REQUIRED)
        targetService.innerRequired();

        log.info("[REQUIRED] 외부 TX 종료 → outer + inner 함께 커밋");
    }

    // ================================================================
    // [2] REQUIRES_NEW - 항상 새 TX 생성 (독립적 커밋/롤백)
    // ================================================================

    /**
     * REQUIRES_NEW 전파 시연 - 감사 로그
     *
     * 호출 흐름:
     * ┌──────────────────────────────────────────────────┐
     * │ outerWithAuditLog() - TX-A 시작                  │
     * │   ├─ save(order)                                 │
     * │   ├─ targetService.saveAuditLog()                │
     * │   │    └─ TX-B 시작 (새 트랜잭션)                │
     * │   │       save(auditLog)                         │
     * │   │       TX-B 커밋 ✓                            │
     * │   └─ 이후 로직에서 예외 발생!                     │
     * │      TX-A 롤백 ✗                                 │
     * │                                                  │
     * │ 결과: 주문은 롤백되었지만, 감사 로그는 유지됨 ✓   │
     * └──────────────────────────────────────────────────┘
     *
     * REQUIRES_NEW 활용 시나리오:
     * - 감사 로그: 비즈니스 실패 시에도 "실패한 시도" 기록 유지
     * - 알림 이력: 알림 발송 결과를 독립적으로 저장
     * - 시퀀스/채번: 번호 채번 후 롤백으로 번호 손실 방지
     */
    @Transactional
    public void outerWithAuditLog() {
        log.info("[REQUIRES_NEW] 외부 TX 시작 - 주문 처리");

        // 주문 저장 (TX-A)
        // orderRepository.save(order);

        // 감사 로그 저장 (TX-B - 독립 트랜잭션)
        // → 이 메서드의 커밋은 외부 TX와 무관
        targetService.saveAuditLog("주문 처리 시도", "ORDER_CREATE");

        // 이후 로직에서 에러가 발생하면:
        // TX-A(주문)는 롤백되지만, TX-B(감사 로그)는 이미 커밋되어 유지됨
        log.info("[REQUIRES_NEW] 외부 TX 종료");
    }

    // ================================================================
    // [3] SUPPORTS - TX 있으면 참여, 없으면 TX 없이 실행
    // ================================================================

    /**
     * SUPPORTS 전파 시연
     *
     * 시나리오 1: TX 있는 상태에서 호출
     * ┌──────────────────────────────────────────┐
     * │ callWithTx() - TX-A 시작                  │
     * │   └─ targetService.supportsMethod()       │
     * │        └─ TX-A에 참여 (더티체킹 적용)     │
     * └──────────────────────────────────────────┘
     *
     * 시나리오 2: TX 없는 상태에서 호출
     * ┌──────────────────────────────────────────┐
     * │ callWithoutTx() (TX 없음)                 │
     * │   └─ targetService.supportsMethod()       │
     * │        └─ TX 없이 실행 (auto-commit)      │
     * └──────────────────────────────────────────┘
     *
     * SUPPORTS 활용 시나리오:
     * - 읽기 전용 유틸리티 메서드
     * - TX가 있으면 영속성 컨텍스트 활용, 없어도 정상 동작
     */
    @Transactional
    public void callSupportsWithTx() {
        log.info("[SUPPORTS] TX 있는 상태에서 호출");
        targetService.supportsMethod(); // → TX 참여
    }

    public void callSupportsWithoutTx() {
        log.info("[SUPPORTS] TX 없는 상태에서 호출");
        targetService.supportsMethod(); // → TX 없이 실행
    }

    // ================================================================
    // [4] NOT_SUPPORTED - TX 없이 실행 (기존 TX 일시 중단)
    // ================================================================

    /**
     * NOT_SUPPORTED 전파 시연 - 외부 API 호출 분리
     *
     * 호출 흐름:
     * ┌──────────────────────────────────────────────────┐
     * │ outerWithExternalCall() - TX-A 시작               │
     * │   ├─ save(data)             ← TX-A 내             │
     * │   ├─ targetService.callExternalApi()              │
     * │   │    └─ TX-A 일시 중단                          │
     * │   │       외부 API 호출 (TX 없이)                  │
     * │   │       TX-A 재개                               │
     * │   └─ save(result)           ← TX-A 내             │
     * │   TX-A 커밋                                       │
     * └──────────────────────────────────────────────────┘
     *
     * NOT_SUPPORTED를 사용하는 이유:
     * - 외부 API 호출은 DB TX와 무관 → TX 범위에서 제외
     * - TX 내에서 외부 호출이 오래 걸리면 DB 커넥션을 불필요하게 점유
     * - 외부 호출 실패가 TX 롤백을 유발하는 것을 방지
     */
    @Transactional
    public void outerWithExternalCall() {
        log.info("[NOT_SUPPORTED] 외부 TX 시작");

        // DB 작업 (TX-A 내)
        // repository.save(entity);

        // 외부 API 호출 (TX 일시 중단 → TX 없이 실행)
        String apiResult = targetService.callExternalApi("request-data");

        // DB 작업 재개 (TX-A 내)
        // repository.save(resultEntity);

        log.info("[NOT_SUPPORTED] 외부 TX 종료 - apiResult={}", apiResult);
    }

    // ================================================================
    // [5] MANDATORY - 반드시 기존 TX 필요 (없으면 예외)
    // ================================================================

    /**
     * MANDATORY 전파 시연
     *
     * 호출 흐름:
     * ┌──────────────────────────────────────────────────┐
     * │ 정상 호출 (TX 있음):                              │
     * │   outerCallMandatory() - TX-A 시작                │
     * │     └─ targetService.mandatoryMethod()            │
     * │          └─ TX-A에 참여 (정상)                    │
     * │                                                   │
     * │ 잘못된 호출 (TX 없음):                            │
     * │   targetService.mandatoryMethod() 직접 호출       │
     * │     → IllegalTransactionStateException 발생!      │
     * └──────────────────────────────────────────────────┘
     *
     * MANDATORY 활용 시나리오:
     * - 반드시 TX 내에서 호출되어야 하는 핵심 도메인 로직
     * - TX 없이 호출하면 데이터 정합성이 깨지는 경우
     * - 개발 단계에서 잘못된 사용을 즉시 감지 (Fail-Fast)
     */
    @Transactional
    public void outerCallMandatory() {
        log.info("[MANDATORY] TX 있는 상태에서 호출 - 정상 동작");
        targetService.mandatoryMethod();
    }

    // 아래처럼 TX 없이 호출하면 IllegalTransactionStateException 발생
    // public void wrongCallMandatory() {
    //     targetService.mandatoryMethod(); // → 예외!
    // }

    // ================================================================
    // [6] NESTED - 세이브포인트 기반 중첩 TX (부분 롤백)
    // ================================================================

    /**
     * NESTED 전파 시연 - 부분 롤백
     *
     * 호출 흐름:
     * ┌───────────────────────────────────────────────────────┐
     * │ outerWithNested() - TX-A 시작                         │
     * │   ├─ save(mainData)                                   │
     * │   ├─ try {                                            │
     * │   │     targetService.nestedMethod()                   │
     * │   │       └─ SAVEPOINT 생성                            │
     * │   │          save(subData)                             │
     * │   │          예외 발생!                                │
     * │   │          SAVEPOINT로 롤백 (subData만 롤백)         │
     * │   │  } catch → 에러 로깅, 계속 진행                   │
     * │   ├─ save(otherData)                                  │
     * │   └─ TX-A 커밋 (mainData + otherData는 유지)          │
     * │                                                       │
     * │ 결과: 중첩 TX 내 작업만 롤백, 외부 TX는 계속 진행      │
     * └───────────────────────────────────────────────────────┘
     *
     * NESTED vs REQUIRES_NEW:
     * ┌────────────────┬───────────────────────────────────────┐
     * │ NESTED          │ 세이브포인트로 부분 롤백               │
     * │                 │ 외부 TX가 커밋되어야 같이 커밋         │
     * │                 │ 외부 TX 롤백 시 중첩도 함께 롤백      │
     * ├────────────────┼───────────────────────────────────────┤
     * │ REQUIRES_NEW    │ 완전히 독립된 새 TX                   │
     * │                 │ 외부 TX와 무관하게 독립 커밋/롤백     │
     * │                 │ 외부 TX 롤백 시에도 새 TX는 유지      │
     * └────────────────┴───────────────────────────────────────┘
     *
     * 주의: JPA(Hibernate)에서는 NESTED가 제한적으로 지원됨.
     * JDBC DataSourceTransactionManager에서 완전하게 동작.
     */
    @Transactional
    public void outerWithNested() {
        log.info("[NESTED] 외부 TX 시작");

        // 메인 데이터 저장 (TX-A)
        // repository.save(mainData);

        // 중첩 TX 시도 → 실패해도 외부 TX 계속 진행
        try {
            targetService.nestedMethod();
        } catch (Exception e) {
            // 중첩 TX가 롤백되었지만 외부 TX는 유효
            log.warn("[NESTED] 중첩 TX 실패 (부분 롤백) - error={}", e.getMessage());
        }

        // 추가 작업 (TX-A에서 계속)
        // repository.save(otherData);

        log.info("[NESTED] 외부 TX 종료 → 메인 데이터는 정상 커밋");
    }

    // ================================================================
    // [7] 실무 종합 예제: 주문 처리 + 감사 로그 + 알림
    // ================================================================

    /**
     * 실무 종합 예제: 여러 전파 수준 조합
     *
     * 호출 흐름:
     * ┌────────────────────────────────────────────────────┐
     * │ processOrder() - TX-A 시작 (REQUIRED)              │
     * │                                                    │
     * │   [1] 주문 저장 (TX-A)                             │
     * │       └─ repository.save(order)                    │
     * │                                                    │
     * │   [2] 감사 로그 저장 (TX-B, REQUIRES_NEW)          │
     * │       └─ 독립 TX → 주문 롤백 시에도 로그 유지      │
     * │                                                    │
     * │   [3] 외부 결제 API 호출 (NOT_SUPPORTED)           │
     * │       └─ TX 밖에서 실행 → DB 커넥션 점유 방지      │
     * │                                                    │
     * │   [4] 결제 결과 저장 (TX-A에 복귀)                 │
     * │       └─ repository.save(paymentResult)            │
     * │                                                    │
     * │   [5] 포인트 적립 시도 (NESTED)                    │
     * │       └─ 실패해도 주문은 정상 처리 (부분 롤백)     │
     * │                                                    │
     * │ TX-A 커밋 → 주문 + 결제 결과 커밋                  │
     * └────────────────────────────────────────────────────┘
     */
    @Transactional
    public void processOrder(Long orderId) {
        log.info("[종합] 주문 처리 시작 - orderId={}", orderId);

        // [1] REQUIRED: 주문 저장 (현재 TX에 참여)
        targetService.innerRequired();

        // [2] REQUIRES_NEW: 감사 로그 (독립 TX → 주문 롤백 시에도 유지)
        targetService.saveAuditLog("주문 처리 - orderId=" + orderId, "ORDER_PROCESS");

        // [3] NOT_SUPPORTED: 외부 결제 API (TX 밖에서 실행)
        String paymentResult = targetService.callExternalApi("payment-" + orderId);

        // [4] 결제 결과 저장 (현재 TX에서)
        log.info("[종합] 결제 결과 저장 - result={}", paymentResult);

        // [5] NESTED: 포인트 적립 시도 (실패해도 주문 진행)
        try {
            targetService.nestedMethod();
        } catch (Exception e) {
            log.warn("[종합] 포인트 적립 실패 (무시) - error={}", e.getMessage());
        }

        log.info("[종합] 주문 처리 완료 - orderId={}", orderId);
    }
}
