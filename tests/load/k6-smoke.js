// k6 load tests for PowerBill GridIQ.
//
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e DEMO_PASSWORD=... \
//     grafana/k6:1.3.0 run - < tests/load/k6-smoke.js
//
// Profiles (K6_PROFILE):
//   smoke (default)  25 virtual users on the customer read paths, plus forecasts at a steady rate.
//   mixed            100 virtual users on the read paths, plus forecasts, Energy Copilot chat and the operations
//                    executive overview, each at a fixed arrival rate.
//
// Requires a stack started with DEMO_MODE=true. Logins happen once in setup() to stay under the login rate limit.
// Forecasts (30 a minute) and the Copilot (20 a minute) are rate-limited per user, so those scenarios use fixed
// arrival rates spread across all 15 demo customers instead of hammering a few accounts.
import http from 'k6/http';
import { check, fail, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const PASSWORD = __ENV.DEMO_PASSWORD;
const PROFILE = __ENV.K6_PROFILE || 'smoke';
const MIXED = PROFILE === 'mixed';
const READ_VUS = Number(__ENV.K6_VUS || (MIXED ? 100 : 25));
const CUSTOMERS = Array.from({ length: 15 }, (_, index) => `customer${String(index + 1).padStart(2, '0')}`);

const scenarios = {
  reads: {
    executor: 'ramping-vus',
    exec: 'reads',
    startVUs: 1,
    stages: [
      { duration: '15s', target: READ_VUS },
      { duration: '45s', target: READ_VUS },
      { duration: '10s', target: 0 },
    ],
  },
  forecasts: {
    executor: 'constant-arrival-rate',
    exec: 'forecast',
    rate: MIXED ? 4 : 2, // per second across 15 customers: well under 30 a minute each
    timeUnit: '1s',
    duration: '70s',
    preAllocatedVUs: 10,
  },
};
if (MIXED) {
  scenarios.copilot = {
    executor: 'constant-arrival-rate',
    exec: 'copilot',
    rate: 60, // a minute across 15 customers: 4 a minute each, under the limit of 20
    timeUnit: '1m',
    duration: '70s',
    preAllocatedVUs: 10,
  };
  scenarios.operations = {
    executor: 'constant-arrival-rate',
    exec: 'operations',
    rate: 2,
    timeUnit: '1s',
    duration: '70s',
    preAllocatedVUs: 5,
  };
}

export const options = {
  scenarios,
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{kind:read}': ['p(95)<250'],
    'http_req_duration{kind:forecast}': ['p(95)<500'],
    ...(MIXED ? {
      'http_req_duration{kind:assistant}': ['p(95)<2000'],
      'http_req_duration{kind:operations}': ['p(95)<250'],
    } : {}),
    checks: ['rate>0.99'],
  },
};

function json(response) {
  try {
    return response.json();
  } catch (error) {
    return null;
  }
}

function login(email) {
  const response = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({ email, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } });
  if (response.status !== 200) fail(`Login failed for ${email}: ${response.status}`);
  return json(response).accessToken;
}

export function setup() {
  if (!PASSWORD) fail('Set DEMO_PASSWORD to the stack demo password.');
  const customers = CUSTOMERS.map((name) => {
    const token = login(`${name}@demo.powerbill.invalid`);
    const meters = json(http.get(`${BASE_URL}/api/v1/consumers/me/meters`,
      { headers: { Authorization: `Bearer ${token}` } }));
    return { token, meterId: meters[0].id };
  });
  return { customers, staff: MIXED ? login('billing@demo.powerbill.invalid') : null };
}

function pick(data) {
  return data.customers[(__VU + __ITER) % data.customers.length];
}

function auth(token, kind) {
  return { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, tags: { kind } };
}

export function reads(data) {
  const session = pick(data);
  const params = auth(session.token, 'read');
  const responses = http.batch([
    ['GET', `${BASE_URL}/api/v1/bills/summary`, null, params],
    ['GET', `${BASE_URL}/api/v1/bills?status=OPEN&size=20`, null, params],
    ['GET', `${BASE_URL}/api/v1/analytics/consumer/overview?meterId=${session.meterId}`, null, params],
    ['GET', `${BASE_URL}/api/v1/notifications?limit=20`, null, params],
    ['GET', `${BASE_URL}/api/v1/complaints?size=20`, null, params],
  ]);
  for (const response of responses) {
    check(response, { 'read status is 200': (r) => r.status === 200 });
  }
  sleep(1);
}

export function forecast(data) {
  const session = pick(data);
  const response = http.get(`${BASE_URL}/api/v1/ai/forecast/${session.meterId}`, auth(session.token, 'forecast'));
  check(response, { 'forecast status is 200': (r) => r.status === 200 });
}

export function copilot(data) {
  const session = pick(data);
  const response = http.post(`${BASE_URL}/api/v1/ai/copilot/chat`,
    JSON.stringify({ message: 'Why did my bill change?', meterId: session.meterId, history: [] }),
    auth(session.token, 'assistant'));
  check(response, { 'copilot status is 200': (r) => r.status === 200 });
}

export function operations(data) {
  const response = http.get(`${BASE_URL}/api/v1/ops/analytics/executive`, auth(data.staff, 'operations'));
  check(response, { 'executive status is 200': (r) => r.status === 200 });
}
