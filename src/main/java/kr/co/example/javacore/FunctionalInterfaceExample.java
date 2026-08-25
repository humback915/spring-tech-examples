package kr.co.example.javacore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.*;
import java.util.stream.Collectors;

/**
 * 함수형 인터페이스 예제 — Consumer, Function, Predicate, Supplier.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  함수형 인터페이스 (Functional Interface) — Java 8+                   │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  추상 메서드가 정확히 1개인 인터페이스 → 람다식으로 표현 가능           │
 * │  @FunctionalInterface 어노테이션으로 명시 (선택적)                     │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  핵심 함수형 인터페이스 4가지                                          │
 * ├──────────────┬──────────────────────┬────────────────────────────────┤
 * │  인터페이스  │  메서드              │  설명                           │
 * ├──────────────┼──────────────────────┼────────────────────────────────┤
 * │  Function    │  R apply(T)          │  입력 → 변환 → 출력             │
 * │  &lt;T, R&gt;│                      │  stream.map()에 사용            │
 * ├──────────────┼──────────────────────┼────────────────────────────────┤
 * │  Consumer    │  void accept(T)      │  입력만 → 반환 없음 (소비)      │
 * │  &lt;T&gt;   │                      │  stream.forEach()에 사용        │
 * ├──────────────┼──────────────────────┼────────────────────────────────┤
 * │  Predicate   │  boolean test(T)     │  입력 → true/false 판정        │
 * │  &lt;T&gt;   │                      │  stream.filter()에 사용         │
 * ├──────────────┼──────────────────────┼────────────────────────────────┤
 * │  Supplier    │  T get()             │  입력 없이 → 값 생성            │
 * │  &lt;T&gt;   │                      │  지연 생성, 팩토리에 사용        │
 * └──────────────┴──────────────────────┴────────────────────────────────┘
 *
 * 확장형:
 * - BiFunction&lt;T, U, R&gt;: 입력 2개 → 출력 1개
 * - BiConsumer&lt;T, U&gt;: 입력 2개 → 반환 없음
 * - BiPredicate&lt;T, U&gt;: 입력 2개 → boolean
 * - UnaryOperator&lt;T&gt;: Function&lt;T, T&gt;의 특수화 (입출력 같은 타입)
 * - BinaryOperator&lt;T&gt;: BiFunction&lt;T, T, T&gt;의 특수화
 * </pre>
 */
public class FunctionalInterfaceExample {

    record User(Long id, String name, int age) {
    }

    // ────────────────────────────────────────
    // [1] Function<T, R> — 변환 (T → R)
    // ────────────────────────────────────────

    /**
     * Function — 값을 변환하는 함수.
     * Stream.map()의 내부 타입이 Function.
     */
    public void functionExample() {
        // 기본: 문자열 → 길이
        Function<String, Integer> strToLength = String::length;
        int length = strToLength.apply("hello"); // 5

        // 체이닝: andThen (A → B → C), compose (C → B → A)
        Function<String, String> toUpper = String::toUpperCase;
        Function<String, String> addPrefix = s -> "[PREFIX] " + s;

        // andThen: toUpper 먼저 → addPrefix
        String result = toUpper.andThen(addPrefix).apply("hello");
        // "[PREFIX] HELLO"

        // compose: addPrefix 먼저 → toUpper
        String result2 = toUpper.compose(addPrefix).apply("hello");
        // "[PREFIX] HELLO"

        // BiFunction: 두 입력 → 하나의 출력
        BiFunction<String, Integer, String> repeat = (s, n) -> s.repeat(n);
        String repeated = repeat.apply("ab", 3); // "ababab"

        // 실무 활용: Entity → DTO 변환 함수
        Function<User, Map<String, Object>> toDto = user -> Map.of(
                "id", user.id(),
                "name", user.name()
        );
    }

    // ────────────────────────────────────────
    // [2] Consumer<T> — 소비 (T → void)
    // ────────────────────────────────────────

    /**
     * Consumer — 값을 받아서 처리 (반환값 없음).
     * Stream.forEach()의 내부 타입이 Consumer.
     */
    public void consumerExample() {
        // 기본: 출력
        Consumer<String> print = System.out::println;
        print.accept("Hello"); // 콘솔에 출력

        // 체이닝: andThen (순차 실행)
        Consumer<String> log = s -> System.out.println("[LOG] " + s);
        Consumer<String> printAndLog = print.andThen(log);
        printAndLog.accept("message"); // 출력 후 로깅

        // 실무 활용: 콜백 패턴
        processUser(new User(1L, "홍길동", 25),
                user -> System.out.println("처리 완료: " + user.name()));
    }

