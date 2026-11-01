import http from 'k6/http';
import { check } from 'k6';

// 稳态压测：固定到达率，用来观察各组件 CPU 分布（找瓶颈）。
// COOKIE=1 时每个 VU 带一个固定的 vid cookie，模拟"回访用户"；
// 不带 cookie 时每个请求都是新访客，会触发 UUID.randomUUID()（SecureRandom，全局同步）。
const BASE = __ENV.BASE || 'http://shortlink-app:8080';
const WITH_COOKIE = __ENV.COOKIE === '1';
// CODE 指定时只打这一个短码（用于"故障注入 + 对账"这类需要精确计数的实验）
const FIXED_CODE = __ENV.CODE || '';
const codes = open('./codes.txt').split('\n').map((s) => s.trim()).filter((s) => s.length > 0);

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 800),
      timeUnit: '1s',
      duration: __ENV.DURATION || '40s',
      preAllocatedVUs: Number(__ENV.VUS || 400),
      maxVUs: Number(__ENV.VUS || 400),
    },
  },
  discardResponseBodies: true,
};

export default function () {
  const code = FIXED_CODE || codes[Math.floor(Math.random() * codes.length)];
  const params = { redirects: 0 };
  if (WITH_COOKIE) {
    params.headers = { Cookie: `vid=00000000-0000-4000-8000-${String(__VU).padStart(12, '0')}` };
  }
  const res = http.get(`${BASE}/${code}`, params);
  check(res, { '302': (r) => r.status === 302 });
}
