package kr.co.example.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import jakarta.annotation.PostConstruct;
import java.time.Duration;

/**
 * ========================================================================
 * [3] Redis 설정 - 캐싱 & 분산 상태 관리
 * ========================================================================
 *
 * ── 핵심 개념 ──
 *
 * 1. Redis란?
 *    - 인메모리 키-값 데이터 스토어
 *    - 캐시, 세션 저장, 메시지 큐, 분산 락 등 다양한 용도
 *    - 단일 스레드 모델 → 원자적 연산 보장
 *    - 데이터 구조: String, Hash, List, Set, Sorted Set
 *
 * 2. Spring Data Redis의 두 가지 Template
 *    ┌────────────────────┬──────────────────────────────────┐
 *    │ StringRedisTemplate │ key/value 모두 String           │
 *    │                     │ 카운터, 간단한 값 저장에 적합    │
 *    ├────────────────────┼──────────────────────────────────┤
 *    │ RedisTemplate       │ key=String, value=Object(JSON)  │
 *    │                     │ 복잡한 객체 저장/조회에 적합     │
 *    └────────────────────┴──────────────────────────────────┘
 *
 * 3. Lettuce vs Jedis
 *    - Lettuce (기본): 논블로킹, Netty 기반, 스레드 세이프
 *    - Jedis: 블로킹, 커넥션 풀 필수
 *    - Spring Boot 2.x부터 Lettuce가 기본 클라이언트
 *
 * 4. Redisson
 *    - Redis 기반 분산 자료구조 라이브러리
 *    - 분산 락(RLock), 분산 맵, 분산 큐 등 제공
 *    - Cache Stampede 방지에 활용
 *
 * 5. @EnableCaching
 *    - Spring Cache Abstraction 활성화
 *    - @Cacheable, @CacheEvict, @CachePut 어노테이션 사용 가능
 *    - Redis를 캐시 저장소로 사용
 *
 * ── 사용 특성 ──
 *
 * 장점:
 * - 초고속 읽기/쓰기 (마이크로초 단위)
 * - 다양한 데이터 구조로 유연한 모델링
 * - TTL(만료 시간) 설정으로 자동 정리
 * - Pub/Sub, Stream 등 메시징 기능
 *
 * 주의점:
 * - 메모리 용량 제한 (비용)
 * - 데이터 영속성 보장 안 됨 (RDB/AOF 설정 필요)
 * - 장애 시 캐시 미스 → DB 부하 급증 (Cache Stampede)
 * - 직렬화/역직렬화 전략 통일 필요
 */
@Slf4j
@Configuration
@EnableCaching  // Spring Cache Abstraction 활성화 → @Cacheable 사용 가능
public class RedisConfig {

    /** Redis 서버 호스트 (application.yml에서 주입) */
    @Value("${spring.data.redis.host:localhost}")
    private String host;

    /** Redis 서버 포트 */
    @Value("${spring.data.redis.port:6379}")
    private int port;

    /** 커맨드 실행 타임아웃(ms) */
    @Value("${spring.data.redis.timeout:2000}")
    private long timeout;

    /** TCP 연결 타임아웃(ms) */
    @Value("${spring.data.redis.connect-timeout:2000}")
    private long connectTimeout;

    /** Redis 서버 가용 여부 */
    private boolean redisAvailable = false;

    /**
     * 애플리케이션 시작 시 Redis 연결 상태 확인.
     * Redis가 없어도 애플리케이션은 정상 기동 (Graceful Degradation).
     */
    @PostConstruct
    public void init() {
        this.redisAvailable = checkRedisConnection();
        if (redisAvailable) {
            log.info("[Redis] 서버 연결 성공 - host={}, port={}", host, port);
        } else {
            log.warn("[Redis] 서버 연결 불가 - Redis 기능 비활성화, 앱은 계속 동작");
        }
    }

