package kr.co.example.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;

/**
 * JWT 토큰 생성/검증/파싱 — JJWT 0.12.x (Spring Boot 3.x 호환).
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  JWT (JSON Web Token) 구조                                           │
 * ├──────────────────────────────────────────────────────────────────────┤
 * │                                                                      │
 * │  Header.Payload.Signature                                            │
 * │                                                                      │
 * │  [Header]   : 토큰 타입(JWT) + 서명 알고리즘(HS256 등)               │
 * │  [Payload]  : 클레임(Claims) — 사용자 ID, 역할, 만료시간 등           │
 * │  [Signature]: Header + Payload를 SecretKey로 서명 → 위변조 방지       │
 * │                                                                      │
 * │  eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwicm9sZSI6...                   │
 * │  ^^^^^^^^^^^^^^^^^^^^^^^^ ^^^^^^^^^^^^^^^^^^^^^^^^ ^^^^^^^^           │
 * │       Header(Base64)       Payload(Base64)         Signature         │
 * │                                                                      │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  JWT 토큰 인증 흐름                                                   │
 * ├──────────────────────────────────────────────────────────────────────┤
 * │  1. 로그인: POST /api/auth/login {email, password}                   │
 * │  2. 서버: 인증 성공 → JWT 토큰 생성 → 클라이언트에 반환               │
 * │  3. 클라이언트: 토큰을 저장 (localStorage / httpOnly 쿠키)            │
 * │  4. API 호출: Authorization: Bearer {token} 헤더에 포함               │
 * │  5. 서버: JwtAuthenticationFilter에서 토큰 검증                       │
 * │  6. 검증 성공 → SecurityContext에 인증 정보 저장 → 컨트롤러 실행       │
 * │  7. 검증 실패 → 401 Unauthorized 응답                                │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  Access Token vs Refresh Token                                       │
 * ├──────────────────┬───────────────────────────────────────────────────┤
 * │  Access Token    │ 짧은 만료 (15분~1시간)                            │
 * │                  │ API 호출 시 Authorization 헤더에 포함              │
 * │                  │ 탈취 시 피해를 최소화하기 위해 짧게 설정            │
 * ├──────────────────┼───────────────────────────────────────────────────┤
 * │  Refresh Token   │ 긴 만료 (7일~30일)                               │
 * │                  │ Access Token 재발급 용도                          │
 * │                  │ httpOnly 쿠키 또는 서버 DB에 저장 (보안)           │
 * │                  │ Redis에 저장하여 즉시 무효화(로그아웃) 가능         │
 * └──────────────────┴───────────────────────────────────────────────────┘
 *
 * JJWT 라이브러리 의존성 (build.gradle):
 *   implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
 *   runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.6'
 *   runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.6'
 * </pre>
 */
@Slf4j
@Component
public class JwtTokenProvider {

    /** 서명용 비밀키 — application.yml에서 주입, 최소 256비트 이상 */
    private final SecretKey secretKey;

    /** Access Token 만료 시간 (밀리초) — 기본 30분 */
    private final long accessTokenExpiry;

    /** Refresh Token 만료 시간 (밀리초) — 기본 7일 */
    private final long refreshTokenExpiry;

    /**
     * 생성자 — Base64로 인코딩된 secret을 SecretKey 객체로 변환.
     *
     * @param secret           Base64 인코딩된 비밀키 (application.yml: jwt.secret)
     * @param accessTokenExpiry  Access Token 만료 시간 (ms)
     * @param refreshTokenExpiry Refresh Token 만료 시간 (ms)
     */
    public JwtTokenProvider(
            @Value("${jwt.secret:dGhpc0lzQVRlc3RTZWNyZXRLZXlGb3JKd3RUb2tlblByb3ZpZGVyRXhhbXBsZQ==}") String secret,
            @Value("${jwt.access-token-expiry:1800000}") long accessTokenExpiry,
            @Value("${jwt.refresh-token-expiry:604800000}") long refreshTokenExpiry) {

        // Base64 디코딩 → HMAC-SHA 키 생성
        this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.accessTokenExpiry = accessTokenExpiry;
        this.refreshTokenExpiry = refreshTokenExpiry;
    }

