# WebSocket + STOMP 가이드

> 클라이언트-서버 간 양방향 실시간 통신. STOMP 프로토콜로 Pub/Sub 메시징 제공.

### 관련 소스 코드 (추천 순서)

| # | 파일 | 설명 |
|---|------|------|
| 1 | [WebSocketConfig.java](../src/main/java/kr/co/example/websocket/WebSocketConfig.java) | STOMP, SockJS 설정 — 엔드포인트와 브로커 구성을 먼저 이해 |
| 2 | [NotificationController.java](../src/main/java/kr/co/example/websocket/NotificationController.java) | @MessageMapping, 서버 Push — 메시지 송수신 핸들러 |
| 3 | [websocket-proxy.conf](../infra/nginx/websocket-proxy.conf) | Nginx WebSocket Upgrade, STOMP 프록시 — 배포 시 프록시 설정 |

---

## 목차

1. [HTTP vs WebSocket](#1-http-vs-websocket)
2. [전체 아키텍처](#2-전체-아키텍처)
3. [STOMP 목적지 규칙](#3-stomp-목적지-규칙)
4. [메시지 흐름 — 1단계: 연결 수립 (CONNECT)](#4-메시지-흐름--1단계-연결-수립-connect)
5. [메시지 흐름 — 2단계: 구독 (SUBSCRIBE)](#5-메시지-흐름--2단계-구독-subscribe)
6. [메시지 흐름 — 3단계: 메시지 전송 (3가지 패턴)](#6-메시지-흐름--3단계-메시지-전송-3가지-패턴)
7. [서버 전송 방법](#7-서버-전송-방법)
8. [Nginx WebSocket 프록시 연동](#8-nginx-websocket-프록시-연동)
9. [주의점](#9-주의점)

---

| 항목 | 내용 |
|------|------|
| 패키지 | `kr.co.example.websocket` |
| 파일 | `WebSocketConfig.java`, `NotificationController.java` |
| 인프라 | `infra/nginx/websocket-proxy.conf` |

## 1. HTTP vs WebSocket

| 항목 | HTTP | WebSocket |
|------|------|-----------|
| 통신 방향 | 단방향 (요청-응답) | 양방향 |
| 연결 | 매 요청마다 수립/해제 | 유지 (persistent) |
| 서버 push | 불가 | 가능 |

## 2. 전체 아키텍처

```
┌─────────────────┐        ┌──────────────────────────────────────────────────┐
│  Browser         │  WS    │                 Spring Boot                      │
│  (SockJS + STOMP)│◄══════►│                                                  │
│                  │        │  ┌──────────────────┐  ┌───────────────────────┐ │
│                  │        │  │ @MessageMapping   │  │    Simple Broker      │ │
│                  │        │  │ /app/* 핸들러     │─►│  /topic/* (1:N)       │ │
│                  │        │  └──────────────────┘  │  /queue/* (1:1)       │ │
│                  │        │                        └───────────────────────┘ │
│                  │        │  ┌──────────────────┐              │             │
│                  │        │  │ SimpMessaging     │──────────────┘             │
│                  │        │  │ Template          │  (서버 능동 전송)          │
│                  │        │  └──────────────────┘                            │
└─────────────────┘        └──────────────────────────────────────────────────┘
```

## 3. STOMP 목적지 규칙

| 접두사 | 방향 | 용도 | 라우팅 |
|--------|------|------|--------|
| `/app/*` | 클라이언트 → 서버 | 서버 핸들러 호출 | `@MessageMapping` 메서드로 라우팅 |
| `/topic/*` | 서버 → 클라이언트 (1:N) | 브로드캐스트 | Simple Broker가 구독자 전원에게 전송 |
| `/queue/*` | 서버 → 클라이언트 (1:1) | 개인 메시지 | Simple Broker가 특정 사용자에게 전송 |
| `/user/*` | 서버 → 클라이언트 (1:1) | 유저별 자동 라우팅 | Spring이 `/user/{userId}/queue/*`로 변환 |

## 4. 메시지 흐름 — 1단계: 연결 수립 (CONNECT)

```
클라이언트                         Nginx                       Spring Boot
    │                               │                               │
    │── GET /ws/notifications ─────►│                               │
    │   Upgrade: websocket          │── Upgrade 헤더 전달 ─────────►│
    │   Connection: Upgrade         │                               │
    │                               │◄── 101 Switching Protocols ───│
    │◄── 101 Switching Protocols ───│                               │
    │                               │                               │
    │═══════════ WebSocket 연결 성립 (TCP 터널) ═══════════════════►│
    │                               │                               │
    │── STOMP CONNECT ─────────────────────────────────────────────►│
    │   ┌──────────────────┐                                        │
    │   │ CONNECT           │                                        │
    │   │ accept-version:   │                                        │
    │   │   1.1,1.2         │                                        │
    │   │ heart-beat:       │                                        │
    │   │   10000,10000     │                                        │
    │   └──────────────────┘                                        │
    │                                                               │
    │◄──────────────────────────────────────── STOMP CONNECTED ─────│
    │   ┌──────────────────┐                                        │
    │   │ CONNECTED         │                                        │
    │   │ version:1.2       │                                        │
    │   │ heart-beat:       │                                        │
    │   │   10000,10000     │                                        │
    │   └──────────────────┘                                        │
```

- SockJS를 사용하므로 WebSocket 실패 시 XHR Streaming → XHR Polling 순으로 폴백
- Nginx는 `Upgrade`/`Connection` 헤더를 백엔드로 전달해야 핸드셰이크 성립

## 5. 메시지 흐름 — 2단계: 구독 (SUBSCRIBE)

```
클라이언트                                                   Simple Broker
    │                                                             │
    │── SUBSCRIBE ───────────────────────────────────────────────►│
    │   ┌──────────────────────────────┐                          │
    │   │ SUBSCRIBE                     │                          │
    │   │ id: sub-0                     │  ← 구독 ID              │
    │   │ destination:                  │                          │  구독자 목록에 등록
    │   │   /topic/notifications        │  ← 브로드캐스트 구독     │
    │   └──────────────────────────────┘                          │
    │                                                             │
    │── SUBSCRIBE ───────────────────────────────────────────────►│
    │   ┌──────────────────────────────┐                          │
    │   │ SUBSCRIBE                     │                          │
    │   │ id: sub-1                     │                          │
    │   │ destination:                  │                          │  유저별 큐에 등록
    │   │   /user/queue/alerts          │  ← 개인 메시지 구독      │
    │   └──────────────────────────────┘                          │
```

## 6. 메시지 흐름 — 3단계: 메시지 전송 (3가지 패턴)

### 패턴 A: 클라이언트 → 서버 → 브로드캐스트 (`@MessageMapping` + `@SendTo`)

`NotificationController.java` — `handleMessage()` 메서드

```
클라이언트 A       @MessageMapping("/message")      Simple Broker       클라이언트 B, C
    │                       │                            │                     │
    │── SEND ──────────────►│                            │                     │
    │  ┌──────────────────┐ │                            │                     │
    │  │ SEND              │ │                            │                     │
    │  │ destination:      │ │                            │                     │
    │  │   /app/message    │ │                            │                     │
    │  │                   │ │                            │                     │
    │  │ {"content":"Hi"}  │ │                            │                     │
    │  └──────────────────┘ │                            │                     │
    │                       │                            │                     │
    │              handleMessage() 실행                  │                     │
    │              content 추출, timestamp 추가           │                     │
    │              Map 반환                               │                     │
    │                       │                            │                     │
    │                       │── @SendTo ────────────────►│                     │
    │                       │   destination:             │                     │
    │                       │     /topic/messages         │                     │
    │                       │   {"content":"Hi",         │                     │
    │                       │    "timestamp":"...",      │                     │
    │                       │    "type":"BROADCAST"}     │                     │
    │                       │                            │                     │
    │◄── MESSAGE ───────────────────────────────────────│                     │
    │                                                    │── MESSAGE ─────────►│
    │   (구독자 전원에게 전송)                              │                     │
```

라우팅: `/app` 접두사 제거 → `@MessageMapping("/message")`에 매칭 → 반환값을 `@SendTo("/topic/messages")` 구독자에게 전송

### 패턴 B: 서버 → 전체 클라이언트 (`SimpMessagingTemplate` 브로드캐스트)

`NotificationController.java` — `sendNotification()` 메서드

```
이벤트 리스너 / 스케줄러        SimpMessagingTemplate        Simple Broker       클라이언트들
        │                             │                          │                   │
  주문 상태 변경 감지                  │                          │                   │
        │                             │                          │                   │
        │── sendNotification() ──────►│                          │                   │
        │   type: "ORDER_STATUS"      │                          │                   │
        │   message: "배송 시작"       │                          │                   │
        │                             │                          │                   │
        │                 convertAndSend(                        │                   │
        │                   "/topic/notifications",              │                   │
        │                   payload)                             │                   │
        │                             │─────────────────────────►│                   │
        │                             │                          │── MESSAGE ────────►│
        │                             │                          │  (구독자 전원)      │
```

`@MessageMapping` 없이도 `SimpMessagingTemplate`을 주입받아 어디서든 메시지 전송 가능

### 패턴 C: 서버 → 특정 사용자 (`convertAndSendToUser` 1:1)

`NotificationController.java` — `sendToUser()` 메서드

```
서비스 레이어               SimpMessagingTemplate            Spring 내부 변환        클라이언트
    │                             │                              │                     │
    │── sendToUser(               │                              │                     │
    │     "user123",              │                              │                     │
    │     "주문 완료")             │                              │                     │
    │                             │                              │                     │
    │           convertAndSendToUser(                            │                     │
    │             "user123",                                     │                     │
    │             "/queue/alerts",                               │                     │
    │             payload)                                       │                     │
    │                             │                              │                     │
    │                             │── destination 변환 ─────────►│                     │
    │                             │   /queue/alerts              │                     │
    │                             │     ↓                        │                     │
    │                             │   /user/user123/queue/alerts │                     │
    │                             │                              │                     │
    │                             │                              │── MESSAGE ──────────►│
    │                             │                              │  (user123만 수신)    │
    │                             │                              │                     │
    │                             │                              │  다른 사용자는       │
    │                             │                              │  수신하지 않음       │
```

클라이언트는 `/user/queue/alerts`를 구독하지만, Spring이 내부적으로 `/user/{userId}/queue/alerts`로 변환하여 해당 사용자에게만 라우팅. `userId`는 `Principal.getName()`과 일치해야 함.

## 7. 서버 전송 방법

| 메서드 | 대상 | 사용 위치 |
|--------|------|----------|
| `@SendTo("/topic/...")` | 구독자 전체 | `@MessageMapping` 핸들러 반환값 |
| `convertAndSend(dest, payload)` | 구독자 전체 | 어디서든 (스케줄러, 이벤트 리스너 등) |
| `convertAndSendToUser(user, dest, payload)` | 특정 사용자 | 어디서든 (1:1 알림) |

## 8. Nginx WebSocket 프록시 연동

```
클라이언트                        Nginx (:80)                   Spring Boot (:8080)
    │                               │                                │
    │── /ws/notifications ─────────►│                                │
    │                               │── proxy_pass ─────────────────►│
    │                               │   proxy_http_version 1.1       │
    │                               │   Upgrade: $http_upgrade       │
    │                               │   Connection: $connection_upgrade
    │                               │   proxy_read_timeout: 3600s    │
    │                               │   proxy_buffering: off         │
    │                               │                                │
    │◄══════════════════════════════│◄═══════════════════════════════│
    │        STOMP 프레임 양방향 전달 (Nginx는 TCP 터널링)            │
```

SockJS 사용 시 `/ws/` 하위 모든 경로(info, xhr, websocket 등)를 프록시해야 함

## 9. 주의점

| 항목 | 설명 |
|------|------|
| 연결 유지 비용 | WebSocket 연결마다 서버 메모리, 파일 디스크립터 소비 |
| 분산 환경 | 인메모리 브로커는 단일 서버만 지원 → Redis Pub/Sub 또는 RabbitMQ로 외부 브로커 전환 필요 |
| 재연결 처리 | 네트워크 불안정 시 SockJS가 자동 재연결하지만, STOMP 구독은 재설정 필요 |
| 메시지 유실 | `convertAndSendToUser()`는 사용자 미접속 시 메시지 유실 → 영속 저장소 별도 활용 |
| heartbeat | `proxy_read_timeout`은 STOMP heartbeat 간격보다 충분히 크게 설정 |
