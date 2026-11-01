package dev.shortlink.redirect;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.shortlink.config.ShortlinkProperties;
import dev.shortlink.dedup.CodeBloom;
import dev.shortlink.dedup.NegativeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * D2 = ② Caffeine(L1) + Redis(L2) + MySQL，D3 = ③ 布隆 + 负缓存。
 * <p>
 * 读路径顺序：L1 → Redis → 布隆 → 负缓存 → MySQL。
 * 前两层是缓存，后两层是"不存在的短码"的闸门。
 * <p>
 * <b>2026-11-01 补齐三个缓存经典问题的处理：</b>
 * <ul>
 *   <li><b>穿透</b>：布隆 + 负缓存 + 路由正则（早已有）；</li>
 *   <li><b>击穿</b>：{@link #loadSingleFlight} —— 同一个短码的并发未命中只查一次库；</li>
 *   <li><b>雪崩</b>：TTL 随机抖动（打散同时过期）+ {@link RedisCircuitBreaker}（Redis 整体故障时快速失败，
 *       并压低全局限流，保住 MySQL）。</li>
 * </ul>
 */
@Component
public class CachedRedirectResolver {

    private static final Logger log = LoggerFactory.getLogger(CachedRedirectResolver.class);

    private static final String KEY_PREFIX = "sl:";
    private static final char SEP = '|';
    /** 永久的短链用 '-' 表示 expireAt，避免和合法的 epoch 0 混淆。 */
    private static final String NO_EXPIRY = "-";

    /** TTL 抖动上限：把"同一批写入的 key 同时过期"摊开到 5 分钟里。 */
    private static final long TTL_JITTER_SECONDS = 300;
    /** single-flight 的等待上限：等不到就自己查，宁可多一次查询也不让请求卡死。 */
    private static final long IN_FLIGHT_WAIT_MS = 300;

    private final Cache<String, CachedLink> local;
    private final ConcurrentHashMap<String, CompletableFuture<Resolution>> inFlight = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final DatabaseResolver database;
    private final CodeBloom bloom;
    private final NegativeCache negativeCache;
    private final RedisCircuitBreaker breaker;
    private final long remoteTtlSeconds;

    /** 只统计"L1 未命中之后"的 Redis 命中情况——这是验证 L1 ⊆ Redis 的关键指标。 */
    private final java.util.concurrent.atomic.AtomicLong redisHits = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong redisMisses = new java.util.concurrent.atomic.AtomicLong();

    public CachedRedirectResolver(StringRedisTemplate redis,
                                  DatabaseResolver database,
                                  CodeBloom bloom,
                                  NegativeCache negativeCache,
                                  RedisCircuitBreaker breaker,
                                  ShortlinkProperties props) {
        this.redis = redis;
        this.database = database;
        this.bloom = bloom;
        this.negativeCache = negativeCache;
        this.breaker = breaker;
        this.remoteTtlSeconds = props.cache().remoteTtlSeconds();
        this.local = Caffeine.newBuilder()
                .maximumSize(props.cache().localMaxEntries())
                .expireAfterWrite(Duration.ofSeconds(props.cache().localTtlSeconds()))
                // 打开统计：D2 的核心论断是"L1 ⊆ Redis，所以 L1 不提升命中率"，
                // 但这句话一直是推理，从来没有实测数据支撑。打开之后每 60 秒打印一次。
                .recordStats()
                .build();
    }

    public Resolution resolve(String code) {
        CachedLink l1 = local.getIfPresent(code);
        if (l1 != null) {
            return l1.toResolution(Instant.now());
        }
        return loadSingleFlight(code);
    }

    /**
     * 击穿防护：同一个短码的并发未命中，只有第一个线程真的去查，其余等它的结果。
     * <p>
     * 为什么不用 Caffeine 的 {@code get(key, loader)}：它会把 loader 的返回值一并缓存，
     * 而我们要缓存的是"存在"而不是"不存在"——NotFound / Expired 不该进 L1
     * （进了就等于给 404 也加了 5 分钟 TTL）。
     */
    private Resolution loadSingleFlight(String code) {
        CompletableFuture<Resolution> mine = new CompletableFuture<>();
        CompletableFuture<Resolution> existing = inFlight.putIfAbsent(code, mine);
        if (existing != null) {
            try {
                return existing.get(IN_FLIGHT_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // 等不到就自己查——绝不因为别人的请求卡死自己
                return load(code);
            }
        }
        try {
            Resolution resolution = load(code);
            mine.complete(resolution);
            return resolution;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(code, mine);
        }
    }

    private Resolution load(String code) {
        Instant now = Instant.now();

        CachedLink l2 = readRemote(code);
        if (l2 != null) {
            local.put(code, l2);
            return l2.toResolution(now);
        }

        // 布隆：只有补齐完成后，"不存在"这个回答才可信。
        if (bloom.ready() && !bloom.mightContain(code)) {
            return new Resolution.NotFound();
        }

        // 负缓存：只承接布隆 1% 的误判，容量很小，不会挤占主缓存。
        if (negativeCache.isKnownMissing(code)) {
            return new Resolution.NotFound();
        }

        Resolution resolution;
        try {
            resolution = database.resolve(code);
        } catch (org.springframework.dao.DataAccessException e) {
            // MySQL 不可用（拿不到连接 / 超时 / 连接被掐断）。
            // 优雅的 503 比 500 好：调用方知道这是暂时的、可以重试。
            log.warn("database unavailable on redirect path: code={}", code, e);
            return new Resolution.Unavailable();
        }
        if (resolution instanceof Resolution.Found found) {
            CachedLink entry = new CachedLink(found.longUrl(), found.expireAt());
            local.put(code, entry);
            writeRemote(code, entry, now);
        } else if (resolution instanceof Resolution.NotFound) {
            negativeCache.markMissing(code);
        }
        // Expired 不写缓存：过期页不能变成缓存里的 302（AC-3）。
        return resolution;
    }

    private CachedLink readRemote(String code) {
        // 熔断中直接跳过 Redis：否则每个请求都要等一次超时，比"查库"慢得多
        if (!breaker.allowRequest()) {
            return null;
        }
        try {
            String raw = redis.opsForValue().get(KEY_PREFIX + code);
            breaker.onSuccess();
            if (raw == null) {
                redisMisses.incrementAndGet();
                return null;
            }
            redisHits.incrementAndGet();
            return CachedLink.decode(raw);
        } catch (RuntimeException e) {
            breaker.onFailure();
            log.warn("redis read failed, degrade to db: code={}", code, e);
            return null;
        }
    }

    private void writeRemote(String code, CachedLink entry, Instant now) {
        if (!breaker.allowRequest()) {
            return;
        }
        long ttl = ttlSeconds(entry, now);
        if (ttl <= 0) {
            return;
        }
        try {
            redis.opsForValue().set(KEY_PREFIX + code, entry.encode(), Duration.ofSeconds(ttl));
            breaker.onSuccess();
        } catch (RuntimeException e) {
            breaker.onFailure();
            log.warn("redis write failed, skip: code={}", code, e);
        }
    }

    /**
     * TTL 取 min(配置上限, 剩余有效期)，再减去一段随机抖动。
     * <p>
     * 抖动是防雪崩的关键：同一批写入的永久短链如果 TTL 完全相同，
     * 会在同一秒集体失效，那一秒的请求全部回源。
     * 减去而不是加上，是为了保证 TTL **永远不超过**剩余有效期（AC-3 的前提）。
     */
    private long ttlSeconds(CachedLink entry, Instant now) {
        long base = remoteTtlSeconds;
        if (entry.expireAt() != null) {
            long remaining = Duration.between(now, entry.expireAt()).toSeconds();
            base = Math.min(remoteTtlSeconds, remaining);
        }
        if (base <= 1) {
            return base;
        }
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.min(TTL_JITTER_SECONDS, base));
        return Math.max(1, base - jitter);
    }

    /**
     * 每 60 秒打印一次缓存命中率。
     * <p>
     * 要看的是三个数：
     * <ul>
     *   <li><b>L1 命中率</b>：有多少请求根本没到 Redis；</li>
     *   <li><b>L1 未命中后的 Redis 命中率</b>：如果 L1 ⊆ Redis 成立，这个数应该很高；</li>
     *   <li><b>最终回源率</b>：= (1 − L1命中率) × (1 − Redis命中率)，这才决定 MySQL 的压力。</li>
     * </ul>
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void logCacheStats() {
        com.github.benmanes.caffeine.cache.stats.CacheStats s = local.stats();
        long redisHit = redisHits.get();
        long redisMiss = redisMisses.get();
        long redisTotal = redisHit + redisMiss;
        double redisHitRate = redisTotal == 0 ? 0 : (double) redisHit / redisTotal;
        double sourceRate = (1 - s.hitRate()) * (1 - redisHitRate);
        log.info("cache stats: L1 hitRate={} (size={}, evictions={}) | Redis-after-L1 hitRate={} ({} calls) | DB fallback≈{}",
                String.format("%.3f", s.hitRate()), local.estimatedSize(), s.evictionCount(),
                String.format("%.3f", redisHitRate), redisTotal,
                String.format("%.4f", sourceRate));
    }

    record CachedLink(String longUrl, Instant expireAt) {

        String encode() {
            return (expireAt == null ? NO_EXPIRY : Long.toString(expireAt.toEpochMilli())) + SEP + longUrl;
        }

        static CachedLink decode(String raw) {
            int idx = raw.indexOf(SEP);
            if (idx <= 0) {
                return null;
            }
            String head = raw.substring(0, idx);
            Instant expireAt = NO_EXPIRY.equals(head) ? null : Instant.ofEpochMilli(Long.parseLong(head));
            return new CachedLink(raw.substring(idx + 1), expireAt);
        }

        Resolution toResolution(Instant now) {
            if (expireAt != null && !now.isBefore(expireAt)) {
                return new Resolution.Expired();
            }
            return new Resolution.Found(longUrl, expireAt);
        }
    }
}
