package kr.co.example.mapper;

import lombok.*;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * MapStruct 예제 — 컴파일 타임 DTO ↔ Entity 매핑 코드 자동 생성.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  MapStruct란?                                                        │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  DTO ↔ Entity 변환 코드를 컴파일 타임에 자동 생성하는 라이브러리.     │
 * │  리플렉션 없이 순수 Java 메서드 호출 → 성능 우수.                     │
 * │                                                                     │
 * │  의존성 (build.gradle):                                              │
 * │    implementation 'org.mapstruct:mapstruct:1.6.3'                    │
 * │    annotationProcessor 'org.mapstruct:mapstruct-processor:1.6.3'     │
 * │    // Lombok과 함께 사용 시 필수:                                     │
 * │    annotationProcessor 'org.projectlombok:lombok-mapstruct-binding:0.2.0'│
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  MapStruct vs 수동 변환 vs ModelMapper 비교                           │
 * ├───────────────────┬─────────────┬──────────────┬────────────────────┤
 * │                   │  MapStruct  │  수동 변환   │  ModelMapper        │
 * ├───────────────────┼─────────────┼──────────────┼────────────────────┤
 * │  코드 생성 시점   │  컴파일 타임 │  없음 (직접) │  런타임 (리플렉션) │
 * │  성능             │  최고       │  최고         │  느림               │
 * │  타입 안전        │  ✓ 컴파일체크│  ✓           │  ✗ 런타임 에러     │
 * │  코드량           │  적음       │  많음         │  적음               │
 * │  커스터마이징     │  @Mapping   │  완전 자유   │  설정으로            │
 * │  디버깅           │  쉬움 (생성 │  쉬움        │  어려움              │
 * │                   │  코드 확인) │              │                     │
 * └───────────────────┴─────────────┴──────────────┴────────────────────┘
 *
 * concert-msa-project에서 실제 사용:
 * - PaymentMapper.java → @Mapping(source = "id", target = "paymentId")
 * - 모든 서비스에서 componentModel = "spring" 으로 Bean 등록
 * </pre>
 */
public class MapStructExample {

    // ====================================================================
    // [1] Entity & DTO 정의
    // ====================================================================

    /** 주문 엔티티 (예시) */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderEntity {
        private Long id;
        private Long userId;
        private String productName;
        private BigDecimal totalAmount;
        private String status;
        private LocalDateTime createdDate;
    }

    /** 주문 응답 DTO */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderResponse {
        private Long orderId;       // Entity의 id → orderId로 매핑
        private Long userId;
        private String productName;
        private BigDecimal totalAmount;
        private String statusName;  // Entity의 status → statusName으로 매핑
    }

    /** 주문 생성 요청 DTO */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderCreateRequest {
        private Long userId;
        private String productName;
        private BigDecimal totalAmount;
    }

    // ====================================================================
    // [2] Mapper 인터페이스 — @Mapper
    // ====================================================================

    /**
     * OrderMapper — Entity ↔ DTO 변환 인터페이스.
     *
     * <pre>
     * componentModel = "spring":
     *   생성된 구현체가 Spring Bean으로 등록됨
     *   → @Autowired / @RequiredArgsConstructor로 주입 가능
     *
     * 빌드 후 생성되는 파일:
     *   build/generated/sources/annotationProcessor/.../OrderMapperImpl.java
     *   → 순수 Java 코드 (getter/setter 호출)
     *   → 디버깅 가능, 성능 최고
     * </pre>
     */
    @Mapper(componentModel = "spring")
    public interface OrderMapper {

        /**
         * Entity → Response DTO 변환.
         *
         * <pre>
         * @Mapping: 필드명이 다를 때 매핑 규칙 지정.
         * - source: Entity의 필드명
         * - target: DTO의 필드명
         *
         * 필드명이 같으면 @Mapping 불필요 → 자동 매핑
         * (userId, productName, totalAmount → 자동)
         * </pre>
         */
        @Mapping(source = "id", target = "orderId")       // id → orderId
        @Mapping(source = "status", target = "statusName") // status → statusName
        OrderResponse toResponse(OrderEntity entity);

        /**
         * Request DTO → Entity 변환.
         *
         * <pre>
         * ignore = true: 해당 필드는 매핑하지 않음
         * → id, status, createdDate는 서비스 로직에서 설정
         * </pre>
         */
        @Mapping(target = "id", ignore = true)
        @Mapping(target = "status", ignore = true)
        @Mapping(target = "createdDate", ignore = true)
        OrderEntity toEntity(OrderCreateRequest request);

        /**
         * 기존 Entity 업데이트 — @MappingTarget.
         *
         * <pre>
         * 새 Entity 생성 대신 기존 Entity의 필드를 업데이트.
         * JPA 더티 체킹과 함께 사용하면 UPDATE 쿼리 자동 실행.
         *
         * 사용법:
         * OrderEntity entity = repository.findById(id).orElseThrow();
         * orderMapper.updateEntity(request, entity); // 기존 entity 필드 업데이트
         * // JPA 더티 체킹으로 UPDATE 자동 실행
         * </pre>
         */
        @Mapping(target = "id", ignore = true)
        @Mapping(target = "status", ignore = true)
        @Mapping(target = "createdDate", ignore = true)
        void updateEntity(OrderCreateRequest request, @MappingTarget OrderEntity entity);
    }

    // ====================================================================
    // [3] 수동 매핑 — 정적 팩토리 메서드 패턴
    // ====================================================================

    /**
     * 정적 팩토리 메서드 — MapStruct 없이 수동 변환.
     *
     * <pre>
     * MapStruct 도입 전 또는 복잡한 변환 로직이 필요한 경우.
     * queenssmile_back 프로젝트에서 from(), of() 패턴으로 사용.
     *
     * 장점: 완전한 제어, 추가 의존성 없음
     * 단점: 필드가 많으면 코드량 증가, 새 필드 추가 시 누락 위험
     * </pre>
     */
    public static class OrderResponseMapper {

        /** Entity → Response (정적 팩토리 메서드) */
        public static OrderResponse from(OrderEntity entity) {
            return OrderResponse.builder()
                    .orderId(entity.getId())
                    .userId(entity.getUserId())
                    .productName(entity.getProductName())
                    .totalAmount(entity.getTotalAmount())
                    .statusName(entity.getStatus())
                    .build();
        }

        /** Request → Entity (정적 팩토리 메서드) */
        public static OrderEntity toEntity(OrderCreateRequest request) {
            return OrderEntity.builder()
                    .userId(request.getUserId())
                    .productName(request.getProductName())
                    .totalAmount(request.getTotalAmount())
                    .status("PENDING")
                    .createdDate(LocalDateTime.now())
                    .build();
        }
    }
}
