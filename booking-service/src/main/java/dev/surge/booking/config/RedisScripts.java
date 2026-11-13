package dev.surge.booking.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisScripts {

    @Bean
    @SuppressWarnings("rawtypes")
    RedisScript<List> holdScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/hold.lua"));
        script.setResultType(List.class);
        return script;
    }

    @Bean
    @SuppressWarnings("rawtypes")
    RedisScript<List> tokenBucketScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/token_bucket.lua"));
        script.setResultType(List.class);
        return script;
    }

    @Bean
    @SuppressWarnings("rawtypes")
    RedisScript<List> finalizeScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/finalize.lua"));
        script.setResultType(List.class);
        return script;
    }

    @Bean
    @SuppressWarnings("rawtypes")
    RedisScript<List> sweepScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/sweep.lua"));
        script.setResultType(List.class);
        return script;
    }
}
