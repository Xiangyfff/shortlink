package dev.shortlink.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shortlink")
public record ShortlinkProperties(
        String baseUrl,
        Code code,
        Cache cache,
        Bloom bloom,
        Negative negative,
        RateLimit rateLimit,
        Mq mq,
        Counters counters,
        Retention retention,
        Stats stats
) {
    public record Code(int length, int maxAttempts) {
    }

    public record Cache(int localMaxEntries, long localTtlSeconds, long remoteTtlSeconds) {
    }

    /**
     * bits 必须是 2 的幂：位索引用位运算取模。
     * 1 &lt;&lt; 27 = 134,217,728 bit = 16 MB，1000 万条、7 次哈希下误判率约 0.18%。
     */
    public record Bloom(long bits, int hashes, String path, long dumpIntervalMs) {
    }

    public record Negative(int maxEntries, long ttlSeconds) {
    }

    public record RateLimit(int perIpPerSecond, int globalPerSecond, int degradedGlobalPerSecond,
                            int maxTrackedIps) {
    }

    public record Mq(String namesrv, String topic, String producerGroup, String consumerGroup) {
    }

    public record Counters(String host, int port) {
    }

    /**
     * @param accessLogDays 明细保留天数（D4：3 天，7 天对账窗口内够用）
     * @param tombstoneDays 墓碑保留天数。超过之后过期链接会从 410 变成 404
     * @param batchSize     每批删除行数（限速用）
     * @param pauseMs       批间隔（限速用）
     */
    public record Retention(int accessLogDays, int tombstoneDays, int batchSize, int pauseMs) {
    }

    /**
     * 只用于压测时的 A/B 归因：关掉之后跳转变成纯读路径，
     * 用同一负载对比 CPU，就能算出"统计事件"在热路径上占了多少。
     * 生产环境必须为 true。
     */
    public record Stats(boolean enabled, boolean consumerEnabled, boolean enqueueEnabled) {
    }
}
