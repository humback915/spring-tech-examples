package kr.co.example.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * ========================================================================
 * Checked Exception vs Unchecked Exception 예제
 * ========================================================================
 *
 * ── Java 예외 계층 구조 ──
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  Object                                                        │
 * │  └── Throwable                                                 │
 * │      ├── Error (Unchecked) ← 복구 불가 (OOM, StackOverflow)    │
 * │      └── Exception                                             │
 * │          ├── IOException (Checked)                              │
 * │          ├── SQLException (Checked)                             │
 * │          ├── ParseException (Checked)                           │
 * │          ├── ReflectiveOperationException (Checked)             │
 * │          └── RuntimeException (Unchecked)                       │
 * │              ├── NullPointerException                           │
 * │              ├── IllegalArgumentException                       │
 * │              ├── IllegalStateException                          │
 * │              ├── IndexOutOfBoundsException                      │
 * │              └── UnsupportedOperationException                  │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * ── Checked vs Unchecked 비교 ──
 *
 * ┌─────────────────┬─────────────────────┬─────────────────────────┐
 * │ 항목            │ Checked Exception   │ Unchecked Exception     │
 * ├─────────────────┼─────────────────────┼─────────────────────────┤
 * │ 상속 대상       │ Exception           │ RuntimeException        │
 * │ 컴파일러 강제   │ O (try-catch/throws)│ X (선택적)              │
 * │ 발생 원인       │ 외부 환경 문제      │ 프로그래밍 실수/논리 오류│
 * │ 복구 가능성     │ 복구 가능한 상황    │ 대부분 복구 불가        │
 * │ @Transactional  │ 기본 롤백 안 됨     │ 기본 롤백됨             │
 * │ 대표 예시       │ IOException         │ NullPointerException    │
 * │                 │ SQLException        │ IllegalArgumentException│
 * │                 │ ParseException      │ IllegalStateException   │
 * └─────────────────┴─────────────────────┴─────────────────────────┘
 *
 * ── @Transactional 롤백 규칙 ──
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  @Transactional                                                │
 * │  → RuntimeException (Unchecked)  : 자동 롤백 O                │
 * │  → Exception (Checked)           : 자동 롤백 X (커밋됨!)      │
 * │                                                                │
 * │  @Transactional(rollbackFor = Exception.class)                 │
 * │  → Exception (Checked)           : 롤백 O (명시적 지정)       │
 * │                                                                │
 * │  @Transactional(noRollbackFor = IllegalArgumentException.class)│
 * │  → IllegalArgumentException      : 롤백 X (예외적 허용)       │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * ── 실무 예외 전략 ──
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │ 1. 비즈니스 예외 → RuntimeException 상속 (Unchecked)           │
 * │    이유: @Transactional 자동 롤백, try-catch 강제 없음         │
 * │                                                                │
 * │ 2. Checked → Unchecked 변환 (래핑)                             │
 * │    try { ... } catch (IOException e) {                         │
 * │        throw new RuntimeException("파일 처리 실패", e);        │
 * │    }                                                           │
 * │                                                                │
 * │ 3. 외부 리소스 접근 → Checked 유지하거나 래핑                   │
 * │    파일 I/O, 네트워크, DB 연결 등                              │
 * └─────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Service
public class CheckedUncheckedExample {

    // ================================================================
    // [1] Checked Exception — 컴파일러가 처리를 강제
    // ================================================================

    /**
     * Checked Exception 발생 예시 — IOException.
     *
     * 파일 읽기/쓰기, 네트워크 통신 등 외부 리소스 접근 시 발생.
     * 컴파일러가 try-catch 또는 throws를 강제하므로 반드시 처리해야 한다.
     *
     * <pre>
     * 대표적인 Checked Exception:
     * - IOException        : 파일/네트워크 I/O 실패
     * - SQLException       : DB 접근 실패
     * - ParseException     : 문자열 파싱 실패 (날짜 등)
     * - ClassNotFoundException : 클래스 로딩 실패
     * - InterruptedException   : 스레드 인터럽트
     * </pre>
     *
     * @param filePath 읽을 파일 경로
     * @return 파일 내용
     * @throws IOException 파일을 읽을 수 없을 때 (Checked — 호출부에서 반드시 처리)
     */
    public String readFileChecked(String filePath) throws IOException {
        // IOException은 Checked → 메서드 시그니처에 throws 선언 필수
        List<String> lines = Files.readAllLines(Path.of(filePath));
        log.info("[Checked] 파일 읽기 성공 - path={}, lines={}", filePath, lines.size());
        return String.join("\n", lines);
    }

