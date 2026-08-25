package kr.co.example.javacore;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Stream API 예제 — 컬렉션 데이터 처리 (Java 8+).
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  Stream이란?                                                         │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  컬렉션(List, Set 등)의 요소를 함수형 스타일로 처리하는 API.           │
 * │  for 루프를 선언적(Declarative)으로 대체 — "무엇을" 할지만 기술.       │
 * │                                                                     │
 * │  stream()          → 중간 연산 → 중간 연산 → ... → 최종 연산          │
 * │  (소스)             (filter, map 등)          (collect, forEach 등)   │
 * │                                                                     │
 * │  특성:                                                               │
 * │  - 원본 데이터를 변경하지 않음 (불변)                                  │
 * │  - Lazy 평가 — 최종 연산이 호출될 때까지 중간 연산 실행 안 함           │
 * │  - 1회용 — 한 번 소비하면 재사용 불가                                  │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  중간 연산 (Intermediate) vs 최종 연산 (Terminal)                     │
 * ├──────────────────┬──────────────────────────────────────────────────┤
 * │  중간 연산       │ filter, map, flatMap, sorted, distinct,          │
 * │  (Stream 반환)   │ peek, limit, skip                                │
 * │  → Lazy 실행     │                                                  │
 * ├──────────────────┼──────────────────────────────────────────────────┤
 * │  최종 연산       │ collect, forEach, count, reduce, toList,         │
 * │  (결과 반환)     │ findFirst, findAny, anyMatch, allMatch           │
 * │  → 실제 실행     │                                                  │
 * └──────────────────┴──────────────────────────────────────────────────┘
 * </pre>
 */
public class StreamApiExample {

    record User(Long id, String name, int age, String department) {
    }

    private final List<User> users = List.of(
            new User(1L, "김철수", 25, "개발"),
            new User(2L, "이영희", 30, "기획"),
            new User(3L, "박지수", 28, "개발"),
            new User(4L, "최민수", 35, "디자인"),
            new User(5L, "정수진", 22, "개발")
    );

    // ────────────────────────────────────────
    // [1] filter — 조건에 맞는 요소만 선택
    // ────────────────────────────────────────

    /** 개발팀 사용자만 필터링 */
    public List<User> filterExample() {
        return users.stream()
                .filter(user -> "개발".equals(user.department())) // Predicate
                .toList(); // Java 16+ (이전: .collect(Collectors.toList()))
    }

    /** 여러 조건 조합 */
    public List<User> multiFilterExample() {
        return users.stream()
                .filter(user -> "개발".equals(user.department()))
                .filter(user -> user.age() >= 25) // AND 조건
                .toList();
    }

    // ────────────────────────────────────────
    // [2] map — 요소 변환 (타입 변경)
    // ────────────────────────────────────────

    /** User → 이름만 추출 */
    public List<String> mapExample() {
        return users.stream()
                .map(User::name) // User → String (메서드 레퍼런스)
                .toList();
    }

    /** User → DTO 변환 (실무에서 가장 많이 사용) */
    public List<Map<String, Object>> mapToDtoExample() {
        return users.stream()
                .map(user -> Map.<String, Object>of(
                        "id", user.id(),
                        "name", user.name(),
                        "age", user.age()
                ))
                .toList();
    }

    // ────────────────────────────────────────
    // [3] sorted — 정렬
    // ────────────────────────────────────────

    /** 나이순 정렬 */
    public List<User> sortExample() {
        return users.stream()
                .sorted(Comparator.comparingInt(User::age))          // 오름차순
                // .sorted(Comparator.comparingInt(User::age).reversed()) // 내림차순
                .toList();
    }

    /** 다중 정렬: 부서 → 나이 */
    public List<User> multiSortExample() {
        return users.stream()
                .sorted(Comparator.comparing(User::department)
                        .thenComparingInt(User::age))
                .toList();
    }

    // ────────────────────────────────────────
    // [4] collect — 결과 수집
    // ────────────────────────────────────────

