package dev.shortlink.dedup;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 补齐放到后台线程：补齐期间 {@code ready=false}，布隆只说"是"不说"否"，
 * 所以这段时间服务可以正常对外，不需要阻塞启动。
 */
@Component
public class BloomBackfill {

    private final CodeBloom bloom;

    public BloomBackfill(CodeBloom bloom) {
        this.bloom = bloom;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread thread = new Thread(bloom::backfill, "bloom-backfill");
        thread.setDaemon(true);
        thread.start();
    }
}
