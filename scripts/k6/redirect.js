import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// 用法（容器内跑，走 compose 内网）：
//   docker run --rm --network shortlink-v2_default \
//     -v "$PWD/scripts/k6:/scripts" -e BASE=http://shortlink-app:8080 \
//     grafana/k6 run /scripts/redirect.js
//
// CODES 是预先生成的"库里真实存在"的短码列表。
// 为什么要预生成：k6 里现算短码等于测不存在的码，那是另一个用例（404 攻击）。
const BASE = __ENV.BASE || 'http://shortlink-app:8080';
const codes = open('./codes.txt').split('\n').map((s) => s.trim()).filter((s) => s.length > 0);

const hit302 = new Counter('redirect_302');
const hitOther = new Counter('redirect_other');
const latency = new Trend('redirect_latency', true);

export const options = {
  scenarios: {
    redirect: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      stages: [
        { target: 500, duration: '30s' },
        { target: 1000, duration: '60s' },
        { target: 2000, duration: '60s' },
        { target: 2000, duration: '60s' },
        { target: 0, duration: '20s' },
      ],
      preAllocatedVUs: 300,
      maxVUs: 3000,
    },
  },
  discardResponseBodies: true,
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const code = codes[Math.floor(Math.random() * codes.length)];
  const res = http.get(`${BASE}/${code}`, { redirects: 0, tags: { name: 'redirect' } });
  latency.add(res.timings.duration);
  if (res.status === 302) {
    hit302.add(1);
  } else {
    hitOther.add(1);
  }
  check(res, { '302': (r) => r.status === 302 });
}
