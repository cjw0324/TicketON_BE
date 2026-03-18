# 티켓 온(Ticket-On)


## Project Overview

## 1. 프로젝트 명

**티켓온 (Ticket-On)** - 고성능 대용량 트래픽 처리 티켓 예매 시스템

<br/>

## 2. 프로젝트 소개

티켓온은 **대용량 트래픽**과 **동시성 문제**를 해결하기 위해 설계된 분산 티켓 예매 시스템입니다.

### 🎯 핵심 가치

- **높은 동시성 처리**: Redis 분산 락을 활용한 좌석 예매 동시성 제어
- **대기열 시스템**: Queue 서버를 통한 트래픽 분산 및 공정한 예매 기회 제공
- **실시간 알림**: SSE를 활용한 실시간 예매 진행 상황 및 알림 전송
- **마이크로서비스 아키텍처**: 서비스별 독립적인 확장과 배포 가능
- **안정적인 결제**: 토스페이먼츠 연동 및 안전한 결제 시스템 구현

### 🏗️ 시스템 구성

- **Main Server**: 핵심 비즈니스 로직 (사용자, 이벤트, 좌석, 결제 관리)
- **Queue Server**: 대기열 시스템으로 트래픽 제어 및 순서 보장
- **Message Dispatcher**: 서비스 간 메시지 전달 및 이벤트 처리
- **User Service**: 사용자 인증 및 권한 관리
- **Common**: 공통 유틸리티 및 설정

<br/>

## 3. 주요 기능

### 🎫 **티켓 예매 시스템**

- **지정석/미지정석** 예매 지원
- **실시간 좌석 상태** 확인 및 Redis 캐싱으로 빠른 응답
- **분산 락 기반** 동시성 제어로 중복 예매 방지
- **최대 4매** 동시 예매 제한

### ⏰ **대기열 시스템**

- **Redis ZSet 기반** 공정한 대기 순서 관리
- **SSE 실시간** 대기 순서 및 진입 알림
- **Redis 기반** 예매 권한 검증 (대기열 통과 여부를 Redis 플래그로 확인)
- **AIMD 유량 제어** — main-server 동시 부하(`HLEN ENTRY_TOKEN`)를 실시간으로 감지해 승격 속도를 자동 조절 (TCP 혼잡 제어와 동일 원리)

### 💳 **결제 시스템**

- **토스페이먼츠 연동**으로 안전한 결제 처리
- **결제 전 좌석 임시 예약** (5분 TTL)
- **결제 실패 시 자동 좌석 해제**
- **Webhook 기반 장애 복구**: `/payments/confirm` 도중 장애 발생 시 Toss Webhook이 자동 복구 경로로 동작 — 비관적 락 기반 멱등성 보장, 티켓 재생성 또는 자동 환불 처리

### 🔔 **실시간 알림**

- **SSE 기반** 실시간 알림 전송
- **예매/결제/취소** 상태별 알림
- **미읽은 알림** 관리 및 재전송 지원

### 👤 **사용자 관리**

- **JWT 기반** 인증/인가 시스템
- **OAuth2** 소셜 로그인 지원
- **역할별 권한** 관리 (ADMIN, MANAGER, USER)

[✨ 기능 명세서 ✨](docs/기능_명세서.md)<br/>
[✅ Redis가 적용된 기능의 Process ✅]()

<br/>
<br/>

# 🔧 트러블슈팅

## 1. AIMD 유량 제어 — 대기열이 트래픽 스파이크를 막지 못하는 문제

### 문제 인식

대기열을 도입한 본래 목적은 **main-server의 트래픽 스파이크 방지**다. 그러나 기존 설계에서 `ENTRY_QUEUE_COUNT`는 `seatCount / 100`으로 고정 초기화되었고, Lua 스크립트는 매 틱(1초)마다 제한 없이 전원을 승격시켰다.

```lua
-- 기존: 전체 대기열 조회 (제한 없음)
local waitingItems = redis.call("ZRANGE", KEYS[3], 0, -1)
```

**결과**: 첫 번째 틱에서 최대 10,000명이 동시에 main-server로 진입 가능. 총량은 제한했지만 유입 **속도(Rate)** 는 전혀 제어하지 않았다.

| 제어 항목 | 기존 설계 | 변경 후 |
|---|---|---|
| 총 입장 인원 | O | O |
| 초당 유입 속도 | X | O |
| main-server 부하 반영 | X | O (AIMD) |

### 설계 방향 탐색

| 방법 | 방식 | 한계 |
|---|---|---|
| A. 고정 배치 크기 | 설정값으로 `batchSize` 고정 | 운영 환경 변수(GC, 슬로우 쿼리) 미반영 |
| B. Open-loop (main-server Push) | main-server가 CPU/P99 기반으로 Redis에 `batchSize` 기록 | 폴링 지연(5초), 진동 현상, 서비스 결합도 증가 |
| **C. AIMD Closed-loop** | message-dispatcher가 결과를 스스로 관찰해 자율 조절 | — |

