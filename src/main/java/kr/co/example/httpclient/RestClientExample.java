package kr.co.example.httpclient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * RestClient 예제 — Spring 6.1 / Boot 3.2 (2023.11) 신규 도입.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  RestClient — Spring Framework 6.1+ / Spring Boot 3.2+          │
 * │  의존성: spring-boot-starter-web (추가 의존성 불필요)             │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  RestTemplate의 현대적 대체 (Spring 공식 권장)                    │
 * │  Fluent API — WebClient와 유사한 체이닝 방식                     │
 * │  동기/블로킹 — WebFlux 의존성 불필요                              │
 * │  기존 RestTemplate 인프라 재사용 (HttpMessageConverter 등)        │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  RestClient vs RestTemplate 비교                                 │
 * ├──────────────────┬──────────────────────────────────────────────┤
 * │                  │  RestClient         │  RestTemplate          │
 * ├──────────────────┼─────────────────────┼────────────────────────┤
 * │  도입 버전       │  6.1 / Boot 3.2     │  3.0 (2009)            │
 * │  API 스타일      │  Fluent (체이닝)     │  메서드 기반            │
 * │  에러 처리       │  .onStatus() 핸들러  │  예외 발생 (try-catch)  │
 * │  타입 안전       │  높음 (제네릭 추론)  │  보통                   │
 * │  null 반환       │  toBodilessEntity()  │  void 메서드 분리       │
 * │  상태            │  활발히 발전 중      │  유지보수 모드           │
 * └──────────────────┴─────────────────────┴────────────────────────┘
 *
 * concert-msa-project에서 실제 사용:
 * - RestClientConfig.java → 서비스별 RestClient Bean 등록
 * - ConcertServiceClient.java → 좌석 hold/release API 호출
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestClientExample {

    private final RestClient restClient;

    // ────────────────────────────────────────
    // [1] GET — Fluent API 체이닝
    // ────────────────────────────────────────

    /**
     * 단건 조회 — .get().uri().retrieve().body()
     * body()로 응답 본문만 추출 (상태코드/헤더 불필요 시).
     */
    public String getUser(Long id) {
        return restClient
                .get()                              // HTTP GET
                .uri("/api/users/{id}", id)         // URL 템플릿 + 변수
                .retrieve()                         // 요청 실행
                .body(String.class);                // 응답 본문 → String 변환
    }

    /**
     * ResponseEntity로 반환 — 상태코드/헤더 접근 필요 시.
     */
    public ResponseEntity<String> getUserEntity(Long id) {
        return restClient
                .get()
                .uri("/api/users/{id}", id)
                .retrieve()
                .toEntity(String.class);    // ResponseEntity 반환
    }

    /**
     * 쿼리 파라미터 — URI 빌더 사용.
     */
    public String searchUsers(String name, int page) {
        return restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/users")
                        .queryParam("name", name)
                        .queryParam("page", page)
                        .queryParam("size", 10)
                        .build())
                .retrieve()
                .body(String.class);
    }

    // ────────────────────────────────────────
    // [2] POST — 요청 본문 포함
    // ────────────────────────────────────────

    /**
     * POST — JSON 요청 본문.
     * .contentType() + .body()로 요청 본문 설정.
     */
    public String createUser(Map<String, Object> requestBody) {
        return restClient
                .post()
                .uri("/api/users")
                .contentType(MediaType.APPLICATION_JSON) // Content-Type 헤더
                .body(requestBody)                       // 요청 본문 (자동 JSON 직렬화)
                .retrieve()
                .body(String.class);
    }

    // ────────────────────────────────────────
    // [3] PATCH / PUT / DELETE
    // ────────────────────────────────────────

    /**
     * PATCH — 부분 수정 (concert-msa-project 패턴).
     *
     * <pre>
     * concert-msa-project 실사용 예:
     * ConcertServiceClient에서 좌석 상태 변경 시 PATCH 사용
     * restClient.patch().uri("/api/seats/{seatId}/hold", seatId).retrieve().body()
     * </pre>
     */
    public String patchUser(Long id, Map<String, Object> body) {
        return restClient
                .patch()
                .uri("/api/users/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    /** DELETE — 삭제 후 본문 없는 응답 */
    public void deleteUser(Long id) {
        restClient
                .delete()
                .uri("/api/users/{id}", id)
                .retrieve()
                .toBodilessEntity(); // 204 No Content 등 본문 없는 응답 처리
    }

    // ────────────────────────────────────────
    // [4] 커스텀 헤더 — 인증 토큰 등
    // ────────────────────────────────────────

    /**
     * Bearer 토큰 포함 요청.
     */
    public String getWithAuth(Long id, String token) {
        return restClient
                .get()
                .uri("/api/users/{id}", id)
                .header("Authorization", "Bearer " + token)  // 인증 헤더
                .header("X-Request-Id", java.util.UUID.randomUUID().toString()) // 추적 ID
                .retrieve()
                .body(String.class);
    }

    // ────────────────────────────────────────
    // [5] 에러 처리 — onStatus() 핸들러
    // ────────────────────────────────────────

    /**
     * 상태코드별 에러 처리 — RestTemplate의 try-catch보다 깔끔.
     *
     * <pre>
     * .onStatus() 핸들러:
     * - RestTemplate: 4xx/5xx에서 무조건 예외 발생 → try-catch 필수
     * - RestClient:   .onStatus()로 상태코드별 처리 가능 → 더 유연
     * </pre>
     */
    public String getWithErrorHandling(Long id) {
        return restClient
                .get()
                .uri("/api/users/{id}", id)
                .retrieve()
                // 4xx 에러 핸들링
                .onStatus(status -> status.is4xxClientError(), (request, response) -> {
                    log.warn("클라이언트 에러: status={}, uri={}",
                            response.getStatusCode(), request.getURI());
                    // 커스텀 예외 던지기 또는 기본값 처리
                })
                // 5xx 에러 핸들링
                .onStatus(status -> status.is5xxServerError(), (request, response) -> {
                    log.error("서버 에러: status={}", response.getStatusCode());
                    throw new RuntimeException("외부 서비스 오류");
                })
                .body(String.class);
    }

    // ────────────────────────────────────────
    // [6] RestTemplate → RestClient 마이그레이션
    // ────────────────────────────────────────

    /**
     * 기존 RestTemplate에서 RestClient로 마이그레이션.
     *
     * <pre>
     * // 기존 RestTemplate 기반으로 RestClient 생성
     * RestClient restClient = RestClient.create(existingRestTemplate);
     *
     * // 기존 RestTemplate의 interceptor, messageConverter 등이 그대로 적용
     * // → 점진적 마이그레이션 가능
     * </pre>
     */
    public void migrationExample() {
        // RestClient.create(restTemplate) 으로 점진적 마이그레이션 가능
    }
}
