package dev.shortlink.stats;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Repository
public class AccessLogRepository {

    private static final Logger log = LoggerFactory.getLogger(AccessLogRepository.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int REFERER_MAX = 512;
    private static final String UNIQUE_INDEX = "uk_code_time_visitor";

    private final JdbcTemplate jdbc;

    public AccessLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 幂等消费的前提：同一 (短码, 时间, 访客) 只能有一行。
     * <p>
     * <b>2026-11-01 故障注入发现的问题：</b>MySQL 停机 30 秒后，54,001 次跳转产生了
     * 54,364 行明细——626 条被写了两次，263 条丢失。
     * 重复的根因是 {@code jdbc.batchUpdate} 部分成功后抛异常，消费者返回 RECONSUME_LATER
     * 重投**整批**，已经写进去的那部分又写了一遍。
     * <b>at-least-once 投递必须配幂等消费端</b>，否则重试就是放大错误。
     */
    @PostConstruct
    public void ensureUniqueIndex() {
        Integer exists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'access_log' AND index_name = ?
                """, Integer.class, UNIQUE_INDEX);
        if (exists != null && exists > 0) {
            return;
        }
        try {
            jdbc.execute("ALTER TABLE access_log ADD UNIQUE KEY " + UNIQUE_INDEX
                    + " (code, visited_at, visitor)");
            log.info("created unique index {} on access_log (duplicate suppression enabled)", UNIQUE_INDEX);
        } catch (RuntimeException e) {
            // 表里已有重复数据时建不出来。不阻塞启动，但要明确告警：
            // 这意味着消费端目前**不幂等**，故障重投会产生重复计数。
            log.warn("failed to create unique index {} — duplicate suppression DISABLED. "
                    + "Run the dedupe script in scripts/ then restart.", UNIQUE_INDEX, e);
        }
    }

    /**
     * 批量插入。用 INSERT IGNORE：重复的 (code, visited_at, visitor) 直接跳过，
     * 让 MQ 的 at-least-once 重投变成幂等操作。
     * <p>
     * 已知取舍：同一访客在同一毫秒点同一个短码两次，第二次会被去重。
     * 在微秒级时间戳下这几乎不可能发生，而它换来的是"重投不放大"。
     */
    public void insertBatch(List<AccessEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT IGNORE INTO access_log (code, visited_at, visitor, referer) VALUES (?, ?, ?, ?)",
                events,
                events.size(),
                (ps, event) -> {
                    ps.setString(1, event.code());
                    ps.setObject(2, LocalDateTime.ofInstant(Instant.ofEpochMilli(event.epochMilli()), ZONE));
                    ps.setString(3, event.visitor());
                    String referer = event.referer();
                    ps.setString(4, referer == null ? null
                            : referer.length() > REFERER_MAX ? referer.substring(0, REFERER_MAX) : referer);
                });
    }
}
