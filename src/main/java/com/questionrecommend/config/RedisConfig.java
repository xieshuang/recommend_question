package com.questionrecommend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis 配置 — 使用 Lettuce 客户端
 * <p>
 * 等价 Python redis_client.py，但利用 Spring Data Redis 的自动管理能力。
 * 连接池由 commons-pool2 提供，通过 spring.redis.lettuce.pool 配置。
 */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    private final AppProperties properties;

    public RedisConfig(AppProperties properties) {
        this.properties = properties;
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        AppProperties.Cache cache = properties.getCache();

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(cache.getHost());
        config.setPort(cache.getPort());
        config.setDatabase(cache.getDb());
        if (cache.getPassword() != null && !cache.getPassword().isEmpty()) {
            config.setPassword(cache.getPassword());
        }

        // Lettuce 客户端配置：超时 + 健康检查
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(3))
                .shutdownTimeout(Duration.ofSeconds(1))
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(config, clientConfig);
        log.info("Redis 连接池配置: {}:{}, db={}", cache.getHost(), cache.getPort(), cache.getDb());
        return factory;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
