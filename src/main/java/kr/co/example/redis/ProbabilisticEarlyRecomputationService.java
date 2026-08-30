package kr.co.example.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * ========================================================================
 * PER (Probabilistic Early Recomputation) 알고리즘 예제
 * ========================================================================
 *
 * ── 개념 ──
 *
 * PER은 Cache Stampede(캐시 쇄도)를 방지하는 확률적 조기 재계산 알고리즘이다.
 * 2015년 논문 "Optimal Probabilistic Cache Stampede Prevention"에서 제안되었으며,
 * XFetch 알고리즘으로도 알려져 있다.
 *
 * ── 기존 방식의 문제 ──
 *
 * TTL 만료 기반 캐시의 문제:
 *
 *   시간 ──────────────────────────────────▶
 *   캐시 SET ─────────── TTL 만료 ──────────
 *                             │
 *                        ┌────┼────┐
 *                     요청1  요청2  요청3  ← 동시에 캐시 미스!
 *                        │    │    │
 *                        DB   DB   DB       ← 3회 중복 DB 조회
 *                        │    │    │
 *                        SET  SET  SET      ← 3회 중복 캐시 저장
 *
 * → Cache Stampede: TTL 만료 시점에 다수 요청이 동시에 DB 조회
 * → DB 부하 급증, 응답 지연, 장애 유발 가능
 *
 * ── PER 알고리즘의 해결 방식 ──
 *
 * TTL 만료 "전에" 확률적으로 캐시를 미리 갱신한다.
 * 만료 시점이 가까워질수록 재계산 확률이 높아진다.
 *
 *   시간 ──────────────────────────────────▶
 *   캐시 SET ─── 확률적 갱신 구간 ── TTL 만료
 *                  │
 *               요청 A가 확률에 의해 조기 갱신 결정
 *                  │
 *                  DB 조회 (1회만)
 *                  │
 *                  SET (새 TTL)
 *                                    ← 다른 요청들은 캐시 히트!
 *
 * ── 핵심 수식 ──
 *
 *   currentTime - (delta * beta * ln(random())) > expiry
 *
 *   ┌──────────────┬──────────────────────────────────────────────┐
 *   │ 변수          │ 설명                                         │
 *   ├──────────────┼──────────────────────────────────────────────┤
 *   │ currentTime  │ 현재 시각                                     │
 *   │ delta        │ 값을 재계산하는 데 걸리는 시간 (DB 조회 시간)    │
 *   │ beta         │ 튜닝 파라미터 (>= 1.0, 클수록 조기 갱신 확률↑)  │
 *   │ ln(random()) │ (0,1) 균등 분포의 자연 로그 → 항상 음수          │
 *   │ expiry       │ 캐시 만료 시각                                 │
 *   └──────────────┴──────────────────────────────────────────────┘
 *
 *   수식 해석:
 *   - ln(random())은 항상 음수 → -ln(random())은 항상 양수
 *   - delta * beta * (-ln(random()))이 "남은 TTL"보다 크면 → 재계산 실행
 *   - 만료 시점에 가까울수록 "남은 TTL"이 작아져 조건 충족 확률 증가
 *   - delta(재계산 비용)가 클수록 더 일찍 갱신 시도 (비용이 큰 작업일수록 미리 준비)
 *
 * ── beta 값에 따른 동작 변화 ──
 *
 *   ┌───────────┬────────────────────────────────────────────────┐
 *   │ beta 값   │ 동작                                           │
 *   ├───────────┼────────────────────────────────────────────────┤
 *   │ 1.0       │ 기본값. 논문에서 수학적으로 최적이라고 증명       │
 *   │ > 1.0     │ 더 일찍, 더 자주 조기 갱신 (높은 트래픽 환경)     │
 *   │ < 1.0     │ 조기 갱신 빈도 감소 (낮은 트래픽, 재계산 비용 낮음)│
 *   └───────────┴────────────────────────────────────────────────┘
 *
 * ── PER vs 다른 Stampede 방지 기법 비교 ──
 *
 *   ┌─────────────────────┬──────────┬──────────┬───────────────────────┐
 *   │ 기법                 │ 추가 인프라│ 동시성   │ 특징                   │
 *   ├─────────────────────┼──────────┼──────────┼───────────────────────┤
 *   │ 분산 락 (Redisson)   │ Redis    │ 1개 통과 │ 락 경합, 대기 시간 발생 │
 *   │ LoadingCache         │ 없음     │ 1개 통과 │ 단일 JVM 한정          │
 *   │ PER (이 알고리즘)    │ 없음     │ 확률적   │ 락 없이 자연스러운 갱신 │
 *   │ TTL Jitter           │ 없음     │ 분산     │ 만료 시점만 분산       │
 *   └─────────────────────┴──────────┴──────────┴───────────────────────┘
 *
 * ── 적용 적합한 상황 ──
 *
 *   적합: 읽기 빈번, 재계산 비용 높음, 다중 인스턴스 환경
 *   부적합: 실시간 정합성 필수 데이터, 쓰기가 매우 빈번한 데이터
 *
 * ── Redis SPOF 리스크와 DB 폴백 ──
 *
 * Redis 장애 시 전체 서비스가 중단되는 것을 방지하기 위해
 * DB 직접 조회로 자동 전환하는 Graceful Degradation 패턴.
 *
 *   [정상 상태]
 *   요청 → Redis(PER) → 히트 → 반환
 *                         ↓ 미스
 *                        DB 조회 → Redis 저장 → 반환
 *
 *   [Redis 장애]
 *   요청 → Redis(PER) → 예외 발생!
 *                         ↓ catch
 *                        DB 직접 조회 → 반환
 *                        (Redis 저장 생략, 로그 경고)
 *
 *   ┌──────────────────┬────────────────────────────────────────┐
 *   │ SPOF 방지 전략    │ 설명                                   │
 *   ├──────────────────┼────────────────────────────────────────┤
 *   │ try-catch 폴백   │ Redis 예외 시 DB 직접 조회 (이 파일)    │
 *   │ Circuit Breaker  │ Redis 연속 실패 시 DB만 사용 (자동 전환) │
 *   │ Health Check     │ 주기적 ping으로 Redis 상태 모니터링      │
 *   │ Redis Sentinel   │ 인프라 레벨 HA (자동 페일오버)           │
 *   │ Redis Cluster    │ 샤딩 + 복제 (인프라 이중화)             │
 *   └──────────────────┴────────────────────────────────────────┘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProbabilisticEarlyRecomputationService {

    private final StringRedisTemplate stringRedisTemplate;

    /** 캐시 키 접두사 */
    private static final String CACHE_KEY_PREFIX = "per:";

    /** TTL 만료 시각을 저장하는 키 접두사 (메타데이터) */
    private static final String EXPIRY_KEY_PREFIX = "per:expiry:";

    /** 재계산 시간(delta)을 저장하는 키 접두사 (메타데이터) */
    private static final String DELTA_KEY_PREFIX = "per:delta:";

    /** 기본 TTL (10분) */
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    /**
     * beta 파라미터: 조기 갱신 강도 조절
     *
     * - 1.0: 논문 권장 기본값 (수학적 최적)
     * - > 1.0: 더 공격적으로 조기 갱신 (높은 트래픽)
     * - < 1.0: 조기 갱신 빈도 감소 (낮은 트래픽)
     */
    private static final double BETA = 1.0;

    // ================================================================
    // [1] PER 기본 구현 - Redis 메타데이터 활용
    // ================================================================

    /**
     * PER 알고리즘 기반 캐시 조회
     *
     * 핵심 흐름:
     * ┌────────────────────────────────────────────────────────────┐
     * │ get(key)                                                   │
     * │   ↓                                                        │
     * │ Redis에서 값 + 만료 시각 + delta 조회                       │
     * │   ↓                                                        │
     * │ 값 없음? ───── YES → DB 조회 → 캐시 저장 → 반환            │
     * │   │                                                        │
     * │   NO (캐시 히트)                                            │
     * │   ↓                                                        │
     * │ PER 수식 평가: shouldRecompute(delta, beta, expiry)?       │
     * │   ↓                                                        │
     * │   YES → DB 조회 → 캐시 갱신 → 반환 (조기 갱신)             │
     * │   NO  → 캐시 값 그대로 반환                                 │
     * └────────────────────────────────────────────────────────────┘
     *
     * @param key       캐시 키
     * @param dbLoader  캐시 미스 시 DB에서 값을 로딩하는 함수
     * @return 캐시 또는 DB에서 조회한 값
     */
    public String getWithPER(String key, Supplier<String> dbLoader) {
        String cacheKey = CACHE_KEY_PREFIX + key;
        String expiryKey = EXPIRY_KEY_PREFIX + key;
        String deltaKey = DELTA_KEY_PREFIX + key;

        // 1. 캐시에서 값 조회
        String cachedValue = stringRedisTemplate.opsForValue().get(cacheKey);

        if (cachedValue == null) {
            // 캐시 미스 → DB 조회 후 캐시 저장
            log.info("[PER] 캐시 미스 - DB 조회 key={}", key);
            return recompute(key, dbLoader);
        }

        // 2. 캐시 히트 → PER 수식 평가
        String expiryStr = stringRedisTemplate.opsForValue().get(expiryKey);
        String deltaStr = stringRedisTemplate.opsForValue().get(deltaKey);

        if (expiryStr == null || deltaStr == null) {
            // 메타데이터 없으면 캐시 값 그대로 반환
            log.info("[PER] 캐시 히트 (메타데이터 없음) - key={}", key);
            return cachedValue;
        }

        long expiryMillis = Long.parseLong(expiryStr);
        long deltaMillis = Long.parseLong(deltaStr);

        // 3. 조기 재계산 여부 판단
        if (shouldRecompute(deltaMillis, BETA, expiryMillis)) {
            log.info("[PER] 조기 재계산 결정 - key={}, 남은TTL={}초",
                    key, (expiryMillis - System.currentTimeMillis()) / 1000);
            return recompute(key, dbLoader);
        }

        log.debug("[PER] 캐시 히트 - key={}", key);
        return cachedValue;
    }

    /**
     * PER 핵심 수식: 조기 재계산 여부 판단
     *
     * 수식: currentTime - (delta * beta * ln(random())) > expiry
     *
     * 해석:
     * - random()은 (0, 1) 균등 분포 → ln(random())은 항상 음수
     * - 따라서 -delta * beta * ln(random())은 항상 양수
     * - currentTime에 이 양수값을 더한 값이 expiry보다 크면 → 재계산
     *
     * 직관적 이해:
     * - 만료까지 남은 시간이 짧을수록 → 재계산 확률 증가
     * - delta(재계산 비용)가 클수록 → 재계산 확률 증가 (비용 큰 작업은 미리 준비)
     * - beta가 클수록 → 재계산 확률 증가
     *
     * @param deltaMillis  재계산 소요 시간 (밀리초)
     * @param beta         튜닝 파라미터 (>= 1.0)
     * @param expiryMillis 캐시 만료 시각 (에포크 밀리초)
     * @return true면 조기 재계산 수행
     */
    private boolean shouldRecompute(long deltaMillis, double beta, long expiryMillis) {
        long now = System.currentTimeMillis();

        // 이미 만료된 경우 → 무조건 재계산
        if (now >= expiryMillis) {
            return true;
        }

        // PER 수식 적용
        //   random: (0, 1) 균등 분포
        //   ln(random): 항상 음수
        //   -ln(random): 항상 양수 (지수 분포 따름)
        double random = ThreadLocalRandom.current().nextDouble(Double.MIN_VALUE, 1.0);
        double gap = deltaMillis * beta * (-Math.log(random));

        // currentTime + gap > expiry → 재계산
        return now + gap > expiryMillis;
    }

    /**
     * 값을 재계산(DB 조회)하고 캐시에 저장
     *
     * delta(재계산 소요 시간)를 측정하여 메타데이터로 함께 저장한다.
     * 다음 PER 수식 평가에 delta 값이 사용된다.
     *
     * @param key       캐시 키
     * @param dbLoader  DB 로딩 함수
     * @return DB에서 조회한 값
     */
    private String recompute(String key, Supplier<String> dbLoader) {
        String cacheKey = CACHE_KEY_PREFIX + key;
        String expiryKey = EXPIRY_KEY_PREFIX + key;
        String deltaKey = DELTA_KEY_PREFIX + key;

        // delta 측정: DB 조회 소요 시간
        long start = System.currentTimeMillis();
        String value = dbLoader.get();
        long delta = System.currentTimeMillis() - start;

        if (value == null) {
            return null;
        }

        // 만료 시각 계산
        long expiryMillis = System.currentTimeMillis() + DEFAULT_TTL.toMillis();

        // 캐시 저장 (값 + 메타데이터)
        // TTL을 약간 여유 있게 설정 (메타데이터가 값보다 먼저 만료되지 않도록)
        Duration metaTtl = DEFAULT_TTL.plusMinutes(1);

        stringRedisTemplate.opsForValue().set(cacheKey, value, DEFAULT_TTL);
        stringRedisTemplate.opsForValue().set(expiryKey, String.valueOf(expiryMillis), metaTtl);
        stringRedisTemplate.opsForValue().set(deltaKey, String.valueOf(delta), metaTtl);

        log.info("[PER] 캐시 저장 - key={}, delta={}ms, ttl={}분", key, delta, DEFAULT_TTL.toMinutes());

        return value;
    }

    // ================================================================
    // [2] PER 간소화 버전 - 단일 키, 논리적 만료
    // ================================================================

    /**
     * PER 간소화 구현 (값과 메타데이터를 하나의 JSON으로 저장)
     *
     * 실무에서 키 수를 줄이기 위해, 값과 메타데이터를 하나로 묶어 저장하는 패턴.
     * Redis 키 3개 → 1개로 줄일 수 있다.
     *
     * 저장 형식: "value|expiryMillis|deltaMillis"
     *
     * 물리적 TTL은 논리적 TTL보다 길게 설정하여,
     * 조기 갱신 기간 동안에도 캐시 값이 Redis에 존재하도록 한다.
     *
     *   ┌──── 논리적 TTL (10분) ────┐
     *   │                           │
     *   SET ───────────── 논리적 만료 ──── 물리적 만료
     *                    │                  │
     *              PER 갱신 구간         Redis 삭제
     *           (조기 갱신 시도)    (이 전에 갱신 안 되면 미스)
     *
     * @param key       캐시 키
     * @param dbLoader  DB 로딩 함수
     * @return 캐시 또는 DB에서 조회한 값
     */
    public String getWithCompactPER(String key, Supplier<String> dbLoader) {
        String cacheKey = CACHE_KEY_PREFIX + "compact:" + key;

        String raw = stringRedisTemplate.opsForValue().get(cacheKey);

        if (raw == null) {
            log.info("[PER-Compact] 캐시 미스 - key={}", key);
            return recomputeCompact(key, dbLoader);
        }

        // 파싱: "value|expiryMillis|deltaMillis"
        String[] parts = raw.split("\\|", 3);
        if (parts.length < 3) {
            return recomputeCompact(key, dbLoader);
        }

        String value = parts[0];
        long expiryMillis = Long.parseLong(parts[1]);
        long deltaMillis = Long.parseLong(parts[2]);

        // PER 수식 평가
        if (shouldRecompute(deltaMillis, BETA, expiryMillis)) {
            log.info("[PER-Compact] 조기 재계산 - key={}", key);
            return recomputeCompact(key, dbLoader);
        }

        return value;
    }

    /**
     * 간소화 버전의 재계산 + 캐시 저장
     */
    private String recomputeCompact(String key, Supplier<String> dbLoader) {
        String cacheKey = CACHE_KEY_PREFIX + "compact:" + key;

        long start = System.currentTimeMillis();
        String value = dbLoader.get();
        long delta = System.currentTimeMillis() - start;

        if (value == null) {
            return null;
        }

        long expiryMillis = System.currentTimeMillis() + DEFAULT_TTL.toMillis();

        // "value|expiry|delta" 형태로 저장
        String packed = value + "|" + expiryMillis + "|" + delta;

        // 물리적 TTL = 논리적 TTL × 2 (조기 갱신 구간 확보)
        Duration physicalTtl = DEFAULT_TTL.multipliedBy(2);
        stringRedisTemplate.opsForValue().set(cacheKey, packed, physicalTtl);

        log.info("[PER-Compact] 캐시 저장 - key={}, delta={}ms", key, delta);

        return value;
    }

    // ================================================================
    // [3] PER + TTL Jitter 조합
    // ================================================================

    /**
     * PER + TTL Jitter 조합
     *
     * PER 알고리즘에 TTL Jitter를 결합하여 이중 방어.
     *
     * TTL Jitter란?
     * - 캐시 항목마다 TTL에 랜덤 편차를 추가
     * - 같은 시점에 SET된 캐시들이 동시에 만료되는 것을 방지
     * - 특히 캐시 워밍이나 배치 SET 시 유용
     *
     *   Without Jitter:
     *     key1 ─────────────── 만료 ┐
     *     key2 ─────────────── 만료 ├── 동시 만료!
     *     key3 ─────────────── 만료 ┘
     *
     *   With Jitter:
     *     key1 ────────────── 만료 (9분 22초)
     *     key2 ──────────────── 만료 (10분 47초)
     *     key3 ─────────────────── 만료 (11분 15초)
     *                              ↑ 만료 시점 분산
     *
     * @param key       캐시 키
     * @param dbLoader  DB 로딩 함수
     * @return 캐시 또는 DB에서 조회한 값
     */
    public String getWithPERAndJitter(String key, Supplier<String> dbLoader) {
        String cacheKey = CACHE_KEY_PREFIX + "jitter:" + key;

        String raw = stringRedisTemplate.opsForValue().get(cacheKey);

        if (raw == null) {
            log.info("[PER+Jitter] 캐시 미스 - key={}", key);
            return recomputeWithJitter(key, dbLoader);
        }

        String[] parts = raw.split("\\|", 3);
        if (parts.length < 3) {
            return recomputeWithJitter(key, dbLoader);
        }

        String value = parts[0];
        long expiryMillis = Long.parseLong(parts[1]);
        long deltaMillis = Long.parseLong(parts[2]);

        if (shouldRecompute(deltaMillis, BETA, expiryMillis)) {
            log.info("[PER+Jitter] 조기 재계산 - key={}", key);
            return recomputeWithJitter(key, dbLoader);
        }

        return value;
    }

    /**
     * TTL Jitter를 적용한 재계산
     *
     * TTL에 ±20% 범위의 랜덤 편차를 추가한다.
     * 예: 기본 TTL 10분 → 실제 TTL 8분~12분 사이 랜덤
     */
    private String recomputeWithJitter(String key, Supplier<String> dbLoader) {
        String cacheKey = CACHE_KEY_PREFIX + "jitter:" + key;

        long start = System.currentTimeMillis();
        String value = dbLoader.get();
        long delta = System.currentTimeMillis() - start;

        if (value == null) {
            return null;
        }

        // TTL Jitter: 기본 TTL ± 20%
        long baseTtlMillis = DEFAULT_TTL.toMillis();
        long jitter = (long) (baseTtlMillis * 0.2 * (ThreadLocalRandom.current().nextDouble() * 2 - 1));
        long jitteredTtlMillis = baseTtlMillis + jitter;

        long expiryMillis = System.currentTimeMillis() + jitteredTtlMillis;
        String packed = value + "|" + expiryMillis + "|" + delta;

        // 물리적 TTL = 논리적 TTL × 2
        Duration physicalTtl = Duration.ofMillis(jitteredTtlMillis * 2);
        stringRedisTemplate.opsForValue().set(cacheKey, packed, physicalTtl);

        log.info("[PER+Jitter] 캐시 저장 - key={}, delta={}ms, ttl={}초",
                key, delta, jitteredTtlMillis / 1000);

        return value;
    }

    // ================================================================
    // [4] Redis SPOF 방지 - DB 폴백 (Graceful Degradation)
    // ================================================================

    /**
     * Redis 장애 시 DB로 자동 전환하는 PER 조회
     *
     * Redis가 SPOF(Single Point of Failure)가 되지 않도록,
     * Redis 접근 실패 시 DB 직접 조회로 폴백한다.
     *
     * ── 동작 흐름 ──
     *
     * ┌────────────────────────────────────────────────────────────────┐
     * │ getWithPERAndFallback(key, dbLoader)                           │
     * │   ↓                                                            │
     * │ try: Redis 캐시 조회 (PER 로직)                                │
     * │   ↓ 성공                                                       │
     * │   → PER 로직 수행 (조기 재계산 포함) → 값 반환                   │
     * │                                                                │
     * │   ↓ Redis 예외 발생 (연결 실패, 타임아웃 등)                     │
     * │ catch: RedisConnectionFailureException 등                      │
     * │   ↓                                                            │
     * │   → 경고 로그 출력                                              │
     * │   → DB 직접 조회 (Redis 저장 생략) → 값 반환                     │
     * │                                                                │
     * │ 결과: Redis 장애와 무관하게 서비스 정상 동작                      │
     * └────────────────────────────────────────────────────────────────┘
     *
     * ── 폴백 시 주의사항 ──
     *
     * 1. DB 부하 급증 대비
     *    - Redis 장애 시 모든 요청이 DB로 집중
     *    - Connection Pool 크기, DB 스케일링 사전 준비 필요
     *    - Rate Limiting 또는 요청 큐잉 고려
     *
     * 2. 모니터링 필수
     *    - 폴백 발생 횟수를 메트릭으로 수집 (Micrometer 등)
     *    - 알림 설정: 폴백 빈도가 임계치 초과 시 경보
     *
     * 3. 복구 후 캐시 워밍
     *    - Redis 복구 후 캐시가 비어있으므로 Cold Start 발생
     *    - 캐시 워밍 또는 PER의 점진적 갱신으로 완화
     *
     * @param key       캐시 키
     * @param dbLoader  DB 로딩 함수
     * @return 캐시 또는 DB에서 조회한 값 (Redis 장애 시에도 반환 보장)
     */
    public String getWithPERAndFallback(String key, Supplier<String> dbLoader) {
        try {
            // 정상 경로: PER 알고리즘 기반 캐시 조회
            return getWithPER(key, dbLoader);

        } catch (Exception e) {
            // Redis 장애 감지 → DB 폴백
            log.warn("[PER-Fallback] Redis 접근 실패, DB 직접 조회로 전환 - key={}, error={}",
                    key, e.getMessage());

            // DB 직접 조회 (캐시 저장 생략)
            return dbLoader.get();
        }
    }

    /**
     * Redis 장애 시 DB 폴백 + 재계산 결과 캐시 복구 시도
     *
     * 기본 폴백보다 한 단계 발전된 패턴:
     * - DB 조회 성공 후, Redis 저장을 비동기로 시도 (실패해도 무시)
     * - Redis가 간헐적으로 불안정한 경우 캐시 복구 가능성 제공
     *
     * ┌──────────────────────────────────────────────────────────────┐
     * │ Redis 조회 실패                                               │
     * │   ↓                                                          │
     * │ DB 직접 조회 → 값 반환                                        │
     * │   ↓ (비동기)                                                  │
     * │ Redis 저장 시도 (best-effort)                                 │
     * │   ↓ 성공 → 다음 요청부터 캐시 히트                             │
     * │   ↓ 실패 → 무시 (다음 요청도 DB 폴백)                         │
     * └──────────────────────────────────────────────────────────────┘
     *
     * @param key       캐시 키
     * @param dbLoader  DB 로딩 함수
     * @return 캐시 또는 DB에서 조회한 값
     */
    public String getWithPERAndRecovery(String key, Supplier<String> dbLoader) {
        try {
            return getWithPER(key, dbLoader);
        } catch (Exception redisEx) {
            log.warn("[PER-Recovery] Redis 접근 실패 - key={}, error={}", key, redisEx.getMessage());

            // DB 직접 조회
            String value = dbLoader.get();

            // Redis 저장 시도 (best-effort: 실패해도 서비스 영향 없음)
            tryRecoverCache(key, value);

            return value;
        }
    }

    /**
     * Redis 캐시 복구 시도 (best-effort)
     *
     * Redis가 일시적으로 불안정한 경우를 대비하여,
     * DB 조회 결과를 Redis에 저장 시도한다.
     * 실패해도 예외를 전파하지 않는다.
     */
    private void tryRecoverCache(String key, String value) {
        if (value == null) {
            return;
        }
        try {
            String cacheKey = CACHE_KEY_PREFIX + key;
            stringRedisTemplate.opsForValue().set(cacheKey, value, DEFAULT_TTL);
            log.info("[PER-Recovery] 캐시 복구 성공 - key={}", key);
        } catch (Exception e) {
            // 복구 실패 → 무시 (다음 요청에서 재시도)
            log.debug("[PER-Recovery] 캐시 복구 실패 (무시) - key={}, error={}", key, e.getMessage());
        }
    }

    // ================================================================
    // [5] Redis Circuit Breaker + PER + DB 폴백 (통합 패턴)
    // ================================================================

    /** Redis 연속 실패 횟수 */
    private volatile int redisFailureCount = 0;

    /** Circuit Breaker OPEN 시각 */
    private volatile long circuitOpenedAt = 0;

    /** OPEN 전환 임계값 */
    private static final int REDIS_FAILURE_THRESHOLD = 5;

    /** OPEN → HALF_OPEN 냉각 시간 (초) */
    private static final long REDIS_RECOVERY_SECONDS = 30;

    /**
     * Redis Circuit Breaker + PER + DB 폴백 통합
     *
     * PER 알고리즘에 Redis 전용 Circuit Breaker를 결합하여,
     * Redis 장애 시 자동으로 DB 직접 조회 모드로 전환한다.
     *
     * ── 상태 전이 ──
     *
     * ┌────────────────────────────────────────────────────────────────┐
     * │ CLOSED (정상)                                                  │
     * │   → Redis PER 캐시 조회 정상 수행                               │
     * │   → Redis 실패 시 failureCount++                               │
     * │   → failureCount >= 5 → OPEN으로 전환                          │
     * │                                                                │
     * │ OPEN (Redis 차단)                                              │
     * │   → Redis 접근 없이 DB 직접 조회 (Stampede 위험이지만 서비스 유지)│
     * │   → 30초 경과 → HALF_OPEN                                      │
     * │                                                                │
     * │ HALF_OPEN (Redis 시험)                                         │
     * │   → Redis PER 1회 시도                                         │
     * │   → 성공 → CLOSED (정상 복귀)                                   │
     * │   → 실패 → OPEN (다시 차단)                                     │
     * └────────────────────────────────────────────────────────────────┘
     *
     * @param key       캐시 키
     * @param dbLoader  DB 로딩 함수
     * @return 캐시 또는 DB에서 조회한 값
     */
    public String getWithCircuitBreaker(String key, Supplier<String> dbLoader) {
        // OPEN 상태: Redis 접근 없이 DB 직접 조회
        if (redisFailureCount >= REDIS_FAILURE_THRESHOLD) {
            long elapsed = (System.currentTimeMillis() - circuitOpenedAt) / 1000;

            if (elapsed < REDIS_RECOVERY_SECONDS) {
                // 냉각 기간 → DB 직접 조회
                log.debug("[PER-CB] OPEN 상태 - DB 직접 조회, 남은 냉각={}초", REDIS_RECOVERY_SECONDS - elapsed);
                return dbLoader.get();
            }

            // 냉각 완료 → HALF_OPEN: Redis 시험 1회
            log.info("[PER-CB] OPEN → HALF_OPEN (냉각 완료, Redis 시험)");
        }

        // CLOSED 또는 HALF_OPEN: Redis PER 시도
        try {
            String result = getWithPER(key, dbLoader);

            // 성공 → 카운터 리셋 (CLOSED 상태)
            if (redisFailureCount > 0) {
                log.info("[PER-CB] Redis 복구 확인 → CLOSED (failureCount 리셋)");
            }
            redisFailureCount = 0;

            return result;

        } catch (Exception e) {
            // 실패 → 카운터 증가
            redisFailureCount++;

            if (redisFailureCount >= REDIS_FAILURE_THRESHOLD) {
                circuitOpenedAt = System.currentTimeMillis();
                log.warn("[PER-CB] CLOSED → OPEN (연속 실패 {}회) - DB 전환", redisFailureCount);
            } else {
                log.warn("[PER-CB] Redis 실패 ({}/{}) - DB 폴백",
                        redisFailureCount, REDIS_FAILURE_THRESHOLD);
            }

            return dbLoader.get();
        }
    }

    // ================================================================
    // [6] 사용 예시
    // ================================================================

    /**
     * PER 적용 사용 예시
     *
     * Controller/Service에서 호출하는 방법:
     *
     * ── 기본 사용 ──
     *
     *   String product = perService.getWithPER("product:123", () -> {
     *       return productRepository.findById(123L)
     *           .map(Product::toJson)
     *           .orElse(null);
     *   });
     *
     * ── 간소화 버전 ──
     *
     *   String config = perService.getWithCompactPER("config:site", () -> {
     *       return configRepository.findByKey("site").getValue();
     *   });
     *
     * ── PER + Jitter (대규모 트래픽) ──
     *
     *   String ranking = perService.getWithPERAndJitter("ranking:daily", () -> {
     *       return rankingService.calculateDailyRanking().toJson();
     *   });
     */
    public void usageExample() {
        // 기본 PER
        String value1 = getWithPER("product:100", () -> {
            log.info("[DB] 상품 조회 - id=100");
            return "{ \"id\": 100, \"name\": \"Spring Boot 실전\" }";
        });

        // 간소화 PER
        String value2 = getWithCompactPER("config:cache-ttl", () -> {
            log.info("[DB] 설정 조회 - key=cache-ttl");
            return "600";
        });

        // PER + Jitter
        String value3 = getWithPERAndJitter("ranking:weekly", () -> {
            log.info("[DB] 랭킹 계산 - type=weekly");
            return "[{\"rank\":1,\"userId\":42}]";
        });
    }
}