**방법 C 채택**: TCP 혼잡 제어(Additive Increase Multiplicative Decrease)와 동일한 원리 적용.

### 혼잡 감지 지표: `HLEN(ENTRY_TOKEN)`

`ENTRY_TOKEN[userId] = "true"` 는 대기열을 통과한 후 결제를 완료하지 않은 사용자의 집합이다.

```
대기열 통과 → ENTRY_TOKEN[userId] = "true"  (queue-server)
결제 완료   → ENTRY_TOKEN[userId] 삭제       (main-server)
```

`HLEN(ENTRY_TOKEN)` = **현재 main-server 결제 플로우의 동시 처리 사용자 수** — Redis O(1) 조회, HTTP 없음, 실시간 반영.

### AIMD 알고리즘

```
매 1초 (EntryPromoteThread)
  ↓
HLEN(ENTRY_TOKEN) 조회
  ↓
혼잡(count >= 100)?
  YES → batchSize = max(batchSize × 0.5, 5)    Multiplicative Decrease
  NO  → batchSize = min(batchSize + 5,   200)   Additive Increase
  ↓
Lua: ZRANGE 0 batchSize-1  (이번 틱 최대 batchSize명만 승격)
```

**수렴 시뮬레이션** (seatCount=10,000):
```
T=0: batchSize=50,  entryToken=10  → +5 → 55
T=3: batchSize=65,  entryToken=115 → 혼잡! ×0.5 → 32
T=4: batchSize=32,  entryToken=95  → +5 → 37  (회복 시작)
...  main-server 처리 한계 부근에서 수렴
```
기존이었다면 T=0에 전체 10,000명이 한 번에 유입됐을 것이다.

### 함께 수정된 버그: `ENTRY_QUEUE_COUNT` 초기화 경합 조건

`hasKey` + `put` 두 단계 초기화는 동시 진입 시 이미 승격된 카운트를 덮어쓰는 버그가 있었다.

```
T=0: User A, B 동시 진입 → 둘 다 hasKey=false 통과
T=2: 500명 승격 → count=500
T=3: User B → put(1000) → count=1000  ← 500명분 카운트 복원 버그!
```

**해결**:
1. `EventRegisterService`: 이벤트 등록 시 Redis에 단일 primary 초기화
2. `WaitingQueueEntryService`: `put` → `putIfAbsent` (Redis `HSETNX`, 원자적)

---

## 2. 결제 Webhook 장애 복구 — 돈은 빠져나갔는데 티켓이 없는 문제

### 문제 인식

`/payments/confirm` 처리 흐름에서 Toss 승인(1번) 이후 단계에서 장애(서버 재시작, GC 중단, DB 슬로우 쿼리)가 발생하면:

```
1. Toss 승인 HTTP 요청  ← 성공
2. 티켓 생성            ← 장애 발생 가능
3. paymentStatus = DONE ← 장애 발생 가능
```

- **사용자 관점**: 돈은 빠져나갔는데 티켓이 없다
- **서버 관점**: `paymentStatus = IN_PROGRESS` + `tickets` 없는 레코드만 남는다

### 설계 방향: 능동 승인 Primary + Webhook Fallback

| 항목 | 방식 A (채택) | 방식 B |
|---|---|---|
| 결제 승인 주체 | main-server → Toss HTTP 요청 | Toss → Webhook만 의존 |
| 응답 지연 | 없음 (동기) | Webhook 도달 수 초 지연 |
| 장애 복구 | Webhook이 자동 fallback | 단일 장애점 |

### 근본 문제: 좌석 선택 정보의 휘발성

Webhook은 `paymentKey`만 알고 있다. 어떤 좌석을 예매하려 했는지는 Redis 락에만 존재하는데, 장애 후 TTL(5분)이 만료되면 Webhook이 도착해도 **티켓을 재생성할 방법이 없다**.

**해결**: `/payments/init` 시점에 선택된 좌석 ID를 `PurchaseSeat` 테이블에 영속화.

```
/payments/init
  → Purchase 저장 (status = IN_PROGRESS)
  → PurchaseSeat 저장 (seatId, eventId, purchaseId)  ← 신규
```

### 엣지 케이스 4가지 방어

| # | 상황 | 감지 조건 | 처리 |
|---|---|---|---|
| EC1 | 중복 Webhook 동시 수신 | — | `SELECT FOR UPDATE` 비관적 락으로 직렬화 → 멱등성 체크 |
| EC2 | 티켓 생성 후 상태 미갱신 | tickets 존재 + status≠DONE | `setPaymentStatus(DONE)` 복구 |
| EC3 | 좌석이 타인에게 점유됨 | PurchaseSeat 존재 + seat.available=false | Toss 자동 환불 + 사용자 알림 |
| EC4 | 티켓·좌석 모두 미생성 | PurchaseSeat 존재 + 좌석 사용 가능 | 좌석 재예약 + 티켓 재생성 → DONE |

### 전체 복구 흐름