    /** groupingBy — 부서별 그룹핑 (가장 실용적) */
    public Map<String, List<User>> groupByDepartment() {
        return users.stream()
                .collect(Collectors.groupingBy(User::department));
        // 결과: { "개발": [김철수, 박지수, 정수진], "기획": [이영희], ... }
    }

    /** groupingBy + counting — 부서별 인원 수 */
    public Map<String, Long> countByDepartment() {
        return users.stream()
                .collect(Collectors.groupingBy(User::department, Collectors.counting()));
    }

    /** toMap — List → Map 변환 (ID를 Key로) */
    public Map<Long, User> toMapExample() {
        return users.stream()
                .collect(Collectors.toMap(
                        User::id,       // Key
                        user -> user    // Value (Function.identity()와 동일)
                ));
    }

    /** partitioningBy — true/false 두 그룹으로 분류 */
    public Map<Boolean, List<User>> partitionExample() {
        return users.stream()
                .collect(Collectors.partitioningBy(user -> user.age() >= 30));
        // { true: [이영희, 최민수], false: [김철수, 박지수, 정수진] }
    }

    /** joining — 문자열 결합 */
    public String joiningExample() {
        return users.stream()
                .map(User::name)
                .collect(Collectors.joining(", ", "[", "]"));
        // "[김철수, 이영희, 박지수, 최민수, 정수진]"
    }

    // ────────────────────────────────────────
    // [5] reduce — 누적 연산
    // ────────────────────────────────────────

    /** 나이 합계 */
    public int reduceExample() {
        return users.stream()
                .map(User::age)
                .reduce(0, Integer::sum); // 초기값 0, 누적 함수
    }

    /** 최댓값/최솟값 */
    public Optional<User> maxExample() {
        return users.stream()
                .max(Comparator.comparingInt(User::age));
    }

    // ────────────────────────────────────────
    // [6] 검색/매칭 — findFirst, anyMatch
    // ────────────────────────────────────────

    /** findFirst — 첫 번째 일치 요소 */
    public Optional<User> findFirstExample() {
        return users.stream()
                .filter(user -> "개발".equals(user.department()))
                .findFirst(); // Optional 반환
    }

    /** anyMatch — 조건에 맞는 요소가 하나라도 있는지 */
    public boolean anyMatchExample() {
        return users.stream()
                .anyMatch(user -> user.age() > 30); // true/false
    }

    /** allMatch — 모든 요소가 조건을 만족하는지 */
    public boolean allMatchExample() {
        return users.stream()
                .allMatch(user -> user.age() >= 18); // 모두 성인인지
    }

    // ────────────────────────────────────────
    // [7] flatMap — 중첩 컬렉션 평탄화
    // ────────────────────────────────────────

    /**
     * flatMap — 1:N 관계를 평탄화.
     *
     * <pre>
     * map:     [1,2,3] → [[a,b], [c,d], [e,f]]  (List&lt;List&lt;String&gt;&gt;)
     * flatMap: [1,2,3] → [a, b, c, d, e, f]      (List&lt;String&gt;)
     * </pre>
     */
    public List<String> flatMapExample() {
        List<List<String>> nestedList = List.of(
                List.of("a", "b"),
                List.of("c", "d"),
                List.of("e", "f")
        );

        return nestedList.stream()
                .flatMap(List::stream) // List<List<String>> → Stream<String>
                .toList();
        // [a, b, c, d, e, f]
    }

    // ────────────────────────────────────────
    // [8] distinct, limit, skip
    // ────────────────────────────────────────

    public void otherOperations() {
        // distinct — 중복 제거 (equals/hashCode 기반)
        List<String> unique = List.of("a", "b", "a", "c").stream()
                .distinct()
                .toList(); // [a, b, c]

        // limit — 처음 N개만
        List<User> top3 = users.stream()
                .sorted(Comparator.comparingInt(User::age).reversed())
                .limit(3)
                .toList();

        // skip — 처음 N개 건너뛰기 (페이징에 활용)
        List<User> page2 = users.stream()
                .skip(2)  // 2개 건너뛰기
                .limit(2) // 2개 가져오기
                .toList();
    }
}
