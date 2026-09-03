# Java/Spring 메모리 릭 가이드

> 더 이상 사용하지 않는 객체가 GC에 의해 회수되지 못하고 힙 메모리에 계속 남아있는 현상.
> Java는 GC가 자동으로 메모리를 관리하지만, **참조(Reference)가 남아있으면 GC가 회수할 수 없다.**

### 관련 소스 코드

| # | 파일 | 설명 |
|---|------|------|
| 1 | [MemoryLeakExample.java](../src/main/java/kr/co/example/javacore/MemoryLeakExample.java) | 메모리 릭 발생 패턴 8종, 방지 방법 — 파일 하나에 패턴별 Bad/Good 코드 포함 |

---

## 목차

1. [GC 회수 조건](#1-gc-회수-조건)
2. [힙 메모리 구조](#2-힙-메모리-구조)
3. [발생 패턴 8가지](#3-발생-패턴-8가지)
   - [패턴 1: static 컬렉션 무한 추가](#패턴-1-static-컬렉션-무한-추가)
   - [패턴 2: 리소스 미반환](#패턴-2-리소스-미반환-connection-stream)
   - [패턴 3: Map 제거 누락](#패턴-3-map-제거-누락)
   - [패턴 4: 리스너/콜백 해제 누락](#패턴-4-리스너콜백-해제-누락)
   - [패턴 5: 내부 클래스 외부 참조](#패턴-5-내부-클래스-외부-참조)
   - [패턴 6: ThreadLocal 미정리](#패턴-6-threadlocal-미정리)
   - [패턴 7: JPA 영속성 컨텍스트 비대화](#패턴-7-jpa-영속성-컨텍스트-비대화)
   - [패턴 8: String 반복 연결](#패턴-8-string-반복-연결)
4. [Spring 환경 주의점](#4-spring-환경-주의점)
5. [메모리 릭 여부 판단](#5-메모리-릭-여부-판단)
6. [증상과 진단](#6-증상과-진단)
7. [핵심 방지 원칙](#7-핵심-방지-원칙)

---

## 1. GC 회수 조건

```
GC Roots (스레드 스택, static 필드, JNI 참조 등)
    │
    ├──→ 도달 가능(Reachable)   → GC 회수 불가
    │
    └──→ 도달 불가(Unreachable) → GC 회수 대상
```

**메모리 릭 = "논리적으로 불필요하지만 참조가 남아있어 Reachable인 상태"**

```
── 정상: GC 회수 가능 ──

GC Roots ──→ Object A ──→ Object B
                  │
             A = null (참조 제거)
                  │
             Object A, B → Unreachable → GC 회수 ✓


── 메모리 릭: GC 회수 불가 ──

GC Roots ──→ Collection ──→ Object A (더 이상 안 쓰임)
                  │
             참조가 남아있음 (remove 안 함)
                  │
             Object A → 여전히 Reachable → GC 회수 불가 ✗
```

---

## 2. 힙 메모리 구조

```
┌───────────────────────────────────────────────────────┐
│                        Heap                            │
│  ┌──────────────────┐  ┌───────────────────────────┐  │
│  │    Young Gen      │  │         Old Gen            │  │
│  │  ┌─────┬────────┐│  │                            │  │
│  │  │Eden │ S0/S1  ││  │  오래 생존한 객체            │  │
│  │  └─────┴────────┘│  │  → 릭 발생 시 계속 증가     │  │
│  │  새 객체 생성      │  │  → Full GC로도 회수 불가    │  │
│  └──────────────────┘  └───────────────────────────┘  │
└───────────────────────────────────────────────────────┘
```

릭 진행 과정:

```
Old Gen 지속 증가 → Full GC 빈도 증가 → Stop-The-World 시간 증가
→ 애플리케이션 응답 시간 저하 → 최종적으로 OutOfMemoryError
```

---

## 3. 발생 패턴 8가지

| # | 패턴 | 원인 | 해결 |
|---|------|------|------|
| 1 | static 컬렉션 무한 추가 | add만, remove 없음 | 크기 제한, WeakHashMap, Caffeine |
| 2 | 리소스 미반환 | close() 누락 | try-with-resources |
| 3 | Map 제거 누락 | put 후 remove 안 함 | 생명주기에 맞춰 remove |
| 4 | 리스너 해제 누락 | addListener 후 해제 안 함 | @EventListener 또는 수동 해제 |
| 5 | 내부 클래스 외부 참조 | non-static inner class | static 내부 클래스 사용 |
| 6 | ThreadLocal 미정리 | set() 후 remove() 누락 | finally에서 remove() |
| 7 | JPA 영속성 컨텍스트 비대화 | 대량 조회 시 1차 캐시 누적 | 청크 단위 clear() |
| 8 | String 반복 연결 | 루프에서 += 반복 | StringBuilder |

---

### 패턴 1: static 컬렉션 무한 추가

```
GC Roots ──→ Class(MyService)
                  │
                  └──→ static leakyCache (ArrayList)
                           │
                           ├──→ Object 1 (안 쓰임)  ← GC 불가
                           ├──→ Object 2 (안 쓰임)  ← GC 불가
                           ├──→ Object 3 (안 쓰임)  ← GC 불가
                           └──→ ... 무한 증가 → OOM
```

```java
// 릭: 추가만 하고 제거하지 않음
private static final List<byte[]> cache = new ArrayList<>();

public void process(byte[] data) {
    cache.add(data);  // 요청마다 쌓임 → OOM
}
```

**해결:**

```java
// 해결 1: 크기 제한
private static final int MAX_SIZE = 1000;
private static final List<byte[]> bounded = new ArrayList<>();

public void process(byte[] data) {
    if (bounded.size() >= MAX_SIZE) {
        bounded.removeFirst();
    }
    bounded.add(data);
}

// 해결 2: WeakHashMap (강한 참조 없으면 GC가 자동 제거)
private final Map<String, byte[]> weakCache = new WeakHashMap<>();

// 해결 3: Caffeine 캐시 (Spring 권장)
// maximumSize + expireAfterWrite로 자동 퇴거
// → 프로젝트의 LocalCacheConfig.java 참고
```

**참조 유형:**

| 유형 | GC 대상 | 예시 |
|------|--------|------|
| Strong (강한) | 참조 존재하면 불가 | `Object obj = new Object()` |
| Weak (약한) | 강한 참조 없으면 즉시 가능 | `WeakReference<Object>` |
| Soft (소프트) | 메모리 부족 시 가능 | `SoftReference<Object>` |

---

### 패턴 2: 리소스 미반환 (Connection, Stream)

```
┌──────────────────────────────────────────────────────────────┐
│  리소스 누수 흐름                                              │
│                                                              │
│  요청 1 → open() → 예외 발생 → close() 호출 안 됨 → 릭       │
│  요청 2 → open() → 예외 발생 → close() 호출 안 됨 → 릭       │
│  ...                                                         │
│  요청 N → open() 실패 → "Too many open files" 또는            │
│                          "Cannot get a connection" 에러       │
└──────────────────────────────────────────────────────────────┘
```

```java
// 릭: 예외 시 close() 누락
public String readFile(String path) throws IOException {
    FileInputStream fis = new FileInputStream(path);
    BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
    String line = reader.readLine();  // 여기서 예외 발생하면?
    reader.close();                   // 실행 안 됨 → 파일 디스크립터 누수
    return line;
}

// 해결: try-with-resources (Java 7+)
public String readFile(String path) throws IOException {
    try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(path)))) {
        return reader.readLine();
    } // 자동 close() — 예외 발생해도 보장
}
```

**JDBC 리소스도 동일:**

```java
// Connection, Statement, ResultSet 모두 close() 필요
try (Statement stmt = conn.createStatement();
     ResultSet rs = stmt.executeQuery("SELECT 1")) {
    while (rs.next()) {
        // 처리
    }
}
// Spring @Transactional, JdbcTemplate은 자동 관리
```

---

### 패턴 3: Map 제거 누락

```
시간 경과에 따른 Map 크기:

메모리   │                              ╱ ← OOM
사용량   │                           ╱
         │                        ╱
         │                     ╱
         │                  ╱
         │               ╱
         │            ╱
         │         ╱
         │──────╱
         └───────────────────────────────── 시간
           remove() 없이 put()만 반복
```

```java
private final Map<String, Object> sessionStore = new ConcurrentHashMap<>();

// 릭: 로그인 시 저장, 로그아웃 시 제거 누락
public void onLogin(String sessionId, Object userData) {
    sessionStore.put(sessionId, userData);
}
// onLogout()에서 sessionStore.remove(sessionId) 빠뜨림 → 릭

// 해결: 생명주기에 맞춰 반드시 제거
public void onLogout(String sessionId) {
    sessionStore.remove(sessionId);
}
```

---

### 패턴 4: 리스너/콜백 해제 누락

```
EventPublisher ──→ listeners (List)
                       │
                       ├──→ Listener A (등록 후 해제 안 함)  ← GC 불가
                       ├──→ Listener B (등록 후 해제 안 함)  ← GC 불가
                       └──→ ...

해결:
- 수동 등록 시: removeListener() 반드시 호출
- Spring: @EventListener 사용 (Bean 생명주기를 컨테이너가 관리)
```

---

### 패턴 5: 내부 클래스 외부 참조

```
┌──────────────────────────────────────────────────────────┐
│  non-static 내부 클래스                                    │
│                                                          │
│  Outer.this ◄──── Inner (암묵적 참조)                     │
│    │                 │                                    │
│    └── largeData     └── Inner가 살아있는 한               │
│        (10MB)            Outer + largeData(10MB) GC 불가   │
│                                                          │
│  static 내부 클래스                                        │
│                                                          │
│  Outer      StaticInner (참조 없음)                       │
│    │                                                      │
│    └── largeData     → Outer와 독립적                     │
│        (10MB)          → Outer 단독 GC 가능               │
└──────────────────────────────────────────────────────────┘
```

```java
private final byte[] largeData = new byte[10_000_000]; // 10MB

// 릭 가능: non-static → Outer(10MB)에 대한 암묵적 참조
public class InnerTask implements Runnable {
    @Override
    public void run() { /* Inner 살아있는 한 Outer GC 불가 */ }
}

// 해결: static 내부 클래스 → 외부 참조 없음
public static class StaticInnerTask implements Runnable {
    @Override
    public void run() { /* Outer에 대한 참조 없음 */ }
}
```

---

### 패턴 6: ThreadLocal 미정리

**스레드 풀 환경(Tomcat, @Async)에서 가장 흔한 릭 + 보안 문제.**

```
┌──────────────────────────────────────────────────────────────┐
│  스레드 풀에서 ThreadLocal 릭                                  │
│                                                              │
│  Thread-1                                                    │
│  ├── 요청 A: context.set(UserA) → 처리 → remove() 안 함     │
│  ├── 요청 B: context.get() → UserA가 보임! (데이터 오염)      │
│  │                                                           │
│  Thread-2                                                    │
│  ├── 요청 C: context.set(UserC) → 처리 → remove() 안 함     │
│  ├── ... (스레드 재사용 시까지 UserC 메모리 점유)               │
│                                                              │
│  스레드가 소멸되지 않는 한 ThreadLocal 값도 소멸되지 않음       │
└──────────────────────────────────────────────────────────────┘
```

```java
private static final ThreadLocal<Map<String, Object>> requestContext = new ThreadLocal<>();

// 릭: set()만 하고 remove() 안 함
public void handleRequestLeaky(String userId) {
    requestContext.set(Map.of("userId", userId));
    // 비즈니스 로직...
    // remove() 누락 → 스레드 재사용 시 이전 데이터 잔존
}

// 해결: finally에서 반드시 remove()
public void handleRequestSafe(String userId) {
    try {
        requestContext.set(Map.of("userId", userId));
        // 비즈니스 로직...
    } finally {
        requestContext.remove();  // 반드시 제거 — 예외 발생해도 보장
    }
}
```

---

### 패턴 7: JPA 영속성 컨텍스트 비대화

```
@Transactional 내부 — 10만 건 조회 시

영속성 컨텍스트 (1차 캐시)
┌──────────────────────────────────────────────┐
│  Entity 1        (관리 상태)                  │
│  Entity 2        (관리 상태)                  │
│  ...                                         │
│  Entity 100,000  (관리 상태)  ← 메모리 폭증   │
└──────────────────────────────────────────────┘
TX 종료까지 모든 엔티티가 메모리에 유지
+ 더티 체킹(dirty checking)으로 CPU도 소비
```

```java
// 해결: 청크 단위로 flush() + clear()
public void processLargeData(EntityManager em, List<Long> ids) {
    int batchSize = 100;
    for (int i = 0; i < ids.size(); i++) {
        em.find(Object.class, ids.get(i));
        // 비즈니스 로직...

        if (i % batchSize == 0 && i > 0) {
            em.flush();  // 변경사항 DB 반영
            em.clear();  // 1차 캐시 초기화 → 메모리 해제
        }
    }
}

// 대안: 읽기 전용 조회 시 영속성 컨텍스트 우회
// - @Transactional(readOnly = true): 더티 체킹 비활성화
// - JdbcTemplate / Native Query + DTO Projection
// - 프로젝트의 JdbcBatchService.java 참고
```

---

### 패턴 8: String 반복 연결

엄밀히 메모리 "릭"은 아니지만, 불필요한 메모리 낭비 + GC 압박을 유발.

```
String 연결 (10,000회 루프):

"a" → "ab" → "abc" → "abcd" → ...
 ↑      ↑       ↑
 GC    GC      GC    ← 중간 객체 매번 생성 후 폐기

StringBuilder:
내부 char[] 배열 하나에 append → 객체 생성 없음
```

```java
// 비효율: 매번 새 String 객체 생성
String result = "";
for (String item : items) {
    result += item + ",";  // N번 루프 → N개의 중간 String 객체
}

// 해결: StringBuilder
StringBuilder sb = new StringBuilder();
for (String item : items) {
    sb.append(item).append(",");
}
return sb.toString();
```

---

## 4. Spring 환경 주의점

| 패턴 | 문제 | 해결 |
|------|------|------|
| `@Cacheable` 무제한 | 캐시 항목이 계속 쌓임 | `maximumSize`, `expireAfterWrite` 설정 (Caffeine) |
| `ApplicationEvent` 리스너 | 수동 등록 시 참조 잔존 | `@EventListener` 사용 (Spring 관리) |
| WebSocket 세션 Map | 연결 종료 후 미제거 | `@OnClose`에서 반드시 제거 |
| `DataSource` Connection | 반환 안 하면 풀 고갈 | try-with-resources, `@Transactional` |
| `EntityManager` | 대량 조회 시 1차 캐시 비대화 | `clear()`, 읽기 전용 쿼리, JdbcTemplate |
| `@Async` 반환값 무시 | `Future` 내부 예외가 삼켜짐 | `CompletableFuture` + 예외 핸들러 |

---

## 5. 메모리 릭 여부 판단

| 상황 | 릭 여부 | 이유 |
|------|--------|------|
| static Map에 추가만, 제거 안 함 | **릭** | 무한 증가 |
| 캐시에 TTL 없이 무한 적재 | **릭** | 시간 경과 → OOM |
| ThreadLocal remove() 누락 | **릭** | 스레드 풀 재사용 |
| Connection close() 누락 | **릭** | 풀 고갈 |
| 리스너 등록 후 해제 안 함 | **릭** | 참조 유지 |
| 대용량 파일 한 번에 로딩 | 아님 | 의도된 사용, 처리 후 GC |
| 요청 처리 중 일시적 메모리 증가 | 아님 | 요청 완료 후 GC 대상 |
| Spring Bean의 필드 | 아님 | 싱글톤 의도 |

---

## 6. 증상과 진단

### 증상

```
1. 애플리케이션이 시간이 지날수록 느려짐
2. Full GC 빈도 증가 (GC 로그로 확인)
3. 힙 사용량이 GC 후에도 줄어들지 않음
4. 최종적으로 java.lang.OutOfMemoryError: Java heap space
```

### 진단 흐름

```
증상 감지 (응답 지연, OOM)
    │
    ▼
GC 로그 분석
  -Xlog:gc*:file=gc.log
    │
    ├── Full GC 후 Old Gen 감소 → 정상 (일시적 부하)
    │
    └── Full GC 후에도 Old Gen 감소 안 함 → 릭 의심
            │
            ▼
        힙 덤프 생성
        jmap -dump:format=b,file=heap.hprof <pid>
            │
            ▼
        Eclipse MAT / VisualVM 분석
            │
            ├── Leak Suspects 리포트
            ├── Dominator Tree (메모리 점유 객체)
            └── Path to GC Roots (참조 체인 추적)
                    │
                    ▼
                원인 객체 + 참조 체인 확인 → 코드 수정
```

### 진단 도구

| 도구 | 용도 | 사용 시점 |
|------|------|----------|
| `jmap -histo <pid>` | 클래스별 인스턴스 수/크기 확인 | 실시간 확인 |
| `jmap -dump:format=b <pid>` | 힙 덤프 생성 | 분석 필요 시 |
| Eclipse MAT | 힙 덤프 분석, Leak Suspects | 사후 분석 |
| VisualVM | 실시간 모니터링 + 힙 덤프 | 개발/스테이징 |
| `-XX:+HeapDumpOnOutOfMemoryError` | OOM 발생 시 자동 덤프 | 운영 환경 상시 |
| `-XX:HeapDumpPath=/path/` | 덤프 저장 경로 지정 | 운영 환경 |

### JVM 옵션 권장 설정

```bash
java -jar app.jar \
  -Xms512m -Xmx2g \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/heapdump/ \
  -Xlog:gc*:file=/var/log/gc.log:time,level,tags
```

---

## 7. 핵심 방지 원칙

```
┌──────────────────────────────────────────────────────────────────────┐
│  1. 리소스는 try-with-resources로 관리                                 │
│     → Connection, Stream, Channel 등 AutoCloseable 구현체            │
│                                                                      │
│  2. 컬렉션에 넣으면 반드시 제거 시점을 설계                               │
│     → 생명주기(로그인↔로그아웃, 연결↔해제)에 맞춰 remove                 │
│                                                                      │
│  3. static 컬렉션은 크기 제한 필수                                      │
│     → maximumSize, TTL, WeakHashMap, Caffeine 캐시                   │
│                                                                      │
│  4. ThreadLocal은 finally에서 remove()                                │
│     → 스레드 풀 재사용 환경에서 필수 (메모리 릭 + 데이터 오염 방지)       │
│                                                                      │
│  5. 내부 클래스는 가능하면 static으로                                    │
│     → 외부 클래스에 대한 암묵적 참조 차단                                │
│                                                                      │
│  6. JPA 대량 조회 시 clear() 또는 JdbcTemplate 사용                    │
│     → 영속성 컨텍스트 비대화 방지                                       │
│                                                                      │
│  7. 운영 환경에 HeapDumpOnOutOfMemoryError 설정                        │
│     → OOM 발생 시 원인 분석 가능                                       │
└──────────────────────────────────────────────────────────────────────┘
```