    private void processUser(User user, Consumer<User> callback) {
        // 비즈니스 로직 처리 후 콜백 실행
        callback.accept(user);
    }

    // ────────────────────────────────────────
    // [3] Predicate<T> — 판정 (T → boolean)
    // ────────────────────────────────────────

    /**
     * Predicate — 조건 판정.
     * Stream.filter()의 내부 타입이 Predicate.
     */
    public void predicateExample() {
        List<User> users = List.of(
                new User(1L, "김철수", 25),
                new User(2L, "이영희", 30),
                new User(3L, "박지수", 17)
        );

        // 기본: 성인 여부 판정
        Predicate<User> isAdult = user -> user.age() >= 18;

        // 조합: and (&&), or (||), negate (!)
        Predicate<User> isYoungAdult = isAdult.and(user -> user.age() < 30);
        Predicate<User> isMinor = isAdult.negate(); // 18세 미만

        // Stream.filter()에 직접 사용
        List<User> adults = users.stream()
                .filter(isAdult)
                .toList();

        List<User> youngAdults = users.stream()
                .filter(isYoungAdult)
                .toList();

        // 실무 활용: 동적 필터 조합
        Predicate<User> combined = buildFilter("개발", 25);
    }

    /** 동적 필터 조합 — QueryDSL의 BooleanExpression과 유사한 패턴 */
    private Predicate<User> buildFilter(String department, Integer minAge) {
        Predicate<User> filter = user -> true; // 기본: 모든 요소 통과
        if (department != null) {
            filter = filter.and(user -> user.name().contains(department));
        }
        if (minAge != null) {
            filter = filter.and(user -> user.age() >= minAge);
        }
        return filter;
    }

    // ────────────────────────────────────────
    // [4] Supplier<T> — 생성 (void → T)
    // ────────────────────────────────────────

    /**
     * Supplier — 값을 생성하는 함수 (입력 없음).
     * 지연 생성(Lazy Creation), 팩토리 패턴에 사용.
     */
    public void supplierExample() {
        // 기본: 객체 생성
        Supplier<List<String>> listFactory = ArrayList::new;
        List<String> newList = listFactory.get();

        // 지연 평가: 필요할 때만 비용이 큰 연산 실행
        // Optional.orElseGet()이 Supplier를 받음
        Supplier<User> defaultUser = () -> new User(0L, "게스트", 0);

        // 실무: 예외 생성 팩토리
        // .orElseThrow(() -> new EntityNotFoundException("User", id))
        // 위에서 () -> ... 가 Supplier<Exception>
    }

    // ────────────────────────────────────────
    // [5] 메서드 레퍼런스 (Method Reference)
    // ────────────────────────────────────────

    /**
     * 메서드 레퍼런스 — 람다식의 축약형.
     *
     * <pre>
     * ┌──────────────────────────────────┬──────────────────────────┐
     * │  유형                            │  예시                     │
     * ├──────────────────────────────────┼──────────────────────────┤
     * │  정적 메서드 참조                │  Integer::parseInt       │
     * │  인스턴스 메서드 참조 (객체)     │  System.out::println     │
     * │  인스턴스 메서드 참조 (타입)     │  String::toUpperCase     │
     * │  생성자 참조                     │  ArrayList::new          │
     * └──────────────────────────────────┴──────────────────────────┘
     * </pre>
     */
    public void methodReferenceExample() {
        List<String> names = List.of("김철수", "이영희", "박지수");

        // 람다식 vs 메서드 레퍼런스
        names.forEach(name -> System.out.println(name));  // 람다
        names.forEach(System.out::println);                // 메서드 레퍼런스 (동일)

        // 정적 메서드 참조
        List<Integer> numbers = List.of("1", "2", "3").stream()
                .map(Integer::parseInt) // s -> Integer.parseInt(s)
                .toList();

        // 생성자 참조
        // List<User> users = dtos.stream()
        //     .map(UserDto::toEntity) // dto -> dto.toEntity()
        //     .toList();
    }
}
