package com.s310.kakaon.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.s310.kakaon.domain.payment.dto.PaymentEventDto;
import com.s310.kakaon.global.redis.DuplicatePaymentDetectionRedisTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    // 공통 Redis 연결 팩토리
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory(host, port);
    }

    // 숫자 카운팅, 매출 합계 등 단순 문자열용
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
        return new StringRedisTemplate(redisConnectionFactory);
    }

    // JSON 직렬화용 (예: DTO 저장, 토큰 블랙리스트)
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(redisConnectionFactory);

        // 직렬화 설정 추가
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();


        redisTemplate.setKeySerializer(stringSerializer);
        redisTemplate.setValueSerializer(jsonSerializer);
        redisTemplate.setHashKeySerializer(stringSerializer);
        redisTemplate.setHashValueSerializer(jsonSerializer);

        return redisTemplate;
    }

    // 중복 결제 탐지용 Redis 저장소
    @Bean
    @Qualifier("duplicateDetectionRedisTemplate")
    public DuplicatePaymentDetectionRedisTemplate duplicateDetectionRedisTemplate(
            RedisConnectionFactory cf,
            ObjectMapper objectMapper
    ) {
        DuplicatePaymentDetectionRedisTemplate template = new DuplicatePaymentDetectionRedisTemplate();
        template.setConnectionFactory(cf);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        Jackson2JsonRedisSerializer<PaymentEventDto> valueSerializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, PaymentEventDto.class);

        template.setKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashKeySerializer(keySerializer);
        template.setHashValueSerializer(valueSerializer);

        template.afterPropertiesSet();
        return template;
    }

    //  동일 결제수단 원거리 사용 탐지, 거래 빈도 급증 탐지용 Redis 저장소
    @Bean
    @Qualifier("paymentEventRedisTemplate")
    public RedisTemplate<String, PaymentEventDto> paymentEventRedisTemplate(
            RedisConnectionFactory cf,
            ObjectMapper objectMapper
    ) {
        RedisTemplate<String, PaymentEventDto> template = new RedisTemplate<>();
        template.setConnectionFactory(cf);

        // key는 String
        StringRedisSerializer keySerializer = new StringRedisSerializer();

        // value는 PaymentEventDto 전용 JSON 직렬화
        Jackson2JsonRedisSerializer<PaymentEventDto> valueSerializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, PaymentEventDto.class);

        template.setKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashKeySerializer(keySerializer);
        template.setHashValueSerializer(valueSerializer);

        template.afterPropertiesSet();
        return template;
    }

}
