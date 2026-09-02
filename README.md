# Spring Tech Examples

> 주니어 백엔드 개발자를 위한 Spring Boot 기술 레퍼런스 예제 프로젝트

## 개요

실무에서 자주 사용하는 Spring Boot 기술 스택을 **69개 Java 파일 + 5개 Nginx 설정**, **29개 주제**로 정리한 예제 프로젝트입니다.
모든 코드에 한글 주석과 ASCII 다이어그램을 포함하여 개념 이해에 집중했습니다.

## 기술 스택

| 항목 | 버전 |
|------|------|
| Java | 21 |
| Spring Boot | 3.3.11 |
| Spring Cloud | 2023.0.5 |
| QueryDSL | 5.1.0 |
| MapStruct | 1.6.3 |
| JJWT | 0.12.6 |
| Redisson | 3.35.0 |
| springdoc-openapi | 2.6.0 |

## 목차

| # | 주제 | 패키지 | 주요 내용 |
|---|------|--------|----------|
| 1 | Reactor Netty + WebClient | `netty` | 커넥션 풀, SSL, Mono/Flux, retry, 동시성 제어 |
| 2 | Spring Kafka | `kafka` | Producer/Consumer, DLT, Outbox Pattern, 멱등성 |
| 3 | Redis + Redisson | `redis` | 자료구조, @Cacheable, 분산 락, TTL, 캐시 전략 5종, Lua Script, ZPOPMIN+INCRBY |
| 3-B | 로컬 캐시 (Caffeine) | `cache` | Manual/Loading Cache, 멀티 레벨, 캐시 워밍 |
| 3-E | PER (Probabilistic Early Recomputation) | `redis` | 확률적 조기 재계산, DB 폴백, Redis Circuit Breaker |
| 4 | 스케줄링 | `scheduling` | fixedRate, fixedDelay, cron, TaskScheduler |
| 5 | 트랜잭션 관리 | `transaction` | @Transactional, TransactionTemplate, 전파 수준 (REQUIRED~NESTED) |
| 6 | 스레드 풀 | `thread` | ThreadPoolTaskExecutor, 용도별 분리, 거부 정책 |
| 7 | 비동기 처리 | `async` | @EnableAsync, @Async, CompletableFuture, 예외 핸들러 |
| 8 | 병렬 처리 | `parallel` | CompletableFuture.allOf, parallelStream, 체이닝/조합 |
| 9 | 디자인 패턴 | `pattern` | Strategy, Template Method, Observer, Facade, Builder |
| 10 | WebSocket | `websocket` | STOMP, SockJS, @MessageMapping, 서버 Push |
| 11 | Circuit Breaker | `circuitbreaker` | 상태 전이 (CLOSED → OPEN → HALF_OPEN) |
| 12 | 배치 처리 | `batch` | Spring Batch, JDBC Batch, 락/버저닝/성능 최적화 |
| 13 | REST API 응답 | `rest` | ResponseEntity, 페이징, ApiResponse 래퍼 |
| 14 | REST API 클라이언트 | `httpclient` | RestTemplate, RestClient, OpenFeign, Java HttpClient |
| 15 | Validation | `validation` | @Valid, Bean Validation, 커스텀 Validator |
| 16 | Exception Handling | `exception` | @RestControllerAdvice, DomainException, AOP 예외 변환, Checked/Unchecked |
| 17 | JPA Entity 심화 | `jpa` | BaseEntity, Soft Delete, @Embedded, @FieldDefaults |
| 18 | JPA Repository + QueryDSL | `jpa` | @Query, Page/Slice, BooleanExpression 동적 쿼리 |
| 19 | Spring Security | `security` | SecurityFilterChain, JWT, Session + Redis |
| 20 | AOP + Interceptor | `aop` | @Aspect, @Around, HandlerInterceptor |
| 21 | Configuration 관리 | `config` | @ConfigurationProperties, @Value, @Profile |
| 22 | Java Core | `javacore` | Optional, Stream API, Enum, 함수형 인터페이스 |
| 23 | Lock 개념 | `lock` | synchronized, ReentrantLock, 비관적/낙관적 락, 격리수준 |
| 24 | Logging | `logging` | @Slf4j, MDC, 레벨별 사용, 민감정보 마스킹 |
| 25 | Swagger / OpenAPI | `swagger` | OpenAPI 3.0, @Operation, JWT SecurityScheme |
| 26 | 파일 업로드 | `file` | MultipartFile, 단일/다중, 파일 검증 |
| 27 | MapStruct | `mapper` | @Mapper, @Mapping, @MappingTarget |
| 28 | Nginx 설정 | `infra/nginx` | 리버스 프록시, 로드밸런싱, SSL 종료, 보안 헤더, WebSocket 프록시 |

