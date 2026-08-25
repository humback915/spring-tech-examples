package kr.co.example.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Builder;
import lombok.Getter;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;
import java.util.List;

/**
 * Bean Validation 예제 — 입력값 검증 패턴 모음.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  Bean Validation (JSR-380) 주요 어노테이션                           │
 * ├──────────────────┬──────────────────────────────────────────────────┤
 * │  @NotNull        │ null 불허 (빈 문자열 "" 허용)                     │
 * │  @NotEmpty       │ null + 빈 문자열 불허 (공백 " " 허용)             │
 * │  @NotBlank       │ null + 빈 문자열 + 공백만 불허 (문자열 전용)       │
 * │  @Size           │ 문자열/컬렉션 크기 제한 (min, max)                │
 * │  @Min / @Max     │ 숫자 최소/최대값                                  │
 * │  @Email          │ 이메일 형식 검증                                  │
 * │  @Pattern        │ 정규식 패턴 검증                                  │
 * │  @Past / @Future │ 과거/미래 날짜                                    │
 * │  @Positive       │ 양수만 허용 (0 불허)                              │
 * │  @PositiveOrZero │ 0 이상                                           │
 * └──────────────────┴──────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  Validation 동작 흐름                                                │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  1. 클라이언트 → JSON 요청                                           │
 * │  2. @RequestBody → DTO 바인딩                                        │
 * │  3. @Valid → Bean Validation 실행                                    │
 * │  4-A. 성공 → 컨트롤러 메서드 실행                                     │
 * │  4-B. 실패 → MethodArgumentNotValidException 발생                    │
 * │  5. GlobalExceptionHandler → 에러 응답 반환                           │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * 의존성: spring-boot-starter-validation
 * (Spring Boot 2.3+ 부터 spring-boot-starter-web에서 분리됨)
 * </pre>
 */
@RestController
@RequestMapping("/api/validation")
public class ValidationExample {

    // ====================================================================
    // [1] 기본 사용법 — @Valid + Request DTO
    // ====================================================================

    /**
     * @Valid — DTO의 Bean Validation 어노테이션을 실행.
     * 검증 실패 시 MethodArgumentNotValidException 자동 발생
     * → GlobalExceptionHandler에서 처리.
     */
    @PostMapping("/signup")
    public ResponseEntity<String> signUp(@Valid @RequestBody SignUpRequest request) {
        // 여기까지 왔으면 검증 통과
        return ResponseEntity.ok("가입 성공: " + request.getName());
    }

    /**
     * BindingResult — 검증 에러를 직접 처리하고 싶을 때.
     *
     * <pre>
     * @Valid 다음에 BindingResult를 선언하면 예외가 발생하지 않고
     * 에러 정보가 BindingResult에 담김 → 직접 처리 가능.
     *
     * 사용 시기: 특정 API에서만 다른 형태의 에러 응답이 필요한 경우
     * 보통은 GlobalExceptionHandler로 통일 처리하는 것이 권장됨.
     * </pre>
     */
    @PostMapping("/signup-manual")
    public ResponseEntity<String> signUpManual(
            @Valid @RequestBody SignUpRequest request,
            BindingResult bindingResult) { // @Valid 바로 뒤에 위치해야 함

        if (bindingResult.hasErrors()) {
            // 직접 에러 처리
            String firstError = bindingResult.getFieldErrors().get(0).getDefaultMessage();
            return ResponseEntity.badRequest().body("검증 실패: " + firstError);
        }

        return ResponseEntity.ok("가입 성공");
    }

    // ====================================================================
    // [2] Request DTO — 검증 어노테이션 적용
    // ====================================================================

    /**
     * 회원가입 요청 DTO — 다양한 검증 어노테이션 예시.
     * concert-msa-project의 SignUpRequest 패턴 참고.
     */
    @Getter
    @Builder
    public static class SignUpRequest {

        /**
         * @NotBlank: null, "", " " 모두 불허.
         * message: 검증 실패 시 클라이언트에 반환할 메시지.
         */
        @NotBlank(message = "이름은 필수입니다")
        @Size(min = 2, max = 50, message = "이름은 2~50자여야 합니다")
        private String name;

        /**
         * @Email: 이메일 형식 검증 (기본: RFC 5322 일부 준수).
         * @Pattern으로 더 엄격한 검증 가능.
         */
        @NotBlank(message = "이메일은 필수입니다")
        @Email(message = "이메일 형식이 올바르지 않습니다")
        private String email;

