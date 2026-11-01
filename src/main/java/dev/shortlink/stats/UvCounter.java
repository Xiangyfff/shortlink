package dev.shortlink.stats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * D5 = ③ HyperLogLog，落在**计数实例**（noeviction）上。
 * <p>
 * 日期的唯一来源是**事件时间**，不是消费时间——否则 23:59:59 的点击会被算进第二天。
 */
@Component
public class UvCounter {

    private static final Logger log = LoggerFactory.getLogger(UvCounter.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 明细保留 3 天，HLL 留 4 天，保证"明细还在时 UV 也还在"。 */
    private static final Duration TTL = Duration.ofDays(4);

    private final StringRedisTemplate counterRedis;

    public UvCounter(@Qualifier("counterRedisTemplate") StringRedisTemplate counterRedis) {
        this.counterRedis = counterRedis;
    }

    public static LocalDate dateOf(long epochMilli) {
        return Instant.ofEpochMilli(epochMilli).atZone(ZONE).toLocalDate();
    }

    /**
     * 尽力而为：失败只记日志，不影响跳转也不影响明细落库（NFR-8 允许统计少量丢失）。
     */
    public void record(String code, long epochMilli, String visitor) {
        LocalDate date = dateOf(epochMilli);
        try {
            counterRedis.opsForHyperLogLog().add(hllKey(date, code), visitor);
            Long added = counterRedis.opsForSet().add(dirtyKey(date), code);
            if (added != null && added > 0) {
                // 每个短码每天只设一次 TTL，不在热路径上每次都发 EXPIRE
                counterRedis.expire(hllKey(date, code), TTL);
            }
        } catch (RuntimeException e) {
            log.warn("uv counter failed: code={}, date={}", code, date, e);
        }
    }

    public long estimate(String code, LocalDate date) {
        Long size = counterRedis.opsForHyperLogLog().size(hllKey(date, code));
        return size == null ? 0 : size;
    }

    /** 取出"这一分钟有活动的短码"，取走即删除，避免重复 PFCOUNT。 */
    public List<String> drainDirty(LocalDate date, long count) {
        List<String> codes = counterRedis.opsForSet().pop(dirtyKey(date), count);
        return codes == null ? List.of() : codes;
    }

    private static String hllKey(LocalDate date, String code) {
        return "uv:" + DAY.format(date) + ":" + code;
    }

    private static String dirtyKey(LocalDate date) {
        return "uvdirty:" + DAY.format(date);
    }
}
