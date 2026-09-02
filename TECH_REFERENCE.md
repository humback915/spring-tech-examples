# Spring 기술 레퍼런스

> Spring Boot 3.3.11 / JDK 21 기반 기술 예제 프로젝트

---

## 목차

1. [Reactor Netty + WebClient](#1-reactor-netty--webclient)
2. [Spring Kafka](#2-spring-kafka)
3. [Redis + Redisson](#3-redis--redisson)
4. [스케줄링 (@Scheduled)](#4-스케줄링-scheduled)
5. [트랜잭션 관리](#5-트랜잭션-관리)
    - 5-B. [트랜잭션 전파 (Propagation)](#5-b-트랜잭션-전파-propagation)
6. [스레드 풀 (ThreadPoolTaskExecutor)](#6-스레드-풀-threadpooltaskexecutor)
7. [비동기 처리 (@Async)](#7-비동기-처리-async)
8. [병렬 처리 (CompletableFuture / parallelStream)](#8-병렬-처리)
    - 8-B. [CompletableFuture 상세](#8-b-completablefuture-상세)
9. [디자인 패턴](#9-디자인-패턴)
    - 9-A. [Strategy Pattern](#9-a-strategy-pattern)
    - 9-B. [Template Method Pattern](#9-b-template-method-pattern)
    - 9-C. [Observer Pattern (Spring Event)](#9-c-observer-pattern)
    - 9-D. [Facade Pattern](#9-d-facade-pattern)
    - 9-E. [Builder Pattern](#9-e-builder-pattern)
10. [WebSocket (STOMP)](#10-websocket-stomp)
11. [Circuit Breaker](#11-circuit-breaker)
12. [배치 처리 (Spring Batch / JDBC Batch)](#12-배치-처리)
13. [REST API 응답 (ResponseEntity, 페이징)](#13-rest-api-응답)
14. [REST API 클라이언트 (호출 방식 비교)](#14-rest-api-클라이언트)
15. [Validation (입력 검증)](#15-validation)
16. [Exception Handling (예외 처리)](#16-exception-handling)
    - 16-B. [Checked vs Unchecked Exception](#16-b-checked-vs-unchecked-exception)
17. [JPA Entity 심화](#17-jpa-entity-심화)
18. [JPA Repository + QueryDSL](#18-jpa-repository--querydsl)
19. [Spring Security (JWT + Session/Redis)](#19-spring-security)
20. [AOP + Interceptor](#20-aop--interceptor)
21. [Configuration 관리](#21-configuration-관리)
22. [Java Core (Optional, Stream, Enum, 함수형, 메모리 릭)](#22-java-core)
    - 22-A. [메모리 릭 (Memory Leak)](#22-a-메모리-릭-memory-leak)
23. [Lock 개념 (애플리케이션/DB/트랜잭션)](#23-lock-개념)
24. [Logging (@Slf4j, MDC)](#24-logging)
25. [Swagger / OpenAPI](#25-swagger--openapi)
26. [파일 업로드 (MultipartFile)](#26-파일-업로드)
27. [MapStruct (DTO 매핑)](#27-mapstruct)
28. [Nginx 설정](#28-nginx-설정)

---

## 1. Reactor Netty + WebClient

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.netty` |
| 파일 | `NettyWebClientConfig.java`, `ExternalApiService.java` |

Spring WebFlux에 포함된 Non-blocking HTTP 클라이언트.
Netty의 Event Loop 모델을 기반으로 적은 스레드로 대량의 동시 HTTP 요청을 처리한다.

### 핵심 구성요소

| 구성요소 | 역할 |
|---------|------|
| `ConnectionProvider` | 커넥션 풀 관리 (최대 연결 수, 유휴 시간, 수명) |
| `HttpClient` | TCP 옵션, SSL, 타임아웃, 압축 설정 |
| `WebClient` | HTTP 요청 빌더 (GET/POST/PUT/DELETE) |
| `Mono<T>` | 0~1개 결과를 비동기로 반환 |
| `Flux<T>` | 0~N개 결과를 비동기로 반환 |

### WebClient vs RestTemplate

| 항목 | RestTemplate | WebClient |
|------|-------------|-----------|
| I/O 모델 | Blocking | Non-blocking |
| 스레드 | 요청당 1스레드 | Event Loop 공유 |
| 대량 요청 | 스레드 풀 고갈 위험 | 효율적 처리 |
| 지원 상태 | Deprecated (6.1) | 권장 |

### 에러 처리

- `retryWhen` — 일시적 오류에 재시도 (지수 백오프)
- `onErrorResume` — 재시도 실패 시 폴백
- `onStatus` — HTTP 상태 코드별 분기
- `timeout` — 개별 요청 타임아웃

---

## 2. Spring Kafka

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.kafka` |
| 파일 | `KafkaConfig.java`, `EventTopic.java`, `EventProducer.java`, `EventConsumer.java`, `OutboxService.java` |

분산 이벤트 스트리밍 플랫폼. Producer → Topic → Consumer 구조로 시스템 간 느슨한 결합을 제공한다.

### 에러 처리 흐름

```
메시지 처리 실패
  ↓
DefaultErrorHandler (1초 간격, 최대 3회 재시도)
  ↓ 재시도 소진
DeadLetterPublishingRecoverer
  ↓
{원본토픽}.DLT 토픽으로 이동
```

### 주요 설정

| 설정 | 값 | 설명 |
|------|----|------|
| `acks` | `all` | 모든 ISR 기록 확인 (가장 안전) |
| `enable.idempotence` | `true` | 중복 전송 방지 |
| `compression-type` | `lz4` | 메시지 압축 |
| `max-poll-records` | `10` | 한 번에 가져올 최대 메시지 수 |
| `addNotRetryableExceptions` | `IllegalArgumentException` 등 | 재시도 없이 즉시 DLT 이동 |

### yml 설정 vs Bean 설정

| 구분 | application.yml | @Bean (Java 코드) |
|------|----------------|------------------|
| 역할 | 단순 속성값 (주소, 직렬화, 숫자) | 커스텀 로직 (에러 핸들러, DLT 라우팅) |
| 대상 | `bootstrap-servers`, `acks`, `retries`, `group-id`, `serializer`, `concurrency` 등 | `DefaultErrorHandler`, `DeadLetterPublishingRecoverer`, `ConcurrentKafkaListenerContainerFactory` |
| 자동 구성 | Spring Boot가 Factory/Template 자동 생성 | Bean 등록 시 해당 타입의 자동 구성 비활성화 |
| 변경 방식 | 재시작만으로 적용 | 코드 수정 + 재빌드 필요 |

> **주의**: `ConcurrentKafkaListenerContainerFactory`를 @Bean으로 등록하면 Spring Boot의 자동 Factory 생성이 무시된다. ConsumerFactory 주입과 에러 핸들러를 직접 설정해야 한다.

### Transactional Outbox Pattern

DB 트랜잭션과 이벤트 발행의 원자성을 보장하는 패턴.

```
@Transactional 내에서:
  1. repository.save(entity)   ← DB 저장
  2. outbox.save(event)        ← 같은 TX에 이벤트 저장

별도 스케줄러:
  outbox 테이블 폴링 → Kafka 발행 → 상태 갱신
```

---

## 3. Redis + Redisson

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.redis`, `kr.co.example.cache` |
| 파일 | `RedisConfig.java`, `RedisStockService.java`, `RedisCacheStrategyService.java`, `RedisDataStructureService.java`, `ProbabilisticEarlyRecomputationService.java`, `LocalCacheConfig.java`, `LocalCacheService.java` |
| 상세 | **[REDIS_CACHE_GUIDE.md](REDIS_CACHE_GUIDE.md)** |

인메모리 키-값 데이터 스토어. 캐시, 재고 관리, 분산 락 등에 활용한다.

| 주제 | 핵심 내용 |
|------|----------|
| Template | StringRedisTemplate (단순), RedisTemplate (객체) |
| 데이터 구조 | String, Hash, List, Set, Sorted Set + Lua Script |
| 캐싱 어노테이션 | @Cacheable, @CacheEvict, @CachePut |
| 분산 락 | Redisson tryLock — Cache Stampede 방지 |
| 로컬 캐시 | Caffeine — maximumSize, expireAfterWrite, 멀티 레벨 캐시 |
| 캐시 전략 5종 | Cache-Aside, Read/Write-Through, Write-Behind, Write-Around |
| PER 알고리즘 | 확률적 조기 재계산으로 Stampede 방지 (락 없음) |
| Redis 장애 대응 | DB 폴백, Circuit Breaker, Sentinel/Cluster |

---

## 4. 스케줄링 (@Scheduled)

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.scheduling` |
| 파일 | `ScheduleConfig.java`, `ScheduledTasks.java` |

`@EnableScheduling`으로 활성화하며, 반복/예약 작업을 어노테이션 기반으로 정의한다.

### 옵션 비교

| 옵션 | 기준 | 예시 |
|------|------|------|
| `fixedRate` | 이전 **시작** 시점 기준 간격 | 3초마다 폴링 |
| `fixedDelay` | 이전 **완료** 시점 기준 간격 | 완료 후 10초 대기 |
| `cron` | Unix cron 표현식 | 매일 새벽 2시 |
| `initialDelay` | 앱 시작 후 첫 실행까지 대기 | 1초 후 시작 |

### Cron 표현식 예시

```
초  분  시  일  월  요일
0   0   2   *   *   ?        ← 매일 새벽 2시
0   0/1 *   *   *   ?        ← 매 1분
0   30  9   *   *   MON-FRI  ← 평일 오전 9시 30분
0   0   0   1   *   ?        ← 매월 1일 자정
```

### ThreadPoolTaskScheduler

기본 스케줄러는 **단일 스레드**. 여러 @Scheduled 작업이 있으면 `poolSize`를 늘려야 한다.

---

## 5. 트랜잭션 관리

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.transaction` |
| 파일 | `TransactionExampleService.java`, `PropagationExampleService.java`, `PropagationTargetService.java` |

선언적(`@Transactional`) + 프로그래밍 방식(`TransactionTemplate`) 두 가지 접근법을 제공한다.

### 전파 수준 (Propagation)

| 수준 | 동작 |
|------|------|
| `REQUIRED` (기본) | 기존 TX 참여, 없으면 새로 생성 |
| `REQUIRES_NEW` | 항상 새 TX (기존 TX 일시 중단) |
| `NOT_SUPPORTED` | TX 없이 실행 (기존 TX 일시 중단) |
| `NESTED` | 기존 TX 내 중첩 TX (세이브포인트) |

### 선언적 vs 프로그래밍 방식

| 방식 | 장점 | 단점 |
|------|------|------|
| `@Transactional` | 간결, AOP 자동 관리 | self-invocation 미동작 |
| `TransactionTemplate` | 세밀한 제어, self-invocation 문제 없음 | 코드 복잡 |

### 활용 패턴

- **readOnly = true** — 더티체킹 비활성화, 레플리카 라우팅 가능
- **2단계 TX 분리** — Phase 1(DB 작업, TX 내) → Phase 2(외부 호출, TX 밖)
- **레코드별 독립 TX** — 배치 처리 시 하나의 실패가 전체에 영향 안 줌

### 5-B. 트랜잭션 전파 (Propagation)

| 항목 | 내용 |
|------|------|
| 파일 | `PropagationExampleService.java`, `PropagationTargetService.java` |

트랜잭션이 이미 진행 중일 때, 새로 호출되는 메서드가 기존 TX에 참여할지/새로 만들지/무시할지를 결정하는 정책.

| 수준 | 기존 TX 있을 때 | 기존 TX 없을 때 | 활용 |
|------|----------------|----------------|------|
| `REQUIRED` | 참여 | 새로 생성 | 일반 서비스 메서드 |
| `REQUIRES_NEW` | 새 TX (기존 중단) | 새로 생성 | 감사 로그, 시퀀스 채번 |
| `SUPPORTS` | 참여 | TX 없이 실행 | 읽기 전용 유틸리티 |
| `NOT_SUPPORTED` | TX 없이 (기존 중단) | TX 없이 실행 | 외부 API 호출 |
| `MANDATORY` | 참여 | 예외 발생 | 핵심 도메인 (Fail-Fast) |
| `NEVER` | 예외 발생 | TX 없이 실행 | TX 금지 로직 |
| `NESTED` | 세이브포인트 생성 | 새로 생성 | 부분 롤백 (포인트 적립 등) |

#### REQUIRES_NEW vs NESTED

```
REQUIRES_NEW:
  TX-A 일시 중단 → TX-B 독립 생성 → TX-B 커밋/롤백 → TX-A 재개
  → TX-A 롤백 시에도 TX-B는 유지됨 (완전 독립)

NESTED:
  TX-A 내에 SAVEPOINT 생성 → 중첩 작업 실행
  → 중첩 실패 시 SAVEPOINT까지만 롤백, TX-A는 유효
  → TX-A 롤백 시 중첩도 함께 롤백 (외부에 종속)
```

#### self-invocation 주의

```java
// 잘못된 예 (같은 클래스 내부 호출 → 전파 무시!)
class A {
    @Transactional
    void outer() { this.inner(); }  // 프록시 미경유 → REQUIRES_NEW 무시

    @Transactional(propagation = REQUIRES_NEW)
    void inner() { ... }
}

// 올바른 예 (별도 빈에서 호출 → 전파 정상 동작)
class A {
    @Autowired B b;
    @Transactional
    void outer() { b.inner(); }  // 프록시 경유 → 새 TX 생성
}
```

#### 실무 조합 예제

```
processOrder() - TX-A (REQUIRED)
  ├─ [1] 주문 저장       → TX-A에 참여 (REQUIRED)
  ├─ [2] 감사 로그       → TX-B 독립 (REQUIRES_NEW)
  ├─ [3] 결제 API 호출   → TX 밖 실행 (NOT_SUPPORTED)
  ├─ [4] 결제 결과 저장  → TX-A에 복귀 (REQUIRED)
  └─ [5] 포인트 적립     → 세이브포인트 (NESTED, 실패해도 주문 유지)
```

---

## 6. 스레드 풀 (ThreadPoolTaskExecutor)

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.thread` |
| 파일 | `ThreadPoolConfig.java` |

용도별로 분리된 스레드 풀을 정의하여 장애 격리와 성능 최적화를 달성한다.

### 동작 흐름

```
작업 제출
  ↓ corePoolSize 미만? → 새 스레드 생성
  ↓ 큐 여유 있음?      → 큐에 대기
  ↓ maxPoolSize 미만?  → 추가 스레드 생성
  ↓ 모두 가득 참       → RejectedExecutionHandler 실행
```

### 정의된 Executor 목록

| 빈 이름 | core | max | queue | 용도 |
|---------|------|-----|-------|------|
| `taskExecutor` | 10 | 50 | 100 | 범용 비동기 작업 |
| `externalApiExecutor` | 10~20 | 15~30 | 200~500 | 외부 API 호출 (환경별 설정) |
| `dispatchExecutor` | 2 | 4 | 5 | 이벤트 디스패치 |
| `batchExecutor` | 4 | 8 | 10 | I/O 바운드 배치 |

### 거부 정책

| 정책 | 동작 |
|------|------|
| `CallerRunsPolicy` | 호출자 스레드에서 직접 실행 (유실 없음) |
| `AbortPolicy` (기본) | 예외 발생 |
| `DiscardPolicy` | 조용히 버림 |
| `DiscardOldestPolicy` | 가장 오래된 작업 교체 |

---

## 7. 비동기 처리 (@Async)

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.async` |
| 파일 | `AsyncConfig.java`, `AsyncService.java` |

`@EnableAsync`로 활성화하며, `@Async` 어노테이션이 붙은 메서드를 별도 스레드에서 실행한다.

### 동작 원리

```
호출자 → AOP 프록시 → TaskExecutor.submit(메서드)
         ↓
호출자는 즉시 반환
별도 스레드에서 메서드 실행
```

### 반환 타입별 사용

| 반환 타입 | 패턴 | 용도 |
|----------|------|------|
| `void` | Fire-and-Forget | 알림 발송, 로그 저장 |
| `CompletableFuture<T>` | 결과 조회/조합 | 비동기 데이터 처리 |

### 주의사항

- **self-invocation**: 같은 클래스 내부 호출 시 동기로 실행됨 (프록시 미경유)
- **SecurityContext**: 비동기 스레드는 호출자의 SecurityContext를 상속받지 않음
- **Transaction**: @Async 메서드는 호출자의 트랜잭션에 참여하지 않음

---

## 8. 병렬 처리

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.parallel` |
| 파일 | `ParallelProcessingService.java`, `CompletableFutureExample.java` |

독립적인 작업을 동시에 실행하여 총 소요 시간을 단축한다.

### CompletableFuture vs parallelStream

| 항목 | CompletableFuture | parallelStream |
|------|-------------------|---------------|
| 스레드 풀 | 커스텀 Executor 지정 가능 | ForkJoinPool.commonPool() 공유 |
| 에러 처리 | 세밀한 예외 처리 | 제한적 |
| 적합한 작업 | I/O 바운드 | CPU 바운드 |
| 코드량 | 비교적 많음 | 간결 |

### CompletableFuture 주요 메서드

| 메서드 | 동작 |
|--------|------|
| `supplyAsync(fn, executor)` | 비동기 실행 (결과 반환) |
| `thenApply(fn)` | 결과 변환 |
| `thenCombine(other, fn)` | 두 Future 결과 조합 |
| `allOf(futures...)` | 모든 Future 완료 대기 |
| `join()` | 결과 블로킹 대기 |

### 8-B. CompletableFuture 상세

| 항목 | 내용 |
|------|------|
| 파일 | `CompletableFutureExample.java` |

Java 8에서 도입된 비동기 프로그래밍의 핵심. 기존 Future의 한계를 해결.

#### Future vs CompletableFuture

| 항목 | Future (Java 5) | CompletableFuture (Java 8) |
|------|----------------|---------------------------|
| 결과 조회 | `get()`만 가능 (블로킹) | `join()` + 논블로킹 콜백 |
| 결과 조합 | 불가 | `thenCombine`, `allOf` 등 |
| 체이닝 | 불가 | `thenApply` → `thenAccept` → `thenRun` |
| 예외 처리 | `ExecutionException` | `exceptionally`, `handle` |
| Executor | 고정 | 메서드별 지정 가능 |

#### 생성 방법

| 방법 | 반환 타입 | 용도 |
|------|----------|------|
| `supplyAsync(fn, executor)` | `CF<T>` | 결과 반환 비동기 작업 |
| `runAsync(fn, executor)` | `CF<Void>` | 결과 없는 비동기 작업 |
| `completedFuture(value)` | `CF<T>` | 이미 완료된 Future (테스트, @Async 반환용) |

#### 체이닝 메서드 분류

```
── 변환 ──
supplyAsync("data") → thenApply(대문자 변환) → thenApply(접두사 추가)
                       T → R                    R → S

── 소비 ──
future → thenAccept(결과 저장)   → thenRun(완료 알림)
         T → void (결과 사용)     void → void (결과 무관)

── 조합 ──
future1 ─┐
          ├─ thenCombine → 두 결과 합침
future2 ─┘

── 순차 비동기 ──
future → thenCompose(결과로 새 Future 생성) → 다음 작업
         userId → CF<Order>  (flatMap과 유사)

── 전체 대기 ──
allOf(f1, f2, f3) → 모두 완료 후 결과 집계
anyOf(f1, f2, f3) → 하나라도 완료 시 즉시 반환
```

#### 예외 처리

| 메서드 | 동작 | 결과 변경 |
|--------|------|----------|
| `exceptionally(fn)` | 예외 시 대체 값 반환 | O |
| `handle(fn)` | 성공/실패 모두 처리 | O |
| `whenComplete(fn)` | 성공/실패 시 사이드 이펙트 (로깅) | X |

#### Executor 미지정 시 주의

```
supplyAsync(() -> work())  ← Executor 미지정
  → ForkJoinPool.commonPool() 사용
  → 전역 공유 풀 (CPU 코어 수 - 1 스레드)
  → I/O 바운드 작업 시 풀 고갈 위험

supplyAsync(() -> work(), batchExecutor)  ← 커스텀 Executor
  → 용도별 격리된 풀 사용
  → I/O 바운드에 맞는 풀 사이즈 설정 가능
```

---

## 9. 디자인 패턴

### 9-A. Strategy Pattern

| 항목 | 내용 |
|------|------|
| 파일 | `pattern/StrategyPatternExample.java` |

동일 인터페이스의 여러 구현체를 런타임에 교체하여 사용.
Spring DI로 `List<Interface>`를 주입받아 `Map`으로 변환하면 if-else 없이 전략 선택 가능.

```
PaymentStrategy (인터페이스)
  ├─ CardPaymentStrategy
  ├─ BankTransferStrategy
  └─ MobilePaymentStrategy

PaymentService → Map<type, strategy>.get(type).pay()
```

### 9-B. Template Method Pattern

| 항목 | 내용 |
|------|------|
| 파일 | `pattern/TemplateMethodExample.java` |

알고리즘의 골격을 상위 클래스에서 정의하고, 구체적인 단계만 하위 클래스에서 구현.

```
AbstractExportService (template method: export())
  1. validate()    ← 공통
  2. fetchData()   ← 추상 (하위 구현)
  3. transform()   ← 추상 (하위 구현)
  4. write()       ← 추상 (하위 구현)
  5. cleanup()     ← hook (선택적 오버라이드)
```

### 9-C. Observer Pattern

| 항목 | 내용 |
|------|------|
| 파일 | `pattern/ObserverPatternExample.java` |

Spring의 `ApplicationEventPublisher` + `@EventListener`로 구현.
발행자와 리스너의 느슨한 결합.

| 리스너 종류 | 실행 시점 |
|-----------|----------|
| `@EventListener` | 즉시 (동기) |
| `@TransactionalEventListener(AFTER_COMMIT)` | TX 커밋 후 |
| `@Async @EventListener` | 별도 스레드 (비동기) |

### 9-D. Facade Pattern

| 항목 | 내용 |
|------|------|
| 파일 | `pattern/FacadePatternExample.java` |

복잡한 하위 시스템을 단일 진입점으로 단순화.
Spring의 Service 레이어가 자연스럽게 퍼사드 역할을 수행.

```
Controller → OrderFacade.placeOrder()
               ├─ checkStock()
               ├─ processPayment()
               ├─ saveOrder()
               └─ sendNotification()
```

### 9-E. Builder Pattern

| 항목 | 내용 |
|------|------|
| 파일 | `pattern/BuilderPatternExample.java` |

복잡한 객체를 단계별로 생성. Lombok `@Builder` 또는 수동 구현.

```java
// 텔레스코핑 생성자 (가독성 나쁨)
new Order(1L, "김철수", 50000, "CARD", null, null, null);

// Builder (가독성 좋음)
Order.builder()
    .userId(1L)
    .userName("김철수")
    .amount(50000)
    .build();
```

---

## 10. WebSocket (STOMP)

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.websocket` |
| 파일 | `WebSocketConfig.java`, `NotificationController.java` |
| 인프라 | `infra/nginx/websocket-proxy.conf` |
| 상세 | **[WEBSOCKET_GUIDE.md](WEBSOCKET_GUIDE.md)** |

클라이언트-서버 간 양방향 실시간 통신. STOMP 프로토콜로 Pub/Sub 메시징 제공.

| 주제 | 핵심 내용 |
|------|----------|
| STOMP 목적지 | `/app/*` (클라이언트→서버), `/topic/*` (1:N), `/queue/*` (1:1) |
| 메시지 전송 | @MessageMapping + @SendTo, SimpMessagingTemplate |
| SockJS | WebSocket 미지원 환경에서 XHR Streaming/Polling 폴백 |
| Nginx 프록시 | Upgrade/Connection 헤더 전달, proxy_read_timeout 설정 |

---

## 11. Circuit Breaker

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.circuitbreaker` |
| 파일 | `SimpleCircuitBreaker.java` |
| 상세 | **[CIRCUIT_BREAKER_GUIDE.md](CIRCUIT_BREAKER_GUIDE.md)** |

외부 서비스 호출 실패가 반복될 때 추가 호출을 차단하여 연쇄 장애를 방지하는 패턴.

| 주제 | 핵심 내용 |
|------|----------|
| 상태 전이 | CLOSED →(실패 5회)→ OPEN →(30초)→ HALF_OPEN →(성공)→ CLOSED |
| execute() | action(외부 호출) + fallback(대체값)을 Supplier로 수신 |
| 스레드 안전성 | AtomicReference(상태), AtomicInteger(카운터), volatile(시간) |
| 실무 | 수동 구현 학습용, 운영에서는 Resilience4j 권장 |

---

## 12. 배치 처리

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.batch` |
| 파일 | `SpringBatchConfig.java`, `JdbcBatchService.java`, `LockBasedBatchService.java`, `VersionedBatchService.java`, `PerformanceAwareBatchService.java` |
| 상세 | **[BATCH_GUIDE.md](BATCH_GUIDE.md)** |

대용량 데이터를 안정적으로 처리하기 위한 배치 기법 모음.

| 주제 | 핵심 내용 |
|------|----------|
| Spring Batch | Job → Step → Chunk(Reader/Processor/Writer) / Tasklet |
| JdbcTemplate 배치 | batchUpdate, 청크 분할, UPSERT — JPA 대비 10배+ 빠름 |
| 락 기반 배치 | AtomicBoolean + 분산 락(Redis/MySQL) 2계층 구조 |
| 버저닝 배치 | 낙관적 락(version), 비관적 락(FOR UPDATE SKIP LOCKED) |
| 성능 최적화 | Keyset Pagination, 병렬 파티셔닝, queryForStream |

---

## 13. REST API 응답

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.rest` |
| 파일 | `RestResponseExample.java` |

ResponseEntity를 활용한 HTTP 응답 제어, 페이징 처리, 표준 응답 래퍼(ApiResponse) 패턴.

| 패턴 | 설명 |
|------|------|
| `ResponseEntity.ok(body)` | 200 OK + 본문 |
| `ResponseEntity.created(uri)` | 201 Created + Location 헤더 |
| `ResponseEntity.noContent()` | 204 No Content (DELETE) |
| `ApiResponse<T>` | 통일된 응답 래퍼 (data / error) |
| `Page<T>` vs `Slice<T>` | COUNT 쿼리 포함 여부 차이 |
| 오프셋 기반 페이징 | `OFFSET + LIMIT` — 페이지 번호 탐색, 뒤쪽 페이지 느림 |
| 커서 기반 페이징 | `WHERE id < :cursor LIMIT n` — 성능 일정, 무한 스크롤에 적합 |
| `CursorPageResponse<T>` | 커서 기반 응답 DTO (content, nextCursor, hasNext) |

---

## 14. REST API 클라이언트

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.httpclient` |
| 파일 | `HttpClientConfig.java`, `RestTemplateExample.java`, `RestClientExample.java`, `FeignClientExample.java`, `JavaHttpClientExample.java` |

### 호출 방식 비교 (버전별)

| 방식 | 도입 시기 | 의존성 | 방식 | 권장 용도 |
|------|----------|--------|------|----------|
| RestTemplate | Spring 3.0 (2009) | starter-web | 동기/메서드 | 레거시 유지보수 |
| WebClient | Spring 5.0 / Boot 2.0 (2017) | starter-webflux | 비동기/Fluent | 리액티브, 대량 호출 |
| RestClient | Spring 6.1 / Boot 3.2 (2023.11) | starter-web | 동기/Fluent | **신규 프로젝트 권장** |
| OpenFeign | Spring Cloud | cloud-openfeign | 선언적 인터페이스 | MSA 서비스 간 통신 |
| Java HttpClient | JDK 11+ (2018) | 없음 (JDK 내장) | 동기+비동기 | Spring 미사용 환경 |

---

## 15. Validation

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.validation` |
| 파일 | `ValidationExample.java` |
| 의존성 | `spring-boot-starter-validation` |

Bean Validation(JSR-380) 어노테이션, 커스텀 Validator, BindingResult 직접 처리.

| 어노테이션 | 용도 |
|-----------|------|
| `@NotBlank` | null, "", " " 불허 (문자열 전용) |
| `@Email` | 이메일 형식 |
| `@Pattern` | 정규식 기반 |
| `@Size(min, max)` | 길이/크기 제한 |
| `@Valid` | 중첩 객체 검증 트리거 |
| 커스텀 `@BusinessNumber` | ConstraintValidator 구현 |

---

## 16. Exception Handling

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.exception` |
| 파일 | `GlobalExceptionHandler.java`, `CustomException.java`, `ErrorResponse.java`, `ExceptionAopExample.java`, `CheckedUncheckedExample.java` |

### 예외처리 전략

| 방식 | 범위 | 용도 |
|------|------|------|
| `@RestControllerAdvice` (통합) | 모든 컨트롤러 | 표준 에러 응답 |
| `@RestControllerAdvice(basePackages)` | 특정 패키지 | 모듈별 분리 |
| 컨트롤러 내부 `@ExceptionHandler` | 해당 컨트롤러만 | 개별 오버라이드 |
| AOP `@AfterThrowing` | 모든 Bean | 예외 로깅/모니터링 |
| AOP `@Around` | 모든 Bean | 예외 변환/재시도 |

### 16-B. Checked vs Unchecked Exception

| 항목 | Checked Exception | Unchecked Exception |
|------|------------------|-------------------|
| 상속 대상 | `Exception` | `RuntimeException` |
| 컴파일러 강제 | O (try-catch/throws 필수) | X (선택적) |
| 발생 원인 | 외부 환경 문제 (파일, 네트워크) | 프로그래밍 실수, 논리 오류 |
| `@Transactional` 롤백 | 기본 롤백 안 됨 | 기본 롤백됨 |
| 대표 예시 | `IOException`, `ParseException` | `NullPointerException`, `IllegalArgumentException` |

#### @Transactional 롤백 규칙

```
RuntimeException (Unchecked) → 자동 롤백
Exception (Checked)          → 롤백 안 됨 (커밋됨!)
@Transactional(rollbackFor = Exception.class) → Checked도 롤백
```

#### Checked → Unchecked 래핑 패턴 (실무 권장)

```java
try {
    Files.readAllLines(path);
} catch (IOException e) {
    throw new RuntimeException("파일 처리 실패", e);  // cause 보존 필수
}
```

---

## 17. JPA Entity 심화

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.jpa` |
| 파일 | `BaseEntity.java`, `JpaEntityExample.java`, `JpaAuditConfig.java` |

| 패턴 | 설명 |
|------|------|
| `@MappedSuperclass` | 공통 필드(audit, version) 상속 |
| `@EnableJpaAuditing` | @CreatedDate, @LastModifiedDate 자동 |
| `@Embeddable` / `@Embedded` | 값 객체 (주소, 좌표 등) |
| Soft Delete | `@SQLDelete` + `@SQLRestriction` |
| `@FieldDefaults(level = PRIVATE)` | Lombok — 모든 필드 접근 제어자 일괄 지정 (private 누락 방지) |
| `@DynamicInsert` / `@DynamicUpdate` | 변경된 컬럼만 SQL에 포함 |
| `@BatchSize` | N+1 방지 (IN절 배치) |
| `@Version` | 낙관적 락 |
| ID 기반 참조 vs 객체 참조 | MSA에서는 ID 기반 권장 |

---

## 18. JPA Repository + QueryDSL

| 항목 | 내용 |
|------|------|
| 파일 | `JpaRepositoryExample.java`, `QueryDslExample.java` |

### 쿼리 생성 방식

| 방식 | 특징 |
|------|------|
| 메서드 이름 기반 | `findByEmailAndStatus()` → 자동 쿼리 |
| `@Query` (JPQL) | 엔티티 기반, DB 독립적 |
| `@Query` (Native) | SQL 직접 사용 (`nativeQuery = true`) |
| QueryDSL | 타입 안전 동적 쿼리 (컴파일 타임 체크) |

### QueryDSL 상세

| 기능 | 설명 |
|------|------|
| `JPAQueryFactory` | selectFrom, select, update, delete + fetch/fetchOne/fetchFirst |
| `BooleanExpression` | null 반환 시 조건 무시 패턴 (동적 WHERE 조립) |
| `BooleanBuilder` | 조건을 순차적으로 and/or 추가 |
| `Projections` | constructor / bean / fields — DTO 직접 조회 |
| `JPAExpressions` (서브쿼리) | WHERE절 (avg, IN, EXISTS), SELECT절 (스칼라 서브쿼리) |
| `ExpressionUtils` | 서브쿼리 별칭(`as`), `allOf`/`anyOf` 조건 조합 |
| `Expressions` | 상수(`constant`), `stringTemplate` (DB 함수 호출) |
| `CaseBuilder` | CASE WHEN ... THEN ... ELSE ... END |
| `NumberExpression` | sum, avg, multiply, divide 등 숫자 연산 |
| `StringExpression` | concat, lower, trim, substring 등 문자열 연산 |

### SubQuery 예시 (JPAExpressions)

```java
// [1] WHERE절 — 평균 금액 이상 주문 조회
queryFactory.selectFrom(order)
    .where(order.totalAmount.goe(
        JPAExpressions
            .select(order.totalAmount.avg())
            .from(order)
    )).fetch();

// [2] WHERE절 — IN (활성 사용자의 주문만)
queryFactory.selectFrom(order)
    .where(order.user.id.in(
        JPAExpressions
            .select(user.id)
            .from(user)
            .where(user.status.eq(UserStatus.ACTIVE))
    )).fetch();

// [3] WHERE절 — EXISTS (주문이 있는 사용자)
queryFactory.selectFrom(user)
    .where(JPAExpressions
        .selectOne()
        .from(order)
        .where(order.user.id.eq(user.id))
        .exists()
    ).fetch();

// [4] SELECT절 — 스칼라 서브쿼리 (사용자별 주문 수)
queryFactory.select(
        user.name,
        ExpressionUtils.as(
            JPAExpressions
                .select(order.count())
                .from(order)
                .where(order.user.id.eq(user.id)),
            "orderCount"
        )
    ).from(user).fetch();
```

> FROM절 서브쿼리는 JPA 표준에서 미지원 → Native Query로 대체

### Page vs Slice

| 타입 | COUNT 쿼리 | 용도 |
|------|-----------|------|
| `Page<T>` | 실행 | 전체 페이지 수 표시 |
| `Slice<T>` | 미실행 | "더보기" 버튼 |

---

## 19. Spring Security

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.security` |
| 파일 | `SecurityConfigExample.java`, `JwtTokenProvider.java`, `JwtAuthenticationFilter.java`, `RedisSessionConfig.java` |

### 세션 기반 vs JWT 토큰 기반

| 항목 | 세션 기반 | JWT 토큰 기반 |
|------|----------|-------------|
| 상태 | Stateful (서버 저장) | Stateless |
| 확장성 | Redis 세션 공유 필요 | 서버 무관 |
| 로그아웃 | 서버에서 즉시 삭제 | 블랙리스트 필요 |
| 적합 | SSR, 모놀리식 | SPA, MSA, 모바일 |

### JWT 인증 흐름

```
로그인 → JWT 생성 → 클라이언트 저장
API 호출 → Authorization: Bearer {token}
→ JwtAuthenticationFilter → 토큰 검증 → SecurityContext 저장 → Controller
```

### Spring Session + Redis

Redis에 세션을 저장하여 분산 환경에서 세션 공유. `@EnableRedisHttpSession`으로 활성화.

---

## 20. AOP + Interceptor

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.aop` |
| 파일 | `AopExample.java` |

| 방식 | 영역 | 실행 순서 |
|------|------|----------|
| Filter | 서블릿 | 가장 먼저 |
| Interceptor | Spring MVC | 컨트롤러 전후 |
| AOP | 모든 Bean | 메서드 호출 시 |

### AOP Advice 종류

| 어노테이션 | 시점 |
|-----------|------|
| `@Before` | 메서드 실행 전 |
| `@AfterReturning` | 정상 완료 후 |
| `@AfterThrowing` | 예외 발생 시 |
| `@Around` | 전후 모두 (가장 강력) |

---

## 21. Configuration 관리

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.config` |
| 파일 | `AppProperties.java` |

| 방식 | 적합한 경우 |
|------|-----------|
| `@Value` | 단순 값 1~2개 |
| `@ConfigurationProperties` | 관련 설정 그룹 (3개+) |
| `@Profile` | 환경별 Bean 분리 (dev/prod) |

---

## 22. Java Core

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.javacore` |
| 파일 | `OptionalExample.java`, `StreamApiExample.java`, `EnumExample.java`, `FunctionalInterfaceExample.java`, `MemoryLeakExample.java` |

| 주제 | 핵심 내용 |
|------|----------|
| Optional | `orElseThrow()`, `map()`, `flatMap()`, orElse vs orElseGet 차이 |
| Stream API | filter, map, collect(groupingBy), reduce, flatMap |
| Enum | 코드+한글 매핑, 상태 전이, 전략 패턴, 그룹핑 |
| Functional Interface | Function, Consumer, Predicate, Supplier, 메서드 레퍼런스 |
| Memory Leak | static 컬렉션, 리소스 미반환, ThreadLocal, JPA 영속성 컨텍스트 |

### 22-A. 메모리 릭 (Memory Leak)

| 항목 | 내용 |
|------|------|
| 파일 | `MemoryLeakExample.java` |
| 상세 | **[MEMORY_LEAK_GUIDE.md](MEMORY_LEAK_GUIDE.md)** |

더 이상 사용하지 않는 객체가 GC에 의해 회수되지 못하고 힙 메모리에 계속 남아있는 현상.

| # | 패턴 | 해결 |
|---|------|------|
| 1 | static 컬렉션 무한 추가 | 크기 제한, WeakHashMap, Caffeine |
| 2 | 리소스 미반환 (Stream, Connection) | try-with-resources |
| 3 | Map 제거 누락 | 생명주기에 맞춰 remove |
| 4 | 리스너 해제 누락 | @EventListener 또는 수동 해제 |
| 5 | 내부 클래스 외부 참조 | static 내부 클래스 사용 |
| 6 | ThreadLocal 미정리 | finally에서 remove() |
| 7 | JPA 영속성 컨텍스트 비대화 | 청크 단위 clear(), JdbcTemplate |
| 8 | String 반복 연결 | StringBuilder |

---

## 23. Lock 개념

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.lock` |
| 파일 | `LockConceptExample.java` |

### 락 종류별 비교

| 종류 | 범위 | 예시 |
|------|------|------|
| 애플리케이션 락 | 단일 JVM | synchronized, ReentrantLock, AtomicInteger |
| DB 비관적 락 | DB 행/테이블 | SELECT FOR UPDATE |
| DB 낙관적 락 | DB 행 | @Version |
| Advisory Lock | DB (논리적) | MySQL GET_LOCK |
| 분산 락 | 서버 간 | Redis RLock (Redisson) |
| 트랜잭션 격리 | DB 전체 | READ_COMMITTED ~ SERIALIZABLE |

### 트랜잭션 격리 수준

| 수준 | Dirty Read | Non-Repeatable Read | Phantom Read |
|------|-----------|-------------------|-------------|
| READ_COMMITTED | 방지 | 허용 | 허용 |
| REPEATABLE_READ | 방지 | 방지 | 허용 |
| SERIALIZABLE | 방지 | 방지 | 방지 |

---

## 24. Logging

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.logging` |
| 파일 | `LoggingExample.java` |

`@Slf4j` + `{}` 플레이스홀더, MDC(Mapped Diagnostic Context)로 요청 추적, 레벨별 사용 기준, 민감 정보 마스킹.

---

## 25. Swagger / OpenAPI

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.swagger` |
| 파일 | `SwaggerConfig.java` |
| 의존성 | `springdoc-openapi-starter-webmvc-ui:2.6.0` |
| URL | `http://localhost:8080/swagger-ui/index.html` |

`@Operation`, `@ApiResponse`, `@Parameter`, `@Schema` 어노테이션으로 API 문서 자동 생성.

---

## 26. 파일 업로드

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.file` |
| 파일 | `FileUploadExample.java` |

MultipartFile 단일/다중 업로드, 파일+JSON 동시 수신, 확장자/크기 검증, UUID 파일명.

---

## 27. MapStruct

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.mapper` |
| 파일 | `MapStructExample.java` |
| 의존성 | `mapstruct:1.6.3` + `lombok-mapstruct-binding:0.2.0` |

컴파일 타임 DTO↔Entity 매핑 코드 자동 생성. `@Mapping`으로 필드명 매핑, `@MappingTarget`으로 기존 Entity 업데이트.

---

## 28. Nginx 설정

| 항목 | 내용 |
|------|------|
| 디렉토리 | `infra/nginx/` |
| 파일 | `nginx-reference.conf`, `upstream-loadbalancing.conf`, `ssl-termination.conf`, `security-headers.conf`, `websocket-proxy.conf` |
| 상세 | **[NGINX_GUIDE.md](NGINX_GUIDE.md)** |

Spring Boot 앱 앞단의 Nginx 인프라 설정 레퍼런스.

| 주제 | 핵심 내용 |
|------|----------|
| 리버스 프록시 | Host, X-Real-IP, X-Forwarded-For 헤더 전달 |
| 로드밸런싱 | round-robin, least_conn, ip_hash, hash, random |
| SSL/TLS 종료 | TLS 1.2/1.3, Let's Encrypt, HSTS, OCSP 스테이플링 |
| 보안 헤더 | CSP, X-Frame-Options, CORS, Rate Limiting |
| WebSocket 프록시 | Upgrade/Connection 헤더, proxy_read_timeout, SockJS 폴백 |
| 배포 패턴 | Blue-Green (전체 전환), Canary (weight 기반 점진적) |

---

## 프로젝트 구조

```
spring-tech-examples/
├── build.gradle
├── settings.gradle
├── TECH_REFERENCE.md                            ← 이 파일
├── infra/nginx/
│   ├── nginx-reference.conf                     ← 리버스 프록시, 정적 파일, Gzip, 타임아웃
│   ├── upstream-loadbalancing.conf              ← upstream, 로드밸런싱 전략 5종, 헬스체크
│   ├── ssl-termination.conf                     ← SSL/TLS 종료, 인증서, HSTS
│   ├── security-headers.conf                    ← 보안 헤더, CORS, Rate Limiting, IP 제한
│   └── websocket-proxy.conf                     ← WebSocket Upgrade, STOMP 프록시
├── src/main/resources/application.yml
└── src/main/java/kr/co/example/
    ├── netty/
    │   ├── NettyWebClientConfig.java            ← 커넥션 풀, SSL, 타임아웃 설정
    │   └── ExternalApiService.java              ← Mono/Flux, retry, 동시성 제어
    ├── kafka/
    │   ├── KafkaConfig.java                     ← DLT, DefaultErrorHandler, 재시도
    │   ├── EventTopic.java                      ← 토픽 상수
    │   ├── EventProducer.java                   ← 비동기/동기 메시지 발행
    │   ├── EventConsumer.java                   ← @KafkaListener, 멱등성
    │   └── OutboxService.java                   ← Transactional Outbox Pattern
    ├── redis/
    │   ├── RedisConfig.java                     ← Lettuce, StringRedisTemplate, Redisson
    │   ├── RedisStockService.java               ← Hash 재고, @Cacheable, 분산 락
    │   ├── RedisCacheStrategyService.java        ← 캐시 전략 5종 (Cache-Aside, Write-Through 등)
    │   ├── RedisDataStructureService.java        ← String/Hash/Set 활용, Lua Script, ZPOPMIN+INCRBY
    │   └── ProbabilisticEarlyRecomputationService.java ← PER 알고리즘, DB 폴백, Redis Circuit Breaker
    ├── cache/
    │   ├── LocalCacheConfig.java                ← Caffeine CacheManager, 퇴거 정책
    │   └── LocalCacheService.java               ← Manual/Loading/@Cacheable, 멀티 레벨, 워밍
    ├── batch/
    │   ├── SpringBatchConfig.java               ← Job/Step/Chunk/Tasklet
    │   ├── JdbcBatchService.java                ← batchUpdate, 청크 분할, UPSERT
    │   ├── LockBasedBatchService.java           ← AtomicBoolean, MySQL GET_LOCK, Redisson
    │   ├── VersionedBatchService.java           ← 낙관적/비관적 락, CAS 상태 전이
    │   └── PerformanceAwareBatchService.java    ← 페이징, 병렬, 스트리밍, 메모리 모니터링
    ├── scheduling/
    │   ├── ScheduleConfig.java                  ← @EnableScheduling, TaskScheduler
    │   └── ScheduledTasks.java                  ← fixedRate, fixedDelay, cron
    ├── transaction/
    │   ├── TransactionExampleService.java       ← @Transactional, TransactionTemplate
    │   ├── PropagationExampleService.java       ← 전파 수준별 호출 예제
    │   └── PropagationTargetService.java        ← 전파 대상 메서드 (REQUIRED~NESTED)
    ├── thread/
    │   └── ThreadPoolConfig.java                ← 용도별 ThreadPoolTaskExecutor 4종
    ├── async/
    │   ├── AsyncConfig.java                     ← @EnableAsync, 예외 핸들러
    │   └── AsyncService.java                    ← @Async, CompletableFuture
    ├── parallel/
    │   ├── ParallelProcessingService.java       ← CompletableFuture.allOf, parallelStream
    │   └── CompletableFutureExample.java        ← 생성, 체이닝, 조합, 예외 처리 상세
    ├── pattern/
    │   ├── StrategyPatternExample.java          ← 전략 패턴 (Spring DI 활용)
    │   ├── TemplateMethodExample.java           ← 템플릿 메서드 패턴
    │   ├── ObserverPatternExample.java          ← 옵저버 패턴 (Spring Event)
    │   ├── FacadePatternExample.java            ← 퍼사드 패턴
    │   └── BuilderPatternExample.java           ← 빌더 패턴 (Lombok + 수동)
    ├── websocket/
    │   ├── WebSocketConfig.java                 ← STOMP, SockJS 설정
    │   └── NotificationController.java          ← @MessageMapping, 서버 push
    ├── circuitbreaker/
    │   └── SimpleCircuitBreaker.java            ← 상태 전이 수동 구현
    ├── rest/
    │   └── RestResponseExample.java             ← ResponseEntity, 페이징, ApiResponse 래퍼
    ├── httpclient/
    │   ├── HttpClientConfig.java                ← RestTemplate/RestClient/Feign Bean 설정
    │   ├── RestTemplateExample.java             ← Spring 3.0+, 동기, getForEntity/exchange
    │   ├── RestClientExample.java               ← Spring 6.1+/Boot 3.2+, Fluent API (권장)
    │   ├── FeignClientExample.java              ← Spring Cloud, 선언적 인터페이스, MSA
    │   └── JavaHttpClientExample.java           ← JDK 11+, 내장, HTTP/2, send/sendAsync
    ├── validation/
    │   └── ValidationExample.java               ← @Valid, @NotBlank, 커스텀 Validator
    ├── exception/
    │   ├── GlobalExceptionHandler.java          ← @RestControllerAdvice, 통합 예외처리
    │   ├── CustomException.java                 ← DomainException, 에러 코드 Enum
    │   ├── ErrorResponse.java                   ← 표준 에러 응답 DTO
    │   ├── ExceptionAopExample.java             ← AOP 예외 로깅/변환, 개별 예외처리
    │   └── CheckedUncheckedExample.java        ← Checked/Unchecked 비교, 래핑, 롤백 차이
    ├── jpa/
    │   ├── BaseEntity.java                      ← @MappedSuperclass, Auditing, @Version
    │   ├── JpaEntityExample.java                ← 관계 매핑, Soft Delete, @Embedded
    │   ├── JpaRepositoryExample.java            ← @Query, Page/Slice, 커스텀 Repository
    │   ├── QueryDslExample.java                 ← JPAQueryFactory, BooleanExpression, Projection
    │   └── JpaAuditConfig.java                  ← @EnableJpaAuditing, AuditorAware
    ├── security/
    │   ├── SecurityConfigExample.java           ← SecurityFilterChain, CORS, CSRF, 인증/인가
    │   ├── JwtTokenProvider.java                ← JWT 생성/검증/파싱 (JJWT 0.12.x)
    │   ├── JwtAuthenticationFilter.java         ← Bearer 토큰 필터, SecurityContext 설정
    │   └── RedisSessionConfig.java              ← Spring Session + Redis, 세션 공유
    ├── aop/
    │   └── AopExample.java                      ← @Aspect, @Around, HandlerInterceptor
    ├── config/
    │   └── AppProperties.java                   ← @ConfigurationProperties, @Value, @Profile
    ├── javacore/
    │   ├── OptionalExample.java                 ← orElseThrow, map, flatMap, 안티패턴
    │   ├── StreamApiExample.java                ← filter, map, collect, groupingBy, flatMap
    │   ├── EnumExample.java                     ← 상태 전이, 전략 패턴, 코드 테이블
    │   ├── FunctionalInterfaceExample.java      ← Function, Consumer, Predicate, Supplier
    │   └── MemoryLeakExample.java              ← 메모리 릭 발생 패턴 8종, 방지 방법
    ├── lock/
    │   └── LockConceptExample.java              ← synchronized, ReentrantLock, FOR UPDATE, 격리수준
    ├── logging/
    │   └── LoggingExample.java                  ← @Slf4j, MDC, 레벨별 사용, 민감정보 마스킹
    ├── swagger/
    │   └── SwaggerConfig.java                   ← OpenAPI 3.0, @Operation, JWT 스키마
    ├── file/
    │   └── FileUploadExample.java               ← MultipartFile, 단일/다중, 파일 검증
    └── mapper/
        └── MapStructExample.java                ← @Mapper, @Mapping, @MappingTarget, 수동 매핑
```
