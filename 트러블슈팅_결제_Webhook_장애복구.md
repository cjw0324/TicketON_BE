# 결제 Webhook 장애 복구 트러블슈팅 기록

## 1. 문제 인식: `/payments/confirm` 장애 시 티켓이 발행되지 않는다

### 배경

토스페이먼츠 결제 흐름은 두 단계로 구성된다.

```
[클라이언트] 결제 완료 버튼
     ↓
[main-server] POST /payments/confirm
  → tossPaymentService.confirmPayment()  (HTTP 호출)
  → 티켓 생성
  → paymentStatus = DONE
     ↓
[Toss 서버] Webhook 전송 (비동기, 별도)
```

기존 설계에서 Webhook은 **상태 모니터링 용도**로만 취급됐다. `/payments/confirm`이 Toss 서버에 능동 승인 요청을 보내고, Webhook은 그 결과를 수동으로 수신하는 역할이었다.

### 장애 지점 분석

```
/payments/confirm 처리 흐름:
  1. Toss 승인 HTTP 요청           ← 성공
  2. purchase.updatePaymentInfo()  ← 장애 발생 가능
  3. 티켓 생성 (ticketRepository)  ← 장애 발생 가능
  4. paymentStatus = DONE          ← 장애 발생 가능
  5. ENTRY_TOKEN 삭제              ← 장애 발생 가능
```

1번(Toss 승인)은 성공했지만 이후 단계에서 장애(서버 재시작, DB 슬로우 쿼리, GC 중단 등)가 발생하면:

- **사용자 관점**: 돈은 빠져나갔는데 티켓이 없다
- **서버 관점**: `paymentStatus = IN_PROGRESS`이고 `tickets`가 없는 구매 레코드만 남는다

---

## 2. 설계 방향: 능동 승인 + Webhook 보조 복구

### 방식 A vs B

| 항목 | 방식 A (능동 승인 Primary) | 방식 B (Webhook 단독) |
|---|---|---|
| 결제 승인 주체 | main-server → Toss HTTP 요청 | Toss → main-server Webhook |
| 응답 지연 | 없음 (동기) | Webhook 도달 최대 수 초 지연 |
| 장애 복구 | Webhook이 fallback | 단일 장애점 |
| 사용자 경험 | 결제 완료 즉시 티켓 확인 가능 | 응답 대기 |

**방식 A 채택**: `/payments/confirm`이 1차 처리를 담당하고, Webhook은 장애 감지 후 자동 복구 경로로만 동작한다.

---

## 3. 근본 문제: 좌석 선택 정보의 휘발성

### 기존 구조의 취약점

```
좌석 선택 → Redis 락 (seat:lock:{userId}:{eventId}:{seatId})
                ↓
          /payments/init 호출
                ↓
          /payments/confirm 호출
            → Redis에서 seatIds 조회
            → 티켓 생성
```

`/payments/confirm` 도중 장애가 발생해 재처리가 필요할 때, Webhook은 `paymentKey`만 알고 있다. 어떤 좌석을 예매하려 했는지는 Redis 락에만 존재한다.

장애 상황에서 Redis 락이 TTL(5분)로 만료되거나 서버 재시작으로 소실되면 **Webhook이 도착해도 티켓을 재생성할 방법이 없다**.

### 해결: `PurchaseSeat` — 좌석 정보 DB 영속화

`/payments/init` 시점에 선택된 좌석 ID를 `purchase_seat` 테이블에 저장한다.

```
/payments/init
  → Purchase 저장 (status = IN_PROGRESS)
  → PurchaseSeat 저장 (seatId, eventId, purchaseId)  ← 신규
```

```java
List<Long> seatIds = redisLockService.getLockedSeatIdsByUserId(userId);
List<PurchaseSeat> purchaseSeats = seatIds.stream()
    .map(seatId -> PurchaseSeat.builder()
        .purchase(purchase)
        .seatId(seatId)
        .eventId(eventId)
        .build())
    .toList();
purchaseSeatRepository.saveAll(purchaseSeats);
```

이제 Redis 락이 사라져도 Webhook은 `PurchaseSeat` 조회만으로 어떤 좌석을 재생성해야 하는지 알 수 있다.

---

## 4. 엣지 케이스 분석 및 방어 전략

Webhook 복구 경로에서 단순히 티켓을 재생성하면 새로운 문제가 발생한다.

### EC1: 중복 Webhook 동시 수신

Toss는 Webhook 전송 실패 시 재전송한다. 동시에 두 개의 `DONE` Webhook이 도착하면:

```
T=0: Webhook A, B 동시 수신
T=1: A → 티켓 존재 여부 확인 → 없음 확인
T=2: B → 티켓 존재 여부 확인 → 없음 확인  (A가 아직 저장 전)
T=3: A → 티켓 생성
T=4: B → 티켓 생성  ← 중복 발행!
```

