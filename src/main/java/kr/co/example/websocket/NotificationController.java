package kr.co.example.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * ========================================================================
 * WebSocket 메시지 핸들러 & 서버 → 클라이언트 전송
 * ========================================================================
 *
 * ── @MessageMapping ──
 *
 * 클라이언트가 "/app/xxx"로 보낸 STOMP 메시지를 처리하는 핸들러.
 * HTTP의 @RequestMapping과 유사한 역할.
 * WebSocketConfig에서 설정한 applicationDestinationPrefixes("/app")와 조합됨.
 *
 * ── @SendTo ──
 *
 * 핸들러의 반환값을 지정된 목적지로 브로드캐스트.
 * 해당 목적지를 구독한 모든 클라이언트에게 자동 전송.
 * HTTP의 @ResponseBody와 유사.
 *
 * ── SimpMessagingTemplate ──
 *
 * 서버에서 능동적으로(프로그래밍 방식으로) 클라이언트에게 메시지를 전송.
 * @MessageMapping 핸들러 외부(스케줄러, 이벤트 리스너, 다른 서비스 등)에서도 사용 가능.
 *
 * 주요 메서드:
 * - convertAndSend(destination, payload): 구독자 전체에게 전송 (1:N 브로드캐스트)
 * - convertAndSendToUser(user, destination, payload): 특정 사용자에게 전송 (1:1)
 *
 * ── 클라이언트 코드 예시 (JavaScript / SockJS + STOMP) ──
 *
 * ```javascript
 * // 1. SockJS를 통해 WebSocket 연결 수립
 * const socket = new SockJS('/ws/notifications');
 * const stompClient = Stomp.over(socket);
 *
 * stompClient.connect({}, () => {
 *     // 2. 서버 → 클라이언트: 브로드캐스트 구독
 *     stompClient.subscribe('/topic/notifications', (message) => {
 *         console.log('알림 수신:', JSON.parse(message.body));
 *     });
 *
 *     // 3. 서버 → 클라이언트: 개인 메시지 구독
 *     stompClient.subscribe('/user/queue/alerts', (message) => {
 *         console.log('개인 알림:', JSON.parse(message.body));
 *     });
 *
 *     // 4. 클라이언트 → 서버: 메시지 전송
 *     stompClient.send('/app/message', {}, JSON.stringify({ content: 'Hello!' }));
 * });
 * ```
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class NotificationController {

    /**
     * SimpMessagingTemplate:
     * Spring이 자동 주입하는 WebSocket 메시지 전송 템플릿.
     *
     * @MessageMapping 핸들러 외부에서 서버가 능동적으로 메시지를 보낼 때 사용.
     * 예: 스케줄러에서 주기적 알림, 이벤트 리스너에서 상태 변경 알림,
     *     다른 REST API 호출 결과를 실시간으로 프론트엔드에 전달.
     */
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 클라이언트 메시지 수신 + 브로드캐스트
     *
     * 클라이언트가 "/app/message"로 STOMP SEND 프레임을 보내면 이 핸들러가 호출됨.
     * 반환값은 @SendTo에 지정된 "/topic/messages"로 자동 브로드캐스트.
     *
     * 메시지 흐름:
     * 1. 클라이언트 → SEND "/app/message" { "content": "Hello" }
     * 2. Spring이 @MessageMapping("/message") 핸들러 호출
     * 3. 핸들러가 메시지를 가공하여 Map 반환
     * 4. 반환값이 @SendTo("/topic/messages") 구독자 전체에게 전송
     *
     * @param message 클라이언트가 보낸 메시지 Map.
     *                필수 키: "content" (메시지 본문).
     *                Jackson이 STOMP 바디(JSON)를 Map으로 자동 역직렬화.
     * @return 가공된 메시지 Map (content, timestamp, type 포함).
     *         @SendTo에 의해 "/topic/messages" 구독자 전체에게 전송됨.
     */
    @MessageMapping("/message")
    @SendTo("/topic/messages")
    public Map<String, Object> handleMessage(Map<String, String> message) {
        String content = message.get("content");
        log.info("[WebSocket] 메시지 수신 - content={}", content);

        return Map.of(
                "content", content,
                "timestamp", LocalDateTime.now().toString(),
                "type", "BROADCAST"
        );
    }

    /**
     * 서버 → 클라이언트 능동 전송 (브로드캐스트)
     *
     * 다른 서비스(이벤트 리스너, 스케줄러 등)에서 호출하여 실시간 알림 전송.
     * "/topic/notifications"를 구독 중인 모든 클라이언트에게 전송.
     *
     * 사용 예:
     *   notificationController.sendNotification("ORDER_STATUS", "주문이 배송 시작되었습니다");
     *
     * @param type    알림 타입 (ORDER_STATUS, STOCK_ALERT 등, 클라이언트가 필터링에 사용)
     * @param message 알림 메시지 본문
     */
    public void sendNotification(String type, String message) {
        Map<String, Object> payload = Map.of(
                "type", type,
                "message", message,
                "timestamp", LocalDateTime.now().toString()
        );

        // /topic/notifications 구독자 전체에게 전송
        messagingTemplate.convertAndSend("/topic/notifications", payload);
        log.info("[WebSocket] 브로드캐스트 알림 전송 - type={}", type);
    }

    /**
     * 특정 사용자에게 개인 메시지 전송 (1:1)
     *
     * convertAndSendToUser()는 내부적으로 "/user/{userId}/queue/alerts" 경로로 메시지를 전송.
     * 클라이언트는 "/user/queue/alerts"를 구독하면
     * Spring이 자동으로 현재 인증된 사용자의 메시지만 수신하도록 라우팅.
     *
     * 사용 예:
     *   notificationController.sendToUser("user123", "주문 #1234가 배송 완료되었습니다");
     *
     * 주의:
     * - userId는 Spring Security의 Principal.getName()과 일치해야 함.
     * - 해당 사용자가 현재 연결되어 있지 않으면 메시지는 유실됨.
     *   (영속적 메시지 전달이 필요하면 별도 저장소 활용)
     *
     * @param userId  수신 대상 사용자 ID (Principal.getName()과 일치)
     * @param message 알림 메시지 본문
     */
    public void sendToUser(String userId, String message) {
        Map<String, Object> payload = Map.of(
                "message", message,
                "timestamp", LocalDateTime.now().toString()
        );

        // 특정 사용자의 "/queue/alerts"로 전송
        // Spring이 내부적으로 "/user/{userId}/queue/alerts"로 변환
        messagingTemplate.convertAndSendToUser(userId, "/queue/alerts", payload);
        log.info("[WebSocket] 개인 알림 전송 - userId={}", userId);
    }
}
