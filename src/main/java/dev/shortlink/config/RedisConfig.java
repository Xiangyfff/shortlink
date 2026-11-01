package dev.shortlink.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 两个 Redis 实例，两套相反的淘汰语义：
 * <ul>
 *   <li><b>缓存实例</b>（spring.data.redis）：{@code allkeys-lru}，可以淘汰，丢了最多回源查库；</li>
 *   <li><b>计数实例</b>（shortlink.counters）：{@code noeviction}，绝不能淘汰——HLL 被踢掉就是静默少算 UV。</li>
 * </ul>
 * {@code maxmemory-policy} 是实例级配置，所以这必须是两个进程，不是两个 db。
 * <p>
 * 注意：一旦这里注册了第二个 {@code RedisConnectionFactory}，Spring Boot 的
 * {@code @ConditionalOnSingleCandidate} 就会失效、不再自动创建 {@code stringRedisTemplate}，
 * 所以下面必须显式声明它，并把缓存那个标记为 {@code @Primary}。
 * <p>
 * <b>2026-11-01 修正（熔断测试暴露的问题）：</b>
 * 默认情况下，Redis 容器被停掉后到它的 TCP 包是被"黑洞"而不是被拒绝，
 * Lettuce 会一直重连，每次命令要等约 20 秒才失败。实测停 Redis 后，
 * 累计 5 次失败花掉近 100 秒，期间请求被严重拖慢。
 * 修法是把 {@code disconnectedBehavior} 设为 {@code REJECT_COMMANDS}：
 * 断连期间**立即失败**，让熔断器能在毫秒级做出反应。
 */
@Configuration
public class RedisConfig {

    private static LettuceClientConfiguration clientConfiguration() {
        return LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(200))
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(Duration.ofMillis(300))
                                .build())
                        // 断连时不排队、不重试，直接抛错 —— 否则熔断器永远等不到足够的失败计数
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .autoReconnect(true)
                        .build())
                .build();
    }

    @Primary
    @Bean
    public LettuceConnectionFactory redisConnectionFactory(RedisProperties props) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(props.getHost(), props.getPort());
        if (props.getPassword() != null && !props.getPassword().isEmpty()) {
            config.setPassword(RedisPassword.of(props.getPassword()));
        }
        return new LettuceConnectionFactory(config, clientConfiguration());
    }

    @Bean
    public LettuceConnectionFactory counterConnectionFactory(ShortlinkProperties props) {
        return new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(props.counters().host(), props.counters().port()),
                clientConfiguration());
    }

    @Primary
    @Bean
    public StringRedisTemplate stringRedisTemplate(
            @Qualifier("redisConnectionFactory") LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    public StringRedisTemplate counterRedisTemplate(
            @Qualifier("counterConnectionFactory") LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
