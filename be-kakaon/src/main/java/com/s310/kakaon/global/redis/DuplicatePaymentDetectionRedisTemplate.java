package com.s310.kakaon.global.redis;

import com.s310.kakaon.domain.payment.dto.PaymentEventDto;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.util.*;

/*
 * 중복 결제 탐지 전용 RedisTemplate
 */
public class DuplicatePaymentDetectionRedisTemplate extends RedisTemplate<String, PaymentEventDto> {

    // getKeySerializer()의 반환 타입이 RedisSerializer<?>라 String을 직접 못 받음
    // → 명시적 캐스팅으로 타입 안전한 래퍼 메서드 제공
    private RedisSerializer<String> keySerializer() {
        @SuppressWarnings("unchecked")
        RedisSerializer<String> s = (RedisSerializer<String>) getKeySerializer();
        return s;
    }

    // getValueSerializer()의 반환 타입이 RedisSerializer<?>라 PaymentEventDto를 직접 못 받음
    // → 명시적 캐스팅으로 타입 안전한 래퍼 메서드 제공
    private RedisSerializer<PaymentEventDto> valueSerializer(){
        @SuppressWarnings("unchecked")
        RedisSerializer<PaymentEventDto> s = (RedisSerializer<PaymentEventDto>) getValueSerializer();
        return s;
    }

    // 중복 결제 탐지에 필요한 4개 명령을 Pipeline으로 묶어 1회 왕복 처리
    public List<PaymentEventDto> executeDuplicateDetectionPipeline(
            String redisKey,
            PaymentEventDto event,
            long nowMillis,
            long windowStartMillis,
            long ttlSeconds
    ){

        // Pipeline 내부에서는 RedisTemplate 자동 직렬화가 동작하지 않으므로 미리 직렬화
        byte[] rawKey = keySerializer().serialize(redisKey);
        byte[] rawValue = valueSerializer().serialize(event);

        // Pipeline: 4개 명령 1회 왕복
        List<Object> results = executePipelined((RedisCallback<Object>) connection -> {
            // 1. ZADD: 현재 이벤트 추가
            connection.zAdd(rawKey, nowMillis, rawValue);

            // 2. ZREMRANGEBYSCORE: 윈도우 밖 오래된 데이터 제거
            connection.zRemRangeByScore(rawKey, 0, windowStartMillis-1);

            // 3. EXPIRE: TTL 갱신
            connection.expire(rawKey, ttlSeconds);

            // 4. ZRANGEBYSCORE: 윈도우 내 유효 데이터 조회
            // : executePipelined가 내부적으로 ValueSerializer로 자동 역직렬화
            connection.zRangeByScore(rawKey, windowStartMillis, nowMillis);

            return null;
        });

        if(results==null || results.size() < 4){
            return Collections.emptyList();
        }

        // results[3] == ZRANGEBYSCORE 결과
        // executePipelined가 ValueSerializer로 자동 역직렬화하므로 Set<PaymentEventDto>로 바로 캐스팅
        @SuppressWarnings("unchecked")
        Set<PaymentEventDto> deserializedResults = (Set<PaymentEventDto>) results.get(3);

        if (deserializedResults == null || deserializedResults.isEmpty()) {
            return Collections.emptyList();
        }

        return new ArrayList<>(deserializedResults);

    }
}
