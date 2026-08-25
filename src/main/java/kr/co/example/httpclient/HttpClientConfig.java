package kr.co.example.httpclient;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * REST API 호출 방식 설정 — RestTemplate, RestClient, OpenFeign, WebClient 등.
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  Spring REST 클라이언트 발전 역사 (버전별 도입 시기)                        │
 * ├──────────────┬───────────────────────────────────────────────────────────┤
 * │  RestTemplate│ Spring 3.0 (2009)                                        │
 * │              │ 동기/블로킹 방식, 가장 오래된 HTTP 클라이언트               │
 * │              │ Spring 5.0부터 유지보수 모드 (신규 기능 추가 안 함)         │
 * │              │ 여전히 많은 프로젝트에서 사용 중                            │
 * ├──────────────┼───────────────────────────────────────────────────────────┤
 * │  WebClient   │ Spring 5.0 / Boot 2.0 (2017)                             │
 * │              │ 비동기/논블로킹 방식 (Reactor 기반: Mono, Flux)             │
 * │              │ spring-boot-starter-webflux 필요                          │
 * │              │ 동기 방식으로도 사용 가능 (.block())                        │
 * │              │ → 기존 NettyWebClientConfig.java 참고                     │
 * ├──────────────┼───────────────────────────────────────────────────────────┤
 * │  OpenFeign   │ Spring Cloud (Netflix OSS → Spring Cloud OpenFeign)       │
 * │              │ 선언적 인터페이스 기반 HTTP 클라이언트                       │
 * │              │ MSA 서비스 간 통신에 주로 사용                              │
 * │              │ spring-cloud-starter-openfeign 필요                       │
 * │              │ Spring Cloud BOM 버전 관리 필요:                           │
 * │              │   Boot 3.3.x → Cloud 2023.0.x                            │
 * ├──────────────┼───────────────────────────────────────────────────────────┤
 * │  RestClient  │ Spring 6.1 / Boot 3.2 (2023.11)                          │
 * │              │ 동기/블로킹, RestTemplate의 현대적 대체                     │
 * │              │ Fluent API, 추가 의존성 불필요 (spring-boot-starter-web)   │
 * │              │ Spring 공식 권장 동기 HTTP 클라이언트                       │
 * ├──────────────┼───────────────────────────────────────────────────────────┤
 * │  Java        │ JDK 11+ (2018)                                           │
 * │  HttpClient  │ JDK 내장, Spring 의존 없음                                │
 * │              │ 동기/비동기 모두 지원                                      │
 * │              │ HTTP/2 기본 지원                                          │
 * │              │ 외부 라이브러리 없이 HTTP 호출 시 사용                      │
 * └──────────────┴───────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  어떤 것을 선택해야 하는가? (Spring Boot 3.x 기준)                         │
 * ├──────────────────────────────────────────────────────────────────────────┤
 * │  동기 호출 (일반 REST API)   → RestClient (권장) 또는 RestTemplate        │
 * │  비동기/리액티브             → WebClient (Mono/Flux)                      │
 * │  MSA 서비스 간 통신          → OpenFeign (선언적, 간편)                   │
 * │  Spring 미사용               → Java HttpClient (JDK 내장)                │
 * │  외부 결제 API 등            → WebClient 또는 RestClient                  │
 * └──────────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
@Configuration
@EnableFeignClients(basePackages = "kr.co.example.httpclient") // Feign 클라이언트 스캔 패키지
public class HttpClientConfig {

    // ────────────────────────────────────────
    // [1] RestTemplate Bean 등록
    // ────────────────────────────────────────

    /**
     * RestTemplate Bean.
     *
     * <pre>
     * Spring 3.0 (2009) 도입, Spring Boot 3.x에서도 사용 가능.
     * Spring 5.0(2017)부터 유지보수 모드 — 새 기능 추가 안 됨.
     *
     * RestTemplate은 Spring Boot에서 자동 Bean 등록하지 않음
     * → 직접 @Bean 등록 필요 (RestTemplateBuilder 사용 권장).
     *
     * 실무에서 타임아웃 설정 필수:
     * - connectTimeout: 서버 연결 최대 대기 시간
     * - readTimeout: 응답 수신 최대 대기 시간
     * </pre>
     */
    @Bean
    public RestTemplate restTemplate() {
        // RestTemplateBuilder로 타임아웃 설정 (실무 필수)
        // return new RestTemplateBuilder()
        //     .setConnectTimeout(Duration.ofSeconds(5))
        //     .setReadTimeout(Duration.ofSeconds(10))
        //     .build();

        return new RestTemplate();
    }

    // ────────────────────────────────────────
    // [2] RestClient Bean 등록
    // ────────────────────────────────────────

    /**
     * RestClient Bean.
     *
     * <pre>
     * Spring 6.1 / Boot 3.2 (2023.11) 신규 도입.
     * RestTemplate의 현대적 대체 — Fluent API 제공.
     *
     * 특징:
     * - spring-boot-starter-web만으로 사용 가능 (추가 의존성 없음)
     * - WebClient와 유사한 Fluent API (.get().uri().retrieve().body())
     * - 동기/블로킹 방식
     * - RestTemplate과 동일한 인프라 사용 (HttpMessageConverter 등)
     * - 기존 RestTemplate에서 마이그레이션 가능: RestClient.create(restTemplate)
     * </pre>
     */
    @Bean
    public RestClient restClient() {
        return RestClient.builder()
                .baseUrl("http://localhost:8080") // 기본 URL
                // .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                // .requestInterceptor((request, body, execution) -> {
                //     // 모든 요청에 공통 헤더 추가 (인증 토큰 등)
                //     request.getHeaders().add("X-Api-Key", "api-key-value");
                //     return execution.execute(request, body);
                // })
                .build();
    }

    /**
     * 서비스별 RestClient — concert-msa-project 패턴 (서비스 URL 분리).
     *
     * <pre>
     * MSA에서 서비스별로 RestClient를 분리하여 관리:
     *
     * @Bean
     * @Qualifier("concertRestClient")
     * public RestClient concertRestClient(ServiceUrlProperties props) {
     *     return RestClient.builder()
     *         .baseUrl(props.getConcertUrl())  // http://concert-service:8082
     *         .build();
     * }
     *
     * @Bean
     * @Qualifier("paymentRestClient")
     * public RestClient paymentRestClient(ServiceUrlProperties props) {
     *     return RestClient.builder()
     *         .baseUrl(props.getPaymentUrl())  // http://payment-service:8084
     *         .build();
     * }
     * </pre>
     */
}
