# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

루트의 `./gradlew`로 모든 모듈을 빌드하거나, 모듈별로 개별 실행할 수 있다.

```bash
# 전체 빌드
./gradlew build

# 특정 모듈 빌드
./gradlew :main-server:build
./gradlew :queue-server:build
./gradlew :message-dispatcher:build

# 특정 모듈 실행
./gradlew :main-server:bootRun
./gradlew :queue-server:bootRun
./gradlew :message-dispatcher:bootRun

# 전체 테스트
./gradlew test

# 특정 모듈 테스트
./gradlew :main-server:test

# 단일 테스트 클래스 실행
./gradlew :main-server:test --tests "org.codeNbug.mainserver.domain.seat.controller.SeatControllerTest"
```

**서버 포트:**
- main-server: `9000`
- queue-server: `9001`
- message-dispatcher: `9002`

## 프로젝트 구조 (멀티 모듈)

```
service/
├── main-server/       # 핵심 비즈니스 (이벤트/좌석/결제/알림)
├── queue-server/      # 대기열 관리 (SSE + Redis ZSet)
├── message-dispatcher/# 대기열→입장큐 승격 스케줄러
├── user/              # 인증/인가 라이브러리 (bootJar 비활성)
└── common/            # JwtUtil, CookieUtil 등 공통 유틸
```

**의존 관계:**
- `main-server` → `user`, `common`
- `queue-server` → `user`, `common`
- `user` → `common`
- `message-dispatcher` → 독립 (Redis만 사용)

`user` 모듈은 라이브러리로만 동작 (`bootJar` disabled). `CustomUserDetails`, `JwtAuthenticationFilter`, `SecurityConfig` 등 인증 인프라를 main-server와 queue-server가 공유한다.

## 대기열 시스템 아키텍처

전체 대기열 흐름의 핵심이므로 반드시 이해해야 한다.

### Redis 키 구조

| 키 | 타입 | 용도 |
|----|------|------|
| `WAITING:{eventId}` | ZSet | 대기열 (score = 등록 순번 idx) |
| `WAITING_QUEUE_RECORD:{eventId}` | Hash | userId → `{userId, eventId, idx, instanceId}` JSON |
| `WAITING_USER_ID:{eventId}` | Hash | userId → idx (중복 입장 방지용) |
| `WAITING_QUEUE_IDX` | Hash | eventId별 자동 증가 idx |
| `ENTRY_QUEUE_COUNT` | Hash | eventId별 입장 가능 잔여 슬롯 수 |
| `ENTRY_QUEUE` | Stream | message-dispatcher → queue-server 메시지 채널 |
| `DISPATCH` | Stream | queue-server 인스턴스별 dispatch 채널 |
| `ENTRY_TOKEN` | Hash | userId → `"true"` (대기열 통과 인증 플래그) |

### 흐름

1. **입장 요청**: 사용자 → queue-server `WaitingQueueEntryService.entry()` → SSE 연결 수립, WAITING ZSet에 추가
2. **승격 (매 1초)**: message-dispatcher `EntryPromoteThread` → AIMD 알고리즘으로 `batchSize` 계산 → Lua 스크립트(`promote_all_waiting_for_event.lua`)로 원자적으로 WAITING ZSet → `ENTRY_QUEUE` Stream으로 이동 (틱당 최대 `batchSize`명)
3. **알림**: queue-server `EntryStreamMessageListener`가 자신의 인스턴스에 할당된 `DISPATCH:{instanceId}` 스트림 소비 → `ENTRY_TOKEN[userId] = "true"` Redis 저장 → SSE로 사용자에게 알림
4. **인증**: 사용자 → main-server 좌석/결제 API 호출 → `EntryTokenValidator.validate(userId)`가 `ENTRY_TOKEN[userId]` 존재 여부 확인
5. **해제**: 결제 완료 후 `RedisLockService.releaseAllEntryQueueLocks(userId)`가 `ENTRY_TOKEN[userId]` 삭제

### AIMD 유량 제어

