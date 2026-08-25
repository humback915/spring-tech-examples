package kr.co.example.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * ========================================================================
 * 로컬 캐시 활용 예제
 * ========================================================================
 *
 * ── 3가지 사용 방식 ──
 *
 * 1. Spring Cache Abstraction (@Cacheable, @CachePut, @CacheEvict)
 *    - 가장 간편한 방식. 어노테이션만 붙이면 캐싱 동작
 *    - CacheManager 구현체만 교체하면 백엔드 변경 가능 (Caffeine → Redis 등)
 *    - AOP 프록시 기반 → self-invocation 주의
 *
 * 2. Caffeine Cache 직접 사용 (Manual Cache)
 *    - Cache<K, V> 객체를 직접 생성하여 get/put/invalidate 호출
 *    - 세밀한 제어 필요 시 사용 (조건부 캐싱, 부분 갱신 등)
 *    - Spring Cache Abstraction 없이도 독립적으로 사용 가능
 *
 * 3. LoadingCache (자동 로딩 캐시)
 *    - 캐시 미스 시 자동으로 로딩 함수를 호출하여 값을 채움
 *    - Cache Stampede 방지: 같은 키에 대해 동시 요청 시 하나만 로딩
 *    - 가장 안전한 방식 (null 처리, 동시성 모두 내장)
 *
 * ── 캐시 적용 적합한 데이터 ──
 *
 * ┌────────────────────────────┬────────────────────────────────┐
 * │ 적합                       │ 부적합                          │
 * ├────────────────────────────┼────────────────────────────────┤
 * │ 자주 조회, 드물게 변경      │ 자주 변경되는 데이터             │
 * │ 코드 테이블 (배송 상태 등)  │ 실시간 재고                     │
 * │ 메뉴/카테고리 목록          │ 주문 상태 (동시 수정 다발)       │
 * │ 사용자 프로필 (읽기 비율 높음)│ 결제 정보 (정합성 최우선)       │
 * │ 외부 API 응답 (변경 느림)   │ 랭킹 (실시간 반영 필요)         │
 * └────────────────────────────┴────────────────────────────────┘
 *
 * ── 캐시 관련 주요 문제와 해결 ──
 *
 * 1. Cache Stampede (캐시 쇄도)
 *    문제: 캐시 만료 시점에 다수 요청이 동시에 원본 조회
 *    해결: LoadingCache 사용 (동일 키 요청은 1개만 로딩)
 *          또는 분산 락 (Redis 환경)
 *
 * 2. Cache Penetration (캐시 관통)
 *    문제: 존재하지 않는 데이터를 반복 요청 → 매번 DB 조회
 *    해결: null 값도 캐싱 (allowNullValues=true)
 *          또는 Bloom Filter로 존재 여부 사전 체크
 *
 * 3. Cache Inconsistency (캐시 불일치)
 *    문제: DB는 갱신됐는데 캐시에 이전 값 남아있음
 *    해결: 쓰기 시 @CacheEvict 또는 @CachePut
 *          TTL을 적절히 설정하여 자연 만료
 *
 * ── 멀티 레벨 캐시 (L1 + L2) ──
 *
 * 대규모 시스템에서는 로컬 캐시와 리모트 캐시를 조합:
 *
 * ┌──────────────────────────────────────────────────────────┐
 * │ 요청 → [L1: Caffeine 로컬 캐시] → 히트? → 반환           │
 * │                                    ↓ 미스                │
 * │         [L2: Redis 리모트 캐시]  → 히트? → L1에 저장 → 반환│
 * │                                    ↓ 미스                │
 * │         [DB 조회]               → L2에 저장 → L1에 저장   │
 * └──────────────────────────────────────────────────────────┘
 *
 * L1 장점: 네트워크 호출 없이 즉시 반환 (나노초)
 * L2 장점: 인스턴스 간 공유, 앱 재시작 시에도 유지
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalCacheService {

    /** Spring이 주입하는 CacheManager (LocalCacheConfig에서 @Primary로 등록한 Caffeine) */
    private final CacheManager cacheManager;

    // ================================================================
    // [1] Caffeine 직접 사용 (Manual Cache)
    // ================================================================

    /**
     * 수동으로 생성한 Caffeine 캐시 인스턴스.
     *
     * Cache<K, V>는 ConcurrentHashMap과 유사하지만:
     * - 크기 제한 (maximumSize) → 메모리 초과 방지
     * - 자동 만료 (expireAfterWrite) → 오래된 데이터 자동 정리
     * - 통계 수집 (recordStats) → 히트율/미스율 모니터링
     *
     * ConcurrentHashMap은 위 기능이 없으므로 캐시 용도에 부적합.
     *
     * ┌──────────────────┬─────────────────┬────────────────────┐
     * │ 항목              │ ConcurrentHashMap│ Caffeine Cache     │
     * ├──────────────────┼─────────────────┼────────────────────┤
     * │ 크기 제한          │ 없음 (무한 증가) │ maximumSize        │
     * │ 자동 만료          │ 없음             │ expireAfterWrite   │
     * │ 퇴거 정책          │ 없음             │ Window TinyLfu     │
     * │ 통계              │ 없음             │ recordStats        │
     * │ 동시성            │ O (세그먼트 락)   │ O (더 최적화)       │
     * │ 메모리 효율        │ 낮음             │ 높음               │
     * └──────────────────┴─────────────────┴────────────────────┘
     */
    private final Cache<String, String> manualCache = Caffeine.newBuilder()
            .maximumSize(1_000)                     // 최대 1,000 엔트리
            .expireAfterWrite(5, TimeUnit.MINUTES)  // 쓰기 후 5분 만료
            .recordStats()                          // 통계 수집
            .build();

    /**
     * 캐시에 값 저장 (put)
     *
     * @param key   캐시 키
     * @param value 캐시 값
     */
    public void putToCache(String key, String value) {
        manualCache.put(key, value);
        log.info("[ManualCache] 저장 - key={}, value={}", key, value);
    }

    /**
     * 캐시에서 값 조회 (getIfPresent)
     *
     * getIfPresent: 캐시에 있으면 반환, 없으면 null.
     * → 캐시 미스 시 별도 로직으로 원본 데이터 조회 필요.
     *
     * @param key 캐시 키
     * @return 캐시된 값 (없으면 null)
     */
    public String getFromCache(String key) {
        String value = manualCache.getIfPresent(key);
        log.info("[ManualCache] 조회 - key={}, hit={}", key, value != null);
        return value;
    }

    /**
     * 캐시에서 값 조회 + 미스 시 자동 로딩 (get with mappingFunction)
     *
     * get(key, mappingFunction):
     * - 캐시에 key가 있으면 → 캐시 값 반환 (로딩 함수 실행 안 함)
     * - 캐시에 key가 없으면 → mappingFunction 실행 → 결과를 캐시에 저장 → 반환
     *
     * 동시에 같은 key로 여러 스레드가 호출해도 mappingFunction은 1번만 실행됨.
     * → 자체적으로 Cache Stampede 방지.
     *
     * @param key 캐시 키
     * @return 캐시된 값 또는 로딩된 값
     */
    public String getOrLoad(String key) {
        // 캐시 미스 시 loadFromDatabase() 자동 호출
        return manualCache.get(key, this::loadFromDatabase);
    }

    /**
     * 캐시에서 특정 키 제거 (invalidate)
     *
     * @param key 제거할 캐시 키
     */
    public void evictFromCache(String key) {
        manualCache.invalidate(key);
        log.info("[ManualCache] 제거 - key={}", key);
    }

    /**
     * 캐시 전체 제거 (invalidateAll)
     *
     * 코드 테이블 갱신, 설정 변경 시 전체 캐시를 비우는 경우에 사용.
     */
    public void evictAll() {
        manualCache.invalidateAll();
        log.info("[ManualCache] 전체 제거");
    }

    // ================================================================
    // [2] LoadingCache - 자동 로딩 캐시
    // ================================================================

    /**
     * LoadingCache: 캐시 미스 시 자동으로 로딩 함수를 호출.
     *
     * Cache<K,V>와의 차이:
     * - Cache: get(key) → null 반환 (수동 로딩 필요)
     * - LoadingCache: get(key) → 자동으로 loader 호출 → 값 반환
     *
     * 장점:
     * - 호출자가 "캐시 미스 시 어떻게 할지"를 신경 쓸 필요 없음
     * - 동일 키에 대한 동시 로딩 요청은 1개만 실행 (나머지는 대기)
     *   → Cache Stampede 방지 내장
     *
     * 주의:
     * - loader에서 예외 발생 시 get()에서 CompletionException으로 감싸져 전파
     * - loader가 null을 반환하면 캐시에 저장되지 않음
     */
    private final LoadingCache<String, String> loadingCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .recordStats()
            .build(key -> {
                // 이 람다가 loader: 캐시 미스 시 자동 호출
                log.info("[LoadingCache] 캐시 미스 - DB 로딩 실행: key={}", key);
                return loadFromDatabase(key);
            });

    /**
     * LoadingCache에서 값 조회
     *
     * 캐시 히트: 즉시 반환 (loader 실행 안 함)
     * 캐시 미스: loader 자동 실행 → 결과 캐싱 → 반환
     *
     * @param key 조회 키
     * @return 캐시되거나 로딩된 값
     */
    public String getWithAutoLoading(String key) {
        // get()만 호출하면 자동으로 캐시/로딩 처리
        return loadingCache.get(key);
    }

    /**
     * LoadingCache에서 여러 키를 한 번에 조회 (getAll)
     *
     * 캐시에 있는 키는 캐시에서, 없는 키만 loader로 일괄 로딩.
     *
     * @param keys 조회할 키 목록
     * @return 키-값 맵
     */
    public Map<String, String> getMultipleWithAutoLoading(List<String> keys) {
        return loadingCache.getAll(keys);
    }

    // ================================================================
    // [3] Spring Cache Abstraction (@Cacheable / @CachePut / @CacheEvict)
    // ================================================================

    /**
     * @Cacheable - 캐시 조회 + 미스 시 실행 후 캐시 저장
     *
     * 동작 흐름:
     * ┌─────────────────────────────────────────────────┐
     * │ @Cacheable 호출                                  │
     * │   ↓                                              │
     * │ CacheManager에서 "products" 캐시 조회             │
     * │   ↓                                              │
     * │ key="#productId" 존재? ─── YES → 캐시 값 반환     │
     * │   │                              (메서드 실행 X)  │
     * │   NO                                             │
     * │   ↓                                              │
     * │ 메서드 본문 실행 (DB 조회 등)                      │
     * │   ↓                                              │
     * │ 결과를 "products" 캐시에 저장                      │
     * │   ↓                                              │
     * │ 결과 반환                                         │
     * └─────────────────────────────────────────────────┘
     *
     * cacheNames: 논리적 캐시 이름 (같은 이름의 캐시를 공유)
     * key: SpEL 표현식으로 캐시 키 생성
     *      #productId → 메서드 파라미터 productId의 값
     *
     * @param productId 상품 ID
     * @return 상품 정보 문자열
     */
    @Cacheable(cacheNames = "products", key = "#productId")
    public String getProduct(Long productId) {
        log.info("[Spring Cache] @Cacheable 미스 - DB 조회 실행: productId={}", productId);
        // 실제로는 repository.findById(productId)
        return "Product-" + productId;
    }

    /**
     * @CachePut - 항상 메서드를 실행하고 결과를 캐시에 저장
     *
     * @Cacheable과의 차이:
     * - @Cacheable: 캐시 히트 시 메서드 실행 안 함
     * - @CachePut:  항상 메서드를 실행하고 결과로 캐시를 갱신
     *
     * 활용:
     * - 데이터 수정 후 캐시를 최신 값으로 갱신할 때
     * - @CacheEvict(삭제 후 재로딩)보다 효율적 (DB 조회 1회 절약)
     *
     * @param productId 상품 ID
     * @param newName   변경할 상품명
     * @return 갱신된 상품 정보
     */
    @CachePut(cacheNames = "products", key = "#productId")
    public String updateProduct(Long productId, String newName) {
        log.info("[Spring Cache] @CachePut - 갱신: productId={}, newName={}", productId, newName);
        // 실제로는 repository.save(entity)
        return "Product-" + productId + "-" + newName;
    }

    /**
     * @CacheEvict - 캐시에서 데이터 제거
     *
     * 데이터 삭제 시 캐시도 함께 무효화.
     * 다음 조회 시 @Cacheable이 DB에서 새로 로딩.
     *
     * allEntries = true 옵션:
     * - 해당 캐시의 모든 엔트리를 제거
     * - 대량 데이터 변경 시 사용
     *
     * beforeInvocation = true 옵션:
     * - 메서드 실행 전에 캐시 제거 (기본값 false는 메서드 성공 후 제거)
     * - 메서드 실패 시에도 캐시를 비워야 할 때 사용
     *
     * @param productId 삭제할 상품 ID
     */
    @CacheEvict(cacheNames = "products", key = "#productId")
    public void deleteProduct(Long productId) {
        log.info("[Spring Cache] @CacheEvict - 삭제: productId={}", productId);
        // 실제로는 repository.deleteById(productId)
    }

    /**
     * 조건부 캐싱 - condition / unless
     *
     * condition: true일 때만 캐시 적용 (캐시 조회 + 저장 모두 영향)
     *   → "#productId > 0" : ID가 양수일 때만 캐싱
     *
     * unless: true일 때 캐시 저장 안 함 (캐시 조회는 영향 없음)
     *   → "#result == null" : 결과가 null이면 캐시 안 함
     *   → "#result.contains('TEMP')" : 임시 데이터면 캐시 안 함
     *
     * condition vs unless 차이:
     * ┌──────────────┬──────────────────────┬─────────────────────┐
     * │ 속성          │ 평가 시점             │ 영향 범위            │
     * ├──────────────┼──────────────────────┼─────────────────────┤
     * │ condition    │ 메서드 실행 전         │ 캐시 조회 + 저장     │
     * │ unless       │ 메서드 실행 후         │ 캐시 저장만          │
     * └──────────────┴──────────────────────┴─────────────────────┘
     *
     * @param productId 상품 ID
     * @return 상품 정보 (null이면 캐시 안 함)
     */
    @Cacheable(
            cacheNames = "products",
            key = "#productId",
            condition = "#productId > 0",     // ID가 양수일 때만 캐싱
            unless = "#result == null"         // 결과가 null이면 캐시 저장 안 함
    )
    public String getProductWithCondition(Long productId) {
        log.info("[Spring Cache] 조건부 캐싱 - productId={}", productId);
        if (productId <= 0) {
            return null;
        }
        return "Product-" + productId;
    }

    // ================================================================
    // [4] 캐시 통계 모니터링
    // ================================================================

    /**
     * Caffeine 캐시 통계 조회
     *
     * recordStats()를 활성화한 캐시에서 수집되는 통계:
     *
     * ┌──────────────────┬───────────────────────────────────────┐
     * │ 지표              │ 설명                                  │
     * ├──────────────────┼───────────────────────────────────────┤
     * │ hitCount         │ 캐시 히트 횟수                         │
     * │ missCount        │ 캐시 미스 횟수                         │
     * │ hitRate          │ 히트율 (hitCount / 전체 요청)           │
     * │ evictionCount    │ 퇴거된 엔트리 수                       │
     * │ loadCount        │ 로딩 실행 횟수 (LoadingCache만)         │
     * │ averageLoadPenalty│ 평균 로딩 소요 시간 (ns)               │
     * └──────────────────┴───────────────────────────────────────┘
     *
     * 히트율(Hit Rate)이 낮으면:
     * - maximumSize가 너무 작은지 확인 (퇴거 빈번)
     * - expireAfterWrite가 너무 짧은지 확인 (너무 빨리 만료)
     * - 캐시 키가 너무 세분화되어 있는지 확인 (유니크 키 과다)
     *
     * 운영 환경에서는 이 통계를 Micrometer → Prometheus → Grafana로 연결하여
     * 대시보드에서 실시간 모니터링.
     */
    public void printCacheStats() {
        // Manual Cache 통계
        CacheStats manualStats = manualCache.stats();
        log.info("[ManualCache 통계] hitCount={}, missCount={}, hitRate={:.2f}%, evictionCount={}",
                manualStats.hitCount(),
                manualStats.missCount(),
                manualStats.hitRate() * 100,
                manualStats.evictionCount());

        // Loading Cache 통계
        CacheStats loadingStats = loadingCache.stats();
        log.info("[LoadingCache 통계] hitCount={}, missCount={}, hitRate={:.2f}%, loadCount={}",
                loadingStats.hitCount(),
                loadingStats.missCount(),
                loadingStats.hitRate() * 100,
                loadingStats.loadCount());
    }

    // ================================================================
    // [5] 캐시 워밍 (Cache Warming / Preloading)
    // ================================================================

    /**
     * 애플리케이션 시작 시 자주 사용되는 데이터를 미리 캐시에 로딩.
     *
     * 캐시 워밍(Cache Warming):
     * - 앱 시작 직후에는 캐시가 비어있어 모든 요청이 DB를 조회 (Cold Start)
     * - 자주 조회되는 데이터를 미리 캐시에 넣어두면 시작 직후부터 높은 히트율 유지
     *
     * 워밍 대상:
     * - 코드 테이블 (배송 상태, 주문 상태 등)
     * - 자주 조회되는 설정값
     * - 인기 상품 목록
     *
     * @PostConstruct:
     * - 빈 생성 + 의존성 주입 완료 후 자동 실행
     * - 애플리케이션 시작 시 1회만 실행
     */
    @PostConstruct
    public void warmUpCache() {
        log.info("[CacheWarming] 캐시 워밍 시작");

        // 자주 사용되는 코드 테이블 데이터를 미리 로딩
        Map<String, String> codeTable = Map.of(
                "ORDER_STATUS:PENDING", "주문 대기",
                "ORDER_STATUS:CONFIRMED", "주문 확인",
                "ORDER_STATUS:SHIPPED", "배송 중",
                "ORDER_STATUS:DELIVERED", "배송 완료",
                "ORDER_STATUS:CANCELLED", "주문 취소"
        );

        codeTable.forEach(manualCache::put);

        log.info("[CacheWarming] 캐시 워밍 완료 - {}건 로딩", codeTable.size());
    }

    // ================================================================
    // [6] 멀티 레벨 캐시 (L1: Caffeine + L2: Redis) 개념 예시
    // ================================================================

    /**
     * 멀티 레벨 캐시 조회 로직 (의사 코드)
     *
     * 실무에서 L1(로컬) + L2(Redis) 캐시를 조합하는 패턴.
     *
     * 장점:
     * - L1 히트 시 네트워크 비용 제로 (나노초 응답)
     * - L1 미스 시에도 L2에서 Redis 조회 (DB 부하 방지)
     * - DB 조회는 L1, L2 모두 미스일 때만 발생
     *
     * 주의:
     * - L1과 L2 사이의 데이터 일관성 관리 필요
     * - L1 TTL < L2 TTL로 설정하여 L1이 먼저 만료되도록 구성
     * - 데이터 변경 시 L1, L2 모두 무효화해야 함
     *
     * ── 동작 흐름 ──
     *
     * ┌────────────────────────────────────────────────────────┐
     * │ getWithMultiLevel("user:123")                          │
     * │   ↓                                                    │
     * │ [L1] Caffeine 조회 ──── 히트 → 즉시 반환 (~ns)         │
     * │   ↓ 미스                                               │
     * │ [L2] Redis 조회 ─────── 히트 → L1에 저장 → 반환 (~ms)  │
     * │   ↓ 미스                                               │
     * │ [DB] 데이터베이스 조회 → L2에 저장 → L1에 저장 → 반환    │
     * └────────────────────────────────────────────────────────┘
     *
     * 아래는 개념을 보여주는 의사 코드.
     * 실제 구현 시 StringRedisTemplate을 주입받아 사용.
     *
     * @param key 조회 키
     * @return 캐시된 값 또는 DB에서 로딩된 값
     */
    public String getWithMultiLevel(String key) {
        // [L1] 로컬 캐시(Caffeine) 조회
        String l1Value = manualCache.getIfPresent(key);
        if (l1Value != null) {
            log.info("[MultiLevel] L1 히트 - key={}", key);
            return l1Value;
        }

        // [L2] 리모트 캐시(Redis) 조회 (의사 코드)
        // String l2Value = stringRedisTemplate.opsForValue().get(key);
        String l2Value = null;  // Redis 미연결 시 null
        if (l2Value != null) {
            log.info("[MultiLevel] L2 히트 - key={}", key);
            // L1에 저장 (다음 조회 시 L1에서 즉시 반환)
            manualCache.put(key, l2Value);
            return l2Value;
        }

        // [DB] 원본 데이터 소스 조회
        log.info("[MultiLevel] L1/L2 모두 미스 - DB 조회: key={}", key);
        String dbValue = loadFromDatabase(key);

        // L2에 저장 (Redis, 의사 코드)
        // stringRedisTemplate.opsForValue().set(key, dbValue, Duration.ofMinutes(30));

        // L1에 저장 (Caffeine)
        manualCache.put(key, dbValue);

        return dbValue;
    }

    // ================================================================
    // 내부 헬퍼 메서드
    // ================================================================

    /**
     * DB 조회 시뮬레이션
     *
     * 실제로는 repository.findById() 등의 호출.
     * 캐시 미스 시 이 메서드가 호출되어 원본 데이터를 반환.
     */
    private String loadFromDatabase(String key) {
        log.info("[DB] 데이터 로딩 - key={}", key);
        // 실제로는 DB 쿼리 실행
        return "DB_VALUE_" + key;
    }
}
