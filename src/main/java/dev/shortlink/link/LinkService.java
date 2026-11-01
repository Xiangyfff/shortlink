package dev.shortlink.link;

import dev.shortlink.config.ShortlinkProperties;
import dev.shortlink.dedup.CodeBloom;
import dev.shortlink.dedup.NegativeCache;
import dev.shortlink.web.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Locale;

@Service
public class LinkService {

    private static final int MAX_URL_LENGTH = 2048;

    private final LinkRepository repository;
    private final ShortlinkProperties props;
    private final CodeBloom bloom;
    private final NegativeCache negativeCache;

    public LinkService(LinkRepository repository,
                       ShortlinkProperties props,
                       CodeBloom bloom,
                       NegativeCache negativeCache) {
        this.repository = repository;
        this.props = props;
        this.bloom = bloom;
        this.negativeCache = negativeCache;
    }

    public CreatedLink create(String rawUrl, Instant expireAt) {
        String longUrl = validate(rawUrl, expireAt);

        int length = props.code().length();
        int attempts = props.code().maxAttempts();
        Instant now = Instant.now();

        for (int attempt = 1; attempt <= attempts; attempt++) {
            String code = ShortCode.random(length);

            // D3-b：先 add 后 insert。这个顺序只会产生假阳性（无害），
            // 反过来的话崩溃会留下假阴性（有效短链变 404）。
            bloom.add(code);

            try {
                repository.insert(new Link(code, longUrl, now, expireAt));
                // 先查后建的情况下，这个码可能已经被负缓存记住了
                negativeCache.invalidate(code);
                return new CreatedLink(code, props.baseUrl() + "/" + code, longUrl, expireAt);
            } catch (DuplicateKeyException e) {
                // 7 位码在 1000 万存量下碰撞率 2.8e-6，几乎不会走到这里。
            }
        }
        throw new IllegalStateException("短码连续 " + attempts + " 次碰撞，检查随机源");
    }

    /**
     * AC-1 要求 Location 与原长 URL 完全一致，所以这里不做任何规范化
     * （不补 http://、不 trim、不重编码）。宁可拒绝，也不改写用户给的地址。
     */
    private String validate(String rawUrl, Instant expireAt) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw ApiException.badRequest("URL_REQUIRED", "url 不能为空");
        }
        if (rawUrl.length() > MAX_URL_LENGTH) {
            throw ApiException.badRequest("URL_TOO_LONG", "url 长度不能超过 " + MAX_URL_LENGTH);
        }
        if (!rawUrl.equals(rawUrl.trim())) {
            throw ApiException.badRequest("URL_HAS_SPACES", "url 首尾不能有空白字符");
        }

        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw ApiException.badRequest("URL_INVALID", "url 不是合法的 URI");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.toLowerCase(Locale.ROOT).equals("http")
                || scheme.toLowerCase(Locale.ROOT).equals("https"))) {
            throw ApiException.badRequest("URL_SCHEME", "只支持 http / https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw ApiException.badRequest("URL_NO_HOST", "url 缺少主机名");
        }

        if (expireAt != null && !expireAt.isAfter(Instant.now())) {
            throw ApiException.badRequest("EXPIRY_IN_PAST", "有效期必须晚于当前时间");
        }
        return rawUrl;
    }

    public record CreatedLink(String code, String shortUrl, String longUrl, Instant expireAt) {
    }
}
