package kr.co.example.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 인증 필터 — 모든 요청에서 Authorization 헤더의 Bearer 토큰을 검증.
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  JWT 인증 필터 동작 흐름                                               │
 * ├──────────────────────────────────────────────────────────────────────┤
 * │                                                                      │
 * │  HTTP 요청                                                           │
 * │    │                                                                 │
 * │    ▼                                                                 │
 * │  [JwtAuthenticationFilter]                                           │
 * │    │                                                                 │
 * │    ├── Authorization 헤더 확인                                        │
 * │    │     └── "Bearer " 접두사 제거 → JWT 토큰 추출                    │
 * │    │                                                                 │
 * │    ├── 토큰 존재? ──No──→ 다음 필터로 (인증 없이)                     │
 * │    │     │                                                           │
 * │    │    Yes                                                          │
 * │    │     │                                                           │
 * │    ├── 토큰 유효? ──No──→ 다음 필터로 (인증 실패 → 401)               │
 * │    │     │                                                           │
 * │    │    Yes                                                          │
 * │    │     │                                                           │
 * │    ├── Authentication 객체 생성                                       │
 * │    │     └── SecurityContextHolder에 저장                             │
 * │    │                                                                 │
 * │    ▼                                                                 │
 * │  [다음 Security 필터들]                                               │
 * │    │                                                                 │
 * │    ▼                                                                 │
 * │  [Controller] — SecurityContext에서 인증 정보 사용                     │
 * │                                                                      │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * OncePerRequestFilter:
 * - 요청당 한 번만 실행됨을 보장 (forward/include 시 중복 실행 방지)
 * - GenericFilterBean보다 안전
 *
 * Bearer Token (RFC 6750):
 * - HTTP 인증 스킴 중 하나
 * - 형식: "Authorization: Bearer {token}"
 * - "Bearer"는 "이 토큰의 보유자(bearer)에게 접근을 허용한다"는 의미
 * - OAuth 2.0 표준에서 정의
 * </pre>
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Authorization 헤더명 */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /** Bearer 토큰 접두사 */
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    /**
     * 필터 핵심 로직 — 모든 HTTP 요청마다 실행.
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // [1] 요청 헤더에서 JWT 토큰 추출
        String token = resolveToken(request);

        // [2] 토큰이 존재하고 유효한 경우 → 인증 정보 설정
        if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {

            // [3] 토큰에서 Authentication 객체 생성
            Authentication authentication = jwtTokenProvider.getAuthentication(token);

            // [4] SecurityContext에 인증 정보 저장
            // → 이후 @PreAuthorize, SecurityContextHolder.getContext() 등에서 사용
            SecurityContextHolder.getContext().setAuthentication(authentication);

            log.debug("인증 성공: userId={}, uri={}",
                    jwtTokenProvider.getUserId(token), request.getRequestURI());
        }

        // [5] 다음 필터로 진행 (인증 여부와 관계없이)
        // 인증이 안 된 상태로 넘어가면 authorizeHttpRequests()에서 401 처리
        filterChain.doFilter(request, response);
    }

    /**
     * Authorization 헤더에서 Bearer 토큰 추출.
     *
     * <pre>
     * 요청 예시:
     * Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIi...
     *
     * → "Bearer " 접두사 제거 → "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIi..."
     * </pre>
     */
    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);

        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length()); // "Bearer " 제거
        }
        return null;
    }

    /**
     * 특정 경로 필터 제외 — shouldNotFilter().
     *
     * <pre>
     * SecurityFilterChain의 permitAll()과 다른 점:
     * - permitAll(): 필터는 실행되지만 인증 없이 접근 허용
     * - shouldNotFilter(): 필터 자체를 실행하지 않음 (성능 이점)
     *
     * 인증이 절대 필요 없는 경로(정적 리소스, 헬스체크)에 사용.
     * </pre>
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/h2-console");
    }
}
