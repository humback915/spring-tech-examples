package kr.co.example.httpclient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * RestTemplate 예제 — Spring 3.0 (2009) 도입, 동기/블로킹 HTTP 클라이언트.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  RestTemplate — Spring Framework 3.0+ (2009)                    │
 * │  의존성: spring-boot-starter-web (추가 의존성 불필요)             │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  상태: Spring 5.0 (2017)부터 유지보수 모드                       │
 * │  → 새로운 프로젝트에서는 RestClient 또는 WebClient 권장           │
 * │  → 레거시 프로젝트에서는 여전히 널리 사용                          │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * 주요 메서드:
 * ┌──────────────────┬──────────────────────────────────────────────┐
 * │  getForObject()  │ GET → 응답 본문만 반환 (헤더/상태코드 무시)    │
 * │  getForEntity()  │ GET → ResponseEntity 반환 (헤더/상태코드 포함) │
 * │  postForObject() │ POST → 응답 본문만 반환                       │
 * │  postForEntity() │ POST → ResponseEntity 반환                    │
 * │  exchange()      │ 모든 HTTP 메서드 + 요청 헤더 커스터마이징       │
 * │  delete()        │ DELETE 요청                                   │
 * │  put()           │ PUT 요청 (응답 본문 없음)                      │
 * │  patchForObject()│ PATCH 요청                                    │
 * └──────────────────┴──────────────────────────────────────────────┘
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestTemplateExample {

    private final RestTemplate restTemplate;

    // ────────────────────────────────────────
    // [1] GET — 단순 조회
    // ────────────────────────────────────────

    /**
     * getForObject — 응답 본문만 반환 (간단한 조회).
     * 상태코드/헤더가 필요 없을 때 사용.
     */
    public String getSimple(Long id) {
        // URL 변수 치환: {id} → 실제 id값
        String url = "https://api.example.com/users/{id}";
        return restTemplate.getForObject(url, String.class, id);
    }

    /**
     * getForEntity — ResponseEntity로 반환 (상태코드/헤더 접근 가능).
     */
    public ResponseEntity<String> getWithEntity(Long id) {
        String url = "https://api.example.com/users/{id}";
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class, id);

        log.info("상태코드: {}", response.getStatusCode());
        log.info("Content-Type: {}", response.getHeaders().getContentType());
        log.info("본문: {}", response.getBody());

        return response;
    }

    /**
     * 쿼리 파라미터가 있는 GET — UriComponentsBuilder 사용.
     * /users?name=홍길동&page=0&size=10
     */
    public String getWithQueryParams(String name, int page, int size) {
        URI uri = UriComponentsBuilder
                .fromHttpUrl("https://api.example.com/users")
                .queryParam("name", name)
                .queryParam("page", page)
                .queryParam("size", size)
                .build()
                .toUri();

        return restTemplate.getForObject(uri, String.class);
    }

    // ────────────────────────────────────────
    // [2] POST — 데이터 생성
    // ────────────────────────────────────────

    /**
     * postForEntity — JSON 본문 포함 POST 요청.
     * 요청 본문은 자동으로 JSON 직렬화됨 (Jackson HttpMessageConverter).
     */
    public ResponseEntity<String> postWithBody(Map<String, Object> requestBody) {
        String url = "https://api.example.com/users";
        return restTemplate.postForEntity(url, requestBody, String.class);
    }

    // ────────────────────────────────────────
    // [3] exchange — 커스텀 헤더 + 모든 HTTP 메서드
    // ────────────────────────────────────────

    /**
     * exchange — 헤더 커스터마이징이 필요한 경우 (인증 토큰 등).
     * 모든 HTTP 메서드(GET, POST, PUT, PATCH, DELETE) 사용 가능.
     */
    public String getWithAuth(Long id, String token) {
        String url = "https://api.example.com/users/{id}";

        // 헤더 설정
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token); // Authorization: Bearer {token}

        // HttpEntity: 헤더 + 본문 조합 (GET은 본문 없으므로 null)
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                String.class,
                id // URL 변수
        );

        return response.getBody();
    }

    /**
     * exchange — 제네릭 타입 응답 (List&lt;T&gt; 등).
     * ParameterizedTypeReference로 제네릭 타입 정보 보존.
     */
    public List<Map<String, Object>> getList() {
        String url = "https://api.example.com/users";

        // List<Map<String, Object>>처럼 제네릭 타입은 .class로 표현 불가
        // → ParameterizedTypeReference 사용
        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {} // 제네릭 타입 유지
        );

        return response.getBody();
    }

    // ────────────────────────────────────────
    // [4] PUT / PATCH / DELETE
    // ────────────────────────────────────────

    /** PUT — 전체 수정 (응답 본문 없음 → void) */
    public void updateUser(Long id, Map<String, Object> body) {
        restTemplate.put("https://api.example.com/users/{id}", body, id);
    }

    /** PATCH — 부분 수정 (exchange 사용) */
    public String patchUser(Long id, Map<String, Object> body) {
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body);

        ResponseEntity<String> response = restTemplate.exchange(
                "https://api.example.com/users/{id}",
                HttpMethod.PATCH,
                entity,
                String.class,
                id
        );
        return response.getBody();
    }

    /** DELETE — 삭제 */
    public void deleteUser(Long id) {
        restTemplate.delete("https://api.example.com/users/{id}", id);
    }

    // ────────────────────────────────────────
    // [5] 에러 처리
    // ────────────────────────────────────────

    /**
     * HTTP 에러 처리 — try-catch 패턴.
     *
     * <pre>
     * RestTemplate은 4xx/5xx 응답 시 예외를 던짐:
     * - HttpClientErrorException: 4xx (400, 401, 403, 404 등)
     * - HttpServerErrorException: 5xx (500, 502, 503 등)
     * - ResourceAccessException: 연결 실패, 타임아웃 등
     *
     * RestClient/WebClient는 예외 대신 상태코드 핸들링 지원 → 더 유연
     * </pre>
     */
    public String getWithErrorHandling(Long id) {
        try {
            return restTemplate.getForObject(
                    "https://api.example.com/users/{id}", String.class, id);
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("사용자 없음: id={}", id);
            return null;
        } catch (HttpClientErrorException e) {
            log.error("클라이언트 에러: status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }
    }
}
