package kr.co.example.jpa;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

/**
 * JPA Auditing 설정 — @CreatedDate, @LastModifiedDate, @CreatedBy 자동 주입.
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────┐
 * │  @EnableJpaAuditing 활성화 시 사용 가능한 어노테이션   │
 * ├──────────────────┬───────────────────────────────────┤
 * │  @CreatedDate    │ 엔티티 생성 시각 자동 설정          │
 * │  @LastModifiedDate│ 엔티티 수정 시각 자동 갱신         │
 * │  @CreatedBy      │ 생성자 자동 설정 (AuditorAware)    │
 * │  @LastModifiedBy │ 수정자 자동 설정 (AuditorAware)    │
 * └──────────────────┴───────────────────────────────────┘
 *
 * @CreatedBy / @LastModifiedBy를 사용하려면
 * AuditorAware Bean을 등록해야 합니다.
 * Spring Security의 SecurityContext에서 현재 사용자를 가져오는 것이 일반적.
 * </pre>
 */
@Configuration
@EnableJpaAuditing // JPA Auditing 기능 활성화 — 이 어노테이션 없으면 @CreatedDate 등 동작 안 함
public class JpaAuditConfig {

    /**
     * 현재 로그인 사용자를 반환하는 AuditorAware 구현.
     *
     * <pre>
     * @CreatedBy, @LastModifiedBy 필드에 자동 주입되는 값을 결정.
     *
     * 실무 예시 (Spring Security 연동):
     * SecurityContextHolder.getContext().getAuthentication().getName()
     * </pre>
     *
     * @return AuditorAware — 현재 사용자 ID/이름을 Optional로 반환
     */
    @Bean
    public AuditorAware<String> auditorAware() {
        // 예시: Spring Security 컨텍스트에서 사용자 이름 추출
        // 실무에서는 SecurityContextHolder 사용
        return () -> Optional.of("system");

        /*
         * 실무 코드:
         * return () -> Optional.ofNullable(SecurityContextHolder.getContext())
         *         .map(SecurityContext::getAuthentication)
         *         .filter(Authentication::isAuthenticated)
         *         .map(Authentication::getName);
         */
    }
}
