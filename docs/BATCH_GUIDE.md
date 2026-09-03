# 배치 처리 가이드

> 대용량 데이터를 안정적으로 처리하기 위한 배치 기법 모음.

### 관련 소스 코드 (추천 순서)

| # | 파일 | 설명 |
|---|------|------|
| 1 | [SpringBatchConfig.java](../src/main/java/kr/co/example/batch/SpringBatchConfig.java) | Job/Step/Chunk/Tasklet 설정 — Spring Batch 프레임워크 기본 구조 |
| 2 | [JdbcBatchService.java](../src/main/java/kr/co/example/batch/JdbcBatchService.java) | batchUpdate, 청크 분할, UPSERT — JPA 대비 10배+ 빠른 배치 |
| 3 | [LockBasedBatchService.java](../src/main/java/kr/co/example/batch/LockBasedBatchService.java) | AtomicBoolean, MySQL GET_LOCK, Redisson — 중복 실행 방지 |
| 4 | [VersionedBatchService.java](../src/main/java/kr/co/example/batch/VersionedBatchService.java) | 낙관적/비관적 락, CAS 상태 전이 — 동시성 제어 |
| 5 | [PerformanceAwareBatchService.java](../src/main/java/kr/co/example/batch/PerformanceAwareBatchService.java) | 페이징, 병렬, 스트리밍, 메모리 모니터링 — 성능 최적화 |

---

## 목차

