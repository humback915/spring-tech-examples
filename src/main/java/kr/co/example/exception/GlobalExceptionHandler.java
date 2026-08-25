package kr.co.example.exception;

import kr.co.example.exception.CustomException.DomainException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/**
 * 글로벌 예외 처리기 — 모든 컨트롤러에서 발생하는 예외를 한 곳에서 처리.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  @ControllerAdvice vs @RestControllerAdvice                     │
 * ├───────────────────┬─────────────────────────────────────────────┤
 * │  @ControllerAdvice│ 뷰(HTML) 반환 가능, @ResponseBody 필요      │
 * │  @RestController  │ JSON 자동 반환 (@ResponseBody 포함)          │
 * │  Advice           │ REST API 서버에서 주로 사용                   │
 * └───────────────────┴─────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  예외 처리 우선순위                                               │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  1. 가장 구체적인 예외 타입의 @ExceptionHandler가 먼저 매칭       │
 * │  2. 부모 클래스의 핸들러보다 자식 클래스 핸들러가 우선              │
 * │  3. 같은 레벨이면 먼저 선언된 핸들러가 우선                       │
 * │  4. 매칭되는 핸들러 없으면 Exception 핸들러(최상위)로 폴백         │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * 참고: concert-msa-project의 GlobalExceptionHandler 패턴 적용
 * </pre>
 */
@Slf4j // Lombok — private static final Logger log = LoggerFactory.getLogger(...) 자동 생성
@RestControllerAdvice // @ControllerAdvice + @ResponseBody — 모든 컨트롤러에 적용
public class GlobalExceptionHandler {

    // ────────────────────────────────────────
    // [1] 비즈니스 예외 처리 — DomainException
    // ────────────────────────────────────────

    /**
     * 비즈니스 로직에서 의도적으로 던진 예외 처리.
     * DomainException에 포함된 HttpStatus를 응답 코드로 사용.
     *
     * <pre>
     * 예: throw new DomainException(DomainExceptionCode.USER_NOT_FOUND)
     * → 404 응답 + { "code": "USER_NOT_FOUND", "message": "사용자를 찾을 수 없습니다" }
     * </pre>
     */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(DomainException e) {
        // WARN 레벨 — 비즈니스 예외는 예상된 상황이므로 ERROR가 아닌 WARN
        log.warn("DomainException: code={}, message={}", e.getCode(), e.getMessage());

        ErrorResponse response = ErrorResponse.of(
                e.getHttpStatus().value(),
                e.getCode(),
                e.getMessage()
        );
        return ResponseEntity.status(e.getHttpStatus()).body(response);
    }

    // ────────────────────────────────────────
    // [2] Validation 예외 처리 — @Valid 검증 실패
    // ────────────────────────────────────────

    /**
     * @Valid @RequestBody 검증 실패 시 발생.
     * 모든 필드 에러를 수집하여 한 번에 응답.
     *
     * <pre>
     * MethodArgumentNotValidException vs BindException:
     * - MethodArgumentNotValidException: @RequestBody (JSON) 바인딩 실패
     * - BindException: @ModelAttribute (폼 데이터) 바인딩 실패
     * - MethodArgumentNotValidException은 BindException을 상속
     * </pre>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException e) {

        // BindingResult에서 모든 필드 에러를 추출하여 리스트로 변환
        List<ErrorResponse.FieldError> fieldErrors = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new ErrorResponse.FieldError(
                        error.getField(),           // 에러 발생 필드명
                        error.getRejectedValue(),   // 거부된 입력값
                        error.getDefaultMessage()   // 에러 메시지 (@NotBlank(message="..."))
                ))
                .toList(); // Java 16+ — .collect(Collectors.toList()) 대체

        log.warn("Validation failed: {}", fieldErrors);

        return ResponseEntity.badRequest().body(ErrorResponse.ofValidation(fieldErrors));
    }

    /**
     * @ModelAttribute(폼 데이터) 바인딩 실패.
     * MethodArgumentNotValidException과 동일 패턴으로 처리.
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse> handleBindException(BindException e) {
        List<ErrorResponse.FieldError> fieldErrors = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new ErrorResponse.FieldError(
                        error.getField(),
                        error.getRejectedValue(),
                        error.getDefaultMessage()
                ))
                .toList();

        return ResponseEntity.badRequest().body(ErrorResponse.ofValidation(fieldErrors));
    }

    // ────────────────────────────────────────
    // [3] 파라미터 관련 예외
    // ────────────────────────────────────────

    /**
     * 필수 쿼리 파라미터 누락.
     * 예: @RequestParam String name → name 파라미터 없이 요청 시
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(
            MissingServletRequestParameterException e) {

        ErrorResponse response = ErrorResponse.of(
                400, "MISSING_PARAMETER",
                "필수 파라미터가 누락되었습니다: " + e.getParameterName()
        );
        return ResponseEntity.badRequest().body(response);
    }

    /**
     * 파라미터 타입 불일치.
     * 예: @PathVariable Long id → /users/abc 요청 시 (String → Long 변환 실패)
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException e) {

        ErrorResponse response = ErrorResponse.of(
                400, "TYPE_MISMATCH",
                "파라미터 타입이 올바르지 않습니다: " + e.getName()
        );
        return ResponseEntity.badRequest().body(response);
    }

    // ────────────────────────────────────────
    // [4] Spring Security 예외
    // ────────────────────────────────────────

    /**
     * 인증 실패 — 로그인하지 않은 사용자가 보호된 리소스 접근 시.
     * SecurityFilterChain에서 처리하지 못한 인증 예외가 여기로 올 수 있음.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(
            AuthenticationException e) {

        ErrorResponse response = ErrorResponse.of(
                401, "AUTHENTICATION_FAILED", "인증에 실패했습니다"
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    /**
     * 인가 실패 — 권한 없는 리소스 접근 시.
     * @PreAuthorize, @Secured 등의 메서드 보안 실패 시 발생.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(
            AccessDeniedException e) {

        ErrorResponse response = ErrorResponse.of(
                403, "ACCESS_DENIED", "접근 권한이 없습니다"
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    // ────────────────────────────────────────
    // [5] 최종 폴백 — 예상하지 못한 예외
    // ────────────────────────────────────────

    /**
     * 위의 모든 핸들러에 매칭되지 않는 예외의 최종 처리.
     * NullPointerException, IllegalStateException 등 예상치 못한 서버 에러.
     *
     * <p>주의: 내부 에러 메시지를 그대로 노출하면 보안 위험 →
     * 클라이언트에는 일반 메시지만 반환, 상세 내용은 서버 로그에만 기록.</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        // ERROR 레벨 — 예상치 못한 에러는 반드시 스택 트레이스와 함께 기록
        log.error("Unhandled exception occurred", e);

        ErrorResponse response = ErrorResponse.of(
                500, "INTERNAL_ERROR", "서버 내부 오류가 발생했습니다"
        );
        return ResponseEntity.internalServerError().body(response);
    }
}
