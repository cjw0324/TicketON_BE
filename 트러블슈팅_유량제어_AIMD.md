# 유량 제어 — AIMD 도입 트러블슈팅 기록

## 1. 문제 인식: 대기열이 트래픽 스파이크를 막지 못한다

### 배경

대기열 시스템을 도입한 본래 목적은 **main-server의 트래픽 스파이크 방지**다. 수만 명이 동시에 예매를 시도할 때 main-server가 직접 요청을 받는 대신, queue-server가 트래픽을 흡수하고 main-server가 처리할 수 있는 속도에 맞춰 사용자를 순차적으로 진입시키는 것이 목표였다.

### 기존의 근본적 문제

> "대기열을 만드는 이유는 결국 main-server의 트래픽 스파이크를 방지하기 위함인데, 그러면 입장 가능한 크기를 main-server의 처리 가능 상황에 따라 가변적으로 가져가야 원래 목적에 부합하는 게 아닌가?

### 기존 설계의 구조적 결함

기존 코드에서 `ENTRY_QUEUE_COUNT`는 이벤트의 총 좌석 수``(seatCount) / 100`` 로 초기화되었다.

```java
// WaitingQueueEntryService.enter()
if (!simpleRedisTemplate.opsForHash().hasKey(ENTRY_QUEUE_COUNT_KEY_NAME, eventId.toString())) {
    simpleRedisTemplate.opsForHash()
        .put(ENTRY_QUEUE_COUNT_KEY_NAME, eventId.toString(), seatCount / 100);  // 예: 10,000
}
```

Lua 스크립트(`promote_all_waiting_for_event.lua`)는 매 틱(1초)마다 대기열 전체를 순회하며 `ENTRY_QUEUE_COUNT`가 소진될 때까지 모든 사용자를 승격시켰다.

```lua
-- 기존: ZRANGE 0 -1 (제한 없이 전체 조회)
local waitingItems = redis.call("ZRANGE", KEYS[3], 0, -1)
```

**결과적으로 첫 번째 틱에서 최대 10,000명이 동시에 main-server로 진입 가능했다.** 대기열이 총량은 제한했지만, 유입 속도(rate)는 전혀 제어하지 않았다.

| 제어 항목 | 기존 설계 | 이상적 설계 |
|---|---|---|
| 총 입장 인원 (총량) | O — `ENTRY_QUEUE_COUNT = seatCount` | O |
| 초당 유입 속도 (Rate) | X — 제한 없음 | O |
| main-server 부하 반영 (Adaptive) | X | O |

---

## 2. 설계 방향 탐색

### 방법 A. 고정 배치 크기

매 틱마다 승격할 인원 수를 설정 파일의 고정값으로 제한한다.

```lua
local batchSize = tonumber(ARGV[2])  -- 예: 50
local waitingItems = redis.call("ZRANGE", KEYS[3], 0, batchSize - 1)
```

**한계**: 부하 테스트로 결정한 값이 실제 운영 환경(GC 일시 정지, DB 슬로우 쿼리, 콜드 스타트 등)에서도 항상 안전하다는 보장이 없다. main-server의 실시간 상태를 반영하지 못한다.

### 방법 B. main-server가 Redis에 Publish (Open-loop 제어)

main-server가 자신의 CPU, P99 응답시간 등을 수집해 Redis에 `batchSize`를 직접 기록한다. message-dispatcher는 이를 폴링해서 사용한다.

```
main-server (@Scheduled, 5초마다)
  → CPU / P99 기반으로 batchSize 계산
  → HSET "MAIN_SERVER_CAPACITY" "batchSize" 50

message-dispatcher (매 1초)
  → HGET "MAIN_SERVER_CAPACITY" "batchSize"
  → Lua에 전달
```

**한계**: 폴링 주기(5초) 동안 main-server가 과부하 상태로 전환되어도 batchSize가 갱신되지 않아 수백 명이 추가로 진입할 수 있다. 또한 임계값 경계에서 batchSize가 급격히 변동하는 진동(oscillation) 현상이 생긴다. main-server가 queue 도메인의 내부 파라미터를 알아야 한다는 설계 결합도 문제도 있다.

### 방법 C. AIMD Closed-loop 피드백 제어

message-dispatcher가 **결과를 스스로 관찰하고 batchSize를 자율적으로 조절**하는 구조다. TCP 혼잡 제어(Additive Increase Multiplicative Decrease)와 동일한 원리를 적용한다.

