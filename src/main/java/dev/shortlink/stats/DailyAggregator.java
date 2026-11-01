package dev.shortlink.stats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 每分钟把窗口内的数据刷进 link_daily（NFR-6：≤1 分钟可见）。
 * <p>
 * 两件事分开做，因为它们的性质完全不同：
 * <ul>
 *   <li><b>PV</b>：从 access_log 按 id 增量累加，**精确**，游标持久化在 MySQL；</li>
 *   <li><b>UV</b>：对"这一分钟有活动的短码"做 PFCOUNT，**近似**（0.81%）。</li>
 * </ul>
 * 只对 dirty 集合里的码做 PFCOUNT，避免每分钟对全站 10 万条链各算一次。
 */
@Component
public class DailyAggregator {

    private static final Logger log = LoggerFactory.getLogger(DailyAggregator.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String PV_CURSOR = "access_id";
    private static final long UV_BATCH = 500;

    private final StatsRepository stats;
    private final UvCounter uvCounter;

    public DailyAggregator(StatsRepository stats, UvCounter uvCounter) {
        this.stats = stats;
        this.uvCounter = uvCounter;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void aggregate() {
        try {
            aggregatePv();
        } catch (RuntimeException e) {
            log.error("pv aggregation failed", e);
        }
        try {
            aggregateUv();
        } catch (RuntimeException e) {
            log.error("uv aggregation failed", e);
        }
    }

    private void aggregatePv() {
        long lastId = stats.cursor(PV_CURSOR);
        long maxId = stats.maxAccessId();
        if (maxId <= lastId) {
            return;
        }
        // 用 maxId 划定窗口：新插入的行留给下一轮，避免同一行被算两次
        List<Map<String, Object>> rows = stats.pvSince(lastId, maxId);
        for (Map<String, Object> row : rows) {
            String code = (String) row.get("code");
            LocalDate date = ((java.sql.Date) row.get("d")).toLocalDate();
            long pv = ((Number) row.get("c")).longValue();
            stats.addPv(code, date, pv);
        }
        stats.saveCursor(PV_CURSOR, maxId);
        if (!rows.isEmpty()) {
            log.debug("pv aggregated: rows={}, upToId={}", rows.size(), maxId);
        }
    }

    private void aggregateUv() {
        LocalDate today = LocalDate.now(ZONE);
        for (LocalDate date : List.of(today, today.minusDays(1))) {
            while (true) {
                List<String> codes = uvCounter.drainDirty(date, UV_BATCH);
                if (codes.isEmpty()) {
                    break;
                }
                for (String code : codes) {
                    stats.setUv(code, date, uvCounter.estimate(code, date));
                }
                if (codes.size() < UV_BATCH) {
                    break;
                }
            }
        }
    }
}