> 각 주제의 상세 설명은 [TECH_REFERENCE.md](TECH_REFERENCE.md)를 참고하세요.

## 프로젝트 구조

```
infra/nginx/
├── nginx-reference.conf        ← 리버스 프록시, 정적 파일, Gzip, 타임아웃
├── upstream-loadbalancing.conf  ← upstream, 로드밸런싱 전략 5종, 헬스체크
├── ssl-termination.conf         ← SSL/TLS 종료, 인증서, HSTS
├── security-headers.conf        ← 보안 헤더, CORS, Rate Limiting, IP 제한
└── websocket-proxy.conf         ← WebSocket Upgrade, STOMP 프록시

src/main/java/kr/co/example/
├── netty/              ← Reactor Netty + WebClient
├── kafka/              ← Spring Kafka (Producer/Consumer/Outbox)
├── redis/              ← Redis + Redisson 분산 락, 캐시 전략, 데이터 구조, PER, DB 폴백
├── cache/              ← Caffeine 로컬 캐시
├── batch/              ← Spring Batch + JDBC Batch
├── scheduling/         ← @Scheduled, cron
├── transaction/        ← @Transactional, 전파 수준
├── thread/             ← ThreadPoolTaskExecutor
├── async/              ← @Async, CompletableFuture
├── parallel/           ← 병렬 처리, CompletableFuture 상세
├── pattern/            ← 디자인 패턴 5종
├── websocket/          ← WebSocket STOMP
├── circuitbreaker/     ← Circuit Breaker
├── rest/               ← REST API 응답 패턴
├── httpclient/         ← REST 클라이언트 (4가지 방식)
├── validation/         ← Bean Validation
├── exception/          ← 예외 처리 (통합/개별/AOP/Checked·Unchecked)
├── jpa/                ← JPA Entity + Repository + QueryDSL
├── security/           ← Spring Security + JWT + Session
├── aop/                ← AOP + Interceptor
├── config/             ← @ConfigurationProperties
├── javacore/           ← Optional, Stream, Enum, Functional
├── lock/               ← 락 개념 (App/DB/Transaction)
├── logging/            ← 로깅 패턴
├── swagger/            ← Swagger/OpenAPI
├── file/               ← 파일 업로드
└── mapper/             ← MapStruct DTO 매핑
```

## 실행 방법

```bash
# 빌드
./gradlew build

# 실행 (H2 인메모리 DB 사용, 외부 인프라 없이 빌드 가능)
./gradlew bootRun
```

> Redis, Kafka 등 외부 인프라가 필요한 기능은 해당 서비스 실행 후 사용할 수 있습니다.
> 이 프로젝트는 **코드 레퍼런스** 목적이므로 빌드 확인만으로도 충분합니다.

## 환경 변수 (프로덕션)

예제 코드에 포함된 기본값은 개발용입니다. 프로덕션 환경에서는 아래 값을 환경 변수로 설정하세요.

| 변수 | 설명 |
|------|------|
| `jwt.secret` | JWT 서명 키 (Base64 인코딩, 256bit 이상) |
| `spring.data.redis.host` | Redis 서버 주소 |
| `spring.kafka.bootstrap-servers` | Kafka 브로커 주소 |

## 라이선스

이 프로젝트는 학습 및 레퍼런스 목적으로 자유롭게 사용할 수 있습니다.