    /**
     * Checked Exception 발생 예시 — ParseException.
     *
     * 날짜 문자열 파싱 시 형식이 맞지 않으면 ParseException 발생.
     * 컴파일러가 예외 처리를 강제한다.
     *
     * @param dateString 날짜 문자열 (yyyy-MM-dd 형식)
     * @return 파싱된 Date 객체
     * @throws ParseException 날짜 형식이 올바르지 않을 때
     */
    public Date parseDateChecked(String dateString) throws ParseException {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        sdf.setLenient(false); // 엄격한 파싱 (2024-13-32 같은 값 거부)
        Date date = sdf.parse(dateString);
        log.info("[Checked] 날짜 파싱 성공 - input={}, result={}", dateString, date);
        return date;
    }

    // ================================================================
    // [2] Unchecked Exception — 컴파일러가 강제하지 않음
    // ================================================================

    /**
     * Unchecked Exception 발생 예시 — IllegalArgumentException.
     *
     * 메서드에 전달된 인자가 유효하지 않을 때 사용.
     * RuntimeException 하위이므로 컴파일러가 try-catch를 강제하지 않는다.
     *
     * <pre>
     * 대표적인 Unchecked Exception:
     * - NullPointerException       : null 참조 접근
     * - IllegalArgumentException   : 잘못된 인자
     * - IllegalStateException      : 잘못된 상태에서 메서드 호출
     * - IndexOutOfBoundsException  : 배열/리스트 인덱스 초과
     * - ArithmeticException        : 0으로 나누기
     * - ClassCastException         : 잘못된 타입 캐스팅
     * - UnsupportedOperationException : 지원하지 않는 연산
     * </pre>
     *
     * @param age 나이 (0 이상이어야 함)
     * @return 검증된 나이
     */
    public int validateAge(int age) {
        // Unchecked — throws 선언 없이 바로 던질 수 있음
        if (age < 0) {
            throw new IllegalArgumentException("나이는 0 이상이어야 합니다: " + age);
        }
        if (age > 150) {
            throw new IllegalArgumentException("나이가 유효 범위를 초과합니다: " + age);
        }
        log.info("[Unchecked] 나이 검증 성공 - age={}", age);
        return age;
    }

    /**
     * Unchecked Exception 발생 예시 — IllegalStateException.
     *
     * 객체의 현재 상태에서 해당 메서드를 호출할 수 없을 때 사용.
     * IllegalArgumentException과의 차이: 인자가 아닌 "상태"가 문제.
     *
     * @param status 현재 주문 상태
     */
    public void cancelOrder(String status) {
        // 이미 배송 완료된 주문은 취소 불가
        if ("DELIVERED".equals(status)) {
            throw new IllegalStateException("배송 완료된 주문은 취소할 수 없습니다: status=" + status);
        }
        if ("CANCELLED".equals(status)) {
            throw new IllegalStateException("이미 취소된 주문입니다: status=" + status);
        }
        log.info("[Unchecked] 주문 취소 처리 - status={}", status);
    }

    /**
     * Unchecked Exception 발생 예시 — NullPointerException 방지 패턴.
     *
     * NullPointerException은 방어적 코딩으로 예방하는 것이 원칙.
     * null 체크 후 의미 있는 예외로 변환한다.
     *
     * @param username 사용자명 (null 불가)
     * @return 대문자로 변환된 사용자명
     */
    public String processUsername(String username) {
        // null 체크 → NullPointerException 대신 명확한 메시지의 IllegalArgumentException
        if (username == null) {
            throw new IllegalArgumentException("사용자명은 null일 수 없습니다");
        }
        if (username.isBlank()) {
            throw new IllegalArgumentException("사용자명은 빈 문자열일 수 없습니다");
        }
        log.info("[Unchecked] 사용자명 처리 - username={}", username);
        return username.toUpperCase();
    }

    // ================================================================
    // [3] Checked → Unchecked 변환 (래핑 패턴)
    // ================================================================

