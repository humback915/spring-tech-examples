package kr.co.example.jpa;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import kr.co.example.jpa.JpaEntityExample.Order;
import kr.co.example.jpa.JpaEntityExample.OrderStatus;
import kr.co.example.jpa.JpaRepositoryExample.OrderRepositoryCustom;
import kr.co.example.jpa.JpaRepositoryExample.OrderSearchCondition;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * QueryDSL 예제 — 타입 안전한 동적 쿼리 빌더.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  QueryDSL이란?                                                      │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  - JPA 엔티티를 기반으로 Q-class를 자동 생성 (QUser, QOrder 등)       │
 * │  - Java 코드로 쿼리를 작성 → 컴파일 타임에 오류 감지                   │
 * │  - 동적 쿼리(조건에 따라 WHERE절 변경)에 강점                          │
 * │  - JPQL 문자열 오타 → 런타임 에러 vs QueryDSL → 컴파일 에러            │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  QueryDSL 설정 (build.gradle)                                       │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  dependencies {                                                     │
 * │    // jakarta classifier 필수 (Spring Boot 3.x = Jakarta EE)        │
 * │    implementation 'com.querydsl:querydsl-jpa:5.1.0:jakarta'         │
 * │    annotationProcessor 'com.querydsl:querydsl-apt:5.1.0:jakarta'    │
 * │    annotationProcessor 'jakarta.annotation:jakarta.annotation-api'  │
 * │    annotationProcessor 'jakarta.persistence:jakarta.persistence-api'│
 * │  }                                                                  │
 * │                                                                     │
 * │  빌드 시 build/generated/sources/annotationProcessor/ 에             │
 * │  QUser, QOrder 등 Q-class가 자동 생성됨                              │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  QueryDSL vs JPQL vs Criteria API 비교                               │
 * ├───────────────┬──────────────┬──────────────┬───────────────────────┤
 * │               │  QueryDSL    │  JPQL        │  Criteria API         │
 * ├───────────────┼──────────────┼──────────────┼───────────────────────┤
 * │  타입 안전    │  ✓ 컴파일체크│  ✗ 문자열     │  ✓ 타입 체크          │
 * │  가독성       │  ✓ 직관적    │  ✓ SQL과 유사 │  ✗ 복잡한 API          │
 * │  동적 쿼리    │  ✓ 쉬움      │  ✗ 문자열 조합│  △ 가능하나 복잡       │
 * │  학습 비용    │  중간         │  낮음         │  높음                  │
 * │  설정 복잡도  │  중간(APT)    │  없음         │  없음                  │
 * └───────────────┴──────────────┴──────────────┴───────────────────────┘
 * </pre>
 */
public class QueryDslExample {

    // ====================================================================
    // [1] JPAQueryFactory Bean 설정
    // ====================================================================

    /**
     * QueryDSL 설정 — JPAQueryFactory Bean 등록.
     * concert-msa-project의 QueryDslConfig 패턴 참고.
     */
    @Configuration
    public static class QueryDslConfig {

