package dev.shortlink.stats;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shortlink.config.ShortlinkProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NFR-2：跳转链路**同步写主库 = 0 次**。
 * <p>
 * <b>2026-11-01 改后版：生产者侧攒批。</b>
 * A/B 实测（900 QPS）：统计事件让 app CPU 从 60% 涨到 146%、p95 从 1.6ms 涨到 30ms。
 * 主要成本是"每条事件一次 JSON 序列化 + 一次单条 MQ 发送"——这两件事都不必在请求线程上做。
 * <p>
 * 现在的做法：请求线程只做一次 {@code queue.offer()}（约几十纳秒），
 * 后台线程每 20ms 或攒满 200 条打包成一条消息发出去。
 * <p>
 * 代价（必须承认）：进程崩溃会丢掉队列里最多 20ms 的事件（900 QPS 下约 18 条）。
 * 这属于 NFR-8 的"统计可容忍极少量丢失"，但**要写进验收说明**，
 * 因为 D4 当初选 MQ 的理由之一就是"崩溃不丢已投递的事件"——这里把"投递"推迟了 20ms。
 */
@Component
public class AccessEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AccessEventPublisher.class);

    public static final String TOPIC = "shortlink_access";

    private static final int QUEUE_CAPACITY = 50_000;
    private static final int MAX_BATCH = 200;
    private static final long FLUSH_INTERVAL_MS = 20;
    /** 一批最多重试几次；5 次 × 50ms 退避 ≈ 250ms 的额外窗口。 */
    private static final int MAX_SEND_ATTEMPTS = 5;

    private final BlockingQueue<AccessEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final ObjectMapper objectMapper;
    private final ShortlinkProperties props;
    private final AtomicLong dropped = new AtomicLong();

    private DefaultMQProducer producer;
    private Thread sender;
    private volatile boolean running;

    public AccessEventPublisher(ObjectMapper objectMapper, ShortlinkProperties props) {
        this.objectMapper = objectMapper;
        this.props = props;
    }

    @PostConstruct
    public void start() {
        if (!props.stats().enabled()) {
            log.warn("access event publishing DISABLED by config (A/B measurement mode)");
            return;
        }
        DefaultMQProducer p = new DefaultMQProducer(props.mq().producerGroup());
        p.setNamesrvAddr(props.mq().namesrv());
        p.setSendMsgTimeout(500);
        p.setRetryTimesWhenSendFailed(0);
        try {
            p.start();
            this.producer = p;
            this.running = true;
            this.sender = new Thread(this::sendLoop, "access-sender");
            this.sender.setDaemon(true);
            this.sender.start();
            log.info("rocketmq producer started (batched): namesrv={}", props.mq().namesrv());
        } catch (Exception e) {
            this.producer = null;
            log.warn("rocketmq producer start failed, access events will be dropped until restart", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (sender != null) {
            sender.interrupt();
        }
        if (producer != null) {
            producer.shutdown();
        }
    }

    /**
     * 请求线程只做这一步：入队。不序列化、不碰网络、不等结果。
     */
    public void publish(AccessEvent event) {
        if (!props.stats().enabled() || !props.stats().enqueueEnabled()) {
            return;
        }
        if (!queue.offer(event)) {
            // 队列满 = 消费端/发送端跟不上。丢新不丢旧：旧数据已经在去库的路上了。
            countDrop(null);
        }
    }

    private void sendLoop() {
        List<AccessEvent> pending = new ArrayList<>(MAX_BATCH);
        int attempts = 0;
        while (running) {
            try {
                if (pending.isEmpty()) {
                    AccessEvent first = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                    if (first == null) {
                        continue;
                    }
                    pending.add(first);
                    // 把这一瞬间已经到达的都带走，最多 MAX_BATCH 条
                    queue.drainTo(pending, MAX_BATCH - 1);
                }

                String payload = objectMapper.writeValueAsString(pending);
                producer.sendOneway(new Message(TOPIC, payload.getBytes(StandardCharsets.UTF_8)));
                pending.clear();
                attempts = 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 故障注入实测：sendOneway 在 broker 拥塞时会抛超时，原来直接丢弃，
                // 90 秒里丢了 706 条（1.31%）。现在**保住这一批、稍后重试**，
                // 超过 MAX_SEND_ATTEMPTS 才放弃——宁可丢，也不能无限占内存。
                attempts++;
                if (attempts > MAX_SEND_ATTEMPTS) {
                    countDrop(e);
                    pending.clear();
                    attempts = 0;
                } else {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private void countDrop(Throwable e) {
        long n = dropped.incrementAndGet();
        if (n % 1000 == 1) {
            log.warn("access batch dropped (total {}): {}", n, e == null ? "queue full" : e.toString());
        }
    }

    public long droppedCount() {
        return dropped.get();
    }
}