    /**
     * Redis 연결 팩토리 (Lettuce 기반)
     *
     * Lettuce 주요 설정:
     * - disconnectedBehavior(REJECT_COMMANDS): 연결 끊김 시 즉시 에러 반환
     * - autoReconnect(true): 자동 재연결 활성화
     * - connectTimeout: TCP 연결 수립 최대 대기 시간
     * - commandTimeout: Redis 커맨드 실행 최대 대기 시간
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // Redis 서버 정보 설정
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration();
        serverConfig.setHostName(host);
        serverConfig.setPort(port);

        // Lettuce 클라이언트 옵션: 연결 끊김 시 동작, 자동 재연결
        ClientOptions clientOptions = ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS) // 끊기면 즉시 에러
                .autoReconnect(true)  // 자동 재연결
                .build();

        // 소켓 옵션: TCP 연결 타임아웃
        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .build();

        // Lettuce 클라이언트 설정 조합
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .clientOptions(clientOptions)
                .commandTimeout(Duration.ofMillis(timeout))  // 커맨드 타임아웃
                .build();

        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }

    /**
     * StringRedisTemplate - key/value 모두 String 직렬화
     *
     * 사용 시기:
     * - 카운터 (INCR/DECR)
     * - 단순 문자열 값 저장
     * - Hash 필드의 숫자 연산
     *
     * 예시:
     *   stringRedisTemplate.opsForValue().set("user:1:name", "홍길동");
     *   stringRedisTemplate.opsForValue().increment("stock:100", -1);  // 재고 감소
     *   stringRedisTemplate.opsForHash().put("item:1", "price", "15000");
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        return template;
    }

    /**
     * RedisTemplate<String, Object> - 복잡한 객체를 JSON으로 직렬화
     *
     * 직렬화 전략:
     * - Key: StringRedisSerializer (사람이 읽을 수 있는 문자열)
     * - Value: GenericJackson2JsonRedisSerializer (객체 → JSON)
     * - Hash Key: StringRedisSerializer
     * - Hash Value: GenericJackson2JsonRedisSerializer
     *
     * JavaTimeModule 등록:
     * - LocalDateTime 등 Java 8 날짜 타입의 직렬화/역직렬화 지원
     * - WRITE_DATES_AS_TIMESTAMPS=false → "2024-01-01T12:00:00" 형식으로 저장
     *
     * 예시:
     *   redisTemplate.opsForValue().set("user:1", userDto);
     *   UserDto user = (UserDto) redisTemplate.opsForValue().get("user:1");
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Jackson ObjectMapper: Java 8 날짜 모듈 + 타임스탬프 비활성화
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());                  // LocalDateTime 지원
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // ISO 문자열 형식

        // 직렬화 전략 설정
        template.setKeySerializer(new StringRedisSerializer());              // key → String
        template.setHashKeySerializer(new StringRedisSerializer());          // hash key → String
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer(objectMapper));     // value → JSON
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer(objectMapper)); // hash value → JSON

        template.afterPropertiesSet();
        return template;
    }

    /**
     * Redisson 클라이언트 - 분산 락, 분산 자료구조
     *
     * 주요 기능:
     * - RLock: 분산 환경에서의 상호 배제 락
     * - RSemaphore: 분산 세마포어
     * - RMap: 분산 맵 (ConcurrentHashMap의 분산 버전)
     *
     * 설정:
     * - connectionMinimumIdleSize(2): 최소 유휴 연결 수
     * - connectionPoolSize(10): 최대 연결 풀 크기
     * - retryAttempts(2): 재시도 횟수
     * - retryInterval(500): 재시도 간격(ms)
     * - keepAlive(true): TCP Keep-Alive 활성화
     */
    @Bean(destroyMethod = "shutdown")  // 빈 소멸 시 shutdown 호출
    public RedissonClient redissonClient() {
        if (!redisAvailable) {
            log.warn("[Redisson] Redis 미사용 - null 반환");
            return null;
        }

        Config config = new Config();
        String address = String.format("redis://%s:%d", host, port);

        config.useSingleServer()
                .setAddress(address)                    // Redis 서버 주소
                .setConnectionMinimumIdleSize(2)        // 최소 유휴 연결
                .setConnectionPoolSize(10)              // 최대 연결 풀
                .setConnectTimeout((int) connectTimeout) // TCP 연결 타임아웃
                .setTimeout((int) timeout)              // 커맨드 타임아웃
                .setRetryAttempts(2)                    // 재시도 횟수
                .setRetryInterval(500)                  // 재시도 간격(ms)
                .setKeepAlive(true);                    // TCP Keep-Alive

        log.info("[Redisson] 클라이언트 초기화 - address={}", address);
        return Redisson.create(config);
    }

    /**
     * Redis 서버 연결 가능 여부 확인
     */
    private boolean checkRedisConnection() {
        try {
            var factory = new LettuceConnectionFactory(host, port);
            factory.afterPropertiesSet();
            factory.getConnection().ping();
            factory.destroy();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
