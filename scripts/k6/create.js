import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

// 创建吞吐（NFR-3：≥1000 QPS）。返回 201 才算成功。
const BASE = __ENV.BASE || 'http://shortlink-app:8080';
const createLatency = new Trend('create_latency', true);

export const options = {
  scenarios: {
    create: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      stages: [
        { target: 200, duration: '20s' },
        { target: 500, duration: '40s' },
        { target: 0, duration: '10s' },
      ],
      preAllocatedVUs: 200,
      maxVUs: 1000,
    },
  },
  discardResponseBodies: true,
};

export default function () {
  const body = JSON.stringify({
    url: `https://example.com/create/${__VU}/${__ITER}`,
  });
  const res = http.post(`${BASE}/api/links`, body, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'create' },
  });
  createLatency.add(res.timings.duration);
  check(res, { '201': (r) => r.status === 201 });
}
