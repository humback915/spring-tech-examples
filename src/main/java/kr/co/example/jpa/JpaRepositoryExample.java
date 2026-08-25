package kr.co.example.jpa;

import kr.co.example.jpa.JpaEntityExample.Order;
import kr.co.example.jpa.JpaEntityExample.OrderStatus;
import kr.co.example.jpa.JpaEntityExample.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA Repository 예제 — 기본 CRUD부터 커스텀 쿼리까지.
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  Spring Data JPA Repository 계층 구조                                │
 * ├──────────────────────────────────────────────────────────────────────┤
 * │                                                                      │
 * │  Repository<T, ID>           ← 최상위 마커 인터페이스                  │
 * │    └── CrudRepository        ← CRUD 기본 메서드 (save, findById 등)   │
 * │          └── ListCrudRepository ← List 반환 (Spring Boot 3.x+)       │
 * │          └── PagingAndSortingRepository ← 페이징 + 정렬              │
 * │                └── JpaRepository  ← JPA 특화 (flush, batch 등)        │
 * │                                                                      │
 * │  실무에서는 JpaRepository를 가장 많이 사용                              │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  쿼리 생성 방식 비교                                                  │
 * ├──────────────────┬───────────────────────────────────────────────────┤
 * │  메서드 이름 기반  │ findByEmailAndStatus() → 자동 쿼리 생성           │
 * │                   │ 간단한 조건에 적합, 조건이 많으면 메서드명이 길어짐   │
 * ├──────────────────┼───────────────────────────────────────────────────┤
 * │  @Query (JPQL)   │ 엔티티 기반 쿼리 (테이블명 대신 클래스명 사용)       │
 * │                   │ DB에 독립적, 컴파일 타임 검증 가능                  │
 * ├──────────────────┼───────────────────────────────────────────────────┤
 * │  @Query (Native) │ 실제 SQL 사용 (nativeQuery = true)                │
 * │                   │ DB 종속적, 복잡한 쿼리나 DB 함수 사용 시             │
 * ├──────────────────┼───────────────────────────────────────────────────┤
 * │  QueryDSL        │ Java 코드로 타입 안전 쿼리 작성                     │
 * │                   │ 동적 쿼리에 강점, 컴파일 타임 오류 감지              │
 * │                   │ → QueryDslExample.java 참고                      │
 * └──────────────────┴───────────────────────────────────────────────────┘
 * </pre>
 */
public class JpaRepositoryExample {

    // ====================================================================
    // [1] 기본 JpaRepository — 메서드 이름 기반 쿼리
    // ====================================================================

    /**
     * UserRepository — JpaRepository의 기본 사용법.
     *
     * <pre>
     * JpaRepository&lt;User, Long&gt; 상속만으로 사용 가능한 메서드:
     * - save(entity)        : INSERT 또는 UPDATE (ID 존재 여부로 판단)
     * - findById(id)        : PK로 단건 조회 → Optional&lt;T&gt; 반환
     * - findAll()           : 전체 조회
     * - findAll(Pageable)   : 페이징 조회 → Page&lt;T&gt; 반환
     * - deleteById(id)      : PK로 삭제
     * - count()             : 전체 개수
     * - existsById(id)      : 존재 여부 확인
     * - flush()             : 영속성 컨텍스트 → DB 동기화
     * - saveAndFlush(entity): save + 즉시 flush
     * </pre>
     */
    public interface UserRepository extends JpaRepository<User, Long> {

        // ── 메서드 이름 기반 쿼리 (Query Method) ──
        // Spring Data가 메서드명을 파싱하여 자동으로 JPQL 생성

        /** findBy + 필드명 → WHERE email = ? */
        Optional<User> findByEmail(String email);

        /** And → WHERE email = ? AND status = ? */
        Optional<User> findByEmailAndStatus(String email, JpaEntityExample.UserStatus status);

        /** existsBy → SELECT COUNT(*) > 0 (존재 여부만 확인, 엔티티 로딩 안 함) */
        boolean existsByEmail(String email);

        /** countBy → SELECT COUNT(*) */
        long countByStatus(JpaEntityExample.UserStatus status);

