package kr.co.example.jpa;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA Entity 심화 예제 — 실무에서 자주 사용하는 엔티티 패턴 모음.
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────┐
 * │  이 파일에서 다루는 패턴                                          │
 * ├──────────────────────────────────────────────────────────────────┤
 * │  1. @Entity 기본 구조 + Lombok 컨벤션                             │
 * │  2. @Embeddable / @Embedded (값 객체)                             │
 * │  3. Soft Delete (논리 삭제 — @SQLDelete, @SQLRestriction)         │
 * │  4. @Enumerated(EnumType.STRING) — Enum 매핑                     │
 * │  5. 엔티티 관계 (@ManyToOne, @OneToMany)                          │
 * │  6. ID 기반 참조 vs 객체 참조 비교                                 │
 * │  7. @DynamicInsert, @DynamicUpdate — SQL 최적화                   │
 * │  8. @BatchSize — N+1 쿼리 방지                                    │
 * │  9. 도메인 메서드 — setter 대신 의미 있는 메서드                    │
 * └──────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class JpaEntityExample {

    // ====================================================================
    // [1] 기본 엔티티 — Lombok 컨벤션 + BaseEntity 상속
    // ====================================================================

    /**
     * 사용자 엔티티 — 기본 JPA 엔티티 구조.
     *
     * <pre>
     * Lombok 컨벤션:
     * - @Getter ✓  (읽기용)
     * - @Setter ✗  (setter 대신 도메인 메서드 사용 → 불변성 보장)
     * - @NoArgsConstructor(access = PROTECTED) ✓ (JPA 프록시 생성용, 외부에서 new 방지)
     * - @Builder ✓  (생성자 위에 선언 → 필요한 필드만 포함)
     * - @FieldDefaults(level = AccessLevel.PRIVATE) ✓ (필드 접근 제어자 일괄 지정)
     * - @Data ✗    (equals/hashCode 자동 생성이 JPA와 충돌 가능)
     *
     * @FieldDefaults 설명:
     * - lombok.experimental 패키지의 실험적 기능
     * - 모든 필드에 접근 제어자를 일괄 적용 → private 누락 실수 방지
     * - level = AccessLevel.PRIVATE → 모든 필드를 private으로 설정
     * - makeFinal = true 옵션 추가 시 → 모든 필드를 final로 설정 (불변 객체)
     * - @FieldDefaults(level = PRIVATE)      → private String name;
     * - @FieldDefaults(makeFinal = true)      → final String name;
     * - @FieldDefaults(level = PRIVATE, makeFinal = true) → private final String name;
     * </pre>
     */
    @Entity
    @Table(name = "users", indexes = {
            // 자주 검색하는 컬럼에 인덱스 추가 — 조회 성능 향상
            @Index(name = "idx_users_email", columnList = "email", unique = true),
            @Index(name = "idx_users_status", columnList = "status")
    })
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 스펙: 기본 생성자 필수 (protected 권장)
    @FieldDefaults(level = AccessLevel.PRIVATE) // 모든 필드를 private으로 일괄 적용 → private 누락 방지
    @DynamicInsert  // INSERT 시 null인 컬럼 제외 → DB DEFAULT 값 활용 가능
    @DynamicUpdate  // UPDATE 시 변경된 컬럼만 포함 → SQL 최적화
    // Soft Delete: DELETE 대신 UPDATE status = 'DELETED' 실행
    @SQLDelete(sql = "UPDATE users SET status = 'DELETED', deleted_at = NOW() WHERE id = ?")
    // 조회 시 DELETED 상태 자동 제외 (Hibernate 6.x에서 @Where → @SQLRestriction으로 변경)
    @SQLRestriction("status <> 'DELETED'")
    public static class User extends BaseEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY) // MySQL AUTO_INCREMENT
        private Long id;

        /**
         * 이메일 — unique + nullable=false → NOT NULL UNIQUE 제약조건.
         * length = 100 → VARCHAR(100)
         */
        @Column(nullable = false, unique = true, length = 100)
        private String email;

        @Column(nullable = false, length = 50)
        private String name;

        /**
         * Enum 매핑 — 반드시 EnumType.STRING 사용.
         *
         * <pre>
         * EnumType.ORDINAL (기본값) — Enum 순서(0,1,2)를 저장
         *   → 위험: Enum에 값을 추가/재정렬하면 기존 데이터 의미가 바뀜
         *   → 예: ACTIVE=0, DORMANT=1 → 중간에 PENDING 추가 시 DORMANT=2로 변경
         *
         * EnumType.STRING (권장) — Enum 이름("ACTIVE","DORMANT")을 문자열로 저장
         *   → 안전: Enum 순서 변경에 영향 없음
         *   → 단점: 저장 공간 약간 더 사용 (무시해도 됨)
         * </pre>
         */
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private UserStatus status;

        /** Soft Delete 시각 — null이면 삭제되지 않은 상태 */
        private LocalDateTime deletedAt;

        /**
         * @Embedded — 값 객체(Value Object)를 엔티티 컬럼으로 포함.
         * Address 클래스의 필드가 users 테이블의 컬럼으로 매핑됨.
         */
        @Embedded
        private Address address;

        /**
         * @OneToMany — 1:N 관계 (한 사용자 → 여러 주문).
         *
         * <pre>
         * mappedBy: 양방향 관계에서 연관관계의 주인이 아님을 표시
         *   → Order.user 필드가 FK를 관리 (연관관계의 주인)
         *   → User.orders는 읽기 전용 (추가/삭제 시 Order 쪽에서 관리)
         *
         * cascade: 부모 엔티티 상태 변경 시 자식도 함께 변경
         *   PERSIST: 부모 저장 시 자식도 저장
         *   MERGE:   부모 수정 시 자식도 수정
         *   REMOVE:  부모 삭제 시 자식도 삭제 (위험 → 신중히 사용)
         *   ALL:     모든 cascade (편리하지만 위험)
         *
         * orphanRemoval: 부모와 연결이 끊긴 자식 자동 삭제
         *   user.getOrders().remove(order) → DELETE 실행
         *
         * FetchType.LAZY (기본값, 권장): 실제 접근 시 쿼리 실행
         * FetchType.EAGER: 부모 조회 시 즉시 함께 조회 (N+1 문제 원인)
         * </pre>
         */
        @OneToMany(mappedBy = "user", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
        @BatchSize(size = 100) // N+1 방지: IN절로 100개씩 묶어서 조회
        private List<Order> orders = new ArrayList<>();

        @Builder
        public User(String email, String name, UserStatus status, Address address) {
            this.email = email;
            this.name = name;
            this.status = status != null ? status : UserStatus.ACTIVE;
            this.address = address;
        }

        // ── 도메인 메서드 — setter 대신 비즈니스 의미가 있는 메서드 ──

        /** 사용자 비활성화 — 단순 status 변경이 아닌 도메인 행위로 표현 */
        public void deactivate() {
            if (this.status == UserStatus.DELETED) {
                throw new IllegalStateException("이미 삭제된 사용자입니다");
            }
            this.status = UserStatus.DORMANT;
        }

        /** 프로필 수정 — 변경 가능한 필드만 노출 */
        public void updateProfile(String name, Address address) {
            this.name = name;
            this.address = address;
        }
    }

    // ====================================================================
    // [2] @Embeddable — 값 객체 (Value Object)
    // ====================================================================

    /**
     * 주소 값 객체 — 독립 테이블 없이 부모 엔티티 테이블에 포함.
     *
     * <pre>
     * @Embeddable vs 별도 @Entity:
     * - @Embeddable: 독립 ID 없음, 부모와 생명주기 동일, 테이블 분리 안 됨
     * - @Entity:     독립 ID 있음, 별도 테이블, 독립적 생명주기
     *
     * 사용 기준: "이 데이터가 독립적으로 조회/수정될 필요가 있는가?"
     * → No → @Embeddable (주소, 좌표, 금액+통화 등)
     * → Yes → @Entity (사용자, 주문, 상품 등)
     * </pre>
     */
    @Embeddable
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Address {

        @Column(length = 10)
        private String zipCode;     // 우편번호

        @Column(length = 200)
        private String street;      // 도로명 주소

        @Column(length = 100)
        private String detail;      // 상세 주소

        @Builder
        public Address(String zipCode, String street, String detail) {
            this.zipCode = zipCode;
            this.street = street;
            this.detail = detail;
        }
    }

    // ====================================================================
    // [3] @ManyToOne — N:1 관계 (연관관계의 주인)
    // ====================================================================

    /**
     * 주문 엔티티 — @ManyToOne 관계 예시.
     */
    @Entity
    @Table(name = "orders")
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Order extends BaseEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        /**
         * [방법 1] 객체 참조 — @ManyToOne
         *
         * <pre>
         * 장점: user.getName() 직접 접근, JPQL JOIN 간편
         * 단점: 강한 결합, 양방향 관계 관리 복잡, MSA에서 서비스 경계 넘기 어려움
         *
         * fetch = LAZY 필수:
         *   EAGER(기본값)면 Order 조회 시 항상 User도 함께 SELECT
         *   → 100개 Order 조회 시 User 100번 추가 SELECT (N+1)
         *   LAZY면 order.getUser() 호출 시에만 SELECT 실행
         * </pre>
         */
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "user_id") // FK 컬럼명 지정
        private User user;

        /**
         * [방법 2] ID 기반 참조 — 객체 참조 대신 FK ID만 저장
         *
         * <pre>
         * 장점: 느슨한 결합, MSA 서비스 경계에서 안전, 불필요한 JOIN 방지
         * 단점: 관련 데이터 조회 시 별도 쿼리/서비스 호출 필요
         *
         * 권장: MSA 환경이거나 서비스 간 경계가 명확한 경우
         * </pre>
         */
        @Column(name = "product_id")
        private Long productId; // Product 엔티티 참조 대신 ID만 저장

        @Column(nullable = false, precision = 10, scale = 2)
        private BigDecimal totalAmount;

        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private OrderStatus orderStatus;

        @Builder
        public Order(User user, Long productId, BigDecimal totalAmount) {
            this.user = user;
            this.productId = productId;
            this.totalAmount = totalAmount;
            this.orderStatus = OrderStatus.PENDING;
        }

        // ── 도메인 메서드: 상태 전이 (State Transition) ──

        /**
         * 결제 완료 처리.
         * 상태 전이 규칙: PENDING → PAID만 허용.
         */
        public void markAsPaid() {
            if (this.orderStatus != OrderStatus.PENDING) {
                throw new IllegalStateException(
                        "결제는 PENDING 상태에서만 가능합니다. 현재: " + this.orderStatus);
            }
            this.orderStatus = OrderStatus.PAID;
        }

        /** 주문 취소 — PENDING 또는 PAID 상태에서만 가능 */
        public void cancel() {
            if (this.orderStatus == OrderStatus.CANCELLED) {
                throw new IllegalStateException("이미 취소된 주문입니다");
            }
            this.orderStatus = OrderStatus.CANCELLED;
        }
    }

    // ====================================================================
    // [4] Enum — 상태 관리
    // ====================================================================

    /** 사용자 상태 Enum */
    public enum UserStatus {
        ACTIVE,     // 활성
        DORMANT,    // 휴면
        DELETED     // 삭제 (Soft Delete)
    }

    /** 주문 상태 Enum — 상태 전이 규칙은 도메인 메서드에서 관리 */
    public enum OrderStatus {
        PENDING,    // 대기
        PAID,       // 결제 완료
        SHIPPED,    // 배송 중
        DELIVERED,  // 배송 완료
        CANCELLED   // 취소
    }
}
