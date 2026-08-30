package kr.co.example.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * ========================================================================
 * Redis 캐시 전략 5종 예제
 * ========================================================================
 *
 * ── 캐시 전략 비교표 ──
 *
 * ┌─────────────────┬────────────┬────────────┬───────────────────────────┐
 * │ 전략            │ 읽기 성능  │ 쓰기 성능  │ 데이터 일관성             │
 * ├─────────────────┼────────────┼────────────┼───────────────────────────┤
 * │ Cache-Aside     │ 높음       │ 보통       │ 캐시 미스 시 최신 보장    │
 * │ Read-Through    │ 높음       │ 보통       │ 캐시 계층이 자동 로딩     │
 * │ Write-Through   │ 높음       │ 낮음       │ 항상 일관 (동기 쓰기)     │
 * │ Write-Behind    │ 높음       │ 높음       │ 지연 쓰기로 불일치 가능   │
 * │ Write-Around    │ 보통       │ 높음       │ 첫 읽기 시 캐시 미스      │
 * └─────────────────┴────────────┴────────────┴───────────────────────────┘
 *
 * ── 전략 선택 가이드 ──
 *
 * ┌───────────────────────────────────────────────────────────────────────┐
 * │ 읽기 빈번 + 쓰기 드묾         → Cache-Aside / Read-Through         │
 * │ 읽기/쓰기 모두 빈번           → Write-Behind (비동기 쓰기)         │
 * │ 데이터 일관성 중요            → Write-Through (동기 쓰기)          │
 * │ 쓰기 후 즉시 읽기 드묾        → Write-Around (DB만 쓰기)          │
 * │ 범용 (가장 일반적)            → Cache-Aside                       │
 * └───────────────────────────────────────────────────────────────────────┘
 *
 * ── 동작 흐름 ──
 *
 * [Cache-Aside]
 *   읽기: App → Cache 조회 → 미스 → DB 조회 → Cache 저장 → 반환
 *   쓰기: App → DB 저장 → Cache 삭제 (Invalidation)
 *
 * [Read-Through]
 *   읽기: App → Cache 조회 → 미스 → Cache가 DB 로딩 → 반환
 *   (Cache 계층이 DB 접근 로직을 포함)
 *
 * [Write-Through]
 *   쓰기: App → Cache 저장 → Cache가 DB 동기 저장 → 완료
 *   (쓰기 지연 발생하지만 일관성 보장)
 *
 * [Write-Behind (Write-Back)]
 *   쓰기: App → Cache 저장 → 버퍼에 쓰기 요청 적재 → 비동기 DB 저장
 *   (쓰기 성능 최고, 데이터 유실 위험)
 *
 * [Write-Around]
 *   쓰기: App → DB 직접 저장 (Cache 업데이트 안 함)
 *   읽기: Cache 미스 시 DB 조회 → Cache 저장
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisCacheStrategyService {

    private final StringRedisTemplate stringRedisTemplate;

    /** 캐시 키 접두사 */
    private static final String CACHE_KEY_PREFIX = "strategy:";

    /** 기본 TTL (10분) */
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    /** Write-Behind 버퍼 (비동기 쓰기 시연용) */
    private final ConcurrentLinkedQueue<WriteRequest> writeBuffer = new ConcurrentLinkedQueue<>();

    // ================================================================
    // [1] Cache-Aside (Lazy Loading)
    // ================================================================

    /**
     * Cache-Aside 읽기
     *
     * 가장 널리 사용되는 캐시 전략. 애플리케이션이 직접 캐시와 DB를 관리한다.
     *
     * 흐름:
     * 1. 캐시에서 조회
     * 2. 히트 → 캐시 값 반환
     * 3. 미스 → DB 조회 → 캐시 저장 → 반환
     *
     * 장점: 구현 간단, 필요한 데이터만 캐싱 (Lazy)
     * 단점: 캐시 미스 시 지연, 캐시-DB 불일치 가능 (TTL로 완화)
     *
     * @param key 조회 키
     * @return 캐시 또는 DB에서 조회한 값
     */
    public String cacheAsideRead(String key) {
        String cacheKey = CACHE_KEY_PREFIX + key;

        // 1. 캐시 조회
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);

        if (cached != null) {
            log.info("[Cache-Aside] 캐시 히트 - key={}", key);
            return cached;
        }

        // 2. 캐시 미스 → DB 조회
        log.info("[Cache-Aside] 캐시 미스 - DB 조회 key={}", key);
        String dbValue = loadFromDatabase(key);

        // 3. 캐시 저장 (TTL 설정)
        if (dbValue != null) {
            stringRedisTemplate.opsForValue().set(cacheKey, dbValue, DEFAULT_TTL);
            log.info("[Cache-Aside] 캐시 저장 - key={}, ttl={}분", key, DEFAULT_TTL.toMinutes());
        }

        return dbValue;
    }

    /**
     * Cache-Aside 쓰기
     *
     * DB를 먼저 업데이트한 후 캐시를 삭제(Invalidation)한다.
     * 다음 읽기 시 캐시 미스가 발생하여 최신 DB 값을 로딩한다.
     *
     * 주의: "캐시 업데이트"가 아닌 "캐시 삭제"를 사용하는 이유
     * → 동시 쓰기 시 캐시에 이전 값이 남는 Race Condition 방지
     *
     * @param key   저장 키
     * @param value 저장 값
     */
    public void cacheAsideWrite(String key, String value) {
        String cacheKey = CACHE_KEY_PREFIX + key;

        // 1. DB 저장
        saveToDatabase(key, value);

        // 2. 캐시 삭제 (Invalidation)
        stringRedisTemplate.delete(cacheKey);
        log.info("[Cache-Aside] DB 저장 + 캐시 삭제 - key={}", key);
    }

    // ================================================================
    // [2] Read-Through
    // ================================================================

    /**
     * Read-Through 읽기
     *
     * Cache-Aside와 유사하지만, 캐시 계층이 DB 로딩 책임을 갖는다.
     * 애플리케이션은 항상 캐시에만 요청하고, 캐시가 미스 시 DB를 조회한다.
     *
     * 흐름:
     * 1. 캐시 조회 → 히트 시 반환
     * 2. 미스 시 캐시가 DB 조회 → 캐시 저장 → 반환
     *
     * Cache-Aside와의 차이:
     * - Cache-Aside: 앱이 DB 조회 로직을 직접 호출
     * - Read-Through: 캐시 계층(이 메서드)이 DB 접근을 캡슐화
     *
     * 실무에서는 Spring @Cacheable이 Read-Through 패턴에 해당한다.
     *
     * @param key 조회 키
     * @return 캐시에서 반환한 값 (미스 시 자동 로딩)
     */
    public String readThrough(String key) {
        String cacheKey = CACHE_KEY_PREFIX + key;

        // 캐시 조회
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);

        if (cached != null) {
            log.info("[Read-Through] 캐시 히트 - key={}", key);
            return cached;
        }

        // 캐시 미스 → 캐시 계층이 DB 로딩 담당 (캡슐화)
        log.info("[Read-Through] 캐시 미스 - 자동 로딩 key={}", key);
        String dbValue = loadFromDatabase(key);

        if (dbValue != null) {
            stringRedisTemplate.opsForValue().set(cacheKey, dbValue, DEFAULT_TTL);
        }

        return dbValue;
    }

    // ================================================================
    // [3] Write-Through
    // ================================================================

    /**
     * Write-Through 쓰기
     *
     * 캐시와 DB에 동기적으로 동시에 쓴다.
     * 캐시는 항상 최신 상태를 유지하므로 읽기 시 일관성이 보장된다.
     *
     * 흐름:
     * 1. 캐시 저장 (TTL 포함)
     * 2. DB 저장 (동기)
     * 3. 두 작업 모두 완료된 후 반환
     *
     * 장점: 캐시-DB 일관성 보장, 읽기 시 항상 캐시 히트
     * 단점: 쓰기 지연 (캐시 + DB 두 번 쓰기), 쓰기 빈번 시 비효율
     *
     * @param key   저장 키
     * @param value 저장 값
     */
    public void writeThrough(String key, String value) {
        String cacheKey = CACHE_KEY_PREFIX + key;

        // 1. 캐시 저장
        stringRedisTemplate.opsForValue().set(cacheKey, value, DEFAULT_TTL);
        log.info("[Write-Through] 캐시 저장 - key={}", key);

        // 2. DB 저장 (동기)
        saveToDatabase(key, value);
        log.info("[Write-Through] DB 저장 완료 - key={}", key);
    }

    // ================================================================
    // [4] Write-Behind (Write-Back)
    // ================================================================

    /**
     * Write-Behind 쓰기
     *
     * 캐시에만 즉시 쓰고, DB 쓰기는 버퍼에 쌓아 비동기로 처리한다.
     * 쓰기 성능이 가장 좋지만, 장애 시 버퍼 데이터 유실 위험이 있다.
     *
     * 흐름:
     * 1. 캐시 즉시 저장
     * 2. 쓰기 요청을 버퍼(큐)에 적재
     * 3. 별도 스케줄러가 버퍼를 Flush하여 DB에 배치 쓰기
     *
     * 장점: 쓰기 성능 최고, DB 부하 분산 (배치 처리)
     * 단점: 장애 시 데이터 유실, 일관성 지연
     *
     * 적용 예시: 조회수 카운터, 좋아요 수, 로그 수집 등 유실 허용 데이터
     *
     * @param key   저장 키
     * @param value 저장 값
     */
    public void writeBehind(String key, String value) {
        String cacheKey = CACHE_KEY_PREFIX + key;

        // 1. 캐시 즉시 저장
        stringRedisTemplate.opsForValue().set(cacheKey, value, DEFAULT_TTL);
        log.info("[Write-Behind] 캐시 즉시 저장 - key={}", key);

        // 2. 쓰기 버퍼에 적재 (비동기 처리 대기)
        writeBuffer.offer(new WriteRequest(key, value, Instant.now()));
        log.info("[Write-Behind] 쓰기 버퍼 적재 - key={}, bufferSize={}", key, writeBuffer.size());
    }

    /**
     * Write-Behind 버퍼 Flush
     *
     * 스케줄러에서 주기적으로 호출하여 버퍼의 쓰기 요청을 DB에 배치 저장.
     * 실무에서는 @Scheduled와 함께 사용한다.
     *
     * @return 처리된 쓰기 요청 수
     */
    public int flushWriteBuffer() {
        int flushed = 0;
        WriteRequest request;

        while ((request = writeBuffer.poll()) != null) {
            saveToDatabase(request.key(), request.value());
            flushed++;
        }

        if (flushed > 0) {
            log.info("[Write-Behind] 버퍼 Flush 완료 - {}건 DB 저장", flushed);
        }

        return flushed;
    }

    // ================================================================
    // [5] Write-Around
    // ================================================================

    /**
     * Write-Around 쓰기
     *
     * DB에만 직접 쓰고, 캐시는 업데이트하지 않는다.
     * 쓰기 직후 읽기가 드문 데이터에 적합하다.
     *
     * 흐름:
     * 1. DB 직접 저장
     * 2. 캐시 무시 (업데이트/삭제 안 함)
     *
     * 장점: 쓰기 후 읽기 않는 데이터의 캐시 오염 방지
     * 단점: 쓰기 직후 읽기 시 캐시 미스 발생 (Cold Read)
     *
     * 적용 예시: 로그 데이터, 감사(Audit) 기록, 이력 데이터
     *
     * @param key   저장 키
     * @param value 저장 값
     */
    public void writeAround(String key, String value) {
        // DB에만 저장 (캐시 업데이트 없음)
        saveToDatabase(key, value);
        log.info("[Write-Around] DB만 저장 (캐시 미갱신) - key={}", key);
    }

    /**
     * Write-Around 읽기
     *
     * 캐시 미스 시에만 DB에서 조회하여 캐시에 적재한다.
     * Cache-Aside 읽기와 동일한 패턴.
     *
     * @param key 조회 키
     * @return 캐시 또는 DB에서 조회한 값
     */
    public String writeAroundRead(String key) {
        String cacheKey = CACHE_KEY_PREFIX + key;

        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.info("[Write-Around] 캐시 히트 - key={}", key);
            return cached;
        }

        log.info("[Write-Around] 캐시 미스 - DB 조회 key={}", key);
        String dbValue = loadFromDatabase(key);

        if (dbValue != null) {
            stringRedisTemplate.opsForValue().set(cacheKey, dbValue, DEFAULT_TTL);
        }

        return dbValue;
    }

    // ================================================================
    // [내부 헬퍼] DB 시뮬레이션
    // ================================================================

    /**
     * DB 조회 시뮬레이션 (실제 구현에서는 Repository 호출)
     */
    private String loadFromDatabase(String key) {
        log.debug("[DB] 조회 - key={}", key);
        return "DB_VALUE_" + key;
    }

    /**
     * DB 저장 시뮬레이션 (실제 구현에서는 Repository 호출)
     */
    private void saveToDatabase(String key, String value) {
        log.debug("[DB] 저장 - key={}, value={}", key, value);
    }

    /**
     * Write-Behind 버퍼 요소
     *
     * @param key       저장 키
     * @param value     저장 값
     * @param timestamp 요청 시각
     */
    public record WriteRequest(String key, String value, Instant timestamp) {}
}