| 비교 항목 | 방법 B (Open-loop) | 방법 C (Closed-loop, AIMD) |
|---|---|---|
| 제어 주체 | main-server | message-dispatcher 자체 |
| 반응 속도 | 폴링 주기 (5초 지연) | 매 틱 즉각 반응 (1초) |
| 혼잡 감지 | CPU 임계값 단일 지표 | 실제 결제 동시 사용자 수 |
| 조절 방식 | 스텝 함수 (진동 가능) | 연속 함수 (수렴) |
| 서비스 결합 | main-server가 queue 파라미터 관리 | 각 서비스가 자기 도메인만 담당 |
| Redis 추가 키 | `MAIN_SERVER_CAPACITY` 필요 | 기존 `ENTRY_TOKEN` 재활용 |


    방법 C를 적용하여 main-server의 유입 속도를 가변적으로 제어하기로 결정하였다.
---


## 3. 혼잡 감지 지표 선정: `HLEN(ENTRY_TOKEN)`

### 왜 `ENTRY_TOKEN`인가

`ENTRY_TOKEN[userId] = "true"`는 대기열을 통과한 후 결제를 완료하지 않은 사용자의 집합이다.

```
대기열 통과 → ENTRY_TOKEN[userId] = "true" 설정 (queue-server, EntryStreamMessageListener)
결제 완료   → ENTRY_TOKEN[userId] 삭제          (main-server, RedisLockService)
```

따라서 `HLEN(ENTRY_TOKEN)` = **현재 main-server의 결제 플로우에서 처리 중인 동시 사용자 수**와 정확히 일치한다.

### 선택 이유

| 후보 지표 | 문제 |
|---|---|
| main-server CPU | HTTP 폴링 필요, 지연 발생 |
| `XLEN(ENTRY_QUEUE)` | queue-server가 빠르게 소비하므로 항상 0에 가까움 |
| `seat:lock:*` KEYS 조회 | Redis `KEYS` 명령은 O(N), 운영 환경 사용 금지 |
| **`HLEN(ENTRY_TOKEN)`** | **Redis 1회 조회, O(1), HTTP 없음, 실제 main-server 부하 직접 반영** |

---

## 4. AIMD 알고리즘 구현

### 파라미터 결정 근거

| 파라미터 | 값 | 근거 |
|---|---|---|
| `DEFAULT_BATCH_SIZE` | 50 | 운영 초기 보수적 출발점 |
| `MIN_BATCH_SIZE` | 5 | 최악의 혼잡에서도 대기열이 완전히 멈추지 않도록 |
| `MAX_BATCH_SIZE` | 200 | main-server 부하 테스트 기반 상한 |
| `AI_STEP` | 5 | 느린 증가 → 과도한 유입 방지 |
| `MD_FACTOR` | 0.5 | TCP 표준값, 혼잡 시 즉각 절반 |
| `ENTRY_TOKEN_TARGET` | 100 | main-server 안전 동시 처리 상한 (부하 테스트 결정) |

`ENTRY_TOKEN_TARGET`은 외부 설정(`@Value`)으로 노출해 서버 사양에 따라 조정 가능하도록 해야 한다.

### 동작 흐름

```
매 1초 (EntryPromoteThread)
  ↓
HLEN(ENTRY_TOKEN) 조회  — O(1), Redis 1번 호출
  ↓
혼잡 판단: count >= ENTRY_TOKEN_TARGET (100)?
  YES (혼잡)  → batchSize = max(batchSize × 0.5, 5)   Multiplicative Decrease
  NO  (정상)  → batchSize = min(batchSize + 5,   200)  Additive Increase
  ↓
Lua 스크립트 호출 (ARGV[1]=eventId, ARGV[2]=batchSize)
  ↓
ZRANGE 0 batchSize-1  — 이번 틱에서 최대 batchSize명만 승격
```

### 수렴 시뮬레이션 예시 (seatCount=10,000, ENTRY_TOKEN_TARGET=100)

