// k6 smoke load test for the customer-facing read paths of PowerBill GridIQ.
//
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e DEMO_PASSWORD=... \
//     grafana/k6:1.3.0 run - < tests/load/k6-smoke.js
//
// Requires a stack started with DEMO_MODE=true. Logins happen once in setup() to stay under the login rate limit.
import http from 'k6/http';
import { check, fail, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const PASSWORD = __ENV.DEMO_PASSWORD;
const CUSTOMERS = ['customer11', 'customer12', 'customer13', 'customer14', 'customer15'];

export const options = {
  scenarios: {
    customers: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '15s', target: Number(__ENV.K6_VUS || 25) },
        { duration: '45s', target: Number(__ENV.K6_VUS || 25) },
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{kind:read}': ['p(95)<250'],
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

export function setup() {
  if (!PASSWORD) fail('Set DEMO_PASSWORD to the stack demo password.');
  return CUSTOMERS.map((name) => {
    const login = http.post(`${BASE_URL}/api/v1/auth/login`,
      JSON.stringify({ email: `${name}@demo.powerbill.invalid`, password: PASSWORD }),
      { headers: { 'Content-Type': 'application/json' } });
    if (login.status !== 200) fail(`Login failed for ${name}: ${login.status}`);
    const token = json(login).accessToken;
    const meters = json(http.get(`${BASE_URL}/api/v1/consumers/me/meters`, { headers: { Authorization: `Bearer ${token}` } }));
    return { token, meterId: meters[0].id };
  });
}

export default function (sessions) {
  const session = sessions[(__VU + __ITER) % sessions.length];
  const params = { headers: { Authorization: `Bearer ${session.token}` }, tags: { kind: 'read' } };
  const responses = http.batch([
    ['GET', `${BASE_URL}/api/v1/bills/summary`, null, params],
    ['GET', `${BASE_URL}/api/v1/bills?status=OPEN&size=20`, null, params],
    ['GET', `${BASE_URL}/api/v1/analytics/consumer/overview?meterId=${session.meterId}`, null, params],
    ['GET', `${BASE_URL}/api/v1/ai/forecast/${session.meterId}`, null, params],
    ['GET', `${BASE_URL}/api/v1/notifications?limit=20`, null, params],
    ['GET', `${BASE_URL}/api/v1/complaints?size=20`, null, params],
  ]);
  for (const response of responses) {
    check(response, { 'status is 200': (r) => r.status === 200 });
  }
  sleep(1);
}
