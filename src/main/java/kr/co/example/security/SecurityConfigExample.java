package kr.co.example.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.example.exception.ErrorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 설정 예제 — 세션 기반 vs JWT 토큰 기반 인증.
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  Spring Security 핵심 개념                                           │
 * ├──────────────────────────────────────────────────────────────────────┤
 * │                                                                      │
 * │  인증(Authentication): "너 누구야?" — 사용자 신원 확인                 │
 * │  인가(Authorization):  "너 이거 해도 돼?" — 권한/역할 확인             │
 * │                                                                      │
 * │  SecurityFilterChain: 요청 → [필터1] → [필터2] → ... → 컨트롤러       │
 * │  모든 HTTP 요청은 Security Filter Chain을 통과                        │
 * │                                                                      │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  세션 기반 vs 토큰(JWT) 기반 인증                                     │
 * ├────────────────┬────────────────────┬────────────────────────────────┤
 * │                │  세션 기반          │  JWT 토큰 기반                  │
 * ├────────────────┼────────────────────┼────────────────────────────────┤
 * │  상태 관리     │  서버에 세션 저장   │  Stateless (서버에 상태 없음)    │
 * │  저장 위치     │  서버 메모리/Redis  │  클라이언트 (쿠키/헤더)          │
 * │  확장성        │  Redis 등 공유 필요 │  서버 무관 (토큰만 검증)         │
 * │  보안          │  세션 ID 탈취 위험  │  토큰 탈취 위험 (만료 필수)      │
 * │  로그아웃      │  서버에서 세션 삭제 │  토큰 블랙리스트 필요 (복잡)     │
 * │  CSRF          │  방어 필요          │  불필요 (쿠키 미사용 시)         │
 * │  적합한 환경   │  모놀리식, SSR      │  MSA, SPA, 모바일 앱             │
 * └────────────────┴────────────────────┴────────────────────────────────┘
 *
 * concert-msa-project: 세션 기반 + Redis 저장 (@EnableRedisHttpSession)
 * queenssmile_back: JWT 토큰 기반 (OAuth2 + JJWT)
 * </pre>
 */
@Configuration
@EnableWebSecurity   // Spring Security 활성화
@EnableMethodSecurity // @PreAuthorize, @PostAuthorize, @Secured 활성화
@RequiredArgsConstructor
public class SecurityConfigExample {

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;

    // ====================================================================
    // [1] JWT 토큰 기반 SecurityFilterChain (Stateless)
    // ====================================================================

    /**
     * JWT 토큰 기반 Security 설정.
     *
     * <pre>
     * Spring Boot 3.x / Security 6.x 변경사항:
     * - WebSecurityConfigurerAdapter 삭제 → SecurityFilterChain @Bean 방식
     * - .csrf().disable() → .csrf(csrf -> csrf.disable()) 람다 DSL
     * - antMatchers() → requestMatchers()
     * </pre>
     */
    @Bean
    public SecurityFilterChain jwtSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                // ── CSRF 비활성화 ──
                // JWT 사용 시 CSRF 방어 불필요 (토큰 자체가 인증 수단)
                // 세션 기반이면 CSRF 활성화 필요
                .csrf(csrf -> csrf.disable())

                // ── CORS 설정 ──
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // ── 세션 관리 ──
                // STATELESS: 서버에 세션 생성하지 않음 (JWT 사용 시 필수)
                // IF_REQUIRED: 필요할 때만 세션 생성 (세션 기반 인증)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // ── URL별 접근 권한 설정 ──
                .authorizeHttpRequests(auth -> auth
                        // 인증 없이 접근 가능한 URL (허용 목록)
                        .requestMatchers("/api/auth/**").permitAll()     // 로그인/회원가입
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll() // Swagger
                        .requestMatchers("/actuator/health").permitAll() // 헬스체크
                        .requestMatchers("/h2-console/**").permitAll()   // H2 콘솔

                        // HTTP 메서드별 권한 설정
                        .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()

                        // 역할(Role) 기반 접근 제어
                        // hasRole("ADMIN") → ROLE_ADMIN 권한 필요 (ROLE_ 접두사 자동 추가)
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        // 나머지 모든 요청은 인증 필요
                        .anyRequest().authenticated()
                )

