package dev.shortlink.web;

import dev.shortlink.ratelimit.RequestRateLimiter;
import dev.shortlink.redirect.CachedRedirectResolver;
import dev.shortlink.redirect.Resolution;
import dev.shortlink.stats.AccessEvent;
import dev.shortlink.stats.AccessEventPublisher;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@RestController
public class RedirectController {

    private static final String VISITOR_COOKIE = "vid";

    private final CachedRedirectResolver resolver;
    private final RequestRateLimiter rateLimiter;
    private final AccessEventPublisher publisher;

    public RedirectController(CachedRedirectResolver resolver,
                              RequestRateLimiter rateLimiter,
                              AccessEventPublisher publisher) {
        this.resolver = resolver;
        this.rateLimiter = rateLimiter;
        this.publisher = publisher;
    }

    /**
     * 路径变量带正则约束：格式不合法的短码在路由层就 404，一次存储查询都不产生。
     * 这是 AC-2 里最便宜的那部分防御（挡不住格式合法的随机码，那部分归 D3 的布隆）。
     */
    @GetMapping("/{code:[0-9a-zA-Z]{7}}")
    public void redirect(@PathVariable String code,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {

        // D3⑤：保险丝。挡在一切存储查询之前，所以被限的请求不会碰 Redis 也不会碰 MySQL。
        // 用 getRemoteAddr 而不是 X-Forwarded-For——没有代理层，信 XFF 等于给攻击者留后门。
        if (!rateLimiter.tryAcquire(request.getRemoteAddr())) {
            response.setStatus(429);
            response.setHeader("Retry-After", "1");
            response.setHeader("Cache-Control", "no-store");
            response.setContentType(MediaType.TEXT_HTML_VALUE + ";charset=UTF-8");
            response.getWriter().write(Pages.TOO_MANY_REQUESTS);
            return;
        }

        String visitor = resolveVisitor(request, response);

        Resolution resolution = resolver.resolve(code);
        // AC-3：过期页/404 页都不允许被浏览器或中间代理缓存
        response.setHeader("Cache-Control", "no-store");

        if (resolution instanceof Resolution.Found found) {
            // NFR-2：这里只有一次**异步** send，不等待、不写主库。
            // Kafka 不可用时最多多花 max.block.ms(100ms) 然后丢弃这一条（NFR-8）。
            publisher.publish(new AccessEvent(code, System.currentTimeMillis(), visitor,
                    request.getHeader("Referer")));

            // 用 setHeader 而不是 sendRedirect：后者会重编码 URL，破坏 AC-1 的"完全一致"
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader("Location", found.longUrl());
            return;
        }

        boolean expired = resolution instanceof Resolution.Expired;
        if (resolution instanceof Resolution.Unavailable) {
            // 存储层暂时不可用：明确告诉调用方"可以重试"，而不是 500 或 404
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", "2");
            response.setContentType(MediaType.TEXT_HTML_VALUE + ";charset=UTF-8");
            response.getWriter().write(Pages.UNAVAILABLE);
            return;
        }
        response.setStatus(expired ? HttpServletResponse.SC_GONE : HttpServletResponse.SC_NOT_FOUND);
        response.setContentType(MediaType.TEXT_HTML_VALUE + ";charset=UTF-8");
        response.getWriter().write(expired ? Pages.EXPIRED : Pages.NOT_FOUND);
    }

    /**
     * P2 = cookie 随机 ID。只用于 UV 去重，不写任何设备指纹。
     * <p>
     * <b>2026-11-01 修正：这里原本用 {@code UUID.randomUUID()}，是个真 bug。</b>
     * JFR 采样发现它落在 {@code sun.security.provider.SecureRandom.engineNextBytes} 的
     * 全局同步锁上——200 个 Tomcat 线程抢同一把锁，直接把首批访客的请求串行化。
     * 实测：100% 新访客时 med 20.8ms / p95 72ms，带 cookie 时 med 0.9ms / p95 23ms。
     * <p>
     * 这个 ID 只是"浏览器里的一个不透明标记"，**不是安全令牌**，不需要密码学随机性。
     * 换成 ThreadLocalRandom（每个线程一个实例，无锁）即可，格式仍是标准 UUID v4。
     */
    private static String newVisitorId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long most = (random.nextLong() & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L;
        long least = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(most, least).toString();
    }

    private String resolveVisitor(HttpServletRequest request, HttpServletResponse response) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (VISITOR_COOKIE.equals(cookie.getName())
                        && cookie.getValue() != null
                        && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
        }
        String visitor = newVisitorId();
        Cookie cookie = new Cookie(VISITOR_COOKIE, visitor);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(365 * 24 * 3600);
        response.addCookie(cookie);
        return visitor;
    }
}