    // ────────────────────────────────────────
    // [1] 토큰 생성
    // ────────────────────────────────────────

    /**
     * Access Token 생성.
     *
     * <pre>
     * Claims (페이로드에 포함되는 정보):
     * - sub (subject): 사용자 고유 식별자 (보통 userId)
     * - role:          사용자 역할 (ADMIN, USER 등)
     * - iat (issuedAt): 토큰 발급 시각
     * - exp (expiration): 토큰 만료 시각
     *
     * 주의: JWT 페이로드는 Base64 디코딩하면 누구나 읽을 수 있음
     * → 비밀번호, 개인정보 등 민감한 정보를 절대 포함하지 말 것
     * </pre>
     */
    public String createAccessToken(Long userId, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpiry);

        return Jwts.builder()
                .subject(String.valueOf(userId))  // sub 클레임 — 사용자 ID
                .claim("role", role)               // 커스텀 클레임 — 역할
                .claim("type", "ACCESS")           // 토큰 종류 구분
                .issuedAt(now)                     // iat 클레임 — 발급 시각
                .expiration(expiry)                // exp 클레임 — 만료 시각
                .signWith(secretKey)               // 서명 (HS256 자동 선택)
                .compact();                        // 토큰 문자열 생성
    }

    /** Refresh Token 생성 — Access Token 재발급용 */
    public String createRefreshToken(Long userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + refreshTokenExpiry);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("type", "REFRESH")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    // ────────────────────────────────────────
    // [2] 토큰 검증
    // ────────────────────────────────────────

    /**
     * 토큰 유효성 검증.
     *
     * <pre>
     * 검증 항목:
     * 1. 서명 위변조 여부 (SecretKey로 검증)
     * 2. 토큰 만료 여부 (exp 클레임)
     * 3. 토큰 형식 유효성 (JWT 구조)
     *
     * 발생 가능한 예외:
     * - ExpiredJwtException:   토큰 만료
     * - MalformedJwtException: 잘못된 JWT 형식
     * - SecurityException:     잘못된 서명
     * - UnsupportedJwtException: 지원하지 않는 JWT
     * </pre>
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey) // 서명 검증 키 설정
                    .build()
                    .parseSignedClaims(token); // 파싱 + 검증
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("만료된 JWT 토큰: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.warn("잘못된 JWT 형식: {}", e.getMessage());
        } catch (SecurityException e) {
            log.warn("잘못된 JWT 서명: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.warn("지원하지 않는 JWT: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("빈 JWT 토큰: {}", e.getMessage());
        }
        return false;
    }

    // ────────────────────────────────────────
    // [3] 토큰에서 정보 추출
    // ────────────────────────────────────────

    /**
     * 토큰에서 Claims(페이로드) 추출.
     */
    public Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 토큰에서 사용자 ID 추출 */
    public Long getUserId(String token) {
        return Long.parseLong(getClaims(token).getSubject());
    }

    /** 토큰에서 역할 추출 */
    public String getRole(String token) {
        return getClaims(token).get("role", String.class);
    }

    // ────────────────────────────────────────
    // [4] Authentication 객체 생성
    // ────────────────────────────────────────

    /**
     * JWT에서 Spring Security Authentication 객체 생성.
     * SecurityContext에 저장하여 @PreAuthorize 등에서 사용.
     *
     * <pre>
     * UsernamePasswordAuthenticationToken:
     * - principal:   인증된 사용자 정보 (UserDetails)
     * - credentials: 비밀번호 (JWT에서는 null — 이미 인증됨)
     * - authorities: 권한 목록 (ROLE_ADMIN, ROLE_USER 등)
     * </pre>
     */
    public Authentication getAuthentication(String token) {
        Claims claims = getClaims(token);
        String role = claims.get("role", String.class);

        // 권한 목록 생성
        List<SimpleGrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + role));

        // UserDetails 생성 (비밀번호는 빈 문자열 — JWT에서는 불필요)
        UserDetails principal = new User(
                claims.getSubject(), // username = userId
                "",                  // password (빈값)
                authorities          // 권한 목록
        );

        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }
}
