# 프로젝트 경험 STAR 기법 정리

---

## 1. AIMD 유량 제어 — 대기열이 트래픽 스파이크를 막지 못하는 문제

### Situation (상황)

대기열 시스템을 도입했음에도 불구하고 **main-server로 유입되는 트래픽 스파이크가 제어되지 않는 구조적 결함**이 존재했다.

기존 설계에서 `ENTRY_QUEUE_COUNT`는 `seatCount / 100`으로 고정 초기화되었고, Lua 스크립트는 매 틱(1초)마다 조건을 만족한 전체 대기자를 제한 없이 승격시켰다. 좌석 수가 10,000석인 이벤트라면, 이론상 첫 번째 틱에서 10,000명이 동시에 main-server로 진입할 수 있었다. 대기열이 **총량(count)** 은 제한했지만, **유입 속도(rate)** 는 전혀 제어하지 않는 구조였다.

또한 `ENTRY_QUEUE_COUNT` 초기화 시 `hasKey` + `put`을 두 단계로 나눠 수행하는 비원자적 구조 때문에, 동시에 여러 사용자가 진입할 경우 이미 승격된 카운트가 원래 값으로 덮어써지는 경합 조건(race condition) 버그도 함께 존재했다.

### Task (과제)

- main-server의 실시간 처리 상황을 반영해 유입 속도를 **가변적**으로 제어하는 구조를 설계한다.
- 설계가 단순하고 외부 의존성을 최소화하며, 서비스 간 결합도를 낮춰야 한다.
- 초기화 경합 조건도 함께 해결한다.

### Action (행동)

**혼잡 감지 지표 선정**

다양한 지표(CPU, Redis Stream 길이, 락 키 수)를 검토한 결과, `HLEN(ENTRY_TOKEN)`을 채택했다. `ENTRY_TOKEN[userId] = "true"`는 대기열을 통과한 뒤 결제를 완료하지 않은 사용자의 집합으로, 이 값이 곧 main-server의 결제 플로우에서 처리 중인 동시 사용자 수와 정확히 일치한다. Redis O(1) 조회이고 HTTP 호출이 전혀 없으며 실시간으로 반영된다는 점에서 가장 적합했다.

**AIMD 알고리즘 구현 (TCP 혼잡 제어와 동일 원리)**

message-dispatcher의 `EntryPromoteThread`가 매 틱마다 스스로 혼잡 상태를 관찰하고 `batchSize`를 자율적으로 조절하도록 구현했다.

- **정상 상태** (`HLEN < 100`): `batchSize += 5` (Additive Increase)
- **혼잡 상태** (`HLEN >= 100`): `batchSize = max(batchSize × 0.5, 5)` (Multiplicative Decrease)
- 범위: `MIN=5`, `MAX=200`, 초기값 `DEFAULT=50`

Lua 스크립트는 기존 `ZRANGE 0 -1` (전체 조회)에서 `ZRANGE 0 batchSize-1` (틱당 상한)으로 변경해 원자적으로 승격 대상을 제한한다.

**경합 조건 해결**

1. 이벤트 등록 시점(`EventRegisterService`)에서 Redis에 단일 primary 초기화(단일 실행이므로 경합 없음)
2. queue-server의 기존 `put` → `putIfAbsent` (Redis `HSETNX`, 원자적)로 교체하여 동시 진입 시 첫 번째 호출만 성공

### Result (결과)

- 수렴 시뮬레이션 기준, 기존 설계였다면 T=0에 10,000명이 한 번에 유입됐을 상황에서, AIMD 적용 후 main-server 처리 한계(동시 100명) 부근에서 진동 폭이 줄며 수렴하는 동작을 확인했다.
- main-server가 queue 도메인의 파라미터를 알 필요가 없어 서비스 결합도가 낮아졌다.
- Redis 추가 키 없이 기존 `ENTRY_TOKEN`을 재활용함으로써 인프라 오버헤드 없이 적응형 제어가 가능해졌다.
- 초기화 경합 조건이 완전히 제거되어 카운트 복원 버그가 사라졌다.

