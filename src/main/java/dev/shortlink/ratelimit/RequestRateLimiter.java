package dev.shortlink.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.shortlink.config.ShortlinkProperties;
import dev.shortlink.redirect.RedisCircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * D3⑤ 的限流：保险丝，不是主防御（主防御是布隆）。
 * <p>
 * 两个阀门分工不同：
 * <ul>
 *   <li><b>按 IP</b>：精准打击单个刷子，不误伤别人；但挡不住分布式。</li>
 *   <li><b>全局</b>：保命开关，保证 MySQL 不被打穿。</li>
 * </ul>
 * 全局阈值必须**高于**设计峰值（5000），否则正常峰值波动就会自己触发限流。
 * <p>
 * <b>2026-11-01 改造：固定窗口 → 滑动窗口计数。</b>
 * 固定窗口在边界上最多放行 2 倍（0.999s 用光 200 个额度，1.001s 又满额）。
 * 对全局限流（8000/s）来说，2 倍意味着瞬时 16000 打到 MySQL——而它只扛得住 3500~4000。
 * <p>
 * 滑动窗口计数只多存一个"上一窗口的计数"，用已过时间的比例加权：
 * <pre>估算 = 上一窗计数 × (1 − 已过比例) + 本窗计数</pre>
 * 精度误差通常 &lt; 1/limit，内存仍是每个 key 三个整数。
 * <p>
 * <b>为什么不换令牌桶</b>：令牌桶允许"空闲攒额度、然后一次性突发"，
 * 而我们要的是硬上限——下游怕的是瞬时并发，不是平均值。
 * <p>
 * <b>为什么不用 Redis + Lua</b>：限流最需要工作的时刻恰恰是 Redis 熔断时，
 * 让限流器依赖 Redis 会形成逻辑死循环。单机约束下也不需要跨节点协调。
 */
@Component
public class RequestRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RequestRateLimiter.class);

    private final Cache<String, SlidingWindow> perIp;
    private final int perIpLimit;
    private final int globalLimit;
    private final int degradedGlobalLimit;
    private final RedisCircuitBreaker breaker;
    private final SlidingWindow global = new SlidingWindow();

    private long lastGlobalWarnSecond = 0;

    public RequestRateLimiter(ShortlinkProperties props, RedisCircuitBreaker breaker) {
        this.perIpLimit = props.rateLimit().perIpPerSecond();
        this.globalLimit = props.rateLimit().globalPerSecond();
        this.degradedGlobalLimit = props.rateLimit().degradedGlobalPerSecond();
        this.breaker = breaker;
        this.perIp = Caffeine.newBuilder()
                .maximumSize(props.rateLimit().maxTrackedIps())
                // 窗口只有 1 秒，10 秒没动静的 IP 就可以丢掉，防止攻击者用海量 IP 撑爆内存
                .expireAfterAccess(Duration.ofSeconds(10))
                .build();
    }

    public boolean tryAcquire(String clientIp) {
        long nowMillis = System.currentTimeMillis();
        // 缓存熔断 → 全部流量回源，此刻 MySQL 的承受力远低于正常峰值
        int effectiveGlobalLimit = breaker.isOpen() ? degradedGlobalLimit : globalLimit;

        if (!global.tryAcquire(nowMillis, effectiveGlobalLimit)) {
            long nowSecond = nowMillis / 1000L;
            if (lastGlobalWarnSecond != nowSecond) {
                lastGlobalWarnSecond = nowSecond;
                log.warn("global rate limit tripped: limit={}/s (degraded={})",
                        effectiveGlobalLimit, breaker.isOpen());
            }
            return false;
        }
        if (clientIp == null || clientIp.isBlank()) {
            return true;
        }
        return perIp.get(clientIp, key -> new SlidingWindow()).tryAcquire(nowMillis, perIpLimit);
    }

    /**
     * 滑动窗口计数器。只需要三个整数，O(1) 更新。
     */
    static final class SlidingWindow {

        private long windowStartSecond;
        private int previousCount;
        private int currentCount;

        synchronized boolean tryAcquire(long nowMillis, int limit) {
            long nowSecond = nowMillis / 1000L;

            if (windowStartSecond == 0) {
                windowStartSecond = nowSecond;
            }
            long gap = nowSecond - windowStartSecond;
            if (gap >= 2) {
                // 空闲超过一整秒：上一窗口也过期了，直接清零重来
                previousCount = 0;
                currentCount = 0;
                windowStartSecond = nowSecond;
            } else if (gap == 1) {
                // 正好跨过一个窗口：当前窗口降级为"上一窗口"
                previousCount = currentCount;
                currentCount = 0;
                windowStartSecond = nowSecond;
            }

            double elapsedFraction = (nowMillis - windowStartSecond * 1000L) / 1000.0;
            int estimated = (int) Math.floor(previousCount * (1.0 - elapsedFraction)) + currentCount;

            if (estimated >= limit) {
                return false;
            }
            currentCount++;
            return true;
        }
    }
}
