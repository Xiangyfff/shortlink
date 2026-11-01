import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// AC-2 的压测版：持续发"格式合法但不存在"的 7 位随机码。
// 判定标准不是"多快"，而是**存储层 QPS 不随攻击量同比例增长**。
// 跑的时候另开一个窗口看 MySQL 的 Com_select 增量。
const BASE = __ENV.BASE || 'http://shortlink-app:8080';
const ALPHABET = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';

const notFound = new Counter('attack_404');
const limited = new Counter('attack_429');
const other = new Counter('attack_other');

function randomCode() {
  let s = '';
  for (let i = 0; i < 7; i++) {
    s += ALPHABET.charAt(Math.floor(Math.random() * ALPHABET.length));
  }
  return s;
}

export const options = {
  scenarios: {
    attack: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 500),
      timeUnit: '1s',
      duration: __ENV.DURATION || '60s',
      preAllocatedVUs: 200,
      maxVUs: 1000,
    },
  },
  discardResponseBodies: true,
  thresholds: {
    // 404 和 429 都算"被挡住了"，真正的失败是 5xx
    'http_req_failed{expected_response:true}': ['rate<0.01'],
  },
};

export default function () {
  const res = http.get(`${BASE}/${randomCode()}`, { redirects: 0, tags: { name: 'attack' } });
  if (res.status === 404) {
    notFound.add(1);
  } else if (res.status === 429) {
    limited.add(1);
  } else {
    other.add(1);
  }
  check(res, { 'not 5xx': (r) => r.status < 500 });
}