    /**
     * Checked Exception을 Unchecked로 래핑하는 패턴.
     *
     * 실무에서 가장 많이 사용하는 전략.
     * Checked Exception을 RuntimeException으로 감싸서 호출부에서
     * try-catch를 강제하지 않으면서도 원인(cause)을 보존한다.
     *
     * <pre>
     * 래핑하는 이유:
     * 1. 서비스 계층에서 throws IOException을 전파하면
     *    컨트롤러, 서비스, 모든 호출 체인에 throws 선언 필요 → 코드 오염
     * 2. @Transactional에서 Checked는 롤백되지 않음
     * 3. GlobalExceptionHandler에서 일관된 처리 가능
     *
     * 주의: 반드시 원본 예외를 cause로 전달 (스택 트레이스 보존)
     *   throw new RuntimeException("메시지", e);  ← e를 반드시 포함
     *   throw new RuntimeException("메시지");      ← 원인 유실 (안티패턴)
     * </pre>
     *
     * @param filePath 읽을 파일 경로
     * @return 파일 내용
     */
    public String readFileWrapped(String filePath) {
        try {
            List<String> lines = Files.readAllLines(Path.of(filePath));
            log.info("[래핑] 파일 읽기 성공 - path={}", filePath);
            return String.join("\n", lines);

        } catch (IOException e) {
            // Checked → Unchecked 래핑 (cause 보존 필수!)
            log.error("[래핑] 파일 읽기 실패 - path={}", filePath, e);
            throw new RuntimeException("파일 읽기 실패: " + filePath, e);
        }
    }

    /**
     * 도메인 예외로 래핑하는 패턴 (실무 권장).
     *
     * RuntimeException 대신 프로젝트의 커스텀 예외(DomainException)로 래핑하면
     * GlobalExceptionHandler에서 HTTP 상태 코드와 에러 코드를 자동 매핑할 수 있다.
     *
     * @param filePath 읽을 파일 경로
     * @return 파일 내용
     */
    public String readFileWithDomainException(String filePath) {
        try {
            List<String> lines = Files.readAllLines(Path.of(filePath));
            return String.join("\n", lines);

        } catch (IOException e) {
            // 도메인 예외로 래핑 → GlobalExceptionHandler에서 500 응답 처리
            throw new CustomException.DomainException(
                    CustomException.DomainExceptionCode.INTERNAL_ERROR);
        }
    }

    // ================================================================
    // [4] @Transactional 롤백 동작 차이
    // ================================================================

    /**
     * Unchecked Exception → @Transactional 자동 롤백.
     *
     * RuntimeException 및 하위 예외는 @Transactional이 감지하여
     * 자동으로 트랜잭션을 롤백한다.
     *
     * <pre>
     * @Transactional
     * public void method() {
     *     repository.save(entity);                 ← DB에 저장
     *     throw new IllegalStateException("실패"); ← 롤백됨!
     * }
     * → entity 저장이 롤백됨 (DB에 반영 안 됨)
     * </pre>
     */
    @Transactional
    public void uncheckedRollbackExample() {
        // 1. 비즈니스 로직 (DB 저장 등)
        log.info("[Rollback] DB 저장 시도");

        // 2. RuntimeException 발생 → 자동 롤백
        throw new IllegalStateException("Unchecked 예외 - 트랜잭션 자동 롤백됨");
    }

    /**
     * Checked Exception → @Transactional 롤백 안 됨 (주의!).
     *
     * Exception(Checked)은 @Transactional의 기본 롤백 대상이 아니다.
     * 예외가 발생해도 트랜잭션이 커밋되어 데이터가 DB에 남을 수 있다.
     *
     * <pre>
     * @Transactional
     * public void method() throws Exception {
     *     repository.save(entity);           ← DB에 저장
     *     throw new IOException("파일 오류"); ← 롤백 안 됨! (커밋됨!)
     * }
     * → entity가 DB에 저장됨 (의도치 않은 동작)
     * </pre>
     *
     * @throws Exception Checked Exception
     */
    @Transactional
    public void checkedNoRollbackExample() throws Exception {
        // 1. 비즈니스 로직 (DB 저장 등)
        log.info("[No Rollback] DB 저장 시도");

        // 2. Checked Exception 발생 → 롤백 안 됨 (커밋!)
        throw new IOException("Checked 예외 - 트랜잭션 롤백되지 않음 (주의!)");
    }

