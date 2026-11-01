package dev.shortlink.stats;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shortlink.config.ShortlinkProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * D4 的消费端：批量落明细。
 * <p>
 * 改后版：一条 MQ 消息里装的是**一批**事件（JSON 数组），所以这里解析数组、一次批量 INSERT。
 * <p>
 * RocketMQ 的确认语义是显式的：**只有返回 {@code CONSUME_SUCCESS} 才会提交位点**。
 * 写库失败必须返回 {@code RECONSUME_LATER}（触发重投），
 * 而**绝不能 catch 住当成成功**——那样这批数据会永久丢失且不留痕迹（见 §16.4 Q3）。
 */
@Component
public class AccessEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AccessEventConsumer.class);

    private static final TypeReference<List<AccessEvent>> BATCH_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final AccessLogRepository repository;
    private final UvCounter uvCounter;
    private final ShortlinkProperties props;

    private DefaultMQPushConsumer consumer;

    public AccessEventConsumer(ObjectMapper objectMapper,
                               AccessLogRepository repository,
                               UvCounter uvCounter,
                               ShortlinkProperties props) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.uvCounter = uvCounter;
        this.props = props;
    }

    @PostConstruct
    public void start() {
        if (!props.stats().consumerEnabled()) {
            log.warn("access event CONSUMER DISABLED by config (A/B measurement mode)");
            return;
        }
        DefaultMQPushConsumer c = new DefaultMQPushConsumer(props.mq().consumerGroup());
        c.setNamesrvAddr(props.mq().namesrv());
        c.setConsumeMessageBatchMaxSize(50);
        try {
            c.subscribe(props.mq().topic(), "*");
            c.registerMessageListener((MessageListenerConcurrently) (messages, context) ->
                    handle(messages));
            c.start();
            this.consumer = c;
            log.info("rocketmq consumer started: group={}, topic={}",
                    props.mq().consumerGroup(), props.mq().topic());
        } catch (Exception e) {
            log.error("rocketmq consumer start failed, stats will not be persisted", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }

    private ConsumeConcurrentlyStatus handle(List<MessageExt> messages) {
        List<AccessEvent> events = new ArrayList<>(messages.size() * 32);
        for (MessageExt message : messages) {
            try {
                events.addAll(objectMapper.readValue(message.getBody(), BATCH_TYPE));
            } catch (Exception e) {
                // 单条脏消息不能卡住整个队列（否则位点永远提交不了）
                log.error("bad access batch skipped: size={}", message.getBody().length, e);
            }
        }
        if (events.isEmpty()) {
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }
        try {
            repository.insertBatch(events);
        } catch (RuntimeException e) {
            // 返回 RECONSUME_LATER 让 RocketMQ 重投——这是 MQ 相对本地队列唯一的真实收益
            log.error("batch insert failed, will be redelivered: size={}", events.size(), e);
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        }
        // 明细落库成功之后再写 HLL：它只是估算值，失败也不该触发重投
        // （重投会把明细写第二遍）。UvCounter 内部已经吞掉异常。
        for (AccessEvent event : events) {
            uvCounter.record(event.code(), event.epochMilli(), event.visitor());
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