        /** OrderBy → ORDER BY createdDate DESC */
        List<User> findByStatusOrderByCreatedDateDesc(JpaEntityExample.UserStatus status);

        /** Top/First → LIMIT (상위 N건 조회) */
        List<User> findTop10ByStatusOrderByCreatedDateDesc(JpaEntityExample.UserStatus status);

        /**
         * Containing → LIKE '%keyword%'
         * 다른 키워드: StartingWith (LIKE 'keyword%'), EndingWith (LIKE '%keyword')
         */
        List<User> findByNameContaining(String keyword);

        /**
         * Between → WHERE createdDate BETWEEN ? AND ?
         */
        List<User> findByCreatedDateBetween(LocalDateTime start, LocalDateTime end);

        /**
         * In → WHERE status IN (?, ?, ?)
         */
        List<User> findByStatusIn(List<JpaEntityExample.UserStatus> statuses);

        // ── 페이징 조회 ──

        /**
         * Page vs Slice 반환 타입:
         *
         * <pre>
         * ┌──────────┬──────────────────────────────────────────────┐
         * │  Page<T> │ 전체 개수 포함 (COUNT 쿼리 추가 실행)          │
         * │          │ getTotalElements(), getTotalPages() 사용 가능  │
         * │          │ 전체 페이지 수를 UI에 표시할 때 사용              │
         * ├──────────┼──────────────────────────────────────────────┤
         * │  Slice<T>│ 전체 개수 없음 (COUNT 쿼리 미실행 → 성능 우수)  │
         * │          │ hasNext()만 제공 ("더보기" 버튼 구현에 적합)      │
         * │          │ 내부적으로 limit+1 조회하여 다음 페이지 존재 확인  │
         * ├──────────┼──────────────────────────────────────────────┤
         * │  List<T> │ 단순 리스트, 페이징 메타 정보 없음               │
         * └──────────┴──────────────────────────────────────────────┘
         * </pre>
         */
        Page<User> findByStatus(JpaEntityExample.UserStatus status, Pageable pageable);

        /** Slice 반환 — COUNT 쿼리 없이 "더보기" 패턴에 적합 */
        Slice<User> findSliceByStatus(JpaEntityExample.UserStatus status, Pageable pageable);
    }

    // ====================================================================
    // [2] @Query — JPQL / Native SQL
    // ====================================================================

