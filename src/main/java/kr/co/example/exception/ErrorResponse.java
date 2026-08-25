package kr.co.example.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 표준 에러 응답 DTO — API 에러 발생 시 클라이언트에 반환하는 통일된 형식.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────┐
 * │  왜 표준 에러 응답이 필요한가?                              │
 * ├─────────────────────────────────────────────────────────┤
 * │  1. 클라이언트가 에러를 일관되게 파싱 가능                   │
 * │  2. 프론트엔드에서 에러 메시지를 UI에 표시하기 쉬움            │
 * │  3. 디버깅 시 어디서 에러가 발생했는지 추적 가능              │
 * │  4. 여러 필드의 유효성 검증 실패를 한 번에 전달               │
 * └─────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>사용 예시:</p>
 * <pre>
 * // 단일 에러
 * {
 *   "status": 404,
 *   "code": "USER_NOT_FOUND",
 *   "message": "사용자를 찾을 수 없습니다",
 *   "timestamp": "2024-01-15T10:30:00"
 * }
 *
 * // Validation 에러 (필드 에러 포함)
 * {
 *   "status": 400,
 *   "code": "VALIDATION_ERROR",
 *   "message": "입력값이 올바르지 않습니다",
 *   "timestamp": "2024-01-15T10:30:00",
 *   "fieldErrors": [
 *     { "field": "email", "rejectedValue": "invalid", "reason": "이메일 형식이 올바르지 않습니다" },
 *     { "field": "name", "rejectedValue": "", "reason": "이름은 필수입니다" }
 *   ]
 * }
 * </pre>
 */
@Getter
@Builder
// null인 필드는 JSON에서 제외 — fieldErrors가 없으면 응답에 포함되지 않음
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    /** HTTP 상태 코드 (400, 404, 500 등) */
    private final int status;

    /** 에러 코드 — 프론트엔드에서 에러 유형 식별에 사용 (예: "USER_NOT_FOUND") */
    private final String code;

    /** 사용자에게 보여줄 에러 메시지 */
    private final String message;

    /** 에러 발생 시각 — 로그 추적에 활용 */
    @Builder.Default
    private final LocalDateTime timestamp = LocalDateTime.now();

    /** Validation 에러 시 필드별 상세 에러 목록 (null이면 JSON에서 제외) */
    private final List<FieldError> fieldErrors;

    // ────────────────────────────────────────
    // 필드 에러 상세 정보 (Validation 실패 시 사용)
    // ────────────────────────────────────────

    /**
     * 개별 필드의 유효성 검증 실패 정보.
     *
     * @param field         실패한 필드명 (예: "email", "password")
     * @param rejectedValue 거부된 입력값 (예: "invalid-email")
     * @param reason        거부 사유 (예: "이메일 형식이 올바르지 않습니다")
     */
    public record FieldError(
            String field,
            Object rejectedValue,
            String reason
    ) {
    }

    // ────────────────────────────────────────
    // 정적 팩토리 메서드 (Static Factory Method)
    // ────────────────────────────────────────

    /**
     * 단순 에러 응답 생성.
     * 예: ErrorResponse.of(404, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다")
     */
    public static ErrorResponse of(int status, String code, String message) {
        return ErrorResponse.builder()
                .status(status)
                .code(code)
                .message(message)
                .build();
    }

    /**
     * Validation 에러 응답 생성 (필드 에러 포함).
     */
    public static ErrorResponse ofValidation(List<FieldError> fieldErrors) {
        return ErrorResponse.builder()
                .status(400)
                .code("VALIDATION_ERROR")
                .message("입력값이 올바르지 않습니다")
                .fieldErrors(fieldErrors)
                .build();
    }
}