1. [application.yml 배치 설정](#1-applicationyml-배치-설정)
2. [Spring Batch (Job/Step/Chunk/Tasklet)](#2-spring-batch-jobstepchunktasklet)
3. [JdbcTemplate 배치](#3-jdbctemplate-배치)
4. [락 기반 배치](#4-락-기반-배치)
5. [버저닝 배치](#5-버저닝-배치)
6. [성능 고려 배치](#6-성능-고려-배치)

---

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.batch` |
| 파일 | `SpringBatchConfig.java`, `JdbcBatchService.java`, `LockBasedBatchService.java`, `VersionedBatchService.java`, `PerformanceAwareBatchService.java` |

대용량 데이터를 안정적으로 처리하기 위한 배치 기법 모음. Spring Batch 프레임워크, JdbcTemplate 배치, 분산 락, 낙관적/비관적 락, 성능 최적화를 다룬다.

## 1. application.yml 배치 설정

| 설정 | 값 | 설명 |
|------|----|------|
| `spring.batch.job.enabled` | `false` | 앱 시작 시 Job 자동 실행 비활성화 |
| `spring.batch.jdbc.initialize-schema` | `always` | 메타데이터 테이블 자동 생성 |
| `hibernate.jdbc.batch_size` | `100` | Hibernate JDBC 배치 크기 |
| `hibernate.order_inserts` | `true` | INSERT 정렬하여 배치 효율 향상 |
| `hibernate.jdbc.batch_versioned_data` | `true` | @Version 엔티티도 배치 가능 |

## 2. Spring Batch (Job/Step/Chunk/Tasklet)

| 항목 | 내용 |
|------|------|
| 파일 | `SpringBatchConfig.java` |

Spring Batch 프레임워크의 Job → Step → Chunk/Tasklet 구조.

### Tasklet vs Chunk

| 항목 | Tasklet | Chunk |
|------|---------|-------|
| 구조 | 단일 execute() | Reader → Processor → Writer |
| 트랜잭션 | execute() 전체 1TX | chunk 단위 커밋 |
| 적합한 작업 | 테이블 초기화, 파일 삭제 | 대량 데이터 변환/이관 |
| 재시작 | 처음부터 | 마지막 커밋 지점부터 |

### Chunk 처리 흐름

```
Reader → 1건 읽기 × N회
  ↓
Processor → 1건씩 변환 × N회
  ↓
Writer → N건 일괄 쓰기 (1회)
  ↓
TX COMMIT → Reader가 null 반환할 때까지 반복
```

### Reader 구현체

| 구현체 | 메모리 | 커넥션 | 대용량 적합도 |
|--------|--------|--------|-------------|
| JdbcCursorItemReader | 1건씩 (효율적) | Step 동안 유지 | 적합 |
| JdbcPagingItemReader | 페이지 크기 | 짧게 사용 | OFFSET 커지면 느림 |
| ListItemReader | 전체 로딩 | 불필요 | 부적합 |

## 3. JdbcTemplate 배치

| 항목 | 내용 |
|------|------|
| 파일 | `JdbcBatchService.java` |

JPA 영속성 컨텍스트를 우회하여 대량 데이터를 빠르게 처리.

### JPA vs JdbcTemplate 배치 성능 (10만 건 INSERT)

| 방식 | 소요 시간 | 메모리 |
|------|----------|--------|
| JPA saveAll() (배치 X) | ~60초 | 높음 |
| JPA saveAll() (배치 O) | ~20초 | 높음 |
| JdbcTemplate batchUpdate | ~5초 | 낮음 |
| JDBC executeBatch() | ~3초 | 매우 낮음 |

### 주요 패턴

| 패턴 | 설명 |
|------|------|
| batchUpdate (전체) | 리스트 전체를 1회 executeBatch |
| 청크 단위 배치 | N건씩 분할 처리 (메모리 제어) |
| 실패 격리 배치 | 레코드별 독립 처리, 1건 실패해도 계속 |
| UPSERT 배치 | ON DUPLICATE KEY UPDATE |

## 4. 락 기반 배치

| 항목 | 내용 |
|------|------|
| 파일 | `LockBasedBatchService.java` |

다중 서버 환경에서 배치 중복 실행을 방지하는 분산 락 전략.

### 3가지 락 전략

| 전략 | 범위 | 인프라 | 적합 환경 |
|------|------|--------|----------|
| AtomicBoolean | 단일 JVM | 없음 | 단일 서버 |
| MySQL GET_LOCK | 같은 DB | MySQL | DB 기반 멀티 서버 |
| Redisson RLock | 전체 | Redis | 대규모 클러스터 |

### 권장: 2계층 락 구조

```
1계층: AtomicBoolean (같은 JVM, 비용 제로)
  ↓ 통과
2계층: 분산 락 (Redis/MySQL, 네트워크 1회)
  ↓ 획득 성공
배치 실행
```

## 5. 버저닝 배치

| 항목 | 내용 |
|------|------|
| 파일 | `VersionedBatchService.java` |

배치와 실시간 API가 같은 데이터를 수정할 때의 동시성 제어.

### 낙관적 락 vs 비관적 락

| 항목 | 낙관적 락 | 비관적 락 |
|------|----------|----------|
| 방식 | UPDATE 시 version 체크 | SELECT FOR UPDATE |
| 락 점유 | 없음 | TX 종료까지 |
| 충돌 처리 | 재시도/건너뛰기 | 대기/타임아웃 |
| 데드락 | 없음 | 발생 가능 |
| 배치 적합도 | 적합 | 소량만 적합 |

### 주요 패턴

| 패턴 | 설명 |
|------|------|
| version 체크 UPDATE | WHERE version=? (충돌 시 affected=0) |
| 충돌 시 재시도 | 최신 version 재조회 후 UPDATE |
| SELECT FOR UPDATE SKIP LOCKED | 잠긴 행 건너뛰기 (워커 분담) |
| CAS 상태 전이 | WHERE status='PENDING' (version 없이) |

## 6. 성능 고려 배치

| 항목 | 내용 |
|------|------|
| 파일 | `PerformanceAwareBatchService.java` |

메모리, CPU, I/O 병목을 고려한 배치 최적화 기법.

### 배치 성능 병목 3대 요소

| 요소 | 원인 | 해결 |
|------|------|------|
| 메모리 | 대량 데이터 일괄 로딩, 영속성 컨텍스트 | 페이징, 스트리밍, JdbcTemplate |
| CPU | 더티 체킹, 복잡한 변환 로직 | 병렬 처리, JdbcTemplate |
| I/O | 건별 INSERT, N+1 쿼리 | 배치 INSERT, JOIN FETCH |

### 주요 패턴

| 패턴 | 메모리 | 속도 | 설명 |
|------|--------|------|------|
| Keyset Pagination | 일정 | 빠름 | OFFSET 없이 id 기반 페이징 |
| 병렬 파티셔닝 | 낮음 | 매우 빠름 | ID 범위 분할 → 스레드별 독립 처리 |
| queryForStream | 최소 | 보통 | 1건씩 스트리밍 (대용량) |
| 메모리 모니터링 | - | - | Runtime.getRuntime() 사용량 추적 |

### OFFSET vs Keyset Pagination

```
OFFSET:
  page 1: LIMIT 1000 OFFSET 0      → 빠름
  page 100: LIMIT 1000 OFFSET 99000 → 느림 (9.9만 건 스캔)

Keyset:
  page 1: WHERE id > 0 LIMIT 1000           → 빠름
  page 100: WHERE id > 99000 LIMIT 1000     → 똑같이 빠름 (인덱스 직접 접근)
```