    /**
     * OrderRepository — @Query 어노테이션 사용법.
     */
    public interface OrderRepository extends JpaRepository<Order, Long>,
            OrderRepositoryCustom { // 커스텀 Repository 인터페이스 결합

        // ── JPQL (Java Persistence Query Language) ──
        // 엔티티 클래스명과 필드명 사용 (테이블명/컬럼명 아님)

        /**
         * JPQL 기본 — 엔티티 필드명으로 쿼리 작성.
         * :status → Named Parameter (위치 기반 ?1보다 가독성 좋음)
         * @Param으로 메서드 파라미터와 매핑
         */
        @Query("SELECT o FROM JpaEntityExample$Order o WHERE o.orderStatus = :status")
        List<Order> findByOrderStatus(@Param("status") OrderStatus status);

        /**
         * JPQL JOIN — 엔티티 관계를 통한 조인.
         * JOIN FETCH: N+1 문제 해결 — 연관 엔티티를 한 번의 쿼리로 함께 조회
         */
        @Query("SELECT o FROM JpaEntityExample$Order o JOIN FETCH o.user WHERE o.id = :id")
        Optional<Order> findByIdWithUser(@Param("id") Long id);

        /**
         * JPQL 집계 — SUM, COUNT, AVG 등.
         */
        @Query("SELECT SUM(o.totalAmount) FROM JpaEntityExample$Order o " +
                "WHERE o.user.id = :userId AND o.orderStatus = :status")
        BigDecimal sumTotalAmountByUserAndStatus(
                @Param("userId") Long userId,
                @Param("status") OrderStatus status);

        // ── Native SQL ──

        /**
         * Native SQL — DB 고유 문법 사용 시 (MySQL 함수, 힌트 등).
         * nativeQuery = true 필수.
         * 테이블명/컬럼명 사용 (엔티티명 아님).
         *
         * <pre>
         * 주의: Native SQL은 DB 종속적
         * → H2 ↔ MySQL 전환 시 쿼리 수정 필요
         * → 가능하면 JPQL 사용 권장
         * </pre>
         */
        @Query(value = "SELECT * FROM orders WHERE user_id = :userId " +
                "ORDER BY created_date DESC LIMIT :limit",
                nativeQuery = true)
        List<Order> findRecentOrdersNative(
                @Param("userId") Long userId,
                @Param("limit") int limit);

        // ── @Modifying — UPDATE / DELETE 쿼리 ──

        /**
         * 벌크 UPDATE — 여러 건을 한 번의 쿼리로 수정.
         *
         * <pre>
         * @Modifying 필수: SELECT가 아닌 DML(UPDATE/DELETE) 쿼리임을 표시
         *
         * clearAutomatically = true:
         *   벌크 연산은 영속성 컨텍스트를 거치지 않고 DB에 직접 실행
         *   → 영속성 컨텍스트의 캐시와 DB가 불일치
         *   → true로 설정하면 실행 후 자동으로 영속성 컨텍스트 초기화(clear)
         *
         * flushAutomatically = true:
         *   벌크 연산 전에 영속성 컨텍스트의 변경사항을 DB에 반영(flush)
         *   → 미반영된 변경사항이 벌크 연산에 의해 덮어씌워지는 것 방지
         * </pre>
         */
        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("UPDATE JpaEntityExample$Order o SET o.orderStatus = :newStatus " +
                "WHERE o.orderStatus = :oldStatus AND o.createdDate < :before")
        int bulkUpdateStatus(
                @Param("oldStatus") OrderStatus oldStatus,
                @Param("newStatus") OrderStatus newStatus,
                @Param("before") LocalDateTime before);

        /**
         * 벌크 DELETE — 조건에 맞는 데이터 일괄 삭제.
         */
        @Modifying(clearAutomatically = true)
        @Query("DELETE FROM JpaEntityExample$Order o " +
                "WHERE o.orderStatus = :status AND o.createdDate < :before")
        int bulkDeleteByStatusAndDate(
                @Param("status") OrderStatus status,
                @Param("before") LocalDateTime before);
    }

    // ====================================================================
    // [3] 커스텀 Repository — 3계층 패턴 (QueryDSL 등 사용 시)
    // ====================================================================

    /**
     * 커스텀 Repository 인터페이스 — 동적 쿼리 등 JpaRepository로 부족한 기능 정의.
     *
     * <pre>
     * 3계층 패턴:
     * ┌──────────────────────────────────────────────────────────────┐
     * │  OrderRepository         (인터페이스)                         │
     * │    extends JpaRepository                                     │
     * │    extends OrderRepositoryCustom  ← [커스텀 인터페이스]        │
     * │                                                              │
     * │  OrderRepositoryCustom   (인터페이스) ← 커스텀 메서드 시그니처  │
     * │                                                              │
     * │  OrderRepositoryImpl     (구현 클래스) ← QueryDSL 등으로 구현  │
     * │    implements OrderRepositoryCustom                           │
     * └──────────────────────────────────────────────────────────────┘
     *
     * 네이밍 규칙: 반드시 {Repository이름}Impl (예: OrderRepositoryImpl)
     * → Spring Data가 Impl 접미사를 자동 인식하여 빈으로 등록
     * </pre>
     */
    public interface OrderRepositoryCustom {

        /** 조건 기반 동적 검색 (QueryDSL 구현) */
        Page<Order> searchOrders(OrderSearchCondition condition, Pageable pageable);
    }

    /**
     * 검색 조건 DTO — 동적 쿼리에 사용.
     *
     * @param userId      사용자 ID (null이면 조건 제외)
     * @param status      주문 상태 (null이면 조건 제외)
     * @param minAmount   최소 금액 (null이면 조건 제외)
     * @param startDate   시작 일시 (null이면 조건 제외)
     * @param endDate     종료 일시 (null이면 조건 제외)
     */
    public record OrderSearchCondition(
            Long userId,
            OrderStatus status,
            BigDecimal minAmount,
            LocalDateTime startDate,
            LocalDateTime endDate
    ) {
    }

    // Impl 클래스는 QueryDslExample.java에서 구현
}
