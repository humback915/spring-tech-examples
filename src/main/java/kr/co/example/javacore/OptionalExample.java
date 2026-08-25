package kr.co.example.javacore;

import java.util.Optional;

/**
 * Optional 예제 — null 안전한 값 처리 (Java 8+).
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  Optional이란?                                                       │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  값이 있을 수도, 없을 수도 있는 컨테이너.                             │
 * │  NullPointerException을 방지하고 null 체크를 명시적으로 표현.          │
 * │                                                                     │
 * │  JPA findById() → Optional&lt;Entity&gt; 반환                         │
 * │  Stream.findFirst() → Optional&lt;T&gt; 반환                          │
 * │  Map.get() → null 반환 → Optional.ofNullable()로 감싸기              │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  Optional 사용 원칙                                                   │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  ✓ 메서드 반환 타입으로 사용 (값이 없을 수 있음을 API로 표현)          │
 * │  ✗ 메서드 파라미터로 사용하지 말 것 (호출 측이 Optional 생성 부담)     │
 * │  ✗ 필드 타입으로 사용하지 말 것 (Serializable 미지원, JPA 매핑 불가)  │
 * │  ✗ 컬렉션을 Optional로 감싸지 말 것 (빈 컬렉션 반환이 더 적절)       │
 * └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class OptionalExample {

    // ────────────────────────────────────────
    // [1] Optional 생성
    // ────────────────────────────────────────

    public void createOptional() {
        // of() — null이 아닌 값 (null이면 NullPointerException)
        Optional<String> opt1 = Optional.of("hello");

        // ofNullable() — null일 수 있는 값 (null이면 Optional.empty())
        String nullableValue = null;
        Optional<String> opt2 = Optional.ofNullable(nullableValue);

        // empty() — 빈 Optional
        Optional<String> opt3 = Optional.empty();
    }

    // ────────────────────────────────────────
    // [2] 값 추출 — 올바른 방법
    // ────────────────────────────────────────

    public void extractValue() {
        Optional<String> opt = Optional.ofNullable("hello");

        // ── orElse() — 값이 없으면 기본값 반환 ──
        // 주의: 값이 있어도 기본값 표현식이 항상 실행됨
        String value1 = opt.orElse("default");

        // ── orElseGet() — 값이 없을 때만 Supplier 실행 (권장) ──
        // orElse()와 달리 값이 있으면 Supplier 실행 안 함 → 성능 이점
        String value2 = opt.orElseGet(() -> expensiveOperation());

        // ── orElseThrow() — 값이 없으면 예외 발생 (가장 많이 사용) ──
        // JPA findById() 결과 처리에 자주 사용
        // User user = userRepository.findById(id)
        //     .orElseThrow(() -> new EntityNotFoundException("User", id));
        String value3 = opt.orElseThrow(() -> new RuntimeException("값 없음"));

        // [Java 10+] orElseThrow() 파라미터 없는 버전 — NoSuchElementException
        String value4 = opt.orElseThrow();
    }

    /**
     * orElse() vs orElseGet() 차이 — 중요!
     *
     * <pre>
     * Optional&lt;String&gt; opt = Optional.of("존재하는 값");
     *
     * // orElse: 값이 있어도 expensiveOperation() 실행됨 (불필요한 연산)
     * opt.orElse(expensiveOperation());
     *
     * // orElseGet: 값이 있으면 Supplier 실행 안 함 → 효율적
     * opt.orElseGet(() -> expensiveOperation());
     *
     * → DB 조회, API 호출 등 비용이 큰 기본값은 반드시 orElseGet() 사용
     * </pre>
     */
    private String expensiveOperation() {
        return "기본값";
    }

    // ────────────────────────────────────────
    // [3] 변환 — map(), flatMap()
    // ────────────────────────────────────────

    /**
     * map() — Optional 내부 값을 변환.
     *
     * <pre>
     * Optional이 비어있으면 변환 함수 실행 안 하고 Optional.empty() 반환.
     *
     * 실무 패턴:
     * String email = userRepository.findById(id)
     *     .map(User::getEmail)          // Optional&lt;User&gt; → Optional&lt;String&gt;
     *     .orElse("unknown@example.com");
     * </pre>
     */
    public void mapExample() {
        Optional<String> name = Optional.of("  홍길동  ");

        // map: Optional<String> → Optional<String> (변환)
        Optional<String> trimmed = name.map(String::trim);
        Optional<Integer> length = name.map(String::trim).map(String::length);

        // 체이닝: findById → map → map → orElse
        // String result = userRepository.findById(id)
        //     .map(User::getAddress)
        //     .map(Address::getCity)
        //     .orElse("미지정");
    }

    /**
     * flatMap() — 중첩 Optional 제거.
     *
     * <pre>
     * map()과의 차이:
     * - map(f):     f의 반환값을 Optional로 감쌈 → Optional&lt;Optional&lt;T&gt;&gt; 가능
     * - flatMap(f): f가 Optional을 반환 → 중첩 없이 Optional&lt;T&gt; 유지
     *
     * 사용 시기: 변환 함수 자체가 Optional을 반환할 때
     * </pre>
     */
    public void flatMapExample() {
        Optional<String> userId = Optional.of("123");

        // findById가 Optional<User>를 반환하므로 flatMap 사용
        // Optional<String> email = userId
        //     .flatMap(id -> userRepository.findById(Long.parseLong(id)))
        //     .map(User::getEmail);
    }

    // ────────────────────────────────────────
    // [4] 조건 — filter(), ifPresent()
    // ────────────────────────────────────────

    public void filterExample() {
        Optional<Integer> age = Optional.of(25);

        // filter: 조건 충족 시 Optional 유지, 불충족 시 Optional.empty()
        Optional<Integer> adult = age.filter(a -> a >= 18);

        // ifPresent: 값이 있을 때만 Consumer 실행 (void)
        age.ifPresent(a -> System.out.println("나이: " + a));

        // [Java 9+] ifPresentOrElse: 값 유무에 따라 다른 동작
        age.ifPresentOrElse(
                a -> System.out.println("나이: " + a),  // 있을 때
                () -> System.out.println("나이 미입력")   // 없을 때
        );
    }

    // ────────────────────────────────────────
    // [5] 안티 패턴 — 하지 말아야 할 것
    // ────────────────────────────────────────

    /**
     * Optional 안티 패턴 모음.
     *
     * <pre>
     * ✗ isPresent() + get() — null 체크와 다를 바 없음
     *   if (opt.isPresent()) { return opt.get(); }
     *   → 대신: return opt.orElse(defaultValue);
     *
     * ✗ Optional을 파라미터로 사용
     *   void method(Optional&lt;String&gt; name) { ... }
     *   → 대신: void method(String name) { ... } + null 체크 또는 @Nullable
     *
     * ✗ Optional을 필드로 사용
     *   private Optional&lt;String&gt; email;
     *   → 대신: private String email; (null 허용)
     *
     * ✗ Optional.of(null) — NullPointerException 발생
     *   → 대신: Optional.ofNullable(value)
     *
     * ✗ 컬렉션을 Optional로 감싸기
     *   Optional&lt;List&lt;User&gt;&gt; users = ...
     *   → 대신: List&lt;User&gt; users = ... (빈 리스트 반환)
     * </pre>
     */
    public void antiPatterns() {
        // 올바른 패턴 예시
        Optional<String> opt = Optional.ofNullable(null);

        // ✗ 나쁜 예
        // if (opt.isPresent()) { return opt.get(); }

        // ✓ 좋은 예
        String value = opt.orElse("기본값");
    }
}