---

## 2. 결제 Webhook 장애 복구 — 돈은 빠져나갔는데 티켓이 없는 문제

### Situation (상황)

토스페이먼츠 결제 흐름에서 `/payments/confirm`이 Toss 서버에 능동 승인 요청을 보낸 뒤(1번 성공), 이후 단계(티켓 생성, 상태 갱신)에서 **서버 재시작·GC 중단·DB 슬로우 쿼리** 등의 장애가 발생할 경우:

- 사용자 관점: 돈은 빠져나갔는데 티켓이 없다
- 서버 관점: `paymentStatus = IN_PROGRESS`이고 `tickets`가 없는 레코드만 남는다

기존 설계에서 Webhook은 상태 모니터링 용도에 불과했고 장애 복구 경로가 전혀 없었다.

더 심각한 구조적 문제는 **좌석 선택 정보가 Redis 락에만 존재**한다는 점이었다. Webhook은 `paymentKey`만 알고 있어, 장애 후 Redis 락 TTL(5분)이 만료되면 Webhook이 도착해도 어떤 좌석을 재생성해야 할지 알 방법이 없었다.

### Task (과제)

- `/payments/confirm` 도중 장애가 발생해도 **사용자 피해 없이 자동 복구**되는 구조를 설계한다.
- 중복 Webhook 동시 수신, 부분 성공, 좌석 충돌 등 다양한 엣지 케이스를 방어한다.
- 자동 복구가 불가능한 경우에도 사용자에게 환불이 이뤄져야 한다.

### Action (행동)

**근본 문제 해결: 좌석 정보 DB 영속화**

`/payments/init` 시점에 선택된 좌석 ID를 `PurchaseSeat` 테이블에 영속화했다. 이제 Redis 락이 사라져도 Webhook은 `PurchaseSeat` 조회만으로 어떤 좌석을 재생성해야 하는지 알 수 있다.

**엣지 케이스 4가지 방어**

| 시나리오 | 감지 조건 | 처리 |
|---|---|---|
| EC1: 중복 Webhook 동시 수신 | — | `SELECT FOR UPDATE` 비관적 락으로 직렬화 → 멱등성 체크 |
| EC2: 티켓 생성 후 상태 미갱신 | `tickets` 존재 + `status != DONE` | `setPaymentStatus(DONE)` 복구 |
| EC3: 좌석이 타인에게 이미 점유 | `PurchaseSeat` 존재 + `seat.available=false` | Toss 자동 환불 + 사용자 알림 |
| EC4: 티켓·좌석 모두 미생성 | `PurchaseSeat` 존재 + 좌석 사용 가능 | 좌석 재예약 + 티켓 재생성 → DONE |

`handleWebhook()`에 `@Transactional` + `findByPaymentUuidWithLock()` (`SELECT FOR UPDATE`)를 적용해 동시성을 보장했다.

**구조 결정: 능동 승인 Primary + Webhook Fallback**

Webhook 단독 방식(방식 B)은 도달 지연이 수 초 발생하고 단일 장애점이 된다. 반면 main-server가 능동적으로 Toss에 HTTP 요청을 보내는 방식(방식 A)은 즉각 응답이 가능하고 Webhook은 장애 감지 후 자동 복구 경로로만 동작한다. 방식 A를 채택했다.

### Result (결과)

- "돈은 빠져나갔는데 티켓이 없는" 상황이 구조적으로 제거되었다.
- EC1~EC4 모든 장애 시나리오에서 자동 복구 또는 자동 환불 처리가 이뤄진다.
- 중복 Webhook에 대해 비관적 락 + 멱등성 체크로 중복 티켓 발행이 방지된다.
- 복구 불가능한 케이스(`PurchaseSeat`조차 없는 경우)에도 자동 환불 시도 + 에러 로그가 남아 운영자가 인지하고 수동 처리할 수 있다.
- 기존에 트랜잭션이 없던 Webhook 처리에 `@Transactional`이 추가되어 부분 커밋으로 인한 데이터 불일치가 사라졌다.

---

