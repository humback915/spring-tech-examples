package kr.co.example.netty;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * ========================================================================
 * WebClient를 활용한 비동기 HTTP 호출 서비스
 * ========================================================================
 *
 * ── Mono vs Flux ──
 *
 * Mono<T>: 0 또는 1개의 결과를 비동기로 반환
 *   - 단건 API 호출, 단건 저장/조회에 사용
 *   - 예: 환불 1건 요청 → Mono<RefundResponse>
 *
 * Flux<T>: 0~N개의 결과를 비동기로 반환
 *   - 목록 조회, 스트리밍, 대량 요청에 사용
 *   - 예: 1,000건의 ID를 순차/병렬로 조회 → Flux<Item>
 *
 * ── 에러 처리 전략 비교 ──
 * ┌──────────────────┬──────────────────────────────────────────┐
 * │ retryWhen         │ 일시적 오류(네트워크, 타임아웃)에 재시도    │
 * │                   │ backoff: 지수적으로 대기 시간 증가         │
 * │                   │ fixedDelay: 일정 간격으로 재시도           │
 * ├──────────────────┼──────────────────────────────────────────┤
 * │ onErrorResume     │ 예외 발생 시 대체 Mono/Flux 반환           │
 * │                   │ 폴백 로직 구현에 사용                      │
 * ├──────────────────┼──────────────────────────────────────────┤
 * │ onErrorReturn     │ 예외 발생 시 고정 기본값 반환              │
 * │                   │ 간단한 폴백에 적합 (로직 없이 값만 반환)    │
 * ├──────────────────┼──────────────────────────────────────────┤
 * │ onStatus          │ HTTP 상태 코드별 분기 처리                 │
 * │                   │ 4xx, 5xx 등 상태에 따라 다른 예외 생성     │
 * └──────────────────┴──────────────────────────────────────────┘
 *
 * ── block() 사용 ──
 *
 * Mono/Flux의 결과를 동기적으로 대기하여 반환.
 * Spring MVC (서블릿 기반) 환경에서 WebClient 결과를 사용할 때 필요.
 * WebFlux 환경에서는 block() 없이 Mono/Flux를 그대로 반환해야 함.
 * (Event Loop 스레드에서 block() 호출 시 IllegalStateException 발생)
 *
 * ── 동시성 제어 ──
 *
 * Flux.flatMap(fn, concurrency)의 두 번째 인자로 동시 실행 수를 제한.
 * 커넥션 풀 크기(200)보다 작은 값(50)으로 설정하여:
 * - 커넥션 풀 고갈 방지
 * - 외부 서버 과부하 방지
 * - 나머지 커넥션을 다른 요청이 사용 가능
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalApiService {

    /** NettyWebClientConfig에서 설정한 WebClient 빈 주입 (커넥션 풀 200, SSL, 타임아웃 설정 포함) */
    private final WebClient externalApiWebClient;

    /**
     * 단건 POST 요청 (Mono 사용)
     *
     * 하나의 외부 API를 호출하고 결과를 Map으로 반환.
     * 에러 발생 시 재시도 후 최종 실패 시 폴백 Map 반환.
     *
     * 호출 체인 흐름:
     * post() → uri() → bodyValue() → retrieve()
     *   → onStatus(4xx 처리) → onStatus(5xx 처리)
     *   → bodyToMono(응답 변환) → retryWhen(재시도)
     *   → onErrorResume(폴백) → block(동기 대기)
     *
     * @param id   요청 식별자 (로깅/폴백용)
     * @param body 요청 바디 (JSON으로 직렬화됨)
     * @return 응답 Map 또는 실패 시 폴백 Map (status=FAILED)
     */
    public Map<String, Object> callSingleApi(String id, Map<String, Object> body) {
        return externalApiWebClient.post()
                .uri("https://api.example.com/v1/process")
                .bodyValue(body)
                // retrieve(): 응답을 받아오는 시작점 (exchange()보다 간결, 메모리 누수 방지)
                .retrieve()
                // 4xx 에러: 클라이언트 오류 (잘못된 파라미터, 인증 실패 등)
                // 응답 바디를 읽어 에러 메시지에 포함 → 디버깅 편의
                .onStatus(
                        status -> status.is4xxClientError(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(b -> Mono.error(new RuntimeException("4xx 에러: " + b)))
                )
                // 5xx 에러: 서버 오류 (외부 서버 장애)
                // 바디를 읽지 않고 바로 에러 생성 → retryWhen에서 재시도 대상
                .onStatus(
                        status -> status.is5xxServerError(),
                        response -> Mono.error(new RuntimeException("서버 오류"))
                )
                // 응답 바디를 Map으로 역직렬화 (Jackson이 자동 처리)
                .bodyToMono(Map.class)
                // 재시도 전략: 지수 백오프
                // backoff(3, 1초): 최대 3회, 초기 대기 1초
                // 재시도 간격: 1초 → 2초 → 4초 (지수적 증가)
                // maxBackoff(5초): 대기 시간 상한선
                // filter: RuntimeException만 재시도 (비즈니스 예외는 제외)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(5))
                        .filter(ex -> ex instanceof RuntimeException))
                // 재시도 모두 실패 시 폴백: 에러 로깅 후 실패 Map 반환
                // 예외를 throw하지 않고 정상 응답으로 처리 → 호출자가 status 필드로 판단
                .onErrorResume(ex -> {
                    log.error("[API] 최종 실패 id={}, error={}", id, ex.getMessage());
                    return Mono.just(Map.of("status", "FAILED", "id", id));
                })
                // block(): Mono의 결과를 동기적으로 대기 (MVC 환경에서 필요)
                .block();
    }

    /**
     * 대량 요청 (Flux + 동시성 제어)
     *
     * 여러 ID에 대해 외부 API를 병렬로 호출하되, 동시 실행 수를 제한.
     *
     * flatMap의 concurrency 파라미터:
     * - 50으로 설정 → 최대 50개의 HTTP 요청이 동시에 실행
     * - 커넥션 풀(200)의 25%만 사용 → 나머지는 다른 요청이 활용
     * - 값이 너무 크면 → 커넥션 풀 고갈, 외부 서버 과부하
     * - 값이 너무 작으면 → 처리 속도 저하
     *
     * @param ids 조회할 ID 목록 (수백~수천 건 가능)
     * @return 각 ID의 조회 결과 Map 리스트 (실패한 항목은 status=FAILED 포함)
     */
    public List<Map> batchCall(List<String> ids) {
        return Flux.fromIterable(ids)
                .flatMap(
                        id -> externalApiWebClient.get()
                                .uri("https://api.example.com/v1/items/{id}", id)
                                .retrieve()
                                .bodyToMono(Map.class)
                                // 재시도: 고정 간격 500ms, 최대 2회
                                // 대량 요청이므로 재시도 횟수를 줄여 전체 처리 시간 단축
                                .retryWhen(Retry.fixedDelay(2, Duration.ofMillis(500)))
                                // 개별 실패 시 폴백: 전체 Flux를 중단하지 않고 실패 항목만 마킹
                                .onErrorResume(ex -> {
                                    log.warn("[Batch] 실패 id={}, error={}", id, ex.getMessage());
                                    return Mono.just(Map.of("status", "FAILED", "id", id));
                                }),
                        50  // 동시 처리 수: 최대 50개 요청이 동시에 실행됨
                )
                // 모든 결과를 List로 수집
                .collectList()
                // 동기 대기 (MVC 환경)
                .block();
    }

    /**
     * GET 요청 + 개별 타임아웃 + 기본값 반환
     *
     * timeout(): 이 요청에만 적용되는 타임아웃 (HttpClient의 responseTimeout과 별개)
     * - HttpClient responseTimeout(10초): 모든 요청의 기본 타임아웃
     * - Mono.timeout(5초): 이 특정 요청에만 적용되는 더 짧은 타임아웃
     * - 둘 중 짧은 쪽이 먼저 트리거됨
     *
     * onErrorReturn(): 어떤 예외든 고정 기본값("UNKNOWN") 반환
     * - onErrorResume보다 간결하지만, 에러 종류별 분기 불가
     * - 상태 조회처럼 실패해도 치명적이지 않은 경우에 적합
     *
     * @param id 조회 대상 ID
     * @return 상태 문자열 또는 에러 시 "UNKNOWN"
     */
    public String getStatus(String id) {
        return externalApiWebClient.get()
                .uri("https://api.example.com/v1/items/{id}/status", id)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(5))   // 이 요청만 5초 타임아웃
                .onErrorReturn("UNKNOWN")          // 모든 에러 → 기본값 반환
                .block();
    }
}
