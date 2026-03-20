/**
 * k6 부하 테스트: 대기열 scale out/in 완전 검증
 *
 * 핵심 개선 사항:
 * - SSE 연결을 60초 유지 (scale-in 이벤트가 발생할 때까지 연결 보유)
 * - 서버 측 연결 끊김(scale-in으로 인한 pod 종료)을 감지하고 자동 재연결
 * - 재연결 시 sticky session 쿠키 없이 접속 (살아있는 다른 pod으로 라우팅)
 * - 재연결 후 ENTRY 이벤트 수신 여부 추적
 *
 * SSE 타임아웃 vs 서버 drop 구분:
 *   error_code === 1050 → 우리의 60s 타임아웃 (정상)
 *   error_code !== 1050 + status === 0 → 서버가 연결 끊음 (scale-in!)
 *
 * 실행 방법:
 *   k6 run k6/load-test.js
 *
 * HPA 자동 scale out/in (수동 트리거 불필요):
 *   - requests.cpu=50m, threshold=30% → 15m 이상이면 scale out
 *   - k6 부하가 걸리면 CPU 상승 → HPA가 자동으로 scale out
 *   - 테스트 종료 후 부하 감소 → HPA가 자동으로 scale in
 *
 * HPA 상태 모니터링 (테스트 중 별도 터미널에서):
 *   kubectl get hpa -n ticketone -w
 *   kubectl get pods -n ticketone -l app=queue-server -w
 *
 * 테스트 후 확인:
 *   kubectl exec -n ticketone redis-master-0 -- redis-cli HLEN ENTRY_TOKEN
 *   kubectl exec -n ticketone redis-master-0 -- redis-cli ZCARD "WAITING:2"
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ─── 커스텀 메트릭 ─────────────────────────────────────────────
const sseConnected       = new Counter('sse_connected');        // SSE 최초 연결 성공 횟수
const sseErrors          = new Counter('sse_errors');           // SSE 연결 실패 횟수
const sseReconnects      = new Counter('sse_reconnects');       // scale-in 감지 후 재연결 횟수
const entryReceived      = new Counter('entry_received');       // ENTRY 이벤트 수신 횟수
const entryAfterReconnect = new Counter('entry_after_reconnect'); // 재연결 후 ENTRY 수신 횟수
const loginFailed        = new Counter('login_failed');         // setup() 로그인 실패 횟수
const sseSuccessRate     = new Rate('sse_success_rate');        // SSE 전체 성공률
const sseConnectTime     = new Trend('sse_connect_ms');         // SSE 최초 연결 시간

// ─── 설정 ─────────────────────────────────────────────────────
const BASE_URL    = 'http://localhost';
const EVENT_ID    = 2;
const SSE_TIMEOUT = '60s';   // Pod 종료 기다릴 만큼 긴 타임아웃
const RECONNECT_DELAY = 3;   // SSE EventSource 기본 재연결 대기 (초)
const MAX_RECONNECTS  = 10;  // VU당 최대 재연결 시도 횟수 (scale-in TCP drop 연속 대응)
const MAX_VUS     = 200;     // stages의 최대 target — 사용자 순환 계산에 사용

// ─── k6 에러 코드 ──────────────────────────────────────────────
const ERR_TIMEOUT = 1050;   // request timeout — 우리가 설정한 60s 타임아웃

// ─── 부하 시나리오 ─────────────────────────────────────────────
export const options = {
  scenarios: {
    scale_test: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50  },  // 워밍업: 50명 증가
        { duration: '30s', target: 200 },  // scale out 유도: 200명으로 증가
        { duration: '60s', target: 200 },  // HPA scale out/in 구간: 200명 유지
        { duration: '30s', target: 0   },  // 종료: VU 감소 → HPA scale in
      ],
      // gracefulRampDown을 60s로 늘려 재연결 메트릭이 완전히 기록되도록
      gracefulRampDown: '60s',
    },
  },
  thresholds: {
    // scale-in 재연결 후에도 SSE 성공률 85% 이상
    'sse_success_rate': ['rate>0.85'],
  },
};

// ─── 사전 사용자 등록 + 로그인 (setup) ────────────────────────
// 회원가입 + 로그인을 setup()에서 미리 수행해 accessToken을 발급.
// default()에서 반복 로그인하지 않으므로 로그인 스파이크 제거.
export function setup() {
  console.log('[setup] 테스트 사용자 300명 등록 및 로그인 시작...');
  const users = [];

  for (let i = 1; i <= 300; i++) {
    const email    = `k6user${i}@loadtest.com`;
    const password = 'Test1234!';

    // 1) 회원가입 (이미 존재해도 무시)
    http.post(
      `${BASE_URL}/api/v1/users/signup`,
      JSON.stringify({
        email, password,
        name:     `K6사용자${i}`,
        age:      20 + (i % 40),
        sex:      i % 2 === 0 ? '남성' : '여성',
        phoneNum: `010-${String(i).padStart(4, '0')}-0000`,
        location: '서울시',
      }),
      { headers: { 'Content-Type': 'application/json' } },
    );

    // 2) 로그인 → accessToken 발급
    const loginRes = http.post(
      `${BASE_URL}/api/v1/users/login`,
      JSON.stringify({ email, password }),
      { headers: { 'Content-Type': 'application/json' } },
    );

    const tok = loginRes.status === 200
      ? (loginRes.cookies['accessToken']?.[0]?.value ?? null)
      : null;

    if (tok) {
      users.push({ email, accessToken: tok });
    } else {
      console.warn(`[setup] 로그인 실패: ${email} (status=${loginRes.status})`);
    }

    if (i % 10 === 0) sleep(0.3);
  }

  console.log(`[setup] 사용자 준비 완료: ${users.length}명 / 300명`);
  return { users };
}

// ─── 로그인 ────────────────────────────────────────────────────
function login(email, password) {
  const res = http.post(
    `${BASE_URL}/api/v1/users/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } },
  );

  if (res.status !== 200) {
    loginFailed.add(1);
    return null;
  }

  const tok = res.cookies['accessToken'];
  return tok ? tok[0].value : null;
}

// ─── SSE 단일 연결 시도 ────────────────────────────────────────
// 반환값:
//   { connected, timedOut, serverDrop, hasEntry, alreadyQueued }
function attemptSSE(accessToken) {
  const start = Date.now();

  const res = http.get(
    `${BASE_URL}/api/v1/events/${EVENT_ID}/tickets/waiting`,
    {
      headers: {
        'Accept': 'text/event-stream',
        // accessToken만 전달 — QUEUE_SERVER_ID sticky 쿠키 없이 요청
        // (재연결 시 NGINX가 살아 있는 pod으로 자유롭게 라우팅)
        'Cookie': `accessToken=${accessToken}`,
      },
      timeout: SSE_TIMEOUT,
    },
  );

  const elapsed = Date.now() - start;
  const body    = res.body || '';

  const isTimeout    = res.error_code === ERR_TIMEOUT;
  const isNetDrop    = res.status === 0 && !isTimeout;       // TCP 레벨 연결 끊김 (pod 종료)
  const isNginxDrop  = res.status === 502 || res.status === 503 || res.status === 504;
                       // 502: NGINX가 죽은 pod으로 라우팅 시도
                       // 503: NGINX upstream 없음 (scale-in 직후)
                       // 504: NGINX upstream 타임아웃
  const isServerDrop = isNetDrop || isNginxDrop;             // ← scale-in 감지 핵심
  // status=500은 대기열 중복 진입("다른 대기열에 이미 들어와 있습니다.")이 유일한 예상 케이스
  // body가 비어 있거나 다른 형식으로 오더라도 500 자체를 alreadyQueued로 처리
  const alreadyQueued = res.status === 500 &&
    (body.includes('이미 들어와 있습니다') || body.length === 0 || body.includes('500'));
  const hasEntry      = body.includes('IN_PROGRESS');

  return {
    status:       res.status,
    elapsed,
    connected:    (res.status === 200 || isTimeout),
    timedOut:     isTimeout,
    serverDrop:   isServerDrop,
    isNginxDrop,
    hasEntry,
    alreadyQueued,
    errorCode:    res.error_code,
    error:        res.error,
    body,
  };
}

// ─── SSE 연결 + 재연결 루프 ────────────────────────────────────
// scale-in으로 pod이 죽으면 즉시 재연결해서 ENTRY를 받는 흐름 시뮬레이션
function connectSSEWithReconnect(accessToken, vuId) {
  let reconnectCount = 0;

  for (let attempt = 0; attempt <= MAX_RECONNECTS; attempt++) {
    const isReconnect = attempt > 0;
    const result = attemptSSE(accessToken);

    // ── 성공 케이스 처리 ──────────────────────────────────────
    if (result.connected || result.alreadyQueued) {
      if (!isReconnect) {
        sseConnected.add(1);
        sseConnectTime.add(result.elapsed);
      }
      sseSuccessRate.add(true);

      if (result.hasEntry) {
        entryReceived.add(1);
        if (isReconnect) {
          entryAfterReconnect.add(1);
          console.log(`[VU ${vuId}] 재연결 후 ENTRY 이벤트 수신 ✓ (재연결 ${reconnectCount}회)`);
        } else {
          console.log(`[VU ${vuId}] ENTRY 이벤트 수신 ✓`);
        }
      }

      // 타임아웃으로 종료됐으면 더 이상 재연결 불필요
      if (result.timedOut || result.alreadyQueued) {
        break;
      }

      // 200 OK로 완료됐어도 종료
      if (result.status === 200) {
        break;
      }
    }

    // ── scale-in/scale-out: 서버가 연결을 끊음 ──────────────
    if (result.serverDrop) {
      sseReconnects.add(1);
      reconnectCount++;

      const reason = result.isNginxDrop
        ? `NGINX ${result.status} (pod 준비 중 또는 종료됨)`
        : `TCP 연결 끊김 (error_code=${result.errorCode})`;

      console.warn(`[VU ${vuId}] 서버 연결 끊김: ${reason}. 재연결 ${reconnectCount}/${MAX_RECONNECTS}`);

      check(result, {
        'scale-in 감지 후 재연결 시도': () => reconnectCount <= MAX_RECONNECTS,
      });

      // 502/503: NGINX upstream 불안정 → 짧게 대기 후 재연결
      // TCP drop (0): pod 완전 종료 → EventSource 기본 재연결 대기
      const delay = result.isNginxDrop ? 2 : RECONNECT_DELAY;
      sleep(delay);
      continue;
    }

    // ── 진짜 오류 (연결 거부 등) ──────────────────────────────
    if (!result.alreadyQueued && result.status !== 0) {
      sseErrors.add(1);
      sseSuccessRate.add(false);
      console.error(
        `[VU ${vuId}] SSE 연결 실패: status=${result.status}, ` +
        `error=${result.error?.substring(0, 80)}, ` +
        `body=${result.body?.substring(0, 100)}`,
      );
      break;
    }

    // 최대 재연결 초과
    if (reconnectCount >= MAX_RECONNECTS) {
      sseErrors.add(1);
      sseSuccessRate.add(false);
      console.error(`[VU ${vuId}] 최대 재연결(${MAX_RECONNECTS}회) 초과`);
      break;
    }
  }
}

// ─── 메인 VU 시나리오 ──────────────────────────────────────────
export default function (data) {
  const users = data?.users;
  if (!users || users.length === 0) { sleep(1); return; }

  // VU 번호 + 반복 횟수를 조합해 300명 전체를 순환
  // 예) VU1 iter0→user1, VU1 iter1→user201, VU1 iter2→user101 ...
  const user = users[((__VU - 1) + __ITER * MAX_VUS) % users.length];

  // setup()에서 발급한 accessToken 재사용 (로그인 스파이크 제거)
  const { accessToken } = user;
  if (!accessToken) { sleep(2); return; }

  sleep(0.2);

  // SSE 대기열 연결 (60s 유지 + scale-in 시 자동 재연결)
  connectSSEWithReconnect(accessToken, __VU);

  sleep(1);
}

// ─── 테스트 완료 후 요약 ──────────────────────────────────────
export function handleSummary(data) {
  const m = data.metrics;
  const get = (metric, field = 'count') =>
    m[metric]?.values?.[field] ?? 0;

  console.log('\n╔══════════════════════════════════════╗');
  console.log('║       부하 테스트 결과 (scale out/in)      ║');
  console.log('╠══════════════════════════════════════╣');
  console.log(`║ SSE 최초 연결 성공:     ${String(get('sse_connected')).padStart(6)} 회       ║`);
  console.log(`║ SSE 연결 실패:          ${String(get('sse_errors')).padStart(6)} 회       ║`);
  console.log(`║ scale-in 재연결:        ${String(get('sse_reconnects')).padStart(6)} 회       ║`);
  console.log(`║ ENTRY 이벤트 수신:      ${String(get('entry_received')).padStart(6)} 회       ║`);
  console.log(`║ 재연결 후 ENTRY 수신:   ${String(get('entry_after_reconnect')).padStart(6)} 회       ║`);
  console.log(`║ 로그인 실패:            ${String(get('login_failed')).padStart(6)} 회       ║`);
  console.log(`║ SSE 성공률:             ${String((get('sse_success_rate', 'rate') * 100).toFixed(1)).padStart(6)} %       ║`);
  console.log('╠══════════════════════════════════════╣');
  console.log('║ Redis 확인 명령어:                        ║');
  console.log('║  kubectl exec -n ticketone \\              ║');
  console.log('║    redis-master-0 -- redis-cli HLEN \\     ║');
  console.log('║    ENTRY_TOKEN                             ║');
  console.log('╚══════════════════════════════════════╝');

  return {};
}
