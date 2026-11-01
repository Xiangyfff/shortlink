package dev.shortlink.redirect;

import dev.shortlink.link.Link;
import dev.shortlink.link.LinkRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 唯一真相源。所有缓存未命中最终都落到这里。
 */
@Component
public class DatabaseResolver {

    private final LinkRepository repository;

    public DatabaseResolver(LinkRepository repository) {
        this.repository = repository;
    }

    public Resolution resolve(String code) {
        Link link = repository.findByCode(code);
        if (link == null) {
            // 主表没有，再问墓碑表：曾经存在且已过期 → 过期页（AC-3）；
            // 墓碑也没有 → 真的不存在（FR-2，且不区分"已删除"）。
            Instant archivedExpireAt = repository.findArchivedExpireAt(code);
            return archivedExpireAt == null ? new Resolution.NotFound() : new Resolution.Expired();
        }
        if (link.isExpiredAt(Instant.now())) {
            return new Resolution.Expired();
        }
        return new Resolution.Found(link.longUrl(), link.expireAt());
    }
}
