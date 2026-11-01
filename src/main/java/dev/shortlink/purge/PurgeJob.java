package dev.shortlink.purge;

import dev.shortlink.config.ShortlinkProperties;
import dev.shortlink.link.Link;
import dev.shortlink.link.LinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * D7 = ③ 定时扫描 + Redis TTL。这里负责"回收"，判定仍在读路径上（不能混）。
 * <p>
 * 三件事，都**限速**（每批 N 行 + sleep），否则大批量 DELETE 会把 B+ 树页合并和 undo 膨胀
 * 一次性全压给前台的跳转查询（§1.1 那笔账里 MySQL 本来就没余量）。
 * <p>
 * 过期链接不是直接删掉，而是搬进 {@code link_expired} 墓碑表：
 * <b>AC-3 要求"过期短码返回过期页"，删干净了就只答得出 404。</b>
 * 墓碑每行约 24 字节，对比主表 500 字节是 1/20，这是"同时满足 AC-3 和空间回收"的最小代价。
 */
@Component
public class PurgeJob {

    private static final Logger log = LoggerFactory.getLogger(PurgeJob.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final LinkRepository links;
    private final JdbcTemplate jdbc;
    private final ShortlinkProperties props;

    public PurgeJob(LinkRepository links, JdbcTemplate jdbc, ShortlinkProperties props) {
        this.links = links;
        this.jdbc = jdbc;
        this.props = props;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void purge() {
        try {
            archiveExpiredLinks();
        } catch (RuntimeException e) {
            log.error("archive expired links failed", e);
        }
        try {
            purgeOldAccessLog();
        } catch (RuntimeException e) {
            log.error("purge access_log failed", e);
        }
        try {
            purgeOldTombstones();
        } catch (RuntimeException e) {
            log.error("purge tombstones failed", e);
        }
    }

    /** 过期链接：主表 → 墓碑表。用 INSERT IGNORE 保证"搬一半崩了"也能幂等重跑。 */
    private void archiveExpiredLinks() {
        int batch = props.retention().batchSize();
        int moved = 0;
        while (true) {
            List<Link> expired = links.findExpiredBatch(batch);
            if (expired.isEmpty()) {
                break;
            }
            links.archive(expired);
            links.deleteByCodes(expired.stream().map(Link::code).toList());
            moved += expired.size();
            if (expired.size() < batch) {
                break;
            }
            sleep();
        }
        if (moved > 0) {
            log.info("archived expired links: {}", moved);
        }
    }

    /** D4：明细保留 3 天。日报表永久保留，所以删明细不影响既有报表。 */
    private void purgeOldAccessLog() {
        LocalDateTime cutoff = LocalDateTime.now(ZONE).minusDays(props.retention().accessLogDays());
        int batch = props.retention().batchSize();
        long deleted = 0;
        while (true) {
            int n = jdbc.update("DELETE FROM access_log WHERE visited_at < ? LIMIT ?", cutoff, batch);
            deleted += n;
            if (n < batch) {
                break;
            }
            sleep();
        }
        if (deleted > 0) {
            log.info("purged access_log rows older than {}: {}", cutoff, deleted);
        }
    }

    /** 墓碑本身也要回收，否则它只是把"永久占空间"换了个地方。 */
    private void purgeOldTombstones() {
        LocalDateTime cutoff = LocalDateTime.now(ZONE).minusDays(props.retention().tombstoneDays());
        int batch = props.retention().batchSize();
        long deleted = 0;
        while (true) {
            int n = jdbc.update("DELETE FROM link_expired WHERE expire_at < ? LIMIT ?", cutoff, batch);
            deleted += n;
            if (n < batch) {
                break;
            }
            sleep();
        }
        if (deleted > 0) {
            log.info("purged tombstones older than {}: {}", cutoff, deleted);
        }
    }

    private void sleep() {
        try {
            Thread.sleep(props.retention().pauseMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
