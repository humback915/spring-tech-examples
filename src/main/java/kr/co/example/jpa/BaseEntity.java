package kr.co.example.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 공통 엔티티 — 모든 엔티티가 상속하는 기본 클래스.
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────┐
 * │  @MappedSuperclass vs @Entity 상속                               │
 * ├──────────────────┬───────────────────────────────────────────────┤
 * │  @MappedSuperclass│ 테이블 생성 안 함, 컬럼만 자식에게 상속        │
 * │                   │ 부모 타입으로 조회 불가 (JPQL FROM 불가)        │
 * │                   │ 공통 필드(audit 등) 공유에 적합                 │
 * ├──────────────────┼───────────────────────────────────────────────┤
 * │  @Inheritance     │ 실제 테이블 생성됨                             │
 * │  (SINGLE_TABLE)   │ 부모 타입으로 조회 가능                        │
 * │                   │ 상속 계층 전체를 하나의 테이블에 저장             │
 * └──────────────────┴───────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────┐
 * │  Auditing 방식 비교                                               │
 * ├──────────────────┬───────────────────────────────────────────────┤
 * │  @EnableJpa      │ Spring Data JPA 제공                          │
 * │  Auditing +      │ @CreatedDate, @LastModifiedDate 자동 설정       │
 * │  AuditingEntity  │ JpaAuditConfig에서 @EnableJpaAuditing 필요     │
 * │  Listener        │ @CreatedBy, @LastModifiedBy도 지원 (AuditorAware) │
 * ├──────────────────┼───────────────────────────────────────────────┤
 * │  @PrePersist     │ JPA 표준 라이프사이클 콜백                      │
 * │  @PreUpdate      │ Spring 의존 없이 순수 JPA로 동작                │
 * │                  │ @EntityListeners 불필요                         │
 * │                  │ 더 세밀한 제어 가능 (커스텀 로직 추가)            │
 * └──────────────────┴───────────────────────────────────────────────┘
 * </pre>
 *
 * <p>이 예제는 두 가지 방식을 모두 보여줍니다.
 * 실무에서는 하나만 선택하여 사용합니다.</p>
 */
@Getter
@MappedSuperclass // 테이블 생성 없이 필드만 자식에게 상속
@EntityListeners(AuditingEntityListener.class) // Spring Data JPA Auditing 리스너 등록
public abstract class BaseEntity {

    // ────────────────────────────────────────
    // [방법 1] Spring Data JPA Auditing (@CreatedDate, @LastModifiedDate)
    // JpaAuditConfig에서 @EnableJpaAuditing 활성화 필요
    // ────────────────────────────────────────

    /**
     * 생성 일시 — INSERT 시 자동 설정.
     * updatable = false: UPDATE 시 변경 불가 (생성 시각 보존)
     */
    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdDate;

    /**
     * 수정 일시 — INSERT/UPDATE 시 자동 갱신.
     */
    @LastModifiedDate
    private LocalDateTime updatedDate;

    // ────────────────────────────────────────
    // [방법 2] JPA 라이프사이클 콜백 (@PrePersist, @PreUpdate)
    // @EntityListeners 없이도 동작 — 순수 JPA 방식
    // ────────────────────────────────────────

    /**
     * JPA 라이프사이클 콜백 — 엔티티 영속화(INSERT) 직전 호출.
     *
     * <pre>
     * 전체 콜백 종류:
     * ┌────────────────┬─────────────────────────────────────────┐
     * │  @PrePersist   │ persist() 직전 (INSERT 전)              │
     * │  @PostPersist  │ persist() 직후 (INSERT 후, flush 후)    │
     * │  @PreUpdate    │ merge() / dirty checking 직전           │
     * │  @PostUpdate   │ UPDATE 실행 후                          │
     * │  @PreRemove    │ remove() 직전 (DELETE 전)               │
     * │  @PostRemove   │ DELETE 실행 후                          │
     * │  @PostLoad     │ 엔티티 조회(SELECT) 직후                 │
     * └────────────────┴─────────────────────────────────────────┘
     *
     * 주의: @PrePersist와 @CreatedDate가 동시에 있으면
     * AuditingEntityListener가 먼저 실행되므로 @PrePersist에서 덮어쓸 수 있음.
     * 실무에서는 둘 중 하나만 사용할 것.
     * </pre>
     */
    @PrePersist
    protected void onCreate() {
        // 이 예제에서는 @CreatedDate와 중복이므로 주석 처리
        // this.createdDate = LocalDateTime.now();
        // this.updatedDate = LocalDateTime.now();
    }

    /** 엔티티 수정(UPDATE) 직전 호출 */
    @PreUpdate
    protected void onUpdate() {
        // this.updatedDate = LocalDateTime.now();
    }

    // ────────────────────────────────────────
    // [3] 낙관적 락 (Optimistic Lock) — @Version
    // ────────────────────────────────────────

    /**
     * 낙관적 락 버전 — UPDATE 시 자동 증가.
     *
     * <pre>
     * 동작 원리:
     * 1. 조회 시 version = 1
     * 2. UPDATE 시 WHERE version = 1 조건 추가
     * 3. 다른 트랜잭션이 먼저 수정했으면 version != 1 → OptimisticLockException
     * 4. 성공 시 version = 2로 증가
     *
     * 적합한 상황: 충돌이 드문 경우 (읽기 > 쓰기)
     * 부적합: 동시 쓰기가 빈번한 재고 차감 등
     * </pre>
     */
    @Version
    private Integer version;
}