                // ── 인증/인가 실패 핸들러 ──
                // REST API는 HTML 로그인 페이지 대신 JSON 에러 응답 반환
                .exceptionHandling(exception -> exception
                        // 인증 실패 (401 Unauthorized)
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");

                            ErrorResponse error = ErrorResponse.of(
                                    401, "UNAUTHORIZED", "인증이 필요합니다");
                            response.getWriter().write(objectMapper.writeValueAsString(error));
                        })
                        // 인가 실패 (403 Forbidden)
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");

                            ErrorResponse error = ErrorResponse.of(
                                    403, "FORBIDDEN", "접근 권한이 없습니다");
                            response.getWriter().write(objectMapper.writeValueAsString(error));
                        })
                )

                // ── JWT 인증 필터 추가 ──
                // UsernamePasswordAuthenticationFilter 앞에 JWT 필터를 삽입
                // → 모든 요청에서 JWT 토큰을 먼저 확인
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider),
                        UsernamePasswordAuthenticationFilter.class
                )

                .build();
    }

    // ====================================================================
    // [2] 세션 기반 SecurityFilterChain (concert-msa-project 패턴)
    // ====================================================================

    /**
     * 세션 기반 Security 설정 예시.
     * concert-msa-project에서 사용하는 패턴.
     * 실제 사용 시에는 jwtSecurityFilterChain과 하나만 선택.
     *
     * <pre>
     * @Bean
     * public SecurityFilterChain sessionSecurityFilterChain(HttpSecurity http) throws Exception {
     *     return http
     *         .csrf(csrf -> csrf.disable()) // REST API → CSRF 비활성화
     *         .cors(cors -> cors.configurationSource(corsConfigurationSource()))
     *
     *         // 세션 기반 — IF_REQUIRED (필요 시 세션 생성)
     *         .sessionManagement(session -> session
     *             .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
     *
     *         // 폼 로그인 설정 (Spring Security 기본 제공)
     *         // .formLogin(form -> form
     *         //     .loginProcessingUrl("/api/auth/login")
     *         //     .successHandler(customSuccessHandler)
     *         //     .failureHandler(customFailureHandler))
     *
     *         .authorizeHttpRequests(auth -> auth
     *             .requestMatchers("/api/auth/**").permitAll()
     *             .anyRequest().authenticated())
     *
     *         .exceptionHandling(exception -> exception
     *             .authenticationEntryPoint(jsonAuthEntryPoint)
     *             .accessDeniedHandler(jsonAccessDeniedHandler))
     *
     *         .build();
     * }
     * </pre>
     */

    // ====================================================================
    // [3] 공통 Bean
    // ====================================================================

    /**
     * PasswordEncoder — 비밀번호 암호화.
     *
     * <pre>
     * BCrypt: 단방향 해시 함수 (복호화 불가)
     * → 같은 비밀번호도 매번 다른 해시값 생성 (salt 내장)
     * → 무차별 대입 공격에 강함 (의도적으로 느리게 설계)
     *
     * 사용법:
     * 암호화: encoder.encode("rawPassword") → "$2a$10$..."
     * 검증:   encoder.matches("rawPassword", encodedPassword) → true/false
     * </pre>
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * AuthenticationManager — 인증 처리 담당.
     * 로그인 시 UserDetailsService + PasswordEncoder를 사용하여 인증.
     */
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * CORS 설정 — Cross-Origin Resource Sharing.
     *
     * <pre>
     * 프론트엔드(localhost:3000)에서 백엔드(localhost:8080)를 호출할 때 필요.
     * 브라우저의 동일 출처 정책(Same-Origin Policy)을 우회.
     * </pre>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
                "http://localhost:3000",     // React 개발 서버
                "https://www.example.com"    // 운영 도메인
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));          // 모든 헤더 허용
        config.setAllowCredentials(true);                 // 쿠키/인증 헤더 허용
        config.setMaxAge(3600L);                          // Preflight 캐시 1시간

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);  // 모든 경로에 적용
        return source;
    }

    // ====================================================================
    // [4] @PreAuthorize — 메서드 레벨 보안 사용 예시
    // ====================================================================

    /**
     * 메서드 레벨 보안 — @PreAuthorize.
     * @EnableMethodSecurity 필요.
     *
     * <pre>
     * 사용 예시 (Controller/Service에서):
     *
     * @PreAuthorize("hasRole('ADMIN')")              // ROLE_ADMIN 권한 필요
     * public void adminOnly() { ... }
     *
     * @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')") // 둘 중 하나
     * public void managerOrAdmin() { ... }
     *
     * @PreAuthorize("isAuthenticated()")              // 인증된 사용자만
     * public void authenticatedOnly() { ... }
     *
     * @PreAuthorize("#userId == authentication.principal.id") // 본인만
     * public void selfOnly(Long userId) { ... }
     *
     * SpEL 표현식:
     * - hasRole('ADMIN')          : 특정 역할
     * - hasAnyRole('A', 'B')      : 여러 역할 중 하나
     * - isAuthenticated()         : 인증된 사용자
     * - isAnonymous()             : 미인증 사용자
     * - #파라미터명                : 메서드 파라미터 참조
     * - authentication.principal   : 현재 인증 사용자 정보
     * </pre>
     */
}
