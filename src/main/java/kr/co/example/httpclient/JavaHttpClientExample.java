package kr.co.example.httpclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Java HttpClient 예제 — JDK 11+ (2018) 내장 HTTP 클라이언트.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  Java HttpClient — JDK 11+ (2018, java.net.http 패키지)             │
 * │  의존성: 없음 (JDK 내장)                                             │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  특징:                                                               │
 * │  - Spring 프레임워크 의존 없이 HTTP 호출 가능                          │
 * │  - HTTP/1.1, HTTP/2 모두 지원 (HTTP/2 기본)                          │
 * │  - 동기(send) + 비동기(sendAsync) 모두 지원                           │
 * │  - 불변 객체 기반 (Builder 패턴)                                      │
 * │  - 쿠키 관리, 리다이렉트, 프록시 내장 지원                              │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  사용 시기                                                           │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  - Spring 없는 순수 Java 프로젝트                                     │
 * │  - 유틸리티/배치 프로그램에서 간단한 HTTP 호출                          │
 * │  - 외부 라이브러리 의존을 최소화하고 싶을 때                             │
 * │  - Spring 프로젝트에서는 RestClient/WebClient가 더 편리               │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  핵심 클래스 3개                                                     │
 * ├──────────────────┬──────────────────────────────────────────────────┤
 * │  HttpClient      │ HTTP 클라이언트 (설정: 타임아웃, HTTP 버전 등)     │
 * │  HttpRequest     │ HTTP 요청 (메서드, URL, 헤더, 본문)               │
 * │  HttpResponse    │ HTTP 응답 (상태코드, 헤더, 본문)                   │
 * └──────────────────┴──────────────────────────────────────────────────┘
 * </pre>
 */
@Slf4j
@Service
public class JavaHttpClientExample {

    /** ObjectMapper — JSON 직렬화/역직렬화 (Jackson) */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * HttpClient 인스턴스 — 재사용 권장 (스레드 안전).
     * 매 요청마다 생성하면 리소스 낭비.
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)        // HTTP/2 사용 (기본값)
            .connectTimeout(Duration.ofSeconds(5))      // 연결 타임아웃
            .followRedirects(HttpClient.Redirect.NORMAL) // 3xx 리다이렉트 자동 추적
            .build();

    // ────────────────────────────────────────
    // [1] 동기 GET 요청
    // ────────────────────────────────────────

    /**
     * 동기 GET — send()는 응답이 올 때까지 블로킹.
     */
    public String getUser(Long id) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.example.com/users/" + id))
                .header("Accept", "application/json")
                .GET()          // GET 메서드 (기본값이므로 생략 가능)
                .build();

        // send() — 동기 호출 (블로킹)
        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString() // 응답 본문을 String으로 변환
        );

        log.info("상태코드: {}", response.statusCode());
        log.info("헤더: {}", response.headers().map());
        log.info("본문: {}", response.body());

        if (response.statusCode() != 200) {
            throw new RuntimeException("API 호출 실패: " + response.statusCode());
        }

        return response.body();
    }

    // ────────────────────────────────────────
    // [2] 동기 POST 요청 (JSON 본문)
    // ────────────────────────────────────────

    /**
     * 동기 POST — JSON 본문 전송.
     */
    public String createUser(Map<String, Object> body) throws Exception {
        // Map → JSON 문자열 직렬화
        String jsonBody = objectMapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.example.com/users"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer my-token")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)) // JSON 본문
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        return response.body();
    }

    // ────────────────────────────────────────
    // [3] 비동기 요청 — sendAsync()
    // ────────────────────────────────────────

    /**
     * 비동기 GET — sendAsync()는 CompletableFuture 반환.
     *
     * <pre>
     * 동기(send) vs 비동기(sendAsync):
     * - send():      현재 스레드 블로킹, 응답 올 때까지 대기
     * - sendAsync(): 즉시 CompletableFuture 반환, 별도 스레드에서 처리
     *
     * 비동기가 유리한 경우:
     * - 여러 API를 동시에 호출할 때 (병렬 요청)
     * - UI 스레드를 블로킹하지 않아야 할 때
     * </pre>
     */
    public CompletableFuture<String> getUserAsync(Long id) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.example.com/users/" + id))
                .header("Accept", "application/json")
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("API 실패: " + response.statusCode());
                    }
                    return response.body();
                })
                .exceptionally(ex -> {
                    log.error("비동기 API 호출 실패", ex);
                    return null;
                });
    }

    /**
     * 여러 API 동시 호출 — CompletableFuture.allOf() 조합.
     */
    public void parallelRequests() throws Exception {
        CompletableFuture<String> user1 = getUserAsync(1L);
        CompletableFuture<String> user2 = getUserAsync(2L);
        CompletableFuture<String> user3 = getUserAsync(3L);

        // 모든 요청 완료 대기
        CompletableFuture.allOf(user1, user2, user3).join();

        log.info("User1: {}", user1.get());
        log.info("User2: {}", user2.get());
        log.info("User3: {}", user3.get());
    }

    // ────────────────────────────────────────
    // [4] PUT / PATCH / DELETE
    // ────────────────────────────────────────

    /** PUT — 전체 수정 */
    public String putUser(Long id, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.example.com/users/" + id))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    /** DELETE — 삭제 */
    public int deleteUser(Long id) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.example.com/users/" + id))
                .DELETE()
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    // ────────────────────────────────────────
    // [5] BodyHandlers — 응답 본문 처리 방식
    // ────────────────────────────────────────

    /**
     * BodyHandlers 종류.
     *
     * <pre>
     * ┌────────────────────────────────┬────────────────────────────────┐
     * │  BodyHandlers.ofString()       │ 문자열로 반환 (JSON 등)        │
     * │  BodyHandlers.ofByteArray()    │ byte[]로 반환 (바이너리)       │
     * │  BodyHandlers.ofInputStream()  │ InputStream으로 반환 (스트리밍) │
     * │  BodyHandlers.ofFile(Path)     │ 파일로 직접 저장               │
     * │  BodyHandlers.discarding()     │ 본문 무시 (상태코드만 확인)    │
     * └────────────────────────────────┴────────────────────────────────┘
     * </pre>
     */
    public void bodyHandlerExamples() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.example.com/data"))
                .build();

        // 파일 다운로드 예시
        // HttpResponse<Path> fileResponse = httpClient.send(
        //     request, HttpResponse.BodyHandlers.ofFile(Path.of("/tmp/download.json")));

        // 본문 무시 (상태코드만 확인)
        // HttpResponse<Void> discardResponse = httpClient.send(
        //     request, HttpResponse.BodyHandlers.discarding());
    }
}
