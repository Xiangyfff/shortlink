package dev.shortlink.stats;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class StatsController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final StatsRepository stats;

    public StatsController(StatsRepository stats) {
        this.stats = stats;
    }

    /**
     * FR-3：按短码 + 时间范围查询。
     * pv 是精确值（来自明细），uv 是 HLL 估算值（误差 ≤0.81%），
     * 响应里把 source 写出来，避免调用方把两个数字当成同一种精度。
     */
    @GetMapping("/api/stats/{code}")
    public Map<String, Object> daily(@PathVariable String code,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate start = from == null ? today : from;
        LocalDate end = to == null ? today : to;

        List<Map<String, Object>> rows = stats.queryDaily(code, start, end);
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("from", start);
        body.put("to", end);
        body.put("uvAccuracy", "hyperloglog, error <= 0.81%");
        body.put("days", rows);
        return body;
    }
}