`EntryPromoteThread`는 매 틱마다 AIMD(Additive Increase Multiplicative Decrease) 알고리즘으로 승격 인원을 동적으로 조절한다.

- **혼잡 감지 지표**: `HLEN(ENTRY_TOKEN)` — 현재 결제 진행 중인 사용자 수 (= main-server 동시 부하)
- **정상 상태** (`HLEN < ENTRY_TOKEN_TARGET`): `batchSize += AI_STEP` (틱당 +5, 천천히 증가)
- **혼잡 상태** (`HLEN >= ENTRY_TOKEN_TARGET`): `batchSize *= MD_FACTOR` (즉시 절반, 빠르게 감소)
- **파라미터**: `DEFAULT=50`, `MIN=5`, `MAX=200`, `TARGET=100` (부하 테스트로 결정)

Lua 스크립트는 `ARGV[2]`로 `batchSize`를 받아 `ZRANGE 0 batchSize-1`로 승격 대상을 제한한다.

### 멀티 인스턴스

각 queue-server 인스턴스는 `custom.instance-id`(예: `waiting-1`)로 구분된다. Lua 스크립트가 `instanceId`를 기반으로 `DISPATCH:{instanceId}` 스트림에 메시지를 라우팅하여 인스턴스 간 메시지 중복 수신을 방지한다.

## 결제 흐름

좌석 선택(Redis 분산 락) → 결제 사전 등록(`/payments/init`) → 토스페이먼츠 승인(`/payments/confirm`) → 티켓 생성 → `ENTRY_TOKEN` 삭제

좌석 선택 시 `seat:lock:{userId}:{eventId}:{seatId}` 키로 Redis `setIfAbsent` 락을 걸고, 5분 TTL 후 자동 해제된다.

### 결제 장애 복구 (Webhook 보조 경로)

`/payments/confirm`(능동 승인) 도중 장애가 발생해도 Toss Webhook(`DONE`)이 도착하면 자동 복구된다.

**복구 전제 조건**: `/payments/init` 시점에 선택된 좌석 ID를 `PurchaseSeat` 테이블에 영속화해 Redis 락 소실 이후에도 좌석 정보를 DB에서 조회할 수 있다.

| 장애 시나리오 | 감지 조건 | 처리 |
|---|---|---|
| EC1: 중복 Webhook 동시 수신 | `SELECT FOR UPDATE` 비관적 락 | 두 번째 요청은 락 대기 후 멱등성 체크로 즉시 return |
| EC2: 티켓 생성 후 상태 미갱신 | `tickets` 존재 + `status != DONE` | `setPaymentStatus(DONE)` 복구 |
| EC3: 좌석이 타인에게 이미 점유 | `PurchaseSeat` 존재 + `seat.available=false` | Toss 자동 환불 + 사용자 알림 |
| EC4: 티켓·좌석 모두 미생성 | `PurchaseSeat` 존재 + 좌석 사용 가능 | 좌석 예약 + 티켓 재생성 후 DONE |

`handleWebhook()`은 `@Transactional` + `findByPaymentUuidWithLock()`(`SELECT FOR UPDATE`)으로 동시성을 보장한다.

## 설정 파일

민감한 값은 `application-secret.yml`에 저장하며 gitignore 처리되어 있다. `application.yml`에서 `ON_SECRET`으로 표시된 값들이 해당된다. (DB 계정, JWT secret, Toss API 키, OAuth 클라이언트 ID/Secret)

## 테스트

- **단위/슬라이스 테스트**: MockMvc + Mockito 사용
- **통합 테스트**: Testcontainers (MySQL + Redis) 사용 — **Docker 실행 필요**
- 통합 테스트 프로파일(`application-test.yml`)은 `ddl-auto: create`로 테이블을 새로 생성한다.

## QueryDSL

`user`와 `main-server`에서 QueryDSL을 사용한다. Q클래스는 `build/generated/querydsl`에 APT로 생성되며, `./gradlew compileJava` 실행 후 IDE에서 인식된다.