        /**
         * JPAQueryFactory — QueryDSL 쿼리 생성에 필요한 핵심 Bean.
         * EntityManager를 주입받아 JPQL 쿼리를 실행.
         */
        @Bean
        public JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
            return new JPAQueryFactory(entityManager);
        }
    }

    // ====================================================================
    // [2] 커스텀 Repository 구현 — OrderRepositoryImpl
    // ====================================================================

    /**
     * OrderRepositoryImpl — 동적 쿼리를 QueryDSL로 구현.
     *
     * <pre>
     * 네이밍 규칙:
     * - 인터페이스: OrderRepositoryCustom
     * - 구현 클래스: OrderRepositoryImpl (반드시 Impl 접미사)
     * - Spring Data가 Impl 접미사를 자동 인식
     *
     * OrderRepository extends JpaRepository, OrderRepositoryCustom
     * → Spring이 OrderRepositoryImpl을 찾아서 자동 주입
     * </pre>
     */
    @Repository
    @RequiredArgsConstructor
    public static class OrderRepositoryImpl implements OrderRepositoryCustom {

        private final JPAQueryFactory queryFactory;

        // 주의: Q-class는 빌드 후 자동 생성됨
        // 아래는 개념 설명용 — 실제 프로젝트에서는 QOrder.order 등을 import하여 사용
        // private final QOrder order = QOrder.order;
        // private final QUser user = QUser.user;

        /**
         * 동적 검색 — 조건이 null이면 해당 WHERE절 생략.
         *
         * <pre>
         * 핵심 패턴: BooleanBuilder 또는 BooleanExpression 반환 메서드
         *
         * [방법 1] BooleanBuilder — 조건을 순차적으로 추가
         *   BooleanBuilder builder = new BooleanBuilder();
         *   if (status != null) builder.and(order.status.eq(status));
         *
         * [방법 2] BooleanExpression 반환 메서드 (권장)
         *   private BooleanExpression statusEq(Status s) {
         *       return s != null ? order.status.eq(s) : null;
         *   }
         *   → .where()에 null이 전달되면 자동으로 조건 무시
         *   → 메서드 재사용 가능, 가독성 우수
         * </pre>
         */
        @Override
        public Page<Order> searchOrders(OrderSearchCondition condition, Pageable pageable) {
            /*
             * 실제 구현 예시 (Q-class 빌드 후):
             *
             * List<Order> content = queryFactory
             *     .selectFrom(order)
             *     .leftJoin(order.user, user).fetchJoin()    // N+1 방지
             *     .where(
             *         userIdEq(condition.userId()),           // null이면 조건 무시
             *         statusEq(condition.status()),
             *         amountGoe(condition.minAmount()),
             *         dateBetween(condition.startDate(), condition.endDate())
             *     )
             *     .orderBy(order.createdDate.desc())
             *     .offset(pageable.getOffset())               // 시작 위치
             *     .limit(pageable.getPageSize())               // 페이지 크기
             *     .fetch();                                    // 결과 조회
             *
             * // COUNT 쿼리 최적화 — 결과가 페이지 크기보다 작으면 COUNT 쿼리 생략
             * JPAQuery<Long> countQuery = queryFactory
             *     .select(order.count())
             *     .from(order)
             *     .where(
             *         userIdEq(condition.userId()),
             *         statusEq(condition.status()),
             *         amountGoe(condition.minAmount()),
             *         dateBetween(condition.startDate(), condition.endDate())
             *     );
             *
             * // PageableExecutionUtils: 마지막 페이지면 COUNT 쿼리 실행 안 함 (최적화)
             * return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
             */

            // 빌드용 stub — 실제 Q-class 생성 후 위 주석 코드로 교체
            return Page.empty(pageable);
        }

        // ── BooleanExpression 반환 메서드 (Where 조건 조립용) ──

        /**
         * null이면 조건 무시 패턴.
         * QueryDSL의 .where()는 null을 받으면 해당 조건을 자동으로 건너뜀.
         *
         * <pre>
         * 사용법:
         * .where(
         *     userIdEq(null),     → 조건 무시됨
         *     statusEq(PAID),     → order.status = 'PAID' 적용
         *     amountGoe(null)     → 조건 무시됨
         * )
         * → 최종 SQL: WHERE order.status = 'PAID'
         * </pre>
         */
        // private BooleanExpression userIdEq(Long userId) {
        //     return userId != null ? order.user.id.eq(userId) : null;
        // }

        // private BooleanExpression statusEq(OrderStatus status) {
        //     return status != null ? order.orderStatus.eq(status) : null;
        // }

        // private BooleanExpression amountGoe(BigDecimal minAmount) {
        //     // goe = Greater Or Equal (>=)
        //     return minAmount != null ? order.totalAmount.goe(minAmount) : null;
        // }

        // private BooleanExpression dateBetween(LocalDateTime start, LocalDateTime end) {
        //     if (start != null && end != null) {
        //         return order.createdDate.between(start, end);
        //     }
        //     if (start != null) {
        //         return order.createdDate.goe(start);
        //     }
        //     if (end != null) {
        //         return order.createdDate.loe(end); // loe = Less Or Equal (<=)
        //     }
        //     return null;
        // }
    }

    // ====================================================================
    // [3] Projection — DTO 직접 조회 (엔티티 전체 로딩 방지)
    // ====================================================================

    /**
     * DTO Projection — 필요한 컬럼만 SELECT하여 DTO로 직접 매핑.
     *
     * <pre>
     * 엔티티 전체 조회 vs DTO Projection:
     * ┌──────────────────┬───────────────────────────────────────────┐
     * │  엔티티 조회     │ SELECT * → 모든 컬럼 로딩, 영속성 관리    │
     * │                  │ 수정이 필요한 경우 사용                    │
     * ├──────────────────┼───────────────────────────────────────────┤
     * │  DTO Projection  │ SELECT col1, col2 → 필요한 컬럼만         │
     * │                  │ 읽기 전용, 영속성 관리 불필요 → 성능 우수  │
     * │                  │ 조회 전용 화면(목록, 통계)에 적합            │
     * └──────────────────┴───────────────────────────────────────────┘
     * </pre>
     */
    @Getter
    @Builder
    public static class OrderSummaryDto {
        private Long orderId;
        private String userName;
        private BigDecimal totalAmount;
        private OrderStatus status;
    }

    /**
     * Projection 사용 예시 (Q-class 빌드 후).
     *
     * <pre>
     * List&lt;OrderSummaryDto&gt; results = queryFactory
     *     .select(Projections.constructor(
     *         OrderSummaryDto.class,
     *         order.id,
     *         order.user.name,
     *         order.totalAmount,
     *         order.orderStatus
     *     ))
     *     .from(order)
     *     .leftJoin(order.user, user)
     *     .where(order.orderStatus.eq(OrderStatus.PAID))
     *     .fetch();
     *
     * Projection 종류:
     * - Projections.constructor() → 생성자 기반 (타입 안전, 권장)
     * - Projections.bean()        → setter 기반
     * - Projections.fields()      → 필드 직접 접근 (private도 가능)
     * - @QueryProjection         → DTO 생성자에 어노테이션 → Q-DTO 생성
     * </pre>
     */
    public void projectionExample() {
        // Q-class 빌드 후 위 주석의 코드로 사용
    }

    // ====================================================================
    // [4] BooleanBuilder 방식 (방법 1) vs BooleanExpression 방식 (방법 2)
    // ====================================================================

    /**
     * BooleanBuilder 방식 — 조건을 순차적으로 추가.
     *
     * <pre>
     * 장점: 직관적, 이해하기 쉬움
     * 단점: 조건이 많아지면 코드가 길어지고 재사용 어려움
     *
     * BooleanBuilder builder = new BooleanBuilder();
     *
     * if (condition.userId() != null) {
     *     builder.and(order.user.id.eq(condition.userId()));
     * }
     * if (condition.status() != null) {
     *     builder.and(order.orderStatus.eq(condition.status()));
     * }
     * if (condition.minAmount() != null) {
     *     builder.and(order.totalAmount.goe(condition.minAmount()));
     * }
     *
     * List&lt;Order&gt; results = queryFactory
     *     .selectFrom(order)
     *     .where(builder)
     *     .fetch();
     * </pre>
     */
    public void booleanBuilderExample() {
        // 개념 설명용 — Q-class 빌드 후 사용
    }
}
