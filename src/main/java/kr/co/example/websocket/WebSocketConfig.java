package kr.co.example.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * ========================================================================
 * [10] WebSocket + STOMP 설정
 * ========================================================================
 *
 * ── 핵심 개념 ──
 *
 * 1. WebSocket이란?
 *    - 클라이언트-서버 간 양방향 실시간 통신 프로토콜
 *    - HTTP와 달리 연결이 유지됨 (persistent connection)
 *    - 서버에서 클라이언트로 능동적으로 메시지 전송 가능
 *    - 실시간 알림, 채팅, 라이브 대시보드 등에 활용
 *
 * 2. STOMP (Simple Text Oriented Messaging Protocol)
 *    - WebSocket 위에서 동작하는 메시징 프로토콜
 *    - Pub/Sub 모델: 토픽 구독(SUBSCRIBE) + 메시지 발행(SEND)
 *    - 프레임 기반: CONNECT, SEND, SUBSCRIBE, UNSUBSCRIBE, MESSAGE
 *
 * 3. HTTP vs WebSocket
 *    ┌──────────┬────────────────────────────────────┐
 *    │ HTTP      │ 요청-응답 모델 (단방향)             │
 *    │           │ 매 요청마다 연결 수립/해제           │
 *    │           │ 서버 → 클라이언트 능동 전송 불가    │
 *    ├──────────┼────────────────────────────────────┤
 *    │ WebSocket │ 양방향 통신                         │
 *    │           │ 연결 유지 (persistent)               │
 *    │           │ 서버 → 클라이언트 능동 전송 가능    │
 *    │           │ 실시간 데이터 전송에 최적            │
 *    └──────────┴────────────────────────────────────┘
 *
 * 4. STOMP 목적지(Destination) 규칙
 *    - /topic/*: 1:N 브로드캐스트 (모든 구독자에게 전송)
 *    - /queue/*: 1:1 개인 메시지 (특정 사용자에게 전송)
 *    - /app/*: 클라이언트 → 서버 메시지 (서버 핸들러로 라우팅)
 *
 * ── SockJS ──
 *
 * WebSocket을 지원하지 않는 브라우저/환경에서 폴백 제공.
 * Long Polling, HTTP Streaming 등으로 대체 동작.
 * .withSockJS()를 추가하면 자동으로 폴백 활성화.
 *
 * ── 사용 특성 ──
 *
 * 장점:
 * - 실시간 양방향 통신
 * - HTTP 폴링보다 효율적 (연결 유지, 헤더 오버헤드 없음)
 * - STOMP로 구조화된 메시징 가능
 *
 * 주의점:
 * - 연결 유지 → 서버 리소스 소비 (메모리, 파일 디스크립터)
 * - 분산 환경에서 세션 공유 필요 (Redis Pub/Sub, RabbitMQ 등)
 * - 네트워크 불안정 시 재연결 처리 필요
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker  // STOMP 기반 WebSocket 메시지 브로커 활성화
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * 메시지 브로커 설정
     *
     * enableSimpleBroker: 인메모리 메시지 브로커 활성화
     * - "/topic": 1:N 브로드캐스트 목적지 접두사
     * - "/queue": 1:1 개인 메시지 목적지 접두사
     *
     * setApplicationDestinationPrefixes: 클라이언트 → 서버 메시지 접두사
     * - "/app"으로 시작하는 메시지는 @MessageMapping 핸들러로 라우팅
     *
     * 메시지 흐름:
     * - 클라이언트 SEND "/app/chat" → @MessageMapping("/chat") 핸들러 실행
     * - 서버 convertAndSend("/topic/messages", msg) → 구독자들에게 브로드캐스트
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 구독 목적지 접두사 설정
        config.enableSimpleBroker("/topic", "/queue");

        // 클라이언트 → 서버 메시지 접두사
        config.setApplicationDestinationPrefixes("/app");

        log.info("[WebSocket] 메시지 브로커 설정 - broker=/topic,/queue, app=/app");
    }

    /**
     * STOMP 엔드포인트 등록
     *
     * addEndpoint: WebSocket 연결 URL
     * - 클라이언트는 이 URL로 WebSocket 연결 수립
     *
     * setAllowedOrigins: CORS 허용 도메인
     * - 프론트엔드 도메인을 명시하여 보안 강화
     *
     * withSockJS: WebSocket 미지원 환경에서 폴백
     * - Long Polling 등으로 대체 통신
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/notifications")    // WebSocket 연결 URL
                .setAllowedOrigins(
                        "http://localhost:3000",      // 로컬 개발
                        "https://example.com"         // 운영 도메인
                )
                .withSockJS();                        // SockJS 폴백 활성화

        log.info("[WebSocket] STOMP 엔드포인트 등록 - /ws/notifications");
    }
}
