package dev.shortlink.stats;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class StatsRepository {

    private final JdbcTemplate jdbc;

    public StatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 游标表。不能放 Redis：计数实例是 noeviction 但无持久化，重启就丢游标，
     * 一丢就会把 access_log 从头再累加一遍，报表直接翻倍。
     * 用 IF NOT EXISTS 建表，是为了让老数据卷（已经初始化过 schema）也能跑起来。
     */
    @PostConstruct
    public void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS job_cursor (
                    name  VARCHAR(32) NOT NULL,
                    value BIGINT      NOT NULL,
                    PRIMARY KEY (name)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """);
    }

    public long cursor(String name) {
        List<Long> rows = jdbc.queryForList("SELECT value FROM job_cursor WHERE name = ?", Long.class, name);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    public void saveCursor(String name, long value) {
        jdbc.update("""
                INSERT INTO job_cursor (name, value) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE value = VALUES(value)
                """, name, value);
    }

    public long maxAccessId() {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM access_log", Long.class);
        return max == null ? 0L : max;
    }

    /**
     * PV 走明细增量，**精确**；UV 走 HLL 估算，**近似**。
     * 两个数字来源不同，这是 AC-7 唯一需要书面解释的差值。
     */
    public List<Map<String, Object>> pvSince(long fromId, long toId) {
        return jdbc.queryForList("""
                SELECT code, DATE(visited_at) AS d, COUNT(*) AS c
                FROM access_log
                WHERE id > ? AND id <= ?
                GROUP BY code, DATE(visited_at)
                """, fromId, toId);
    }

    public void addPv(String code, LocalDate date, long pv) {
        jdbc.update("""
                INSERT INTO link_daily (code, stat_date, pv, uv) VALUES (?, ?, ?, 0) AS new
                ON DUPLICATE KEY UPDATE pv = link_daily.pv + new.pv
                """, code, date, pv);
    }

    /**
     * UV 只增不减（GREATEST）：计数实例重启会让 HLL 从零开始，
     * 如果直接覆盖，报表上的 UV 会突然变小——那比"暂时不涨"糟糕得多。
     */
    public void setUv(String code, LocalDate date, long uv) {
        jdbc.update("""
                INSERT INTO link_daily (code, stat_date, pv, uv) VALUES (?, ?, 0, ?) AS new
                ON DUPLICATE KEY UPDATE uv = GREATEST(link_daily.uv, new.uv)
                """, code, date, uv);
    }

    public List<Map<String, Object>> queryDaily(String code, LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                SELECT stat_date AS statDate, pv, uv
                FROM link_daily
                WHERE code = ? AND stat_date BETWEEN ? AND ?
                ORDER BY stat_date
                """, code, from, to);
    }
}
