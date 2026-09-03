# Nginx 설정 가이드

> Spring Boot 앱 앞단의 Nginx 인프라 설정 레퍼런스.

설정 파일: `infra/nginx/`

---

## 목차

1. [Nginx 역할](#1-nginx-역할)
2. [기본 설정 (리버스 프록시, 정적 파일, Gzip)](#2-기본-설정-리버스-프록시-정적-파일-gzip)
3. [로드밸런싱 (upstream, 전략 5종)](#3-로드밸런싱-upstream-전략-5종)
4. [SSL/TLS 종료](#4-ssltls-종료)
5. [보안 헤더, CORS, Rate Limiting](#5-보안-헤더-cors-rate-limiting)
6. [WebSocket 프록시](#6-websocket-프록시)

---

| 항목 | 내용 |
|------|------|
| 디렉토리 | `infra/nginx/` |
| 파일 | `nginx-reference.conf`, `upstream-loadbalancing.conf`, `ssl-termination.conf`, `security-headers.conf`, `websocket-proxy.conf` |

Spring Boot 앱 앞단의 Nginx 인프라 설정 레퍼런스. 리버스 프록시, 로드밸런싱, SSL 종료, 보안 헤더, WebSocket 프록시를 다룬다.

## 1. Nginx 역할

| 역할 | 설명 |
|------|------|
| 리버스 프록시 | 클라이언트 → Nginx → Spring Boot (내부 네트워크) |
| 로드밸런싱 | 여러 Spring Boot 인스턴스로 트래픽 분산 |
| SSL 종료 | HTTPS 암호화를 Nginx에서 해제, 내부는 HTTP |
| 정적 파일 서빙 | JS/CSS/이미지를 Nginx가 직접 서빙 (앱 부하 감소) |
| 보안 1차 방어 | Rate Limiting, IP 차단, 보안 헤더 |

## 2. 기본 설정 (리버스 프록시, 정적 파일, Gzip)

| 항목 | 내용 |
|------|------|
| 파일 | `nginx-reference.conf` |

Nginx 아키텍처(Event-driven, master/worker), 블록 구조, 리버스 프록시, 정적 파일, Gzip, 타임아웃.

### Nginx 아키텍처

```
master process (설정 관리, worker fork)
  ├─ worker process 1  ← Event Loop (epoll)
  ├─ worker process 2  ← 수천 커넥션 동시 처리
  └─ worker process N  ← Non-blocking I/O
```

### Nginx vs Apache vs 내장 Tomcat

| 항목 | Nginx | Apache | 내장 Tomcat |
|------|-------|--------|------------|
| 아키텍처 | Event-driven | Process/Thread | Thread Pool |
| 동시 접속 | 수만 연결 | 수천 연결 | 수백 연결 |
| 정적 파일 | 매우 빠름 | 보통 | 느림 |
| 리버스 프록시 | 네이티브 | mod_proxy | 미지원 |
| 설정 리로드 | 무중단 reload | graceful restart | 앱 재시작 필요 |

### 리버스 프록시 필수 헤더

| 헤더 | 용도 |
|------|------|
| `Host` | 원본 호스트명 유지 |
| `X-Real-IP` | 클라이언트 실제 IP |
| `X-Forwarded-For` | 프록시 체인 IP 목록 |
| `X-Forwarded-Proto` | 원본 프로토콜 (http/https) |
| `X-Request-ID` | 요청 추적 ID (MDC 연동) |

## 3. 로드밸런싱 (upstream, 전략 5종)

| 항목 | 내용 |
|------|------|
| 파일 | `upstream-loadbalancing.conf` |

upstream 블록, 로드밸런싱 전략 5종, 헬스체크, Blue-Green/Canary 배포 패턴.

### 로드밸런싱 전략 비교

| 전략 | 분배 방식 | 적합한 경우 |
|------|----------|------------|
| round-robin | 순차적 분배 (기본) | 서버 스펙 동일 |
| least_conn | 활성 연결 수 기반 | 처리 시간 편차 클 때 |
| ip_hash | 클라이언트 IP 해시 | 세션 기반 인증 (Sticky) |
| hash | 지정 키 해시 (URI 등) | 캐시 서버, 파티셔닝 |
| random | 랜덤 (Two Choices) | 대규모 분산 환경 |

### 패시브 헬스체크

```
요청 → app 실패 (502/503/timeout)
  → fail_count++ (max_fails까지 카운트)
  → max_fails 도달 → fail_timeout 동안 비활성
  → fail_timeout 경과 → 재시도 → 성공 시 복구
```

### Blue-Green / Canary 배포

| 패턴 | 설명 |
|------|------|
| Blue-Green | upstream 전체 전환 (100% → 새 버전), nginx reload로 무중단 |
| Canary | weight로 일부 트래픽만 새 버전 (95:5 → 점진적 증가) |

## 4. SSL/TLS 종료

| 항목 | 내용 |
|------|------|
| 파일 | `ssl-termination.conf` |

SSL 종료 개념, 인증서(Let's Encrypt), TLS 1.2/1.3, 세션 캐시, OCSP 스테이플링, HSTS.

### SSL 종료 흐름

```
클라이언트 ─(HTTPS)─→ Nginx (443) ─(HTTP)─→ Spring Boot (8080)
                       ↑ SSL 종료              ↑ 암호화 부담 없음
```

### TLS 버전 비교

| 버전 | 핸드셰이크 | 보안 | 상태 |
|------|-----------|------|------|
| TLS 1.0/1.1 | 2 RTT | 취약 | 사용 금지 |
| TLS 1.2 | 2 RTT | 안전 | 호환성 유지 |
| TLS 1.3 | 1 RTT | 매우 안전 | 권장 |

### HSTS 동작

```
1회차: http://example.com → 301 → https://example.com
       응답: Strict-Transport-Security: max-age=63072000
2회차~: 브라우저가 내부적으로 https로 변환 (네트워크 요청 전)
```

## 5. 보안 헤더, CORS, Rate Limiting

| 항목 | 내용 |
|------|------|
| 파일 | `security-headers.conf` |

보안 헤더, CSP, CORS(Nginx vs Spring Security 비교), Rate Limiting(Nginx vs Redis Lua), IP 제한.

### 보안 헤더 요약

| 헤더 | 방어 대상 |
|------|----------|
| `X-Content-Type-Options` | MIME 스니핑 |
| `X-Frame-Options` | 클릭재킹 |
| `Content-Security-Policy` | XSS, 데이터 삽입 |
| `Referrer-Policy` | Referer 정보 유출 |
| `Strict-Transport-Security` | HTTP 다운그레이드 |

### CORS: Nginx vs Spring Security

| 항목 | Nginx | Spring Security |
|------|-------|----------------|
| 적용 범위 | 모든 백엔드 공통 | Spring 앱 한정 |
| Preflight | Nginx에서 즉시 응답 | Filter 통과 필요 |
| 유연성 | 정적 설정 | 동적 (DB 기반 가능) |
| 주의 | 중복 설정 시 헤더 2번 추가 → 하나만 선택 |

### Rate Limiting: Nginx vs Redis Lua

| 항목 | Nginx limit_req | Redis Lua Script |
|------|----------------|-----------------|
| 동작 범위 | 단일 Nginx 인스턴스 | 전체 서버 (Redis 공유) |
| 알고리즘 | Leaky Bucket | 커스텀 (Sliding Window) |
| 키 기준 | IP, URI | 사용자 ID, API Key |
| 적합한 경우 | DDoS 기본 방어 | 사용자별 API 할당량 |

## 6. WebSocket 프록시

| 항목 | 내용 |
|------|------|
| 파일 | `websocket-proxy.conf` |

WebSocket Upgrade 흐름, 필수 헤더, STOMP 프록시, SockJS 폴백, 타임아웃.

### WebSocket 프록시 필수 설정

| 설정 | 값 | 설명 |
|------|----|------|
| `proxy_http_version` | 1.1 | WebSocket은 HTTP/1.1 필수 |
| `Upgrade` | $http_upgrade | 프로토콜 전환 요청 전달 |
| `Connection` | $connection_upgrade | Hop-by-hop 헤더 직접 설정 |
| `proxy_read_timeout` | 3600s | 유휴 연결 유지 (heartbeat보다 크게) |
| `proxy_buffering` | off | 메시지 지연 방지 |

### SockJS 폴백 순서

| 순서 | 전송 방식 | 설명 |
|------|----------|------|
| 1 | WebSocket | 가장 빠름, 양방향 |
| 2 | XHR Streaming | 서버→클라이언트 스트리밍 |
| 3 | XHR Polling | 주기적 HTTP 요청 (최후 수단) |

### 프로젝트 연동

기존 `WebSocketConfig.java`의 `/ws/notifications` 엔드포인트를 Nginx에서 프록시하는 예제 포함.
SockJS 사용 시 `/ws/` 하위 모든 경로(info, xhr, websocket 등)를 프록시해야 한다.
