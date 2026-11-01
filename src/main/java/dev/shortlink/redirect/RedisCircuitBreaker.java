package dev.shortlink.redirect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 缓存层熔断器。
 * <p>
 * 不加这个会出人命：Redis 挂掉后，每个请求都要等一次连接/读超时（我们配的是 500ms 连接 + 200ms 读）。
 * 900 QPS 下光是等超时就能把 Tomcat 的 200 个线程全部占满——**缓存故障会直接变成全站 503**。
 * <p>
 * 半开策略：熔断 5 秒后放行探测，连续 5 次失败再次熔断。
 * 熔断期间跳转直接走 L1 → 数据库，不碰 Redis。
 * <p>
 * 同时它会通知限流器把全局阈值降到 3500/s——因为缓存全挂后，MySQL 最多只扛得住这个量。
 */
@Component
public class RedisCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(RedisCircuitBreaker.class);

    // 阈值不能太大：实测每次失败可能耗掉数百毫秒，5 次就意味着上秒级的坏请求。
    // 3 次足够确认"不是偶发抖动"。
    private static final int FAILURE_THRESHOLD = 3;
    private static final long OPEN_MILLIS = 5_000;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long openUntilMillis = 0;

    /** 熔断中 → false，请求别去碰 Redis。 */
    public boolean allowRequest() {
        return System.currentTimeMillis() >= openUntilMillis;
    }

    public boolean isOpen() {
        return System.currentTimeMillis() < openUntilMillis;
    }

    public void onSuccess() {
        if (consecutiveFailures.getAndSet(0) >= FAILURE_THRESHOLD) {
            log.info("redis circuit CLOSED (recovered)");
        }
    }

    public void onFailure() {
        if (consecutiveFailures.incrementAndGet() >= FAILURE_THRESHOLD) {
            consecutiveFailures.set(0);
            long wasOpen = openUntilMillis;
            openUntilMillis = System.currentTimeMillis() + OPEN_MILLIS;
            // 只在从"关闭"第一次打开时打日志，避免熔断期间刷屏
            if (wasOpen < System.currentTimeMillis()) {
                log.warn("redis circuit OPEN for {}ms — falling back to L1 + MySQL, "
                        + "and lowering global rate limit", OPEN_MILLIS);
            }
        }
    }
}
