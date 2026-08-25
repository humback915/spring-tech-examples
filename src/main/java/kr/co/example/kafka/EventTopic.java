package kr.co.example.kafka;

/**
 * ========================================================================
 * Kafka 토픽 상수 정의
 * ========================================================================
 *
 * ── 토픽 네이밍 컨벤션 ──
 *
 * "{도메인}.{이벤트}" 형식의 점(dot) 구분자를 사용.
 * 예: order.paid, order.cancelled, stock.confirmed
 *
 * 점 구분자의 장점:
 * - 계층적 구조를 표현 (도메인 → 이벤트)
 * - 모니터링 도구에서 도메인별 그룹핑 용이
 * - Kafka 토픽 필터링 시 와일드카드 패턴 적용 가능
 *
 * ── 토픽과 Consumer Group의 관계 ──
 *
 * 하나의 토픽에 여러 Consumer Group이 구독 가능.
 * 같은 Group 내 Consumer끼리는 파티션을 나눠 처리 (분산).
 * 다른 Group은 같은 메시지를 각각 독립적으로 수신 (복제).
 *
 * ── DLT(Dead-Letter Topic) ──
 *
 * 재시도 소진 시 "{원본토픽}.DLT" 토픽으로 이동.
 * 예: order.paid → order.paid.DLT
 * DLT 메시지는 별도 모니터링 후 수동/자동 재처리.
 */
public final class EventTopic {

    /** 인스턴스 생성 방지 (상수 클래스) */
    private EventTopic() {}

    /**
     * 주문 결제 완료 이벤트.
     * Producer: 결제 서비스 (결제 성공 시 발행)
     * Consumer: 재고 서비스 (재고 확정), 장바구니 서비스 (장바구니 삭제)
     */
    public static final String ORDER_PAID = "order.paid";

    /**
     * 주문 취소 이벤트.
     * Producer: 주문 서비스 (취소 요청 시 발행)
     * Consumer: 재고 서비스 (재고 복원), 결제 서비스 (환불 처리)
     */
    public static final String ORDER_CANCELLED = "order.cancelled";

    /**
     * 재고 확정 이벤트.
     * Producer: 재고 서비스 (장바구니 → 주문 확정 전환 시 발행)
     * Consumer: 알림 서비스 (확정 알림 발송)
     */
    public static final String STOCK_CONFIRMED = "stock.confirmed";
}
