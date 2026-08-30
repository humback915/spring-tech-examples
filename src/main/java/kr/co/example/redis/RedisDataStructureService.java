package kr.co.example.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ========================================================================
 * Redis 데이터 구조 실전 활용 예제
 * ========================================================================
 *
 * ── 데이터 구조별 Spring 메서드 → Redis 명령 매핑 ──
 *
 * ┌────────────┬──────────────────────────────┬──────────────────────┐
 * │ 구조       │ Spring 메서드                │ Redis 명령           │
 * ├────────────┼──────────────────────────────┼──────────────────────┤
 * │ String     │ opsForValue().set()           │ SET key value        │
 * │            │ opsForValue().get()           │ GET key              │
 * │            │ opsForValue().increment()     │ INCR key             │
 * │            │ opsForValue().setIfAbsent()   │ SETNX key value      │
 * │            │ opsForValue().multiGet()      │ MGET key1 key2 ...   │
 * │            │ opsForValue().multiSet()      │ MSET k1 v1 k2 v2    │
 * ├────────────┼──────────────────────────────┼──────────────────────┤
 * │ Hash       │ opsForHash().put()            │ HSET key field val   │
 * │            │ opsForHash().get()            │ HGET key field       │
 * │            │ opsForHash().entries()        │ HGETALL key          │
 * │            │ opsForHash().multiGet()       │ HMGET key f1 f2 ...  │
 * │            │ opsForHash().increment()      │ HINCRBY key field n  │
 * ├────────────┼──────────────────────────────┼──────────────────────┤
 * │ Set        │ opsForSet().add()             │ SADD key m1 m2 ...   │
 * │            │ opsForSet().members()         │ SMEMBERS key         │
 * │            │ opsForSet().intersect()       │ SINTER key1 key2     │
 * │            │ opsForSet().union()           │ SUNION key1 key2     │
 * │            │ opsForSet().randomMember()    │ SRANDMEMBER key      │
 * └────────────┴──────────────────────────────┴──────────────────────┘
 *
 * ── Lua Script ──
 *
 * Redis의 EVAL 명령으로 서버 측에서 원자적으로 실행되는 스크립트.
 * 여러 명령을 하나의 원자적 연산으로 묶어 Race Condition을 방지한다.
 * Spring에서는 DefaultRedisScript를 사용하여 실행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisDataStructureService {

    private final StringRedisTemplate stringRedisTemplate;

    // ================================================================
    // [1] String 자료구조
    // ================================================================

    /**
     * SET / GET — 기본 값 저장 및 조회
     *
     * Redis 명령:
     *   SET user:1:name "홍길동" EX 600
     *   GET user:1:name
     *
     * 활용: 세션 토큰, 임시 데이터, 단순 캐시
     *
     * @param key   저장 키
     * @param value 저장 값
     * @param ttl   만료 시간
     * @return 저장된 값
     */
    public String setAndGet(String key, String value, Duration ttl) {
        stringRedisTemplate.opsForValue().set(key, value, ttl);
        log.info("[String] SET - key={}, ttl={}초", key, ttl.toSeconds());

        String result = stringRedisTemplate.opsForValue().get(key);
        log.info("[String] GET - key={}, value={}", key, result);
        return result;
    }

    /**
     * INCR / DECR — 원자적 카운터
     *
     * Redis 명령:
     *   INCR page:views:home
     *   INCRBY page:views:home 5
     *
     * 활용: 페이지 조회수, API 호출 횟수, 좋아요 수
     * 특징: 원자적 연산으로 동시 요청에도 정확한 카운팅 보장
     *
     * @param key   카운터 키
     * @param delta 증가/감소량
     * @return 연산 후 값
     */
    public Long incrementCounter(String key, long delta) {
        Long result = stringRedisTemplate.opsForValue().increment(key, delta);
        log.info("[String] INCRBY - key={}, delta={}, result={}", key, delta, result);
        return result;
    }

    /**
     * SETNX + SETEX — 중복 방지 + 만료 시간
     *
     * Redis 명령:
     *   SET lock:order:123 "1" NX EX 30
     *
     * 활용: 간이 분산 락, 중복 요청 방지 (멱등성 키)
     * SETNX: 키가 없을 때만 설정 (Set if Not eXists)
     *
     * @param key   락 키
     * @param value 락 값
     * @param ttl   자동 해제 시간
     * @return true: 락 획득 성공, false: 이미 존재
     */
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(key, value, ttl);
        boolean acquired = Boolean.TRUE.equals(result);
        log.info("[String] SETNX - key={}, acquired={}, ttl={}초", key, acquired, ttl.toSeconds());
        return acquired;
    }

    /**
     * MGET / MSET — 다중 키 일괄 조회/저장
     *
     * Redis 명령:
     *   MSET user:1:name "홍길동" user:1:email "hong@test.com"
     *   MGET user:1:name user:1:email
     *
     * 활용: 여러 키를 한 번의 네트워크 왕복으로 처리 (Round-Trip 절감)
     *
     * @param map 저장할 키-값 맵
     * @return 저장된 키 목록의 값
     */
    public List<String> multiSetAndGet(Map<String, String> map) {
        // MSET: 다중 키 일괄 저장
        stringRedisTemplate.opsForValue().multiSet(map);
        log.info("[String] MSET - keys={}", map.keySet());

        // MGET: 다중 키 일괄 조회
        List<String> keys = List.copyOf(map.keySet());
        List<String> values = stringRedisTemplate.opsForValue().multiGet(keys);
        log.info("[String] MGET - keys={}, values={}", keys, values);
        return values;
    }

    // ================================================================
    // [2] Hash 자료구조
    // ================================================================

    /**
     * HSET — Hash 필드 저장
     *
     * Redis 명령:
     *   HSET user:100 name "홍길동"
     *   HSET user:100 email "hong@test.com"
     *
     * 활용: 객체의 필드별 저장 (메모리 효율적, 부분 업데이트 가능)
     * String으로 JSON 전체를 저장하는 것보다 필드별 접근이 유리한 경우 사용
     *
     * @param key   Hash 키
     * @param field 필드명
     * @param value 필드 값
     */
    public void hashPut(String key, String field, String value) {
        stringRedisTemplate.opsForHash().put(key, field, value);
        log.info("[Hash] HSET - key={}, field={}, value={}", key, field, value);
    }

    /**
     * HGET — Hash 단일 필드 조회
     *
     * Redis 명령:
     *   HGET user:100 name
     *
     * @param key   Hash 키
     * @param field 필드명
     * @return 필드 값
     */
    public String hashGet(String key, String field) {
        Object value = stringRedisTemplate.opsForHash().get(key, field);
        log.info("[Hash] HGET - key={}, field={}, value={}", key, field, value);
        return value != null ? value.toString() : null;
    }

    /**
     * HGETALL — Hash 전체 필드 조회
     *
     * Redis 명령:
     *   HGETALL user:100
     *
     * 활용: 객체 전체 조회 (모든 필드를 한 번에 가져옴)
     * 주의: 필드 수가 매우 많은 경우 HSCAN 사용 권장
     *
     * @param key Hash 키
     * @return 전체 필드-값 맵
     */
    public Map<Object, Object> hashGetAll(String key) {
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
        log.info("[Hash] HGETALL - key={}, entries={}", key, entries);
        return entries;
    }

    /**
     * HMGET — Hash 다중 필드 조회
     *
     * Redis 명령:
     *   HMGET user:100 name email age
     *
     * 활용: 필요한 필드만 선택적으로 조회 (네트워크 효율)
     *
     * @param key    Hash 키
     * @param fields 조회할 필드 목록
     * @return 필드 값 목록 (순서 보장)
     */
    public List<Object> hashMultiGet(String key, List<Object> fields) {
        List<Object> values = stringRedisTemplate.opsForHash().multiGet(key, fields);
        log.info("[Hash] HMGET - key={}, fields={}, values={}", key, fields, values);
        return values;
    }

    /**
     * HINCRBY — Hash 필드 원자적 증가
     *
     * Redis 명령:
     *   HINCRBY product:100 viewCount 1
     *   HINCRBY product:100 stock -1
     *
     * 활용: 필드별 카운터 (조회수, 재고, 좋아요 등)
     * 특징: Hash 단위로 여러 카운터를 관리할 수 있어 구조적
     *
     * @param key   Hash 키
     * @param field 카운터 필드
     * @param delta 증가/감소량
     * @return 연산 후 값
     */
    public Long hashIncrement(String key, String field, long delta) {
        Long result = stringRedisTemplate.opsForHash().increment(key, field, delta);
        log.info("[Hash] HINCRBY - key={}, field={}, delta={}, result={}", key, field, delta, result);
        return result;
    }

    // ================================================================
    // [3] Set 자료구조
    // ================================================================

    /**
     * SADD — Set에 멤버 추가
     *
     * Redis 명령:
     *   SADD tag:java "spring" "redis" "jpa"
     *
     * 활용: 태그, 카테고리, 관심사 목록 (중복 자동 제거)
     *
     * @param key     Set 키
     * @param members 추가할 멤버들
     * @return 실제 추가된 멤버 수 (이미 존재하면 카운트 안 됨)
     */
    public Long setAdd(String key, String... members) {
        Long added = stringRedisTemplate.opsForSet().add(key, members);
        log.info("[Set] SADD - key={}, members={}, added={}", key, List.of(members), added);
        return added;
    }

    /**
     * SMEMBERS — Set 전체 멤버 조회
     *
     * Redis 명령:
     *   SMEMBERS tag:java
     *
     * 주의: 멤버 수가 매우 많은 경우 SSCAN 사용 권장
     *
     * @param key Set 키
     * @return 전체 멤버 집합
     */
    public Set<String> setMembers(String key) {
        Set<String> members = stringRedisTemplate.opsForSet().members(key);
        log.info("[Set] SMEMBERS - key={}, members={}", key, members);
        return members;
    }

    /**
     * SINTER — 교집합 (두 Set의 공통 멤버)
     *
     * Redis 명령:
     *   SINTER user:1:interests user:2:interests
     *
     * 활용: 공통 관심사, 공통 친구, 공통 태그 찾기
     *
     * @param key1 첫 번째 Set 키
     * @param key2 두 번째 Set 키
     * @return 교집합 결과
     */
    public Set<String> setIntersect(String key1, String key2) {
        Set<String> result = stringRedisTemplate.opsForSet().intersect(key1, key2);
        log.info("[Set] SINTER - key1={}, key2={}, result={}", key1, key2, result);
        return result;
    }

    /**
     * SUNION — 합집합 (두 Set의 모든 멤버)
     *
     * Redis 명령:
     *   SUNION user:1:interests user:2:interests
     *
     * 활용: 전체 태그 목록, 통합 관심사 목록
     *
     * @param key1 첫 번째 Set 키
     * @param key2 두 번째 Set 키
     * @return 합집합 결과
     */
    public Set<String> setUnion(String key1, String key2) {
        Set<String> result = stringRedisTemplate.opsForSet().union(key1, key2);
        log.info("[Set] SUNION - key1={}, key2={}, result={}", key1, key2, result);
        return result;
    }

    /**
     * SRANDMEMBER — 랜덤 멤버 조회
     *
     * Redis 명령:
     *   SRANDMEMBER event:participants
     *
     * 활용: 랜덤 추첨, 랜덤 추천, A/B 테스트 그룹 배정
     * 특징: 멤버를 제거하지 않고 랜덤 조회 (SPOP은 제거하면서 반환)
     *
     * @param key Set 키
     * @return 랜덤 멤버 1개
     */
    public String setRandomMember(String key) {
        String member = stringRedisTemplate.opsForSet().randomMember(key);
        log.info("[Set] SRANDMEMBER - key={}, member={}", key, member);
        return member;
    }

    // ================================================================
    // [4] Lua Script — 원자적 연산
    // ================================================================

    /**
     * Lua Script를 이용한 원자적 재고 차감
     *
     * Redis 명령:
     *   EVAL "스크립트" 1 stock:item:100 1
     *
     * 동작:
     * 1. 현재 재고 조회 (GET)
     * 2. 재고 >= 요청 수량이면 차감 (DECRBY)
     * 3. 재고 부족이면 -1 반환
     *
     * Lua Script를 사용하는 이유:
     * - GET → 비교 → DECRBY를 별도 명령으로 실행하면 Race Condition 발생
     * - Lua Script는 Redis 서버에서 원자적으로 실행 (중간에 다른 명령 개입 불가)
     *
     * 반환값:
     * - 양수: 차감 후 남은 재고
     * - -1: 재고 부족
     *
     * @param key      재고 키
     * @param quantity 차감 수량
     * @return 남은 재고 (-1이면 재고 부족)
     */
    public Long atomicStockDecrement(String key, int quantity) {
        String script = """
                local stock = tonumber(redis.call('GET', KEYS[1]))
                if stock == nil then
                    return -1
                end
                if stock >= tonumber(ARGV[1]) then
                    return redis.call('DECRBY', KEYS[1], ARGV[1])
                else
                    return -1
                end
                """;

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(script, Long.class);

        Long result = stringRedisTemplate.execute(
                redisScript,
                Collections.singletonList(key),
                String.valueOf(quantity)
        );

        if (result != null && result >= 0) {
            log.info("[Lua] 재고 차감 성공 - key={}, quantity={}, remaining={}", key, quantity, result);
        } else {
            log.warn("[Lua] 재고 부족 - key={}, requested={}", key, quantity);
        }

        return result;
    }

    /**
     * Lua Script를 이용한 원자적 ZPOPMIN + INCRBY (대기열 소비 + 카운터 갱신)
     *
     * Redis 명령:
     *   EVAL "스크립트" 2 queue:orders counter:processed 3
     *
     * 동작:
     * 1. Sorted Set에서 점수가 가장 낮은 N개 멤버를 꺼냄 (ZPOPMIN)
     * 2. 꺼낸 개수만큼 처리 카운터를 증가 (INCRBY)
     * 3. 꺼낸 멤버 목록을 반환
     *
     * ── 사용 시나리오 ──
     *
     * 대기열 + 처리 현황 추적을 원자적으로 수행:
     *
     *   [Sorted Set: 대기열]          [String: 처리 카운터]
     *   ┌─────────────────────┐      ┌──────────────────┐
     *   │ score │ member      │      │ counter:processed │
     *   │   1   │ order:101   │      │        47         │
     *   │   2   │ order:102   │  →   │        50 (+3)    │
     *   │   3   │ order:103   │      └──────────────────┘
     *   │   4   │ order:104   │
     *   └─────────────────────┘
     *         ↑ 3개 POP
     *
     * 원자성이 필요한 이유:
     * - ZPOPMIN과 INCRBY를 별도 실행하면 중간에 장애 시
     *   "큐에서 꺼냈지만 카운터 미반영" 또는 그 반대 상황 발생
     * - Lua Script로 묶으면 둘 다 성공하거나 둘 다 실패
     *
     * 활용 예시:
     * - 주문 처리 대기열 + 처리 건수 추적
     * - 작업 큐 소비 + 완료 통계 갱신
     * - 예약 대기열 + 확정 카운터
     *
     * @param queueKey   Sorted Set 대기열 키
     * @param counterKey 처리 카운터 키
     * @param count      꺼낼 멤버 수
     * @return 꺼낸 멤버 목록 (score와 member 교차 배열)
     */
    public List<String> atomicZpopminAndIncrby(String queueKey, String counterKey, int count) {
        String script = """
                local results = redis.call('ZPOPMIN', KEYS[1], ARGV[1])
                local popped = #results / 2
                if popped > 0 then
                    redis.call('INCRBY', KEYS[2], popped)
                end
                return results
                """;

        DefaultRedisScript<List> redisScript = new DefaultRedisScript<>(script, List.class);

        @SuppressWarnings("unchecked")
        List<String> result = stringRedisTemplate.execute(
                redisScript,
                List.of(queueKey, counterKey),
                String.valueOf(count)
        );

        int popped = (result != null) ? result.size() / 2 : 0;
        log.info("[Lua] ZPOPMIN+INCRBY - queue={}, counter={}, requested={}, popped={}",
                queueKey, counterKey, count, popped);

        return result;
    }

    /**
     * Lua Script를 이용한 원자적 ZPOPMIN + INCRBY + 결과 Hash 저장
     *
     * ZPOPMIN + INCRBY의 확장 버전.
     * 대기열에서 꺼낸 항목을 처리 이력 Hash에 기록한다.
     *
     * 동작:
     * 1. Sorted Set에서 N개 꺼냄 (ZPOPMIN)
     * 2. 처리 카운터 증가 (INCRBY)
     * 3. 꺼낸 각 멤버를 Hash에 상태 기록 (HSET)
     * 4. 꺼낸 개수 반환
     *
     *   [Sorted Set: 대기열]    [String: 카운터]    [Hash: 처리 이력]
     *   ┌──────┬──────────┐   ┌─────────────┐    ┌────────────────────┐
     *   │  1   │ order:101│   │     47       │    │ order:101: DONE    │
     *   │  2   │ order:102│ → │     49 (+2)  │    │ order:102: DONE    │
     *   │  3   │ order:103│   └─────────────┘    └────────────────────┘
     *   └──────┴──────────┘
     *       ↑ 2개 POP
     *
     * @param queueKey   Sorted Set 대기열 키
     * @param counterKey 처리 카운터 키
     * @param historyKey 처리 이력 Hash 키
     * @param count      꺼낼 멤버 수
     * @param status     기록할 상태 값 (예: "DONE", "PROCESSING")
     * @return 실제 꺼낸 멤버 수
     */
    public Long atomicZpopminIncrbyAndRecord(
            String queueKey, String counterKey, String historyKey, int count, String status) {
        String script = """
                local results = redis.call('ZPOPMIN', KEYS[1], ARGV[1])
                local popped = #results / 2
                if popped > 0 then
                    redis.call('INCRBY', KEYS[2], popped)
                    for i = 1, #results, 2 do
                        redis.call('HSET', KEYS[3], results[i], ARGV[2])
                    end
                end
                return popped
                """;

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(script, Long.class);

        Long popped = stringRedisTemplate.execute(
                redisScript,
                List.of(queueKey, counterKey, historyKey),
                String.valueOf(count),
                status
        );

        log.info("[Lua] ZPOPMIN+INCRBY+HSET - queue={}, counter={}, history={}, popped={}",
                queueKey, counterKey, historyKey, popped);

        return popped;
    }

    /**
     *
     * Redis 명령:
     *   EVAL "스크립트" 1 rate:api:user:100 10 60
     *
     * 동작:
     * 1. 현재 요청 횟수 조회 (GET)
     * 2. 제한 미만이면 카운터 증가 (INCR)
     * 3. 첫 요청이면 TTL 설정 (EXPIRE)
     * 4. 제한 초과면 0 반환
     *
     * 반환값:
     * - 1: 요청 허용
     * - 0: 요청 거부 (Rate Limit 초과)
     *
     * @param key          Rate Limit 키 (예: "rate:api:user:100")
     * @param maxRequests  윈도우 내 최대 요청 수
     * @param windowSeconds 윈도우 크기(초)
     * @return 1: 허용, 0: 거부
     */
    public Long rateLimiter(String key, int maxRequests, int windowSeconds) {
        String script = """
                local current = tonumber(redis.call('GET', KEYS[1]))
                if current == nil then
                    redis.call('SET', KEYS[1], 1)
                    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
                    return 1
                end
                if current < tonumber(ARGV[1]) then
                    redis.call('INCR', KEYS[1])
                    return 1
                else
                    return 0
                end
                """;

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(script, Long.class);

        Long result = stringRedisTemplate.execute(
                redisScript,
                Collections.singletonList(key),
                String.valueOf(maxRequests),
                String.valueOf(windowSeconds)
        );

        if (result != null && result == 1) {
            log.info("[Lua] Rate Limit 허용 - key={}, max={}, window={}초", key, maxRequests, windowSeconds);
        } else {
            log.warn("[Lua] Rate Limit 초과 - key={}", key);
        }

        return result;
    }
}