```
Webhook DONE 수신
  ↓
SELECT FOR UPDATE (비관적 락)
  ↓
status == DONE?  →  YES  →  return (멱등성)
  ↓ NO
tickets 존재?    →  YES  →  EC2: 상태 DONE 복구 → 알림
  ↓ NO
PurchaseSeat 존재?  →  NO   →  autoRefund() + 에러 로그
  ↓ YES
좌석 점유됨?     →  YES  →  EC3: autoRefund() + 알림
  ↓ NO
                          →  EC4: 티켓 재생성 → DONE → 알림
```

| 항목 | 변경 전 | 변경 후 |
|---|---|---|
| Webhook 역할 | 상태 모니터링 | 장애 복구 보조 경로 |
| 좌석 정보 영속화 | 없음 (Redis 락) | `PurchaseSeat` DB 저장 |
| 중복 Webhook 방어 | 없음 | 비관적 락 + 멱등성 체크 |
| 티켓 재생성 | 불가 | `PurchaseSeat` 기반 자동 재생성 |
| 좌석 선점 충돌 | 미처리 | 자동 환불 + 사용자 알림 |
| 트랜잭션 | 없음 | `@Transactional` 전체 보장 |
 
<br/>
<br/>


# 🛠️ Tech

## 기술 스택
### 💻 언어
<div align="left">
  <img src="https://img.shields.io/badge/Java-007396?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java" />
  <img src="https://img.shields.io/badge/TypeScript-3178C6?style=for-the-badge&logo=typescript&logoColor=white" alt="TypeScript" />
</div>

<br/>

### ⚙️ 프레임워크 및 라이브러리
<div align="left">
  <img src="https://img.shields.io/badge/Spring_Boot-6DB33F?style=for-the-badge&logo=springboot&logoColor=white" alt="Spring Boot" />
  <img src="https://img.shields.io/badge/Next.js-000000?style=for-the-badge&logo=nextdotjs&logoColor=white" alt="Next.js" />
  <img src="https://img.shields.io/badge/Shadcn_UI-111827?style=for-the-badge&logoColor=white" alt="ShadCN UI" />
</div>

<br/>

### 🗄️ 데이터베이스
<div align="left">
  <img src="https://img.shields.io/badge/MySQL-4479A1?style=for-the-badge&logo=mysql&logoColor=white" alt="MySQL" />
  <img src="https://img.shields.io/badge/H2-ACD3C7?style=for-the-badge&logo=h2&logoColor=white" alt="H2" />
  <img src="https://img.shields.io/badge/Redis-DC382D?style=for-the-badge&logo=redis&logoColor=white" alt="Redis" />
</div>

<br/>

### 🛠️ IDE 및 개발 도구
<div align="left">
  <img src="https://img.shields.io/badge/IntelliJ_IDEA-000000?style=for-the-badge&logo=intellijidea&logoColor=white" alt="IntelliJ IDEA" />
  <img src="https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white" alt="Docker" />
  <img src="https://img.shields.io/badge/Amazon_S3-569A31?style=for-the-badge&logo=amazon-s3&logoColor=white" alt="Amazon S3" />
  <img src="https://img.shields.io/badge/AWS_ECS-232F3E?style=for-the-badge&logo=amazonaws&logoColor=white" alt="AWS ECS" />
  <img src="https://img.shields.io/badge/Vercel-000000?style=for-the-badge&logo=vercel&logoColor=white" alt="Vercel" />
</div>

<br/>

### 🌐 통신 및 네트워크
<div align="left">
  <img src="https://img.shields.io/badge/WebSocket-000000?style=for-the-badge&logo=websocket&logoColor=white" alt="WebSocket" />
  <img src="https://img.shields.io/badge/STOMP-82B541?style=for-the-badge&logoColor=white" alt="STOMP" />
</div>

<br/>

### 🔗 버전 관리 및 협업 도구
<div align="left">
  <img src="https://img.shields.io/badge/GitHub-181717?style=for-the-badge&logo=github&logoColor=white" alt="GitHub" />
  <img src="https://img.shields.io/badge/Notion-000000?style=for-the-badge&logo=notion&logoColor=white" alt="Notion" />
  <img src="https://img.shields.io/badge/Discord-5865F2?style=for-the-badge&logo=discord&logoColor=white" alt="Discord" />
  <img src="https://img.shields.io/badge/Slack-4A154B?style=for-the-badge&logo=slack&logoColor=white" alt="Slack" />
  <img src="https://img.shields.io/badge/ZEP-FF9E0F?style=for-the-badge&logoColor=white" alt="ZEP" />
</div>



## ERD

<img src="docs/티켓팅 ERD v2.png" alt="ERD"/>

## System Architecture

<img src="docs/최종프로젝트구조도.svg" />


## Sequence Diagram



## 브랜치 전략
[🔧 GitHub Flow Convention 🔧]()

## API 명세서

[🔖 API 명세서🔖 ](docs/API 명세서.md)
<br/>
<br/>

## 컨벤션

[📌 Code Convention 📌]()