        /**
         * @Pattern: 정규식 기반 검증.
         * 비밀번호: 최소 8자, 영문+숫자+특수문자 포함.
         */
        @NotBlank(message = "비밀번호는 필수입니다")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[@$!%*#?&])[A-Za-z\\d@$!%*#?&]{8,}$",
                message = "비밀번호는 8자 이상, 영문+숫자+특수문자를 포함해야 합니다"
        )
        private String password;

        /**
         * @Pattern: 휴대폰 번호 형식 (010-1234-5678).
         */
        @NotBlank(message = "휴대폰 번호는 필수입니다")
        @Pattern(
                regexp = "^01[016789]-\\d{3,4}-\\d{4}$",
                message = "휴대폰 번호 형식이 올바르지 않습니다 (예: 010-1234-5678)"
        )
        private String phone;

        /** @Min, @Max: 숫자 범위 검증 */
        @Min(value = 14, message = "14세 이상만 가입 가능합니다")
        @Max(value = 150, message = "나이를 정확히 입력해주세요")
        private Integer age;

        /** @Past: 과거 날짜만 허용 (생년월일 등) */
        @Past(message = "생년월일은 과거 날짜여야 합니다")
        private LocalDate birthDate;

        /**
         * @Valid — 중첩 객체 검증.
         * Address 내부의 검증 어노테이션도 실행.
         * @Valid 없으면 Address 내부 어노테이션 무시됨.
         */
        @Valid
        private AddressRequest address;

        /**
         * @Size — 컬렉션 크기 제한.
         * 최소 1개 이상의 관심사 선택 필수.
         */
        @Size(min = 1, max = 5, message = "관심사는 1~5개 선택해야 합니다")
        private List<String> interests;
    }

    /** 주소 요청 DTO — 중첩 객체 검증 예시 */
    @Getter
    @Builder
    public static class AddressRequest {
        @NotBlank(message = "우편번호는 필수입니다")
        @Pattern(regexp = "^\\d{5}$", message = "우편번호는 5자리 숫자입니다")
        private String zipCode;

        @NotBlank(message = "도로명 주소는 필수입니다")
        private String street;

        private String detail; // 선택 항목 — 검증 어노테이션 없음
    }

    // ====================================================================
    // [3] 커스텀 Validator — 재사용 가능한 검증 로직
    // ====================================================================

    /**
     * 커스텀 Validation 어노테이션 — 사업자등록번호 형식 검증.
     *
     * <pre>
     * 커스텀 Validator 구현 순서:
     * 1. 어노테이션 정의 (@interface) — @Constraint로 Validator 클래스 연결
     * 2. ConstraintValidator 구현 — isValid() 메서드에서 검증 로직 작성
     * 3. DTO 필드에 어노테이션 적용
     *
     * 사용:
     * @BusinessNumber
     * private String businessNumber;
     * </pre>
     */
    @Target({ElementType.FIELD, ElementType.PARAMETER}) // 필드, 파라미터에 사용 가능
    @Retention(RetentionPolicy.RUNTIME) // 런타임에 리플렉션으로 읽기 가능
    @Constraint(validatedBy = BusinessNumberValidator.class) // Validator 클래스 지정
    public @interface BusinessNumber {
        String message() default "사업자등록번호 형식이 올바르지 않습니다 (예: 123-45-67890)";

        Class<?>[] groups() default {}; // 검증 그룹 (필수)

        Class<? extends Payload>[] payload() default {}; // 메타데이터 (필수)
    }

    /**
     * ConstraintValidator 구현 — 실제 검증 로직.
     *
     * <p>ConstraintValidator&lt;어노테이션, 검증 대상 타입&gt;</p>
     */
    public static class BusinessNumberValidator
            implements ConstraintValidator<BusinessNumber, String> {

        /** 사업자등록번호 패턴: 3자리-2자리-5자리 */
        private static final java.util.regex.Pattern PATTERN =
                java.util.regex.Pattern.compile("^\\d{3}-\\d{2}-\\d{5}$");

        /**
         * 초기화 — 어노테이션 속성 읽기 (필요 시).
         * 보통은 빈 구현.
         */
        @Override
        public void initialize(BusinessNumber constraintAnnotation) {
            // 어노테이션 속성으로 초기화 로직이 필요한 경우 여기에 작성
        }

        /**
         * 검증 로직 — true면 검증 통과, false면 실패.
         *
         * @param value   검증 대상 값
         * @param context 검증 컨텍스트 (에러 메시지 커스터마이징에 사용)
         */
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            // null은 @NotBlank 등에서 처리 → 여기서는 통과시킴
            if (value == null) {
                return true;
            }
            return PATTERN.matcher(value).matches();
        }
    }
}
