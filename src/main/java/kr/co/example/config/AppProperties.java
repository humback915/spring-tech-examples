package kr.co.example.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 설정 바인딩 예제 — @ConfigurationProperties, @Value, @Profile.
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  @Value vs @ConfigurationProperties 비교                              │
 * ├──────────────────┬───────────────────────────────────────────────────┤
 * │                  │  @Value                │  @ConfigurationProperties│
 * ├──────────────────┼────────────────────────┼──────────────────────────┤
 * │  바인딩 방식     │  개별 필드에 하나씩     │  접두사 기반 그룹 바인딩  │
 * │  타입 안전       │  약함 (SpEL 문자열)     │  강함 (Java 타입)         │
 * │  리팩토링        │  프로퍼티명 변경 시     │  필드명 = 프로퍼티명       │
 * │                  │  모든 @Value 수정 필요  │  한 곳만 수정              │
 * │  중첩 구조       │  지원 안 함             │  지원 (내부 클래스)        │
 * │  컬렉션          │  지원 안 함             │  List, Map 지원            │
 * │  Validation      │  제한적                 │  @Validated 지원           │
 * │  적합한 경우     │  단순 값 1~2개          │  관련 설정 그룹             │
 * └──────────────────┴────────────────────────┴──────────────────────────┘
 *
 * 결론: 설정이 3개 이상이면 @ConfigurationProperties 사용 권장
 * </pre>
 */
public class AppProperties {

    // ====================================================================
    // [1] @Value — 개별 프로퍼티 주입
    // ====================================================================

    /**
     * @Value 사용법 예시.
     *
     * <pre>
     * // 단순 값 주입
     * @Value("${app.name}")
     * private String appName;
     *
     * // 기본값 지정 (: 뒤에 기본값)
     * @Value("${app.timeout:5000}")
     * private int timeout;
     *
     * // SpEL 표현식 (Spring Expression Language)
     * @Value("#{${app.timeout} * 2}")
     * private int doubleTimeout;
     *
     * // 환경변수 참조 (없으면 기본값)
     * @Value("${TOSS_SECRET_KEY:test-key}")
     * private String tossSecretKey;
     *
     * // 리스트 (쉼표 구분)
     * // application.yml: app.allowed-origins: http://localhost:3000,https://example.com
     * @Value("${app.allowed-origins}")
     * private List&lt;String&gt; allowedOrigins;
     * </pre>
     */

    // ====================================================================
    // [2] @ConfigurationProperties — 타입 안전 프로퍼티 그룹
    // ====================================================================

    /**
     * 서비스 URL 프로퍼티 — MSA 서비스 간 통신 URL 관리.
     * concert-msa-project의 ServiceUrlProperties 패턴 참고.
     *
     * <pre>
     * application.yml:
     * service:
     *   concert-url: http://localhost:8082
     *   order-url: http://localhost:8083
     *   payment-url: http://localhost:8084
     *
     * 사용법:
     * @Autowired
     * private ServiceUrlProperties serviceUrlProperties;
     * String url = serviceUrlProperties.getConcertUrl();
     * </pre>
     */
    @Getter
    @Setter // @ConfigurationProperties는 setter 필요 (바인딩에 사용)
    @Component
    @ConfigurationProperties(prefix = "service") // "service.*" 프로퍼티와 매핑
    public static class ServiceUrlProperties {
        /** 콘서트 서비스 URL */
        private String concertUrl;
        /** 주문 서비스 URL */
        private String orderUrl;
        /** 결제 서비스 URL */
        private String paymentUrl;
    }

    /**
     * 애플리케이션 설정 — 중첩 구조 + 컬렉션 바인딩.
     *
     * <pre>
     * application.yml:
     * app:
     *   name: spring-tech-examples
     *   version: 1.0.0
     *   cors:
     *     allowed-origins:
     *       - http://localhost:3000
     *       - https://example.com
     *     max-age: 3600
     *   cache:
     *     default-ttl: 10m
     *     max-size: 1000
     * </pre>
     */
    @Getter
    @Setter
    @Component
    @ConfigurationProperties(prefix = "app")
    public static class AppConfig {
        private String name;
        private String version;

        /** 중첩 객체 — app.cors.* */
        private CorsConfig cors = new CorsConfig();

        /** 중첩 객체 — app.cache.* */
        private CacheConfig cache = new CacheConfig();

        @Getter
        @Setter
        public static class CorsConfig {
            /** List 바인딩 — app.cors.allowed-origins */
            private List<String> allowedOrigins;
            private long maxAge = 3600;
        }

        @Getter
        @Setter
        public static class CacheConfig {
            /** Duration 바인딩 — "10m" → Duration.ofMinutes(10) 자동 변환 */
            private Duration defaultTtl = Duration.ofMinutes(10);
            private int maxSize = 1000;
        }
    }

    // ====================================================================
    // [3] @Profile — 환경별 Bean/설정 분리
    // ====================================================================

    /**
     * @Profile — 특정 환경에서만 활성화되는 Bean.
     *
     * <pre>
     * 활성화 방법:
     * 1. application.yml: spring.profiles.active: dev
     * 2. JVM 옵션: -Dspring.profiles.active=dev
     * 3. 환경변수: SPRING_PROFILES_ACTIVE=dev
     *
     * 프로파일별 설정 파일:
     * - application.yml          → 공통 설정
     * - application-dev.yml      → 개발 환경
     * - application-prod.yml     → 운영 환경
     * - application-local.yml    → 로컬 환경
     *
     * 프로파일별 파일이 공통 설정을 덮어씀 (override)
     * </pre>
     */
    @Configuration
    public static class ProfileConfig {

        /**
         * 개발 환경 전용 Bean — @Profile("dev")
         * dev 프로파일이 활성화될 때만 생성됨.
         *
         * <pre>
         * 표현식:
         * @Profile("dev")         → dev일 때만
         * @Profile("!prod")       → prod가 아닐 때
         * @Profile({"dev","local"})→ dev 또는 local일 때
         * </pre>
         */
        @Bean
        @Profile("dev")
        public String devOnlyBean() {
            return "개발 환경 전용 Bean";
        }

        @Bean
        @Profile("prod")
        public String prodOnlyBean() {
            return "운영 환경 전용 Bean";
        }
    }
}