```
T=0:  batchSize=50,  entryToken=10  → +5 → 55
T=1:  batchSize=55,  entryToken=40  → +5 → 60
T=2:  batchSize=60,  entryToken=85  → +5 → 65
T=3:  batchSize=65,  entryToken=115 → 혼잡! ×0.5 → 32  ← 즉각 절반
T=4:  batchSize=32,  entryToken=95  → +5 → 37           ← 회복 시작
T=5:  batchSize=37,  entryToken=80  → +5 → 42
T=6:  batchSize=42,  entryToken=90  → +5 → 47
T=7:  batchSize=47,  entryToken=100 → 혼잡! ×0.5 → 23
...
결국 main-server 처리 한계 부근에서 진동 폭이 줄며 수렴
```

기존 설계였다면 T=0에 전체 10,000명이 한 번에 유입됐을 것이다.

---

## 5. 변경된 코드 구조

### Lua 스크립트 (`promote_all_waiting_for_event.lua`)

```
변경 전: ZRANGE KEYS[3] 0 -1          (전체 조회)
변경 후: ZRANGE KEYS[3] 0 batchSize-1 (틱당 상한)

ARGV 추가: ARGV[2] = batchSize
```

### `EntryPromoteThread.java`

```java
// 추가된 AIMD 상태 필드
private volatile double currentBatchSize = DEFAULT_BATCH_SIZE;

// 매 틱 실행
Long entryTokenCount = redisTemplate.opsForHash().size(ENTRY_TOKEN_STORAGE_KEY_NAME);
boolean congested = entryTokenCount != null && entryTokenCount >= ENTRY_TOKEN_TARGET;

if (congested) {
    currentBatchSize = Math.max(currentBatchSize * MD_FACTOR, MIN_BATCH_SIZE);
} else {
    currentBatchSize = Math.min(currentBatchSize + AI_STEP, MAX_BATCH_SIZE);
}

// Lua 호출 시 batchSize 전달
redisTemplate.execute(promoteAllScript, scriptKeys,
    Long.parseLong(eventId), (long) batchSize);
```

### `RedisConfig.java` (message-dispatcher)

```java
// 추가된 상수
public static final String ENTRY_TOKEN_STORAGE_KEY_NAME = "ENTRY_TOKEN";
```

---

## 6. `ENTRY_QUEUE_COUNT` 초기화 경합 조건 동시 해결

AIMD 도입 과정에서 기존에 존재하던 별도의 버그도 함께 수정했다.

### 문제

`WaitingQueueEntryService.enter()`에서 `ENTRY_QUEUE_COUNT` 초기화 시 `hasKey` + `put`을 두 단계로 수행했다. 동시에 여러 사용자가 진입할 경우 두 명 이상이 `hasKey = false`를 보고 통과한 뒤 `put`을 실행하면, 이미 일부 사용자가 승격된 이후에도 카운트가 원래 값으로 리셋될 수 있었다.

```
T=0: User A, B 동시 진입 → 둘 다 hasKey = false 통과
T=1: User A → put(1000) → count = 1000
T=2: 500명 승격           → count = 500
T=3: User B → put(1000) → count = 1000  ← 500명분 카운트 복원 버그!
```

### 해결

1. **이벤트 등록 시점(primary)**: `EventRegisterService.registerEvent()`에서 이벤트 생성 완료 후 Redis에 즉시 초기화. 단일 실행이므로 경합이 발생하지 않는다.

2. **Fallback(방어적)**: queue-server의 기존 초기화 코드에서 `put` → `putIfAbsent`(Redis `HSETNX`)로 교체. 동시 진입이 발생해도 첫 번째 호출만 성공하고 나머지는 no-op이 된다.

```java
// 변경 전
simpleRedisTemplate.opsForHash().put(..., seatCount);

// 변경 후
simpleRedisTemplate.opsForHash().putIfAbsent(..., seatCount);  // HSETNX (원자적)
```

---

## 7. 정리

| 항목 | 변경 전 | 변경 후 |
|---|---|---|
| 총량 제어 | O (seatCount 기준) | O (유지) |
| 속도 제어 | X (첫 틱에 전원 유입 가능) | O (틱당 batchSize 명) |
| 적응형 제어 | X | O (AIMD, 매 틱 자동 조절) |
| 혼잡 감지 방법 | 없음 | HLEN(ENTRY_TOKEN) — HTTP 없이 Redis 1회 조회 |
| 초기화 경합 조건 | 존재 (put 덮어쓰기) | 해결 (이벤트 등록 시 primary + HSETNX fallback) |
| main-server 결합도 | 높음 (queue-server가 seatCount HTTP 조회) | 낮음 (이벤트 등록 시 main-server가 직접 초기화) |
