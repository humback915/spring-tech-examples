package kr.co.example.javacore;

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 메모리 릭(Memory Leak) 예제 — 발생 패턴과 방지 방법.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  메모리 릭이란?                                                       │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  더 이상 사용하지 않는 객체가 GC에 의해 회수되지 못하고                    │
 * │  힙 메모리에 계속 남아있는 현상.                                        │
 * │                                                                     │
 * │  Java는 GC가 자동으로 메모리를 관리하지만,                                │
 * │  참조(Reference)가 남아있으면 GC가 회수할 수 없다.                       │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  GC 회수 조건                                                        │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │                                                                     │
 * │  정상 (GC 회수 가능):                                                 │
 * │  GC Roots ──→ Object A ──→ Object B                                 │
 * │                    │                                                │
 * │               A = null (참조 제거)                                    │
 * │                    │                                                │
 * │               Object A, B → Unreachable → GC 회수 ✓                  │
 * │                                                                     │
 * │  메모리 릭 (GC 회수 불가):                                              │
 * │  GC Roots ──→ Collection ──→ Object A (더 이상 안 쓰임)                │
 * │                    │                                                │
 * │               참조가 남아있음 (remove 안 함)                            │
 * │                    │                                                │
 * │               Object A → 여전히 Reachable → GC 회수 불가 ✗             │
 * │                                                                     │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  JVM 힙 메모리 구조                                                   │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │                                                                     │
 * │  ┌─────────────────────────────────────────────────────┐            │
 * │  │                      Heap                           │            │
 * │  │  ┌──────────────┐  ┌─────────────────────────────┐  │            │
 * │  │  │  Young Gen    │  │         Old Gen              │  │            │
 * │  │  │  ┌────┬─────┐│  │                              │  │            │
 * │  │  │  │Eden│S0/S1││  │  오래 생존한 객체             │  │            │
 * │  │  │  └────┴─────┘│  │  → 여기서 릭이 발생하면       │  │            │
 * │  │  │  새 객체 생성  │  │    Full GC로도 회수 불가      │  │            │
 * │  │  └──────────────┘  └─────────────────────────────┘  │            │
 * │  └─────────────────────────────────────────────────────┘            │
 * │                                                                     │
 * │  메모리 릭 → Old Gen 지속 증가 → Full GC 빈도 증가                      │
 * │  → 최종적으로 OutOfMemoryError 발생                                    │
 * │                                                                     │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  진단 도구                                                           │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  jmap -histo &lt;pid&gt;          클래스별 인스턴스 수/크기 확인       │
 * │  jmap -dump:format=b &lt;pid&gt; 힙 덤프 생성                        │
 * │  Eclipse MAT / VisualVM      힙 덤프 분석, Leak Suspects 리포트     │
 * │  -XX:+HeapDumpOnOutOfMemoryError  OOM 발생 시 자동 덤프             │
 * └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
@Slf4j
public class MemoryLeakExample {

    // ════════════════════════════════════════════════════════════════
    // [1] static 컬렉션에 계속 추가 (제거 없음)
    // ════════════════════════════════════════════════════════════════

    /**
     * ── 문제 ──
     * static 필드는 클래스가 언로드될 때까지(사실상 앱 종료까지) GC 대상이 아니다.
     * 여기에 데이터를 추가만 하고 제거하지 않으면 메모리가 무한히 증가한다.
     *
     * <pre>
     * GC Roots ──→ Class(MemoryLeakExample)
     *                   │
     *                   └──→ static leakyCache (ArrayList)
     *                            │
     *                            ├──→ Object 1 (안 쓰임)  ← GC 불가
     *                            ├──→ Object 2 (안 쓰임)  ← GC 불가
     *                            ├──→ Object 3 (안 쓰임)  ← GC 불가
     *                            └──→ ... 무한 증가
     * </pre>
     */
    private static final List<byte[]> leakyCache = new ArrayList<>();

    /** 릭 발생: 추가만 하고 제거하지 않음 */
    public void addToLeakyCache(byte[] data) {
        leakyCache.add(data);  // 요청마다 쌓임 → OOM
    }

