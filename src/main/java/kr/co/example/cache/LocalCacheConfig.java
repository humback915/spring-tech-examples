package kr.co.example.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.TimeUnit;

/**
 * ========================================================================
 * 로컬 캐시 설정 (Caffeine)
 * ========================================================================
 *
 * ── 로컬 캐시란? ──
 *
 * 애플리케이션 프로세스의 JVM 힙 메모리에 데이터를 캐싱하는 방식.
 * 네트워크 호출 없이 메모리에서 직접 읽으므로 가장 빠른 캐시.
 *
 * ── 로컬 캐시 vs 리모트 캐시 (Redis) ──
 *
 * ┌────────────────┬─────────────────────────┬────────────────────────────┐
 * │ 항목            │ 로컬 캐시 (Caffeine)      │ 리모트 캐시 (Redis)          │
 * ├────────────────┼─────────────────────────┼────────────────────────────┤
 * │ 저장 위치       │ JVM 힙 메모리             │ 외부 Redis 서버              │
 * │ 접근 속도       │ ~ns (나노초)              │ ~ms (밀리초, 네트워크 왕복)    │
 * │ 다중 인스턴스   │ 각 인스턴스별 독립 캐시    │ 모든 인스턴스가 공유           │
 * │ 데이터 일관성   │ 인스턴스 간 불일치 가능    │ 항상 일관됨                   │
 * │ 장애 영향       │ 앱 재시작 시 소실         │ 앱과 독립적으로 유지           │
 * │ 메모리 제한     │ JVM 힙 크기에 제한        │ Redis 서버 메모리에 제한       │
 * │ 직렬화         │ 불필요 (객체 참조)         │ 필요 (JSON, 바이트 등)        │
 * │ 적합한 데이터   │ 자주 변하지 않는 설정값,   │ 세션, 인증 토큰,              │
 * │                │ 코드 테이블, 메뉴 등       │ 분산 환경 공유 데이터          │
 * └────────────────┴─────────────────────────┴────────────────────────────┘
 *
 * ── Caffeine 캐시 라이브러리 ──
 *
 * Google Guava Cache의 후속 라이브러리.
 * Window TinyLfu 퇴거 정책으로 높은 적중률(Hit Rate)을 제공.
 *
 * 주요 특징:
 * - 크기 기반 퇴거: maximumSize → 캐시 최대 엔트리 수 제한
 * - 시간 기반 퇴거: expireAfterWrite, expireAfterAccess
 * - 약한/소프트 참조: weakKeys, weakValues, softValues
 * - 통계 수집: recordStats → 히트율, 미스율, 퇴거 수 모니터링
 * - 비동기 로딩: AsyncLoadingCache
 *
 * ── 퇴거 정책(Eviction Policy) ──
 *
 * ┌────────────────────┬──────────────────────────────────────────┐
 * │ 정책               │ 설명                                     │
 * ├────────────────────┼──────────────────────────────────────────┤
 * │ maximumSize        │ 엔트리 수가 초과하면 가장 덜 사용된 항목 퇴거  │
 * │ expireAfterWrite   │ 쓰기 후 일정 시간 경과 시 퇴거              │
 * │                    │ → 데이터 신선도 보장 (주기적 갱신)           │
 * │ expireAfterAccess  │ 마지막 읽기/쓰기 후 일정 시간 경과 시 퇴거   │
 * │                    │ → 자주 쓰는 데이터는 오래 유지              │
 * │ refreshAfterWrite  │ 쓰기 후 일정 시간이 지나면 백그라운드 갱신    │
 * │                    │ → 만료와 달리 기존 값을 반환하면서 비동기 갱신 │
 * └────────────────────┴──────────────────────────────────────────┘
 *
 * ── 퇴거 vs 만료 ──
 *
 * 퇴거(Eviction):  공간 부족 시 정책에 따라 제거 (maximumSize 초과)
 * 만료(Expiration): 시간 경과로 인한 무효화 (expireAfterWrite/Access)
 * 갱신(Refresh):    만료 전 미리 새 값으로 교체 (refreshAfterWrite)
 *
 * ── @Primary 어노테이션 ──
 *
 * 이 프로젝트에서는 Redis CacheManager(RedisConfig)도 존재.
 * 같은 타입의 빈이 2개 이상이면 Spring이 어떤 빈을 주입할지 모호해짐.
 * @Primary를 붙이면 기본 CacheManager로 Caffeine이 사용됨.
 *
 * 특정 메서드에서 Redis 캐시를 쓰고 싶으면:
 *   @Cacheable(cacheNames = "xxx", cacheManager = "redisCacheManager")
 * 이렇게 cacheManager를 명시적으로 지정.
 */
