package dev.shortlink.redirect;

/**
 * 跳转解析结果。四种状态互斥；NotFound 不区分"不存在"和"已删除"（FR-2）。
 * <p>
 * Found 携带 expireAt：缓存层要用它算 TTL，读时也要靠它判过期——
 * 这样即使 Redis 的 key 被 LRU 提前驱逐，也不会把有效短链误判成过期（D7 的坑）。
 */
public sealed interface Resolution {

    record Found(String longUrl, java.time.Instant expireAt) implements Resolution {
    }

    record NotFound() implements Resolution {
    }

    record Expired() implements Resolution {
    }

    /**
     * 存储层暂时不可用（连接池拿不到连接、超时等）。
     * <p>
     * 必须和 404 区分开：告诉调用方"现在不行"，而不是"这个短链不存在"。
     * 前者可以重试，后者不能——把可恢复的故障伪装成永久事实是最糟的降级。
     */
    record Unavailable() implements Resolution {
    }
}