    /**
     * ── 해결 1: 크기 제한 ──
     * 최대 크기를 설정하고 초과 시 오래된 항목을 제거한다.
     */
    private static final int MAX_CACHE_SIZE = 1000;
    private static final List<byte[]> boundedCache = new ArrayList<>();

    public void addToBoundedCache(byte[] data) {
        if (boundedCache.size() >= MAX_CACHE_SIZE) {
            boundedCache.removeFirst(); // 가장 오래된 항목 제거
        }
        boundedCache.add(data);
    }

    /**
     * ── 해결 2: WeakReference 기반 캐시 ──
     * WeakHashMap은 키에 대한 강한 참조가 없으면 GC가 자동으로 엔트리를 제거한다.
     *
     * <pre>
     * 강한 참조(Strong):  Object obj = new Object();         → GC 불가
     * 약한 참조(Weak):    WeakReference&lt;Object&gt; w = ...  → 강한 참조 없으면 GC 가능
     * </pre>
     */
    private final Map<String, byte[]> weakCache = new WeakHashMap<>();

    /**
     * ── 해결 3: Caffeine / @Cacheable (Spring 권장) ──
     * maximumSize + expireAfterWrite로 자동 퇴거.
     * 프로젝트의 LocalCacheConfig.java 참고.
     */

    // ════════════════════════════════════════════════════════════════
    // [2] 리소스 미반환 (Connection, Stream 등)
    // ════════════════════════════════════════════════════════════════

    /**
     * ── 문제 ──
     * I/O 리소스(Stream, Connection, Statement 등)는 OS 자원(파일 디스크립터,
     * 소켓)과 연결되어 있다. close()를 호출하지 않으면:
     * - 파일 디스크립터 고갈 → "Too many open files"
     * - DB 커넥션 풀 고갈 → 새 요청 처리 불가
     * - 메모리 누수 (내부 버퍼가 해제되지 않음)
     *
     * <pre>
     * ┌──────────────────────────────────────────────────────────────┐
     * │  리소스 누수 흐름                                              │
     * │                                                              │
     * │  요청 1 → open() → 예외 발생 → close() 호출 안 됨 → 릭       │
     * │  요청 2 → open() → 예외 발생 → close() 호출 안 됨 → 릭       │
     * │  ...                                                        │
     * │  요청 N → open() 실패 → "Too many open files" 또는           │
     * │                          "Cannot get a connection" 에러      │
     * └──────────────────────────────────────────────────────────────┘
     * </pre>
     */

    /** 릭 발생: 예외 시 close() 누락 */
    public String readFileLeaky(String path) throws IOException {
        FileInputStream fis = new FileInputStream(path);
        BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
        String line = reader.readLine();
        // 여기서 예외 발생하면 아래 close()가 실행되지 않음
        reader.close();
        return line;
    }