## 3. SSE 기반 대기열 설계 — maxConnections 한계와 수평 확장 구조

### Situation (상황)

서버 환경은 EC2 프리티어(t2.micro, vCPU 1개, RAM 1GB)였다. SSE는 HTTP 연결을 끊지 않고 유지하는 방식이므로, 대기자가 늘어날수록 TCP 소켓 슬롯(maxConnections)을 지속 점유한다. Tomcat 기본값 8,192 그대로 사용하면 수천 명의 SSE 연결 시 RAM이 먼저 한계에 이를 수 있었다.

또한 `QueueInfoScheduler`가 매 1초마다 전체 대기자 N명에게 Redis 조회(2N+1회)를 수행하고 SSE를 전송하므로, t2.micro의 지속 CPU 성능(약 10%)에서 대기자 1,000명 수준이면 빡빡해지는 구조였다.

실제로 500명 초과 동시 접속 시 신규 연결이 거부되는 응답 실패 장애가 발생했다.

### Task (과제)

- t2.micro 환경에 맞는 적정 maxConnections를 산정하고 적용한다.
- SSE를 유지하면서 단일 인스턴스의 한계를 넘는 수평 확장(scale-out) 구조를 설계한다.
- 수평 확장 시 SSE가 특정 인스턴스에 sticky하게 묶인다는 제약 조건을 해결한다.

### Action (행동)

**maxConnections 산정 및 설정**

RAM 기준(SSE 1개당 5~8KB, JVM 힙 400MB → 이론 5만 개, 안전 한계 3,000~5,000개)보다 **CPU가 더 현실적인 병목**임을 파악했다. OS 파일 디스크립터 한도(기본 1,024~4,096)도 고려해 t2.micro 기준 현실적인 권장값인 300~500으로 maxConnections를 설정했다.

한편, SSE가 스레드 풀을 고갈시킨다는 통념을 재검토했다. Spring MVC `SseEmitter`는 Servlet AsyncContext를 사용하므로 연결 수립 순간 워커 스레드는 풀로 반납된다. 실제 점유 자원은 스레드가 아닌 **TCP 소켓(maxConnections 슬롯)**임을 확인하고 팀 내에서 공유했다.

**SSE 유지 결정 (폴링 전환 불가)**

폴링 방식으로 전환하면 사용자 이탈을 서버 측에서 즉각 감지하기 어렵다. 마지막 요청 이후 일정 시간이 지나야 이탈로 판단할 수 있어, 이탈한 사용자가 대기열 순번을 점유하는 문제가 생긴다. SSE는 `onError()` / `onCompletion()` 콜백으로 이탈을 즉각 감지하고 Redis ZSet에서 제거한다. **이탈 감지 요구사항 때문에 폴링 전환 불가 → SSE 유지 결정**했다.

**수평 확장 구조 설계 (멀티 인스턴스)**

SSE는 특정 인스턴스에 고정(sticky)된다. 입장 허가 메시지를 보낼 때 SSE가 연결된 바로 그 인스턴스로 라우팅해야 한다. 이를 위해 다음 구조를 도입했다.

1. **대기 등록 시 `instanceId` 저장**: 사용자가 대기열에 진입할 때 연결된 인스턴스를 `WAITING_QUEUE_RECORD:{eventId}` Hash에 기록
2. **`instanceId` 기반 메시지 라우팅**: `message-dispatcher`가 입장 허가 메시지 발행 시, 사용자의 `instanceId`를 조회해 해당 인스턴스의 `DISPATCH:{instanceId}` 스트림에만 메시지 전달
3. **인스턴스별 독립 Consumer Group**: 각 인스턴스가 자신에게 라우팅된 메시지만 소비

### Result (결과)

- t2.micro 환경에 맞는 maxConnections(300~500)를 산정하고 장애 원인을 명확히 규명했다.
- SSE 연결이 스레드 풀이 아닌 TCP 소켓을 점유한다는 사실을 팀 내에서 정확히 정리했다.
- `instanceId` 기반 라우팅 구조 덕분에 queue-server 인스턴스를 수평으로 추가해도 메시지가 올바른 인스턴스로 정확히 전달된다.
- message-dispatcher가 단일 조정자 역할을 맡아, 각 queue-server 인스턴스는 자신의 스트림만 소비하는 단순한 구조를 유지했다.

