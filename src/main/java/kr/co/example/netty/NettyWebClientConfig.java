package kr.co.example.netty;

import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import javax.net.ssl.SSLException;
import java.time.Duration;

/**
 * ========================================================================
 * [1] Reactor Netty + WebClient 설정
 * ========================================================================
 *
 * ── 핵심 개념 ──
 *
 * 1. Reactor Netty란?
 *    - Spring WebFlux의 기본 HTTP 클라이언트/서버 엔진
 *    - Non-blocking I/O 기반 (Netty 프레임워크 위에 구축)
 *    - 적은 스레드로 대량의 동시 연결 처리 가능
 *    - Event Loop 모델: 소수의 스레드가 다수의 연결을 관리
 *
 * 2. WebClient vs RestTemplate
 *    ┌──────────────┬──────────────────┬──────────────────┐
 *    │     항목      │   RestTemplate   │    WebClient     │
 *    ├──────────────┼──────────────────┼──────────────────┤
 *    │ I/O 모델     │ Blocking         │ Non-blocking     │
 *    │ 스레드 사용   │ 요청당 1스레드    │ Event Loop 공유  │
 *    │ 대량 요청     │ 스레드 풀 고갈    │ 효율적 처리      │
 *    │ 지원 상태     │ Deprecated(6.1)  │ 권장             │
 *    │ 반환 타입     │ 동기 객체        │ Mono/Flux        │
 *    └──────────────┴──────────────────┴──────────────────┘
 *
 * 3. ConnectionProvider (커넥션 풀)
 *    - maxConnections: 동시 최대 연결 수
 *    - maxIdleTime: 유휴 연결 유지 시간
 *    - maxLifeTime: 연결 최대 수명
 *    - pendingAcquireTimeout: 풀에서 연결 획득 대기 시간
 *
 * 4. SSL 설정
 *    - sessionCacheSize: SSL 세션 캐시 (핸드셰이크 재사용으로 성능 향상)
 *    - sessionTimeout: 캐시된 세션 유효 시간
 *
 * ── 사용 특성 ──
 *
 * 장점:
 * - 적은 리소스로 대량의 동시 HTTP 요청 처리 가능
 * - 논블로킹이므로 스레드가 I/O 대기 중 다른 작업 수행
 * - 요청/응답 필터링, 타임아웃 등 세밀한 제어 가능
 * - Reactor 파이프라인과 자연스러운 통합 (retry, timeout, fallback)
 *
 * 주의점:
 * - 블로킹 코드와 혼용 시 Event Loop 스레드 블로킹 위험
 * - 디버깅이 RestTemplate보다 복잡 (스택트레이스가 비직관적)
 * - 커넥션 풀 설정이 부적절하면 연결 고갈 발생 가능
 */
@Slf4j
@Configuration
public class NettyWebClientConfig {

    /**
     * 외부 API 호출 전용 WebClient
     * - SSL, 커넥션 풀, 타임아웃을 세밀하게 설정
     */
    @Bean
    public WebClient externalApiWebClient() throws SSLException {

        // [1] SSL Context 설정
        SslContext sslContext = SslContextBuilder
                .forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE) // 예제용
                .sessionCacheSize(10)    // SSL 세션 캐시 크기
                .sessionTimeout(60)      // 세션 유효 시간(초)
                .build();

        // [2] ConnectionProvider (커넥션 풀) 설정
        ConnectionProvider connectionProvider = ConnectionProvider.builder("api-pool")
                .maxConnections(200)                           // 최대 동시 연결
                .maxIdleTime(Duration.ofSeconds(60))           // 유휴 연결 유지 시간
                .maxLifeTime(Duration.ofMinutes(10))           // 연결 최대 수명
                .pendingAcquireTimeout(Duration.ofSeconds(10)) // 연결 대기 시간
                .build();

        // [3] HttpClient 설정
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .secure(sslSpec -> sslSpec.sslContext(sslContext))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .responseTimeout(Duration.ofSeconds(10))
                .compress(true);

        // [4] WebClient 빌더
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(logRequest())
                .filter(logResponse())
                .build();
    }

    /**
     * 범용 WebClient (간단한 호출용)
     */
    @Bean
    public WebClient defaultWebClient() {
        return WebClient.builder()
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    private ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            log.debug("[WebClient] Request: {} {}", request.method(), request.url());
            return Mono.just(request);
        });
    }

    private ExchangeFilterFunction logResponse() {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            log.debug("[WebClient] Response: {}", response.statusCode());
            return Mono.just(response);
        });
    }
}
