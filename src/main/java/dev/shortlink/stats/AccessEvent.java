package dev.shortlink.stats;

/**
 * 一次成功跳转。只记录"跳转成功"的事件——404 和 410 不计 PV（FR-3）。
 *
 * @param code      短码
 * @param epochMilli 发生时刻
 * @param visitor   P2 的 cookie 随机 ID，用于 UV 去重
 * @param referer   来源，可能为 null；入库前截断到 512
 */
public record AccessEvent(String code, long epochMilli, String visitor, String referer) {
}
