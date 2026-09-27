package com.shivang.obd.security.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(LoginRateLimitProperties.class)
public class LoginRateLimitConfiguration {

    @Bean
    public LoginRateLimiter loginRateLimiter(
        StringRedisTemplate redisTemplate,
        LoginRateLimitProperties properties
    ) {
        return new RedisLoginRateLimiter(redisTemplate, properties);
    }
}
