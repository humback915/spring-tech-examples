package kr.co.example.jpa;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.ExpressionUtils;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.JPAExpressions;
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

    // ====================================================================
    // [5] JPAQueryFactory 주요 메서드 — select / update / delete
    // ====================================================================

    /**
     * JPAQueryFactory 핵심 메서드 정리.
     *
     * <pre>
     * ┌──────────────────────────────────────────────────────────────────────┐
     * │  JPAQueryFactory 주요 메서드                                          │
     * ├───────────────────────┬──────────────────────────────────────────────┤
     * │  selectFrom(Q)        │ SELECT * FROM — 엔티티 전체 조회              │
     * │  select(expr...)      │ SELECT col1, col2 — 특정 컬럼/DTO 조회       │
     * │  update(Q)            │ UPDATE — 벌크 업데이트                        │
     * │  delete(Q)            │ DELETE — 벌크 삭제                            │
     * ├───────────────────────┼──────────────────────────────────────────────┤
     * │  .fetch()             │ 결과 리스트 반환 (List&lt;T&gt;)             │
     * │  .fetchOne()          │ 단건 조회 (없으면 null, 2건 이상이면 예외)    │
     * │  .fetchFirst()        │ 첫 번째 결과 (limit(1).fetchOne())           │
     * │  .fetchCount()        │ COUNT 쿼리 실행 (deprecated → select count)  │
     * │  .fetchResults()      │ 결과 + 전체 개수 (deprecated)                │
     * ├───────────────────────┼──────────────────────────────────────────────┤
     * │  .where()             │ WHERE 조건 (BooleanExpression, null 무시)    │
     * │  .orderBy()           │ ORDER BY (asc/desc)                          │
     * │  .offset() / .limit() │ 페이징 (OFFSET / LIMIT)                     │
     * │  .groupBy()           │ GROUP BY                                     │
     * │  .having()            │ HAVING (그룹 조건)                            │
     * │  .join() / .leftJoin()│ JOIN (fetchJoin으로 N+1 방지)                │
     * │  .distinct()          │ SELECT DISTINCT                              │
     * └───────────────────────┴──────────────────────────────────────────────┘
     *
     * ── 조회 예시 ──
     *
     * // 단건 조회
     * Order order = queryFactory
     *     .selectFrom(QOrder.order)
     *     .where(QOrder.order.id.eq(orderId))
     *     .fetchOne();
     *
     * // 목록 조회 + 정렬 + 페이징
     * List&lt;Order&gt; orders = queryFactory
     *     .selectFrom(order)
     *     .leftJoin(order.user, user).fetchJoin()
     *     .where(statusEq(OrderStatus.PAID))
     *     .orderBy(order.createdDate.desc(), order.id.asc())
     *     .offset(pageable.getOffset())
     *     .limit(pageable.getPageSize())
     *     .fetch();
     *
     * // 집계 (GROUP BY + HAVING)
     * List&lt;Tuple&gt; stats = queryFactory
     *     .select(order.user.id, order.totalAmount.sum())
     *     .from(order)
     *     .groupBy(order.user.id)
     *     .having(order.totalAmount.sum().gt(100000))
     *     .fetch();
     *
     * ── 벌크 UPDATE ──
     *
     * long updatedCount = queryFactory
     *     .update(order)
     *     .set(order.orderStatus, OrderStatus.CANCELLED)
     *     .where(
     *         order.orderStatus.eq(OrderStatus.PENDING),
     *         order.createdDate.lt(LocalDateTime.now().minusDays(7))
     *     )
     *     .execute();
     * // 주의: 벌크 연산 후 em.flush() + em.clear() 필요 (영속성 컨텍스트 동기화)
     *
     * ── 벌크 DELETE ──
     *
     * long deletedCount = queryFactory
     *     .delete(order)
     *     .where(order.orderStatus.eq(OrderStatus.CANCELLED))
     *     .execute();
     * </pre>
     */
    public void jpaQueryFactoryMethods() {
        // 개념 설명용 — Q-class 빌드 후 사용
    }

    // ====================================================================
    // [6] SubQuery — JPAExpressions
    // ====================================================================

    /**
     * 서브쿼리 — JPAExpressions를 사용한 서브쿼리 작성법.
     *
     * <pre>
     * ┌─────────────────────────────────────────────────────────────────────┐
     * │  QueryDSL 서브쿼리                                                  │
     * ├─────────────────────────────────────────────────────────────────────┤
     * │  - JPAExpressions.select()로 서브쿼리 생성                          │
     * │  - WHERE절, SELECT절에서 사용 가능                                   │
     * │  - JPA 표준 한계: FROM절 서브쿼리(인라인 뷰) 지원 안 함              │
     * │    → Native Query 또는 쿼리 분리로 해결                              │
     * └─────────────────────────────────────────────────────────────────────┘
     *
     * ┌─────────────────────────────────────────────────────────────────────┐
     * │  서브쿼리 사용 가능 위치                                              │
     * ├──────────────┬──────────────────────────────────────────────────────┤
     * │  WHERE절     │ .where(order.totalAmount.gt(JPAExpressions...))     │
     * │  SELECT절    │ .select(Projections..., JPAExpressions...)          │
     * │  HAVING절    │ .having(order.count().gt(JPAExpressions...))        │
     * ├──────────────┼──────────────────────────────────────────────────────┤
     * │  FROM절 (✗)  │ JPA 표준에서 미지원 → Native Query로 대체             │
     * └──────────────┴──────────────────────────────────────────────────────┘
     *
     * ── [1] WHERE절 서브쿼리: 평균 금액 이상인 주문 조회 ──
     *
     * // SQL: SELECT * FROM orders WHERE total_amount &gt;= (SELECT AVG(total_amount) FROM orders)
     *
     * List&lt;Order&gt; aboveAvgOrders = queryFactory
     *     .selectFrom(order)
     *     .where(order.totalAmount.goe(
     *         JPAExpressions
     *             .select(order.totalAmount.avg())
     *             .from(order)
     *     ))
     *     .fetch();
     *
     * ── [2] WHERE절 서브쿼리: IN절 ──
     *
     * // SQL: SELECT * FROM orders WHERE user_id IN (SELECT id FROM users WHERE status = 'ACTIVE')
     *
     * List&lt;Order&gt; activeUserOrders = queryFactory
     *     .selectFrom(order)
     *     .where(order.user.id.in(
     *         JPAExpressions
     *             .select(user.id)
     *             .from(user)
     *             .where(user.status.eq(UserStatus.ACTIVE))
     *     ))
     *     .fetch();
     *
     * ── [3] WHERE절 서브쿼리: EXISTS ──
     *
     * // SQL: SELECT * FROM users u WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id)
     *
     * List&lt;User&gt; usersWithOrders = queryFactory
     *     .selectFrom(user)
     *     .where(JPAExpressions
     *         .selectOne()
     *         .from(order)
     *         .where(order.user.id.eq(user.id))
     *         .exists()
     *     )
     *     .fetch();
     *
     * ── [4] SELECT절 서브쿼리 (스칼라 서브쿼리) ──
     *
     * // SQL: SELECT u.name, (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) AS order_count FROM users u
     *
     * // ExpressionUtils.as()로 서브쿼리에 별칭(alias) 부여
     * List&lt;Tuple&gt; userOrderCounts = queryFactory
     *     .select(
     *         user.name,
     *         ExpressionUtils.as(
     *             JPAExpressions
     *                 .select(order.count())
     *                 .from(order)
     *                 .where(order.user.id.eq(user.id)),
     *             "orderCount"    // 별칭
     *         )
     *     )
     *     .from(user)
     *     .fetch();
     *
     * ── [5] 서브쿼리 + 동적 조건 조합 ──
     *
     * // 특정 금액 이상 주문이 있는 사용자만 조회 (금액이 null이면 조건 무시)
     * private BooleanExpression hasOrderAbove(BigDecimal minAmount) {
     *     if (minAmount == null) return null;
     *     return JPAExpressions
     *         .selectOne()
     *         .from(order)
     *         .where(
     *             order.user.id.eq(user.id),
     *             order.totalAmount.goe(minAmount)
     *         )
     *         .exists();
     * }
     * </pre>
     */
    public void subQueryExample() {
        // 개념 설명용 — Q-class 빌드 후 사용
    }

    // ====================================================================
    // [7] Expressions — 상수, 문자열 조합, CASE, 커스텀 SQL
    // ====================================================================

    /**
     * Expressions 유틸리티 — QueryDSL에서 상수, 연산, CASE문, 커스텀 표현식 생성.
     *
     * <pre>
     * ┌──────────────────────────────────────────────────────────────────────┐
     * │  Expressions / Expression 관련 클래스 정리                             │
     * ├──────────────────────┬───────────────────────────────────────────────┤
     * │  Expressions         │ 상수, 템플릿, 타입 변환 등 유틸리티 팩토리      │
     * │  ExpressionUtils     │ 서브쿼리 별칭(as), 표현식 조합 유틸리티          │
     * │  NumberExpression    │ 숫자 연산 (sum, avg, multiply, divide 등)      │
     * │  StringExpression    │ 문자열 연산 (concat, lower, trim, substring)   │
     * │  BooleanExpression   │ 조건식 (eq, ne, gt, lt, in, between 등)       │
     * │  CaseBuilder         │ CASE WHEN ... THEN ... ELSE ... END           │
     * └──────────────────────┴───────────────────────────────────────────────┘
     *
     * ── [1] Expressions.constant() — 상수값 ──
     *
     * // SELECT 'FIXED_VALUE' AS label, name FROM users
     * List&lt;Tuple&gt; result = queryFactory
     *     .select(Expressions.constant("FIXED_VALUE"), user.name)
     *     .from(user)
     *     .fetch();
     *
     * ── [2] Expressions.asNumber() / asString() — 타입 변환 ──
     *
     * NumberExpression&lt;Integer&gt; zero = Expressions.asNumber(0);
     * StringExpression empty = Expressions.asString("");
     *
     * ── [3] StringExpression — 문자열 조합 ──
     *
     * // CONCAT: "[", name, "] ", email
     * StringExpression display = user.name
     *     .prepend("[")
     *     .append("] ")
     *     .append(user.email);
     *
     * // lower / upper / trim / substring
     * StringExpression lowerEmail = user.email.lower();
     * StringExpression domain = user.email.substring(
     *     user.email.indexOf("@").add(1));
     *
     * ── [4] NumberExpression — 숫자 연산 ──
     *
     * // 할인가 계산: totalAmount * (1 - discountRate / 100)
     * NumberExpression&lt;BigDecimal&gt; discountedPrice = order.totalAmount
     *     .multiply(Expressions.asNumber(1)
     *         .subtract(order.discountRate.divide(100)));
     *
     * // 집계: sum, avg, min, max, count
     * NumberExpression&lt;BigDecimal&gt; totalSum = order.totalAmount.sum();
     * NumberExpression&lt;Double&gt; avgAmount = order.totalAmount.avg();
     *
     * ── [5] CaseBuilder — CASE WHEN 표현식 ──
     *
     * // CASE WHEN order_status = 'PAID' THEN '결제완료'
     * //      WHEN order_status = 'SHIPPED' THEN '배송중'
     * //      ELSE '기타' END AS statusLabel
     *
     * StringExpression statusLabel = new CaseBuilder()
     *     .when(order.orderStatus.eq(OrderStatus.PAID)).then("결제완료")
     *     .when(order.orderStatus.eq(OrderStatus.SHIPPED)).then("배송중")
     *     .when(order.orderStatus.eq(OrderStatus.CANCELLED)).then("취소")
     *     .otherwise("기타");
     *
     * // 숫자 CASE — 정렬 우선순위 지정
     * NumberExpression&lt;Integer&gt; sortPriority = new CaseBuilder()
     *     .when(order.orderStatus.eq(OrderStatus.PENDING)).then(1)
     *     .when(order.orderStatus.eq(OrderStatus.PAID)).then(2)
     *     .when(order.orderStatus.eq(OrderStatus.SHIPPED)).then(3)
     *     .otherwise(99);
     *
     * // 정렬에 활용
     * // .orderBy(sortPriority.asc(), order.createdDate.desc())
     *
     * ── [6] Expressions.stringTemplate() — DB 함수 호출 ──
     *
     * // MySQL DATE_FORMAT 함수 사용
     * StringExpression formattedDate = Expressions.stringTemplate(
     *     "DATE_FORMAT({0}, {1})",
     *     order.createdDate,
     *     Expressions.constant("%Y-%m-%d")
     * );
     *
     * // MySQL IFNULL 함수 사용
     * StringExpression safeName = Expressions.stringTemplate(
     *     "IFNULL({0}, {1})",
     *     user.name,
     *     Expressions.constant("(이름없음)")
     * );
     *
     * // MySQL MATCH ... AGAINST (전문 검색)
     * BooleanExpression fullTextSearch = Expressions.booleanTemplate(
     *     "MATCH({0}) AGAINST({1} IN BOOLEAN MODE)",
     *     order.description,
     *     Expressions.constant("검색어")
     * );
     *
     * ── [7] ExpressionUtils — 표현식 조합 ──
     *
     * // 서브쿼리 별칭 (SELECT절에서 서브쿼리 사용 시 필수)
     * // ExpressionUtils.as(subQuery, "alias")  → [6] SubQuery 참고
     *
     * // 여러 BooleanExpression 합치기 (null-safe)
     * BooleanExpression combined = ExpressionUtils.allOf(
     *     statusEq(condition.status()),
     *     userIdEq(condition.userId()),
     *     amountGoe(condition.minAmount())
     * );
     * // allOf: 모든 조건 AND (null은 자동 무시)
     * // anyOf: 하나라도 만족하면 OR
     *
     * ── [8] 종합 예시: Projection + SubQuery + Expression ──
     *
     * List&lt;UserStatsDto&gt; stats = queryFactory
     *     .select(Projections.constructor(UserStatsDto.class,
     *         user.id,
     *         user.name,
     *         ExpressionUtils.as(
     *             JPAExpressions.select(order.count())
     *                 .from(order).where(order.user.id.eq(user.id)),
     *             "orderCount"),
     *         ExpressionUtils.as(
     *             JPAExpressions.select(order.totalAmount.sum())
     *                 .from(order).where(order.user.id.eq(user.id)),
     *             "totalSpent"),
     *         new CaseBuilder()
     *             .when(user.status.eq(UserStatus.ACTIVE)).then("활성")
     *             .otherwise("비활성")
     *     ))
     *     .from(user)
     *     .fetch();
     * </pre>
     */
    public void expressionsExample() {
        // 개념 설명용 — Q-class 빌드 후 사용
    }
}
