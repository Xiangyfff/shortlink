package dev.shortlink.link;

import java.time.Instant;

/**
 * 短链映射。expireAt 为 null 表示永久。
 */
public record Link(String code, String longUrl, Instant createdAt, Instant expireAt) {

    /**
     * 过期判定。注意：这是"判定"，与 D7 的"回收"无关——
     * 判定必须在读路径上做，否则 AC-3（过期页不得被缓存成 302）必破。
     */
    public boolean isExpiredAt(Instant now) {
        return expireAt != null && !now.isBefore(expireAt);
    }
}
