package kr.co.example.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * Spring Session + Redis 설정 — 분산 환경에서 세션 공유.
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  Spring Session이란?                                                  │
 * ├──────────────────────────────────────────────────────────────────────┤
 * │  HttpSession을 외부 저장소(Redis, JDBC 등)에 저장하는 프레임워크.      │
 * │  서블릿 컨테이너(Tomcat)의 내장 세션 대신 Spring이 세션을 관리.         │
 * │                                                                      │
 * │  왜 필요한가?                                                         │
 * │  - 서버가 여러 대일 때 (로드밸런서) → 어느 서버든 같은 세션 접근       │
 * │  - 서버 재시작 시 세션 유지                                            │
 * │  - MSA에서 서비스 간 세션 공유                                         │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  세션 저장소 비교                                                     │
 * ├──────────────────┬───────────────────────────────────────────────────┤
 * │  Tomcat 내장     │ 단일 서버 메모리에 저장                            │
 * │                  │ 서버 재시작 시 세션 소멸, 서버 간 공유 불가          │
 * ├──────────────────┼───────────────────────────────────────────────────┤
 * │  Redis           │ 외부 메모리 DB에 저장 (가장 많이 사용)              │
 * │                  │ 빠른 읽기/쓰기, TTL(만료) 자동 관리                │
 * │                  │ 서버 무관하게 세션 공유 가능                         │
 * ├──────────────────┼───────────────────────────────────────────────────┤
 * │  JDBC            │ RDB에 저장 (MySQL, PostgreSQL 등)                  │
 * │                  │ Redis보다 느리지만 별도 인프라 불필요               │
 * └──────────────────┴───────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  Spring Session + Redis 동작 흐름                                     │
 * ├──────────────────────────────────────────────────────────────────────┤
 * │  1. 로그인 성공 → 세션 생성 → Redis에 저장                             │
 * │     Redis Key: "spring:session:sessions:{sessionId}"                  │
 * │  2. 클라이언트: JSESSIONID 쿠키로 세션 ID 저장                         │
 * │  3. API 요청: 쿠키에 JSESSIONID 포함                                  │
 * │  4. 서버: SessionRepositoryFilter가 Redis에서 세션 조회                │
 * │  5. 세션 유효 → SecurityContext 복원 → 인증된 요청으로 처리             │
 * │  6. 세션 만료 → Redis TTL 도달 → 자동 삭제                             │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * 설정 순서:
 * 1. 의존성: spring-boot-starter-data-redis + spring-session-data-redis
 *    (spring-boot-starter-data-redis에 spring-session-data-redis 포함)
 * 2. application.yml:
 *    spring.session.store-type: redis
 *    spring.session.redis.namespace: spring:session
 *    server.servlet.session.timeout: 1800  # 30분
 * 3. @EnableRedisHttpSession (이 설정 클래스)
 * 4. SecurityFilterChain에서 SessionCreationPolicy.IF_REQUIRED 설정
 *
 * concert-msa-project에서 실제 사용:
 * - SessionConfig.java → @EnableRedisHttpSession
 * - SecurityConfig.java → HttpSessionSecurityContextRepository 사용
 * </pre>
 */
@Configuration
// @EnableRedisHttpSession 활성화 시 Redis에 세션 저장
// maxInactiveIntervalInSeconds: 세션 만료 시간 (초) — 기본 1800초 (30분)
// redisNamespace: Redis 키 접두사 — "spring:session" (기본값)
// @EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    /**
     * 세션 직렬화 방식 — JSON 형태로 Redis에 저장.
     *
     * <pre>
     * 직렬화 방식 비교:
     * ┌──────────────────────┬──────────────────────────────────────┐
     * │  JdkSerializationRedis│ Java 기본 직렬화 (바이너리)          │
     * │  Serializer (기본값)  │ 사람이 읽기 어려움, 클래스 변경 시   │
     * │                      │ 역직렬화 실패 위험                    │
     * ├──────────────────────┼──────────────────────────────────────┤
     * │  GenericJackson2Json │ JSON 형태로 저장                      │
     * │  RedisSerializer     │ Redis CLI에서 읽기 가능              │
     * │                      │ 클래스 변경에 더 유연                 │
     * │                      │ 권장 방식                             │
     * └──────────────────────┴──────────────────────────────────────┘
     * </pre>
     */
    @Bean
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        return new GenericJackson2JsonRedisSerializer();
    }

    /**
     * application.yml 세션 관련 설정 예시.
     *
     * <pre>
     * spring:
     *   session:
     *     store-type: redis          # 세션 저장소: redis / jdbc / none
     *     redis:
     *       namespace: spring:session # Redis 키 접두사
     *
     * server:
     *   servlet:
     *     session:
     *       timeout: 1800            # 세션 만료 시간 (초) — 30분
     *       cookie:
     *         name: JSESSIONID       # 세션 쿠키명
     *         http-only: true        # JavaScript에서 접근 불가 (XSS 방어)
     *         secure: true           # HTTPS에서만 쿠키 전송 (운영 환경)
     *         same-site: lax         # CSRF 방어 (Strict/Lax/None)
     * </pre>
     */

    /**
     * 세션 기반 인증 vs JWT 토큰 기반 인증 — 선택 기준.
     *
     * <pre>
     * ┌──────────────────────────────────────────────────────────────────┐
     * │  세션 기반 선택 시                                                │
     * ├──────────────────────────────────────────────────────────────────┤
     * │  - 서버 렌더링(SSR) 웹 애플리케이션                               │
     * │  - 로그아웃 시 즉시 세션 무효화가 중요한 경우                      │
     * │  - Redis 등 세션 저장소 인프라가 이미 있는 경우                    │
     * │  - 보안 요구사항이 높은 경우 (은행, 관공서)                       │
     * │                                                                  │
     * │  JWT 토큰 기반 선택 시                                            │
     * ├──────────────────────────────────────────────────────────────────┤
     * │  - SPA(React, Vue) + REST API 구조                               │
     * │  - 모바일 앱 백엔드                                               │
     * │  - MSA에서 서비스 간 인증 전파                                    │
     * │  - 서버 Stateless 유지가 중요한 경우                              │
     * │  - Redis 없이 간단하게 구성하고 싶은 경우                          │
     * └──────────────────────────────────────────────────────────────────┘
     * </pre>
     */
}
