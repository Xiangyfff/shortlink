package dev.shortlink.dedup;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.shortlink.config.ShortlinkProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * D3③ 的负缓存：只承接布隆 1% 误判那部分流量，所以它很小（默认 20 万条 / 30 秒）。
 * <p>
 * 为什么不做成 Redis 负缓存：那样它会承接 100% 的攻击流量，360MB 起步，
 * 还会在 allkeys-lru 下把热链挤出去（缓存污染）。放在进程内、容量封顶，问题就没了。
 */
@Component
public class NegativeCache {

    private final Cache<String, Boolean> cache;

    public NegativeCache(ShortlinkProperties props) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(props.negative().maxEntries())
                .expireAfterWrite(Duration.ofSeconds(props.negative().ttlSeconds()))
                .build();
    }

    public boolean isKnownMissing(String code) {
        return cache.getIfPresent(code) != null;
    }

    public void markMissing(String code) {
        cache.put(code, Boolean.TRUE);
    }

    /**
     * 新建短链时必须失效：否则"先查后建"的短码会在 TTL 内被负缓存挡住，变成 404。
     */
    public void invalidate(String code) {
        cache.invalidate(code);
    }
}
