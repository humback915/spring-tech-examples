# Redis + Redisson + 캐시 가이드

> 인메모리 키-값 데이터 스토어. 캐시, 재고 관리, 분산 락 등에 활용.

코드 예제: `src/main/java/kr/co/example/redis/`, `src/main/java/kr/co/example/cache/`

---

## 목차

1. [Template 비교](#1-template-비교)
2. [데이터 구조별 활용](#2-데이터-구조별-활용)
3. [캐싱 어노테이션](#3-캐싱-어노테이션)
4. [Redisson 분산 락](#4-redisson-분산-락)
5. [로컬 캐시 (Caffeine)](#5-로컬-캐시-caffeine)
6. [캐시 전략 5종](#6-캐시-전략-5종)
7. [데이터 구조 실전 활용](#7-데이터-구조-실전-활용)
8. [PER (Probabilistic Early Recomputation)](#8-per-probabilistic-early-recomputation)

---

## 1. Template 비교

| Template | Key/Value 타입 | 용도 |
|----------|---------------|------|
| `StringRedisTemplate` | String / String | 카운터, 단순 값 |
| `RedisTemplate<String, Object>` | String / JSON(Object) | 복잡한 객체 저장 |

## 2. 데이터 구조별 활용

| 구조 | 메서드 | 활용 예시 |
|------|--------|----------|
| String | `opsForValue()` | 카운터, 단순 캐시 |
| Hash | `opsForHash()` | 재고 필드별 관리 (total, cart, ordered) |
| List | `opsForList()` | 큐, 스택 |
| Set | `opsForSet()` | 중복 제거, 교집합 |
| Sorted Set | `opsForZSet()` | 리더보드, 대기열 |

## 3. 캐싱 어노테이션

| 어노테이션 | 동작 |
|-----------|------|
| `@Cacheable` | 캐시 히트 시 메서드 실행 안 함, 미스 시 실행 후 캐시 저장 |
| `@CacheEvict` | 캐시 제거 (데이터 변경 시 무효화) |
| `@CachePut` | 항상 실행 후 캐시 갱신 |

## 4. Redisson 분산 락

Cache Stampede(캐시 만료 시 동시 DB 조회) 방지용.

```
tryLock(waitTime=3초, leaseTime=5초)
  → 한 스레드만 DB 조회 → 캐시 저장
  → 나머지 스레드는 캐시에서 읽기
```

## 5. 로컬 캐시 (Caffeine)

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.cache` |
| 파일 | `LocalCacheConfig.java`, `LocalCacheService.java` |

JVM 힙 메모리에 데이터를 캐싱하는 방식. 네트워크 호출 없이 나노초 단위의 빠른 응답을 제공한다.
Caffeine은 Google Guava Cache의 후속 라이브러리로, Window TinyLfu 퇴거 정책으로 높은 히트율을 보장한다.

### 로컬 캐시 vs 리모트 캐시 (Redis)

| 항목 | 로컬 캐시 (Caffeine) | 리모트 캐시 (Redis) |
|------|---------------------|-------------------|
| 저장 위치 | JVM 힙 메모리 | 외부 Redis 서버 |
| 접근 속도 | ~ns (나노초) | ~ms (네트워크 왕복) |
| 다중 인스턴스 | 각 인스턴스별 독립 | 모든 인스턴스 공유 |
| 데이터 일관성 | 불일치 가능 | 항상 일관됨 |
| 장애 영향 | 앱 재시작 시 소실 | 앱과 독립적 유지 |
| 직렬화 | 불필요 (객체 참조) | 필요 (JSON 등) |

### 퇴거 정책 (Eviction Policy)

| 정책 | 설명 |
|------|------|
| `maximumSize` | 엔트리 수 초과 시 가장 덜 사용된 항목 퇴거 |
| `expireAfterWrite` | 쓰기 후 일정 시간 경과 시 만료 |
| `expireAfterAccess` | 마지막 접근 후 일정 시간 경과 시 만료 |
| `refreshAfterWrite` | 쓰기 후 일정 시간 지나면 백그라운드 갱신 |

### 3가지 사용 방식

| 방식 | 설명 |
|------|------|
| Spring Cache (`@Cacheable`) | 어노테이션 기반, 가장 간편 |
| Manual Cache (`Cache<K,V>`) | 직접 put/get/invalidate, 세밀한 제어 |
| LoadingCache | 캐시 미스 시 자동 로딩, Stampede 방지 내장 |

### 캐시 관련 주요 문제

| 문제 | 증상 | 해결 |
|------|------|------|
| Cache Stampede | 만료 시점에 다수 요청이 동시 DB 조회 | LoadingCache 또는 분산 락 |
| Cache Penetration | 존재하지 않는 데이터 반복 조회 | null 캐싱 (allowNullValues) |
| Cache Inconsistency | DB 갱신 후 캐시에 이전 값 남음 | @CacheEvict 또는 TTL 설정 |

### 멀티 레벨 캐시 (L1 + L2)

```
요청 → [L1: Caffeine] → 히트? → 반환 (~ns)
              ↓ 미스
       [L2: Redis]    → 히트? → L1에 저장 → 반환 (~ms)
              ↓ 미스
       [DB 조회]      → L2 저장 → L1 저장 → 반환
```

### 캐시 워밍 (Cache Warming)

앱 시작 직후 캐시가 비어있는 Cold Start 문제를 방지.
`@PostConstruct`에서 자주 사용되는 데이터를 미리 로딩하여 시작 직후부터 높은 히트율 유지.

## 6. 캐시 전략 5종

| 항목 | 내용 |
|------|------|
| 파일 | `RedisCacheStrategyService.java` |

캐시와 DB 간 데이터 동기화 방식에 따른 5가지 전략 패턴.

### 전략 비교

| 전략 | 읽기 성능 | 쓰기 성능 | 일관성 | 구현 복잡도 |
|------|----------|----------|--------|-----------|
| Cache-Aside | 높음 | 보통 | 캐시 미스 시 최신 | 낮음 |
| Read-Through | 높음 | 보통 | 캐시 계층이 자동 로딩 | 보통 |
| Write-Through | 높음 | 낮음 | 항상 일관 (동기 쓰기) | 보통 |
| Write-Behind | 높음 | 높음 | 지연 쓰기로 불일치 가능 | 높음 |
| Write-Around | 보통 | 높음 | 첫 읽기 시 캐시 미스 | 낮음 |

### 전략별 동작 흐름

```
[Cache-Aside]  읽기: App → Cache? → 미스 → DB → Cache 저장
               쓰기: App → DB 저장 → Cache 삭제

[Read-Through] 읽기: App → Cache? → 미스 → Cache가 DB 로딩 → 반환

[Write-Through] 쓰기: App → Cache 저장 → Cache가 DB 동기 저장

[Write-Behind]  쓰기: App → Cache 저장 → 버퍼 적재 → 비동기 DB 배치 쓰기

[Write-Around]  쓰기: App → DB 직접 저장 (Cache 무시)
                읽기: Cache 미스 시 DB → Cache 저장
```

### 선택 가이드

| 상황 | 권장 전략 |
|------|----------|
| 읽기 빈번, 쓰기 드묾 | Cache-Aside / Read-Through |
| 읽기/쓰기 모두 빈번 | Write-Behind |
| 데이터 일관성 중요 | Write-Through |
| 쓰기 후 즉시 읽기 드묾 | Write-Around |
| 범용 (가장 일반적) | Cache-Aside |

## 7. 데이터 구조 실전 활용

| 항목 | 내용 |
|------|------|
| 파일 | `RedisDataStructureService.java` |

String, Hash, Set 자료구조의 실전 활용법과 Lua Script를 이용한 원자적 연산 예제.

### Spring 메서드 → Redis 명령 매핑

| 구조 | Spring 메서드 | Redis 명령 | 활용 |
|------|-------------|-----------|------|
| String | `opsForValue().set()` | `SET` | 단순 캐시, 세션 |
| String | `opsForValue().increment()` | `INCR` | 카운터 |
| String | `opsForValue().setIfAbsent()` | `SETNX` | 간이 분산 락 |
| String | `opsForValue().multiGet()` | `MGET` | 일괄 조회 |
| Hash | `opsForHash().put()` | `HSET` | 객체 필드별 저장 |
| Hash | `opsForHash().entries()` | `HGETALL` | 전체 필드 조회 |
| Hash | `opsForHash().increment()` | `HINCRBY` | 필드별 카운터 |
| Set | `opsForSet().add()` | `SADD` | 태그, 관심사 |
| Set | `opsForSet().intersect()` | `SINTER` | 공통 관심사 |
| Set | `opsForSet().randomMember()` | `SRANDMEMBER` | 랜덤 추첨 |

### Lua Script 원자적 연산

여러 Redis 명령을 서버 측에서 하나의 원자적 단위로 실행. Race Condition 방지에 활용.

| 패턴 | 동작 | 반환 |
|------|------|------|
| 재고 차감 | GET → 비교 → DECRBY | 남은 수량 (-1: 부족) |
| Rate Limiter | GET → 비교 → INCR + EXPIRE | 1: 허용, 0: 거부 |

Spring에서는 `DefaultRedisScript<Long>`에 Lua 스크립트를 전달하고, `StringRedisTemplate.execute()`로 실행한다.
Java 21 text block(`"""`)으로 스크립트를 가독성 있게 작성할 수 있다.

### Lua ZPOPMIN + INCRBY (대기열 소비 + 카운터)

대기열(Sorted Set)에서 항목을 꺼내면서 처리 카운터를 원자적으로 갱신하는 패턴.

```
[Sorted Set: 대기열]         [String: 카운터]
│ score │ member     │       │ counter: 47     │
│   1   │ order:101  │  →    │ counter: 50 (+3)│
│   2   │ order:102  │       └─────────────────┘
│   3   │ order:103  │
└───────┴────────────┘
      ↑ 3개 ZPOPMIN
```

| 변형 | 동작 | 반환 |
|------|------|------|
| ZPOPMIN + INCRBY | 꺼내기 + 카운터 증가 | 꺼낸 멤버 목록 |
| ZPOPMIN + INCRBY + HSET | 꺼내기 + 카운터 + 이력 기록 | 꺼낸 개수 |

## 8. PER (Probabilistic Early Recomputation)

| 항목 | 내용 |
|------|------|
| 파일 | `ProbabilisticEarlyRecomputationService.java` |

Cache Stampede를 확률적으로 방지하는 조기 재계산 알고리즘.
2015년 논문 "Optimal Probabilistic Cache Stampede Prevention"에서 제안. XFetch로도 알려져 있다.

### 기존 TTL 방식의 문제

```
캐시 SET ────────── TTL 만료
                       │
                  ┌────┼────┐
               요청1  요청2  요청3  ← 동시 캐시 미스
                  │    │    │
                  DB   DB   DB       ← 중복 DB 조회 (Stampede)
```

### PER 알고리즘의 해결

TTL 만료 **전에** 확률적으로 캐시를 미리 갱신한다.

```
캐시 SET ─── 확률적 갱신 구간 ── TTL 만료
                │
             요청 A가 확률 판단 → 조기 갱신 (DB 1회)
                                   ← 다른 요청은 캐시 히트
```

### 핵심 수식

```
currentTime - (delta * beta * ln(random())) > expiry
```

| 변수 | 설명 |
|------|------|
| `delta` | 재계산 소요 시간 (DB 조회 시간) |
| `beta` | 튜닝 파라미터 (1.0 = 논문 최적값) |
| `ln(random())` | (0,1) 균등 분포의 자연 로그 (항상 음수) |
| `expiry` | 캐시 만료 시각 |

- 만료까지 남은 시간이 짧을수록 → 재계산 확률 증가
- delta(재계산 비용)가 클수록 → 더 일찍 재계산 시도
- beta가 클수록 → 재계산 빈도 증가

### PER vs 다른 Stampede 방지 기법

| 기법 | 추가 인프라 | 동시성 | 특징 |
|------|-----------|--------|------|
| 분산 락 (Redisson) | Redis | 1개 통과 | 락 경합, 대기 시간 |
| LoadingCache | 없음 | 1개 통과 | 단일 JVM 한정 |
| **PER** | **없음** | **확률적** | **락 없이 자연스러운 갱신** |
| TTL Jitter | 없음 | 분산 | 만료 시점만 분산 |

### 구현 변형

| 변형 | 키 수 | 특징 |
|------|-------|------|
| 기본 (메타데이터 분리) | 3개 | 값, 만료 시각, delta 각각 저장 |
| Compact (단일 키) | 1개 | `value|expiry|delta` 형태로 압축 |
| PER + TTL Jitter | 1개 | TTL에 ±20% 랜덤 편차 추가 |

### Redis SPOF 방지 — DB 폴백

Redis 장애 시 전체 서비스가 중단되지 않도록 DB 직접 조회로 자동 전환.

```
[정상]  요청 → Redis(PER) → 히트 → 반환
                             ↓ 미스
                            DB → Redis 저장 → 반환

[장애]  요청 → Redis → 예외!
                        ↓ catch
                       DB 직접 조회 → 반환 (Redis 저장 생략)
```

| 전략 | 설명 |
|------|------|
| try-catch 폴백 | Redis 예외 시 DB 직접 조회 |
| Circuit Breaker | 연속 실패 N회 → DB만 사용 (냉각 후 Redis 재시도) |
| best-effort 복구 | DB 조회 후 Redis 저장 시도 (실패 무시) |
| Redis Sentinel/Cluster | 인프라 레벨 HA (자동 페일오버) |

### Redis Circuit Breaker 상태 전이

```
CLOSED ──(연속 실패 5회)──→ OPEN
(Redis PER 정상)           (Redis 차단, DB만 사용)
  ↑                           │
  │                    30초 냉각
  │                           ↓
  └───(성공)──── HALF_OPEN ───(실패)──→ OPEN
                (Redis 시험 1회)
```
