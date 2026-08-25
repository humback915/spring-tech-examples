package kr.co.example.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 커스텀 예외 계층 — 비즈니스 로직에서 발생하는 예외를 HTTP 상태 코드와 매핑.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  예외 계층 설계 원칙                                              │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  1. RuntimeException 상속 — @Transactional 롤백 대상              │
 * │     (Checked Exception은 기본적으로 롤백되지 않음)                  │
 * │  2. HttpStatus를 포함 — GlobalExceptionHandler에서 자동 매핑       │
 * │  3. 에러 코드(code) 포함 — 프론트엔드에서 에러 유형 식별             │
 * │  4. Enum으로 에러 코드 관리 — 코드 중복 방지, 일관성 유지            │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  Exception vs RuntimeException                                  │
 * ├──────────────────┬──────────────────────────────────────────────┤
 * │  Exception       │ Checked — 컴파일러가 try-catch 강제           │
 * │  (Checked)       │ 복구 가능한 예외에 사용 (파일 I/O, 네트워크)    │
 * │                  │ @Transactional에서 기본 롤백 안 됨             │
 * ├──────────────────┼──────────────────────────────────────────────┤
 * │  RuntimeException│ Unchecked — 컴파일러가 강제하지 않음            │
 * │  (Unchecked)     │ 프로그래밍 에러, 비즈니스 규칙 위반에 사용       │
 * │                  │ @Transactional에서 기본 롤백됨                 │
 * └──────────────────┴──────────────────────────────────────────────┘
 * </pre>
 */
public class CustomException {

    // ────────────────────────────────────────
    // [1] 도메인 예외 — 비즈니스 로직 공통 예외
    // ────────────────────────────────────────

    /**
     * 도메인 예외 (비즈니스 규칙 위반 시 사용).
     * concert-msa-project의 DomainException 패턴 참고.
     *
     * <p>사용 예시:</p>
     * <pre>
     * throw new DomainException(DomainExceptionCode.USER_NOT_FOUND);
     * throw new DomainException(HttpStatus.CONFLICT, "ALREADY_EXISTS", "이미 존재하는 데이터");
     * </pre>
     */
    @Getter
    public static class DomainException extends RuntimeException {

        /** HTTP 응답 코드 (자동 매핑용) */
        private final HttpStatus httpStatus;

        /** 에러 식별 코드 (프론트엔드 연동용) */
        private final String code;

        /** Enum 기반 생성자 — 에러 코드를 Enum으로 관리할 때 사용 */
        public DomainException(DomainExceptionCode exceptionCode) {
            super(exceptionCode.getMessage());
            this.httpStatus = exceptionCode.getHttpStatus();
            this.code = exceptionCode.getCode();
        }

        /** 직접 지정 생성자 — Enum에 없는 일회성 에러에 사용 */
        public DomainException(HttpStatus httpStatus, String code, String message) {
            super(message);
            this.httpStatus = httpStatus;
            this.code = code;
        }
    }

    // ────────────────────────────────────────
    // [2] 에러 코드 Enum — 모든 비즈니스 에러를 한 곳에서 관리
    // ────────────────────────────────────────

    /**
     * 에러 코드 Enum — HttpStatus + 에러 코드 + 메시지를 한 곳에서 관리.
     *
     * <pre>
     * 장점:
     * - 에러 코드 중복 방지 (컴파일 타임 체크)
     * - 에러 목록을 한 파일에서 확인 가능
     * - IDE 자동완성 지원
     * - 프론트엔드 에러 코드 문서화 용이
     * </pre>
     */
    @Getter
    public enum DomainExceptionCode {

        // ── 400 Bad Request ──
        INVALID_INPUT(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "입력값이 올바르지 않습니다"),
        INVALID_STATUS(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "현재 상태에서 수행할 수 없는 작업입니다"),

        // ── 401 Unauthorized ──
        UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다"),
        INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "유효하지 않은 토큰입니다"),
        EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "EXPIRED_TOKEN", "만료된 토큰입니다"),

        // ── 403 Forbidden ──
        FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다"),

        // ── 404 Not Found ──
        USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다"),
        ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다"),
        RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "리소스를 찾을 수 없습니다"),

        // ── 409 Conflict ──
        ALREADY_EXISTS(HttpStatus.CONFLICT, "ALREADY_EXISTS", "이미 존재하는 데이터입니다"),
        CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "동시 수정이 발생했습니다"),

        // ── 429 Too Many Requests ──
        TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "요청이 너무 많습니다"),

        // ── 500 Internal Server Error ──
        INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 내부 오류가 발생했습니다"),
        EXTERNAL_API_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "EXTERNAL_API_ERROR", "외부 API 호출 중 오류가 발생했습니다");

        private final HttpStatus httpStatus;
        private final String code;
        private final String message;

        DomainExceptionCode(HttpStatus httpStatus, String code, String message) {
            this.httpStatus = httpStatus;
            this.code = code;
            this.message = message;
        }
    }

    // ────────────────────────────────────────
    // [3] HTTP 상태별 특화 예외 (queenssmile_back 패턴)
    // ────────────────────────────────────────

    /**
     * 404 Not Found — 리소스를 찾을 수 없을 때.
     * <pre>
     * throw new EntityNotFoundException("User", userId);
     * → "User(id=123)를 찾을 수 없습니다"
     * </pre>
     */
    @Getter
    public static class EntityNotFoundException extends DomainException {
        public EntityNotFoundException(String entityName, Object id) {
            super(HttpStatus.NOT_FOUND, "NOT_FOUND",
                    entityName + "(id=" + id + ")를 찾을 수 없습니다");
        }
    }

    /** 400 Bad Request — 잘못된 요청 파라미터 */
    public static class BadRequestException extends DomainException {
        public BadRequestException(String message) {
            super(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
        }
    }

    /** 401 Unauthorized — 인증 실패 */
    public static class UnAuthorizedException extends DomainException {
        public UnAuthorizedException(String message) {
            super(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
        }
    }

    /** 403 Forbidden — 권한 없음 */
    public static class ForbiddenException extends DomainException {
        public ForbiddenException(String message) {
            super(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
        }
    }

    /** 409 Conflict — 리소스 충돌 (중복 가입, 동시 수정 등) */
    public static class ConflictException extends DomainException {
        public ConflictException(String message) {
            super(HttpStatus.CONFLICT, "CONFLICT", message);
        }
    }

    /** 429 Too Many Requests — 요청 횟수 초과 */
    public static class TooManyRequestsException extends DomainException {
        public TooManyRequestsException(String message) {
            super(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", message);
        }
    }
}
