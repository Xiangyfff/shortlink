package dev.shortlink.web;

public final class Pages {

    public static final String NOT_FOUND = """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <title>链接不存在</title></head><body>
            <h1>链接不存在</h1>
            <p>这个短链不存在。</p>
            </body></html>
            """;

    public static final String EXPIRED = """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <title>链接已过期</title></head><body>
            <h1>链接已过期</h1>
            <p>这个短链已经超过有效期。</p>
            </body></html>
            """;

    public static final String TOO_MANY_REQUESTS = """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <title>请求过于频繁</title></head><body>
            <h1>请求过于频繁</h1>
            <p>请稍后再试。</p>
            </body></html>
            """;

    public static final String UNAVAILABLE = """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <title>服务暂时不可用</title></head><body>
            <h1>服务暂时不可用</h1>
            <p>请稍后重试。</p>
            </body></html>
            """;

    private Pages() {
    }
}