@Slf4j
@Configuration
public class LocalCacheConfig {

    /**
     * Caffeine 기반 CacheManager (기본 캐시 매니저)
     *
     * ── 설정 값 의미 ──
     *
     * maximumSize(10_000):
     *   캐시에 최대 10,000개 엔트리 저장.
     *   초과 시 Window TinyLfu 알고리즘으로 가장 덜 사용된 항목 퇴거.
     *   메모리 사용량 = 엔트리 수 × (key 크기 + value 크기 + 오버헤드 ~100bytes)
     *
     * expireAfterWrite(10, MINUTES):
     *   엔트리가 캐시에 쓰인 후 10분이 경과하면 만료.
     *   다음 접근 시 캐시 미스로 처리되어 원본 데이터 소스에서 재로딩.
     *   데이터 신선도를 보장하려면 이 설정 사용.
     *
     * recordStats():
     *   캐시 통계 수집 활성화.
     *   hitCount, missCount, evictionCount 등을 확인 가능.
     *   운영 환경에서 캐시 효율을 모니터링할 때 필수.
     *
     * ── CaffeineCacheManager 동작 ──
     *
     * setAllowNullValues(true):
     *   null 값도 캐시에 저장 가능. DB 조회 결과가 null일 때
     *   매번 DB를 조회하지 않도록 "없음"도 캐싱 (Negative Caching).
     *   → Cache Penetration(캐시 관통) 방지
     *
     * 캐시 관통(Cache Penetration):
     *   존재하지 않는 데이터를 반복 요청 → 매번 DB 조회
     *   → null도 캐시하면 DB 부하 방지
     */
    @Bean
    @Primary  // 여러 CacheManager 중 기본으로 사용
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();

        // Caffeine 캐시 기본 설정
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10_000)                      // 최대 10,000 엔트리
                .expireAfterWrite(10, TimeUnit.MINUTES)   // 쓰기 후 10분 만료
                .recordStats()                            // 통계 수집
        );

        // null 값 캐싱 허용 (Cache Penetration 방지)
        cacheManager.setAllowNullValues(true);

        log.info("[LocalCache] Caffeine CacheManager 초기화 - maxSize=10000, expireAfterWrite=10분");
        return cacheManager;
    }

    /**
     * 캐시별 개별 설정이 필요한 경우의 CacheManager
     *
     * 실무에서는 캐시 종류마다 TTL과 크기가 다르다:
     * - 상품 목록: 큰 용량, 긴 TTL (30분)
     * - 사용자 세션: 작은 용량, 짧은 TTL (5분)
     * - 코드 테이블: 변경 거의 없음, 매우 긴 TTL (1시간)
     *
     * 이 경우 CaffeineCacheManager 하나로는 부족하고,
     * SimpleCacheManager + CaffeineCache 조합으로 캐시별 설정을 분리한다.
     *
     * ── 다중 캐시 설정 예시 ──
     *
     * <pre>
     * {@code
     * @Bean
     * public CacheManager multiCaffeineCacheManager() {
     *     SimpleCacheManager cacheManager = new SimpleCacheManager();
     *     cacheManager.setCaches(List.of(
     *         buildCache("products", 5000, 30, TimeUnit.MINUTES),
     *         buildCache("users", 1000, 5, TimeUnit.MINUTES),
     *         buildCache("codeTable", 500, 1, TimeUnit.HOURS)
     *     ));
     *     return cacheManager;
     * }
     *
     * private CaffeineCache buildCache(String name, int maxSize, long duration, TimeUnit unit) {
     *     return new CaffeineCache(name, Caffeine.newBuilder()
     *             .maximumSize(maxSize)
     *             .expireAfterWrite(duration, unit)
     *             .recordStats()
     *             .build());
     * }
     * }
     * </pre>
     */
    // 위 코드는 참고용 주석. 실제 사용 시 주석 해제하여 Bean 등록.
}
