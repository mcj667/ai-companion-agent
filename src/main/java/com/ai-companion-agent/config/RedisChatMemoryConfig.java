package com.example.aiagent.config;

import com.example.aiagent.memory.ChatMemoryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

@Configuration
@EnableConfigurationProperties(ChatMemoryProperties.class)
public class RedisChatMemoryConfig {

    /**
     * 存放 Kryo 二进制数据的专用 RedisTemplate。
     *
     * <p>Spring Boot 只会自动配置 {@code RedisTemplate<Object, Object>} 和
     * {@code StringRedisTemplate}，泛型都对不上 {@code RedisTemplate<String, byte[]>}，
     * 不显式声明这个 Bean 的话，RedisChatMemory 注入时会直接启动失败。
     */
    @Bean
    public RedisTemplate<String, byte[]> byteArrayRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(RedisSerializer.string());
        template.setValueSerializer(RedisSerializer.byteArray());
        template.setHashKeySerializer(RedisSerializer.string());
        template.setHashValueSerializer(RedisSerializer.byteArray());
        template.afterPropertiesSet();
        return template;
    }
}