**해결**: `SELECT FOR UPDATE` 비관적 락으로 직렬화

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Purchase p WHERE p.paymentUuid = :paymentKey")
Optional<Purchase> findByPaymentUuidWithLock(@Param("paymentKey") String paymentKey);
```

두 번째 요청은 락 대기 → A 처리 완료 후 → 멱등성 체크(`status == DONE`) → 즉시 return.

### EC2: 티켓 생성 후 paymentStatus 미갱신

`/payments/confirm`에서 티켓 저장 직후 `purchaseRepository.save(purchase)`(DONE 갱신) 직전에 장애 발생.

```
tickets 존재 + paymentStatus = IN_PROGRESS
```

**해결**: 티켓이 이미 존재하면 상태만 DONE으로 복구. 티켓 재생성 불필요.

```java
if (!tickets.isEmpty()) {
    purchase.setPaymentStatus(PaymentStatusEnum.DONE);
    purchaseRepository.save(purchase);
}
```

### EC3: 좌석이 다른 사용자에게 점유됨

`/payments/confirm` 장애 후 Redis 락 TTL 만료 → 동일 좌석을 다른 사용자가 선택하여 결제 완료 → 원래 사용자의 Webhook이 도착.

```
PurchaseSeat 존재 + seat.available = false + seat.ticket != null
```

이 상황에서 억지로 좌석을 빼앗아 티켓을 발행할 수 없다. 사용자에게는 이미 돈이 빠져나간 상태다.

**해결**: Toss에 자동 취소 요청 후 사용자에게 환불 알림

```java
List<Seat> alreadyTaken = seats.stream()
    .filter(s -> !s.isAvailable() && s.getTicket() != null)
    .toList();

if (!alreadyTaken.isEmpty()) {
    autoRefund(paymentKey, purchase, "선택하신 좌석이 이미 예약되어 자동 취소되었습니다.");
}
```

### EC4: PurchaseSeat조차 없는 경우

`/payments/init` 내부에서도 장애가 발생해 `PurchaseSeat` 저장 전에 서버가 내려간 경우. 또는 `initiatePayment()` 이전 구버전 레코드.

**해결**: 자동 복구 불가 → Toss 자동 환불 시도 + 에러 로그 + 담당자 알림

```
[Webhook] 결제 승인됐으나 티켓·좌석 정보 없음 — 수동 처리 필요
```

---

## 5. 전체 Webhook 복구 흐름

```
Webhook DONE 수신
  ↓
비관적 락 (SELECT FOR UPDATE)
  ↓
status == DONE?  →  YES  →  멱등성 통과, return
  ↓ NO
tickets 존재?    →  YES  →  EC2: setPaymentStatus(DONE)  →  알림
  ↓ NO
PurchaseSeat 존재?  →  NO  →  EC4: autoRefund() + 에러 로그
  ↓ YES
좌석 조회 실패?   →  YES  →  autoRefund()
  ↓ NO
좌석 점유됨?      →  YES  →  EC3: autoRefund() + 알림
  ↓ NO
좌석 재예약 + 티켓 재생성 → DONE → 알림  (EC4 정상 복구)
```

---

## 6. 변경된 코드 구조

### 신규 파일

**`PurchaseSeat.java`** — `/payments/init` 시점 좌석 영속화
```java
@Entity
@Table(name = "purchase_seat")
public class PurchaseSeat {
    private Long seatId;
    private Long eventId;
    @ManyToOne(fetch = FetchType.LAZY)
    private Purchase purchase;
}
```

**`PurchaseSeatRepository.java`**
```java
List<PurchaseSeat> findByPurchase(Purchase purchase);
```

### 수정 파일

**`PurchaseRepository.java`** — 비관적 락 쿼리 추가
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Purchase p WHERE p.paymentUuid = :paymentKey")
Optional<Purchase> findByPaymentUuidWithLock(@Param("paymentKey") String paymentKey);
```

**`PurchaseService.initiatePayment()`** — PurchaseSeat 저장
```java
@Transactional
public InitiatePaymentResponse initiatePayment(...) {
    // ... purchase 저장 ...
    List<Long> seatIds = redisLockService.getLockedSeatIdsByUserId(userId);
    purchaseSeatRepository.saveAll(
        seatIds.stream()
            .map(id -> PurchaseSeat.builder()
                .purchase(purchase).seatId(id).eventId(eventId).build())
            .toList()
    );
}
```

**`WebhookService.java`** — 전면 재작성
```
변경 전: status 업데이트만 / 트랜잭션 없음 / 락 없음
변경 후: @Transactional / 비관적 락 / 4단계 복구 분기 / autoRefund() / notifyRecovery()
```

---

## 7. 정리

| 항목 | 변경 전 | 변경 후 |
|---|---|---|
| Webhook 역할 | 상태 모니터링 | 장애 복구 보조 경로 |
| 좌석 정보 영속화 | 없음 (Redis 락에만 존재) | `PurchaseSeat` DB 저장 |
| 중복 Webhook 방어 | 없음 | 비관적 락 + 멱등성 체크 |
| 티켓 재생성 | 불가 (좌석 정보 없음) | `PurchaseSeat` 기반 자동 재생성 |
| 좌석 선점 충돌 | 미처리 | 자동 환불 + 사용자 알림 |
| 복구 불가 케이스 | 무한 IN_PROGRESS | 자동 환불 시도 + 에러 로그 |
| 트랜잭션 | 없음 | `@Transactional` 전체 보장 |