    /** 해결: try-with-resources (Java 7+) — 예외 발생해도 자동 close() 보장 */
    public String readFileSafe(String path) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(path)))) {
            return reader.readLine();
        } // 자동 close() — 정상 종료 + 예외 발생 모두 보장
    }

    /**
     * ── JDBC 리소스 미반환 ──
     *
     * Connection, Statement, ResultSet 모두 close() 필요.
     * Spring에서는 @Transactional이나 JdbcTemplate이 자동으로 관리하지만,
     * 직접 JDBC를 사용할 때는 반드시 try-with-resources를 사용해야 한다.
     */
    public void jdbcResourceLeak(Connection conn) throws Exception {
        // 릭: Statement, ResultSet close() 누락
        // Statement stmt = conn.createStatement();
        // ResultSet rs = stmt.executeQuery("SELECT 1");

        // 해결: try-with-resources
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            while (rs.next()) {
                log.info("result: {}", rs.getString(1));
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // [3] Map에 넣고 제거하지 않음 (세션, 캐시 등)
    // ════════════════════════════════════════════════════════════════

    /**
     * ── 문제 ──
     * 사용자 세션 데이터를 Map에 저장하고, 로그아웃/세션 만료 시 제거하지 않으면
     * 사용자가 늘어날수록 메모리가 계속 증가한다.
     *
     * <pre>
     * ┌──────────────────────────────────────────────────────┐
     * │  시간 경과에 따른 Map 크기                              │
     * │                                                      │
     * │  메모리  │                              ╱ ← OOM      │
     * │  사용량  │                           ╱               │
     * │         │                        ╱                  │
     * │         │                     ╱                     │
     * │         │                  ╱                        │
     * │         │               ╱                           │
     * │         │            ╱                              │
     * │         │         ╱                                 │
     * │         │──────╱                                    │
     * │         └────────────────────────────────── 시간     │
     * │           remove() 없이 put()만 반복                 │
     * └──────────────────────────────────────────────────────┘
     * </pre>
     */
    private final Map<String, Object> sessionStore = new ConcurrentHashMap<>();

    /** 릭: 로그인 시 저장, 로그아웃 시 제거 누락 */
    public void onLogin(String sessionId, Object userData) {
        sessionStore.put(sessionId, userData);
    }

    // onLogout()에서 sessionStore.remove(sessionId) 빠뜨림 → 릭

    /** 해결: 생명주기에 맞춰 반드시 제거 */
    public void onLogout(String sessionId) {
        sessionStore.remove(sessionId);  // 반드시 제거
    }

    // ════════════════════════════════════════════════════════════════
    // [4] 리스너/콜백 등록 후 해제 안 함
    // ════════════════════════════════════════════════════════════════

    /**
     * ── 문제 ──
     * 이벤트 리스너를 등록하고 해제하지 않으면, 이벤트 소스가 리스너 객체에 대한
     * 참조를 유지하므로 GC가 회수할 수 없다.
     *
     * <pre>
     * EventPublisher ──→ listeners (List)
     *                        │
     *                        ├──→ Listener A (등록 후 해제 안 함)  ← GC 불가
     *                        ├──→ Listener B (등록 후 해제 안 함)  ← GC 불가
     *                        └──→ ...
     * </pre>
     *
     * Spring의 @EventListener는 Spring 컨테이너가 Bean 생명주기를 관리하므로
     * 이 문제가 발생하지 않는다. 수동으로 addListener()를 호출할 때 주의.
     */

    interface EventListener {
        void onEvent(String event);
    }

    static class EventPublisher {
        private final List<EventListener> listeners = new ArrayList<>();

        void addListener(EventListener listener) {
            listeners.add(listener);
        }

        /** 해결: 해제 메서드 제공 */
        void removeListener(EventListener listener) {
            listeners.remove(listener);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // [5] 내부 클래스의 외부 클래스 참조
    // ════════════════════════════════════════════════════════════════

    /**
     * ── 문제 ──
     * non-static 내부 클래스는 외부 클래스 인스턴스에 대한 암묵적 참조를 가진다.
     * 내부 클래스 객체가 살아있는 한, 외부 클래스(+ 모든 필드)도 GC 불가.
     *
     * <pre>
     * ┌───────────────────────────────────────────────────────┐
     * │  non-static 내부 클래스                                 │
     * │                                                       │
     * │  Outer.this ←──── Inner (암묵적 참조)                   │
     * │    │                 │                                 │
     * │    └── largeData     └── Inner가 살아있는 한             │
     * │        (10MB)            Outer + largeData GC 불가      │
     * │                                                       │
     * │  static 내부 클래스                                      │
     * │                                                       │
     * │  Outer      StaticInner (참조 없음)                     │
     * │    │                                                   │
     * │    └── largeData     → Outer와 독립적                   │
     * │        (10MB)          → Outer 단독 GC 가능             │
     * └───────────────────────────────────────────────────────┘
     * </pre>
     */

    private final byte[] largeData = new byte[10_000_000]; // 10MB

    /** 릭 가능: non-static → Outer(10MB)에 대한 암묵적 참조 */
    public class InnerTask implements Runnable {
        @Override
        public void run() {
            // this가 살아있는 한 MemoryLeakExample(10MB)도 GC 불가
            log.info("inner task");
        }
    }

    /** 해결: static 내부 클래스 → 외부 참조 없음 */
    public static class StaticInnerTask implements Runnable {
        @Override
        public void run() {
            // MemoryLeakExample에 대한 참조 없음 → 독립적 GC 가능
            log.info("static inner task");
        }
    }

    // ════════════════════════════════════════════════════════════════
    // [6] ThreadLocal 미정리
    // ════════════════════════════════════════════════════════════════

    /**
     * ── 문제 ──
     * ThreadLocal은 스레드별 독립 저장소를 제공한다.
     * 스레드 풀 환경(Tomcat, @Async 등)에서는 스레드가 재사용되므로,
     * 사용 후 remove()하지 않으면:
     * 1. 메모리 릭: 이전 요청의 데이터가 계속 남아있음
     * 2. 데이터 오염: 다른 사용자의 데이터가 보임 (보안 문제)
     *
     * <pre>
     * ┌──────────────────────────────────────────────────────────────┐
     * │  스레드 풀에서 ThreadLocal 릭                                  │
     * │                                                              │
     * │  Thread-1                                                    │
     * │  ├── 요청 A: context.set(UserA) → 처리 → remove() 안 함     │
     * │  ├── 요청 B: context.get() → UserA가 보임! (데이터 오염)      │
     * │  │                                                           │
     * │  Thread-2                                                    │
     * │  ├── 요청 C: context.set(UserC) → 처리 → remove() 안 함     │
     * │  ├── ... (Thread-2가 재사용될 때까지 UserC 메모리 점유)        │
     * │                                                              │
     * │  스레드가 소멸되지 않는 한 ThreadLocal 값도 소멸되지 않음       │
     * └──────────────────────────────────────────────────────────────┘
     * </pre>
     */
    private static final ThreadLocal<Map<String, Object>> requestContext = new ThreadLocal<>();

    /** 릭 발생: set()만 하고 remove() 안 함 */
    public void handleRequestLeaky(String userId) {
        requestContext.set(Map.of("userId", userId, "timestamp", System.currentTimeMillis()));
        // 비즈니스 로직 처리...
        // remove() 누락 → 스레드 재사용 시 이전 데이터 잔존
    }

    /** 해결: finally 블록에서 반드시 remove() */
    public void handleRequestSafe(String userId) {
        try {
            requestContext.set(Map.of("userId", userId, "timestamp", System.currentTimeMillis()));
            // 비즈니스 로직 처리...
        } finally {
            requestContext.remove();  // 반드시 제거 — 예외 발생해도 보장
        }
    }

    // ════════════════════════════════════════════════════════════════
    // [7] JPA 영속성 컨텍스트 비대화 (Spring 특화)
    // ════════════════════════════════════════════════════════════════

    /**
     * ── 문제 ──
     * 대량 데이터를 조회하면 영속성 컨텍스트(1차 캐시)에 엔티티가 계속 쌓인다.
     * 트랜잭션이 끝나기 전까지 GC 대상이 되지 않으므로 OOM 가능.
     *
     * <pre>
     * ┌──────────────────────────────────────────────────────────────┐
     * │  @Transactional 내부                                          │
     * │                                                              │
     * │  영속성 컨텍스트 (1차 캐시)                                    │
     * │  ┌──────────────────────────────────────────┐                │
     * │  │  Entity 1 (관리 상태)                     │                │
     * │  │  Entity 2 (관리 상태)                     │                │
     * │  │  Entity 3 (관리 상태)                     │                │
     * │  │  ...                                     │                │
     * │  │  Entity 100,000 (관리 상태)  ← 메모리 폭증│                │
     * │  └──────────────────────────────────────────┘                │
     * │                                                              │
     * │  TX 종료 시까지 모든 엔티티가 메모리에 유지                      │
     * │  + 더티 체킹으로 CPU도 소비                                    │
     * └──────────────────────────────────────────────────────────────┘
     * </pre>
     */

    /** 해결: 청크 단위로 clear() 호출 */
    public void processLargeData(EntityManager em, List<Long> ids) {
        int batchSize = 100;

        for (int i = 0; i < ids.size(); i++) {
            em.find(Object.class, ids.get(i));
            // 비즈니스 로직 처리...

            // 100건마다 영속성 컨텍스트 초기화
            if (i % batchSize == 0 && i > 0) {
                em.flush();  // 변경사항 DB 반영
                em.clear();  // 1차 캐시 초기화 → 메모리 해제
                log.info("[JPA] 영속성 컨텍스트 초기화 - processed={}", i);
            }
        }
    }

    /**
     * 해결 대안: 읽기 전용 조회 시 영속성 컨텍스트 우회
     * - @Transactional(readOnly = true): 더티 체킹 비활성화
     * - JdbcTemplate / Native Query + DTO Projection: 영속성 컨텍스트 미사용
     * - 프로젝트의 JdbcBatchService.java 참고
     */

    // ════════════════════════════════════════════════════════════════
    // [8] String 반복 연결 (String Immutability)
    // ════════════════════════════════════════════════════════════════

    /**
     * ── 문제 ──
     * String은 불변(immutable)이므로 + 연산 시 매번 새 객체가 생성된다.
     * 루프에서 반복하면 중간 객체들이 힙에 쌓여 GC 부하가 증가한다.
     * 엄밀히 메모리 "릭"은 아니지만, 불필요한 메모리 낭비 + GC 압박을 유발.
     *
     * <pre>
     * ┌─────────────────────────────────────────────────────────┐
     * │  String 연결 (10,000회 루프)                              │
     * │                                                         │
     * │  "a" → "ab" → "abc" → "abcd" → ...                     │
     * │   ↑      ↑       ↑                                     │
     * │   GC    GC      GC    ← 중간 객체 매번 생성 후 폐기      │
     * │                                                         │
     * │  StringBuilder:                                          │
     * │  내부 char[] 배열 하나에 append → 객체 생성 없음           │
     * └─────────────────────────────────────────────────────────┘
     * </pre>
     */

    /** 비효율: 매번 새 String 객체 생성 */
    public String buildStringInefficient(List<String> items) {
        String result = "";
        for (String item : items) {
            result += item + ",";  // 매번 새 String 객체 생성
        }
        return result;
    }

    /** 해결: StringBuilder 사용 */
    public String buildStringEfficient(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            sb.append(item).append(",");
        }
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    // 요약: 메모리 릭 vs 아닌 것
    // ════════════════════════════════════════════════════════════════

    /**
     * <pre>
     * ┌──────────────────────────────────────────────────────────────────┐
     * │  메모리 릭인가?                                                    │
     * ├────────────────────────────────────────┬─────────┬───────────────┤
     * │  상황                                  │ 릭 여부  │ 이유           │
     * ├────────────────────────────────────────┼─────────┼───────────────┤
     * │  static Map에 추가만, 제거 안 함        │   릭    │ 무한 증가       │
     * │  캐시에 TTL 없이 무한 적재              │   릭    │ 시간 → OOM     │
     * │  ThreadLocal remove() 누락             │   릭    │ 스레드 풀 재사용│
     * │  Connection close() 누락               │   릭    │ 풀 고갈         │
     * │  리스너 등록 후 해제 안 함               │   릭    │ 참조 유지       │
     * ├────────────────────────────────────────┼─────────┼───────────────┤
     * │  대용량 파일 한 번에 로딩               │ 아님    │ 의도된 사용     │
     * │  요청 처리 중 일시적 메모리 증가         │ 아님    │ 요청 후 GC     │
     * │  Spring Bean의 필드                    │ 아님    │ 싱글톤 의도     │
     * └────────────────────────────────────────┴─────────┴───────────────┘
     *
     * ┌──────────────────────────────────────────────────────────────────┐
     * │  핵심 방지 원칙                                                    │
     * ├──────────────────────────────────────────────────────────────────┤
     * │  1. 리소스는 try-with-resources로 관리                             │
     * │  2. 컬렉션에 넣으면 반드시 제거 시점을 설계                          │
     * │  3. static 컬렉션은 크기 제한 필수 (maximumSize, TTL)              │
     * │  4. ThreadLocal은 finally에서 remove()                            │
     * │  5. 내부 클래스는 가능하면 static으로                                │
     * │  6. JPA 대량 조회 시 clear() 또는 JdbcTemplate 사용                │
     * └──────────────────────────────────────────────────────────────────┘
     * </pre>
     */
    private void summary() {
        // 이 메서드는 문서화 목적. 위 Javadoc 참고.
    }
}
