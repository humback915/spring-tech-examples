package kr.co.example.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ========================================================================
 * Redis 활용 예제 - 재고 관리 & 캐싱 & 분산 락
 * ========================================================================
 *
 * ── Redis 데이터 구조별 활용 ──
 *
 * 1. String (opsForValue)
 *    - 단순 값 저장, 카운터, 분산 락
 *    - SET, GET, INCR, DECR, SETNX
 *
 * 2. Hash (opsForHash)
 *    - 객체의 필드별 저장 (메모리 효율적)
 *    - HSET, HGET, HMGET, HINCRBY
 *    - 예: "stock:100" → { total: "50", cart: "3", ordered: "10" }
 *
 * 3. List (opsForList)
 *    - 큐, 스택 구현
 *    - LPUSH, RPUSH, LPOP, RPOP, LRANGE
 *
 * 4. Set (opsForSet)
 *    - 중복 없는 집합, 교집합/합집합
 *    - SADD, SMEMBERS, SINTER, SUNION
 *
 * 5. Sorted Set (opsForZSet)
 *    - 점수 기반 정렬, 리더보드, 대기열
 *    - ZADD, ZRANGE, ZRANGEBYSCORE
 *
 * ── @Cacheable / @CacheEvict ──
 *
 * @Cacheable:
 * - 메서드 결과를 캐시에 저장
 * - 동일 파라미터로 재호출 시 캐시에서 즉시 반환 (메서드 실행 안 함)
 * - key: SpEL 표현식으로 캐시 키 생성
 *
 * @CacheEvict:
 * - 캐시에서 데이터 제거
 * - 데이터 변경 시 호출하여 캐시 무효화
 *
 * ── 분산 락 (Redisson RLock) ──
 *
 * Cache Stampede 문제:
 * - 캐시 만료 시점에 다수의 요청이 동시에 DB 조회 → DB 과부하
 * - 분산 락으로 한 스레드만 DB 조회, 나머지는 대기 후 캐시 사용
 *
 * ┌──────────────────────────────────────────┐
 * │ Thread-1: 락 획득 → DB 조회 → 캐시 저장  │
 * │ Thread-2: 락 대기 → 캐시에서 읽기         │
 * │ Thread-3: 락 대기 → 캐시에서 읽기         │
 * └──────────────────────────────────────────┘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisStockService {

    /** String 전용 Template - 카운터, 간단한 값 관리 */
    private final StringRedisTemplate stringRedisTemplate;

    /** 분산 락 클라이언트 */
    private final RedissonClient redissonClient;

    /** Redis Hash 키 접두사 */
    private static final String STOCK_KEY_PREFIX = "stock:item:";

    /** Hash 필드명 */
    private static final String FIELD_TOTAL = "total";
    private static final String FIELD_CART = "cart";
    private static final String FIELD_ORDERED = "ordered";

    // ================================================================
    // [1] Hash 기반 재고 관리
    // ================================================================

    /**
     * 재고 초기화 - Hash에 필드별 저장
     *
     * Redis 명령: HSET stock:item:100 total 50 cart 0 ordered 0
     *
     * @param itemId    상품 ID
     * @param total     총 재고 수량
     * @param ttlMinutes TTL(분) - 자동 만료 시간
     */
    public void initStock(Long itemId, int total, long ttlMinutes) {
        String key = STOCK_KEY_PREFIX + itemId;

        // Hash 필드별 저장
        stringRedisTemplate.opsForHash().put(key, FIELD_TOTAL, String.valueOf(total));
        stringRedisTemplate.opsForHash().put(key, FIELD_CART, "0");
        stringRedisTemplate.opsForHash().put(key, FIELD_ORDERED, "0");

        // TTL 설정 (만료 시간)
        stringRedisTemplate.expire(key, Duration.ofMinutes(ttlMinutes));

        log.info("[Stock] 초기화 - itemId={}, total={}, ttl={}분", itemId, total, ttlMinutes);
    }

    /**
     * 재고 상태 조회 - Hash 다중 필드 조회 (HMGET)
     *
     * HMGET은 여러 필드를 한 번의 네트워크 왕복으로 조회.
     * GET을 여러 번 호출하는 것보다 효율적.
     */
    public StockStatus getStockStatus(Long itemId) {
        String key = STOCK_KEY_PREFIX + itemId;

        List<Object> values = stringRedisTemplate.opsForHash()
                .multiGet(key, Arrays.asList(FIELD_TOTAL, FIELD_CART, FIELD_ORDERED));

        if (values.get(0) == null) {
            log.warn("[Stock] 데이터 없음 - itemId={}", itemId);
            return null;
        }

        int total = Integer.parseInt((String) values.get(0));
        int cart = Integer.parseInt((String) values.get(1));
        int ordered = Integer.parseInt((String) values.get(2));
        int available = total - cart - ordered;

        return new StockStatus(itemId, total, cart, ordered, available);
    }

    /**
     * 장바구니 재고 감소 - Hash 필드 원자적 증가 (HINCRBY)
     *
     * HINCRBY는 원자적 연산이므로 동시 요청에도 안전.
     * 별도의 락 없이 정확한 카운팅 가능.
     */
    public boolean decrementForCart(Long itemId, int quantity) {
        String key = STOCK_KEY_PREFIX + itemId;

        // cart 필드를 quantity만큼 증가 (장바구니에 담김)
        Long newCartCount = stringRedisTemplate.opsForHash()
                .increment(key, FIELD_CART, quantity);

        log.info("[Stock] 장바구니 추가 - itemId={}, quantity={}, cartTotal={}",
                itemId, quantity, newCartCount);
        return true;
    }

    // ================================================================
    // [2] @Cacheable / @CacheEvict 캐싱
    // ================================================================

    /**
     * 캐시 조회 - @Cacheable
     *
     * 동작:
     * 1. 캐시에 key가 있으면 → 캐시에서 즉시 반환 (메서드 실행 X)
     * 2. 캐시에 key가 없으면 → 메서드 실행 → 결과를 캐시에 저장 → 반환
     *
     * cacheNames: 캐시 이름 (논리적 그룹)
     * key: SpEL 표현식으로 캐시 키 생성
     *      "#itemId + '-' + #type" → "100-NORMAL" 형태의 키
     */
    @Cacheable(cacheNames = "itemInfo", key = "#itemId + '-' + #type")
    public String getItemInfoCached(Long itemId, String type) {
        log.info("[Cache] 캐시 미스 - DB 조회 실행 itemId={}, type={}", itemId, type);
        // 실제로는 DB 조회 로직
        return "Item-" + itemId + "-" + type;
    }

    /**
     * 캐시 제거 - @CacheEvict
     *
     * 데이터 변경 시 호출하여 캐시를 무효화.
     * 다음 조회 시 DB에서 최신 데이터를 가져와 캐시 갱신.
     */
    @CacheEvict(cacheNames = "itemInfo", key = "#itemId + '-' + #type")
    public void evictItemInfoCache(Long itemId, String type) {
        log.info("[Cache] 캐시 제거 - itemId={}, type={}", itemId, type);
    }

    // ================================================================
    // [3] 분산 락 (Redisson RLock)
    // ================================================================

    /**
     * 분산 락을 사용한 Cache Stampede 방지
     *
     * Cache Stampede:
     * 캐시 만료 시 수백 개의 동시 요청이 모두 DB를 조회하는 현상.
     * → DB에 순간 과부하 발생
     *
     * 해결:
     * 분산 락으로 한 스레드만 DB 조회 후 캐시 저장.
     * 나머지 스레드는 락 해제 후 캐시에서 읽기.
     *
     * tryLock 파라미터:
     * - waitTime(3초): 락 획득 최대 대기 시간
     * - leaseTime(5초): 락 자동 해제 시간 (데드락 방지)
     */
    public String getWithLock(Long itemId) {
        String lockKey = "lock:item:" + itemId;

        // Redisson 분산 락 생성
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 락 획득 시도: 3초 대기, 5초 후 자동 해제
            boolean acquired = lock.tryLock(3, 5, TimeUnit.SECONDS);

            if (!acquired) {
                log.warn("[Lock] 락 획득 실패 - itemId={}", itemId);
                // 폴백: 캐시에서 읽기 시도 또는 DB 직접 조회
                return "FALLBACK_DATA";
            }

            // 락 획득 성공 → 캐시 확인 → 없으면 DB 조회 후 캐시 저장
            String cacheKey = "cache:item:" + itemId;
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);

            if (cached != null) {
                log.info("[Lock] 캐시 히트 - itemId={}", itemId);
                return cached;
            }

            // DB 조회 (시뮬레이션)
            log.info("[Lock] DB 조회 - itemId={}", itemId);
            String dbResult = "DB_RESULT_" + itemId;

            // 캐시 저장 (TTL 5분)
            stringRedisTemplate.opsForValue().set(cacheKey, dbResult, Duration.ofMinutes(5));

            return dbResult;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("락 획득 중 인터럽트", e);
        } finally {
            // 락 해제 (현재 스레드가 보유한 경우에만)
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("[Lock] 락 해제 - itemId={}", itemId);
            }
        }
    }

    /**
     * 재고 상태 DTO
     */
    public record StockStatus(
            Long itemId,
            int total,     // 총 재고
            int cart,      // 장바구니에 담긴 수량
            int ordered,   // 주문 확정된 수량
            int available  // 구매 가능 수량 (total - cart - ordered)
    ) {}
}