    /**
     * Checked Exception도 롤백하려면 rollbackFor 명시.
     *
     * rollbackFor = Exception.class 를 지정하면 Checked Exception에서도
     * 트랜잭션이 롤백된다. 실무에서는 이 설정을 권장한다.
     *
     * <pre>
     * @Transactional(rollbackFor = Exception.class)  ← 핵심!
     * public void method() throws Exception {
     *     repository.save(entity);
     *     throw new IOException("파일 오류"); ← 이제 롤백됨!
     * }
     * </pre>
     *
     * @throws Exception Checked Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public void checkedWithRollbackForExample() throws Exception {
        // 1. 비즈니스 로직 (DB 저장 등)
        log.info("[RollbackFor] DB 저장 시도");

        // 2. Checked Exception 발생 → rollbackFor 덕분에 롤백됨!
        throw new IOException("Checked 예외 - rollbackFor 지정으로 롤백됨");
    }

    // ================================================================
    // [5] 커스텀 Checked / Unchecked Exception 정의
    // ================================================================

    /**
     * 커스텀 Checked Exception 예시.
     *
     * Exception을 상속하면 Checked Exception이 된다.
     * 호출부에서 반드시 try-catch 또는 throws로 처리해야 한다.
     *
     * <pre>
     * 사용 시기:
     * - 호출부에서 반드시 예외를 인지하고 처리해야 하는 경우
     * - 외부 시스템 연동 실패 등 복구 가능한 상황
     * - 실무에서는 드물게 사용 (Unchecked 선호 추세)
     * </pre>
     */
    public static class InsufficientBalanceException extends Exception {
        private final long currentBalance;
        private final long requestedAmount;

        public InsufficientBalanceException(long currentBalance, long requestedAmount) {
            super(String.format("잔액 부족: 현재 %d원, 요청 %d원", currentBalance, requestedAmount));
            this.currentBalance = currentBalance;
            this.requestedAmount = requestedAmount;
        }

        public long getCurrentBalance() { return currentBalance; }
        public long getRequestedAmount() { return requestedAmount; }
    }

    /**
     * 커스텀 Unchecked Exception 예시.
     *
     * RuntimeException을 상속하면 Unchecked Exception이 된다.
     * 호출부에서 try-catch 없이 자유롭게 사용할 수 있다.
     *
     * <pre>
     * 사용 시기:
     * - 비즈니스 규칙 위반 (프로그래밍 오류가 아닌 도메인 로직)
     * - @Transactional에서 자동 롤백이 필요한 경우
     * - 실무에서 가장 많이 사용하는 패턴
     * </pre>
     */
    public static class OrderAlreadyPaidException extends RuntimeException {
        private final String orderId;

        public OrderAlreadyPaidException(String orderId) {
            super("이미 결제된 주문입니다: orderId=" + orderId);
            this.orderId = orderId;
        }

        public String getOrderId() { return orderId; }
    }

    // ================================================================
    // [6] 예외 처리 안티패턴
    // ================================================================

    /**
     * 예외 처리 안티패턴 모음 (이렇게 하면 안 됨!).
     *
     * <pre>
     * ── 안티패턴 1: 예외 삼키기 (Swallowing) ──
     * try { ... }
     * catch (Exception e) { }  ← 아무것도 안 함 → 장애 추적 불가!
     *
     * ── 안티패턴 2: 원인(cause) 유실 ──
     * catch (IOException e) {
     *     throw new RuntimeException("실패");     ← e를 빠뜨림
     *     throw new RuntimeException("실패", e);  ← 올바른 방법
     * }
     *
     * ── 안티패턴 3: Exception으로 뭉뚱그리기 ──
     * catch (Exception e) { ... }  ← 모든 예외를 한 곳에서 처리
     * → 구체적인 예외 타입별로 분리 처리해야 함
     *
     * ── 안티패턴 4: 비즈니스 로직에 Checked 사용 ──
     * public void placeOrder() throws Exception { ... }
     * → 호출 체인 전체에 throws 전파 → 코드 오염
     * → RuntimeException 상속 커스텀 예외 사용 권장
     *
     * ── 안티패턴 5: catch 후 같은 예외 다시 던지기 ──
     * catch (SomeException e) {
     *     log.error("에러", e);
     *     throw e;  ← 상위에서 또 로깅 → 중복 로깅
     * }
     * → 로깅은 최종 핸들러(GlobalExceptionHandler)에서만 하거나,
     *   여기서 로깅하고 다른 예외로 변환
     * </pre>
     */
    private void antiPatterns() {
        // 이 메서드는 문서화 목적 — 실행하지 않음
    }
}