---

## 4. JWT 클레임 확장 — queue-server의 매 요청 DB 조회 제거

### Situation (상황)

queue-server는 사용자가 SSE 연결을 맺을 때마다 `JwtAuthenticationFilter`가 `loadUserByUsername(email)`을 호출해 DB에서 사용자를 조회했다. 수천 명이 동시에 SSE 연결을 수립하면 DB에 수천 건의 쿼리가 동시에 발생하고, 대기열 시스템의 목적(main-server 보호)과 반대로 **queue-server 자체가 DB 병목**이 되는 구조였다.

기존 JWT payload에는 `sub(email)`, `iat`, `exp`만 존재해, 토큰만으로 `userId`와 `role`을 알 수 없었다. 인증 완료 후 `SecurityUtil.getCurrentUser()`도 `userId`를 얻기 위해 별도 DB 조회를 수행했다.

### Task (과제)

- JWT payload에 `userId`와 `role`을 추가해 DB 조회 없이 인증이 완료되도록 개선한다.
- 기존 Redis 블랙리스트(토큰 즉시 무효화) 기능은 유지한다.
- main-server는 기존 stateful 방식을 유지하고, queue-server만 stateless 방식으로 전환한다.

### Action (행동)

**JWT 클레임 확장**

`JwtConfig.generateAccessToken()`과 `generateRefreshToken()`에 `userId(Long)`, `role(String)` 파라미터를 추가하고 claims에 포함시켰다. 리프레시 토큰 갱신 시에도 DB 조회 없이 리프레시 토큰의 클레임에서 `userId`와 `role`을 추출해 새 액세스 토큰을 발급하도록 수정했다.

**`JwtUserDetails` 신규 구현**

DB 의존 없이 JWT 클레임만으로 동작하는 경량 `UserDetails` 구현체(`JwtUserDetails`)를 추가했다. `userId`, `identifier`, `role`을 필드로 갖고 `getAuthorities()`에서 `SimpleGrantedAuthority(role)`을 반환한다.

**Stateless 필터 분기**

`JwtAuthenticationFilter`에 `@Value("${jwt.stateless:false}") private boolean stateless` 설정을 추가하고, `stateless=true`일 때는 클레임에서 `userId`와 `role`을 추출해 `JwtUserDetails`로 직접 인증을 설정한다. 블랙리스트 체크는 분기 이전에 수행해 두 모드 모두에서 토큰 즉시 무효화가 가능하다.

queue-server의 `application.yml`에 `jwt.stateless: true`를 추가하고, main-server는 기본값 `false`를 유지했다.

**보안 trade-off 정리**

토큰 만료 전 강제 로그아웃 시 즉각 무효화 능력이 감소하는 trade-off를 만료시간을 10분으로 제한하고 Redis 블랙리스트로 보완하는 방식으로 처리했다.

### Result (결과)

- queue-server의 모든 SSE 인증 요청에서 DB 조회가 완전히 제거됐다.
- 수천 명의 동시 SSE 연결 수립 시에도 DB 부하 없이 Redis 블랙리스트 조회(O(1)) 한 번으로 인증이 완료된다.
- main-server는 기존 `SecurityUtil.getCurrentUser()`(`User` 엔티티 반환)를 그대로 사용할 수 있어 변경 범위가 최소화됐다.
- 블랙리스트 기능이 stateless 모드에서도 동작해 긴급 로그아웃 요구사항을 유지했다.


helm install ingress-nginx ingress-nginx/ingress-nginx --namespace ingress-nginx --create-namespace

helm install redis bitnami/redis --namespace ticketone --create-namespace --set auth.enabled=false --set architecture=standalone

helm install mysql bitnami/mysql --namespace ticketone --set auth.rootPassword=password --set auth.database=ticketone --set primary.persistence.size=1Gi

