package com.s310.kakaon.domain.fraud.detector;

import com.s310.kakaon.domain.alert.dto.AlertEvent;
import com.s310.kakaon.domain.alert.entity.AlertType;
import com.s310.kakaon.domain.alert.repository.AlertRepository;
import com.s310.kakaon.domain.payment.dto.PaymentEventDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import static com.s310.kakaon.global.util.Util.generateAlertId;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;

@Slf4j
//@Component
@RequiredArgsConstructor
public class DuplicatePaymentDetector implements FraudDetector {
    private final AlertRepository alertRepository;



    @Qualifier("paymentEventRedisTemplate")
    private final RedisTemplate<String, PaymentEventDto> paymentEventRedisTemplate;

    @Value("${fraud.duplicate.window-minutes}")
    private int windowMinutes;          // 중복 결제 탐지 윈도우 (분)

    @Value("${fraud.duplicate.threshold-count}")
    private int thresholdCount;         // 중복 결제 탐지 횟수 threshold (횟수)

    private static final String REDIS_KEY_PREFIX = "fraud:duplicate:";

    @Override
    public List<AlertEvent> detect(PaymentEventDto event) {

        // 필수 정보 없으면 탐지 스킵
        if (event.getPaymentUuid() == null) {
            return Collections.emptyList();
        }

        String redisKey = generateRedisKey(event);

        // Redis Transaction 또는 Lua Script 사용 권장
        paymentEventRedisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.multi();

            // 1) 현재 이벤트 추가
            paymentEventRedisTemplate.opsForList().rightPush(redisKey, event);
            paymentEventRedisTemplate.expire(redisKey, Duration.ofMinutes(windowMinutes + 5));

            connection.exec();
            return null;
        });

//        // 1) 현재 이벤트 추가
//        paymentEventRedisTemplate.opsForList().rightPush(redisKey, event);
//        paymentEventRedisTemplate.expire(redisKey, Duration.ofMinutes(TIME_WINDOW_MINUTES + 5));

        // 2) 전체 윈도우 조회 (이미 타입이 PaymentEventDto)
        List<PaymentEventDto> rawList =
                paymentEventRedisTemplate.opsForList().range(redisKey, 0, -1);

        if (rawList == null || rawList.isEmpty()) {
            return Collections.emptyList();
        }

        LocalDateTime windowStart = event.getApprovedAt().minusMinutes(windowMinutes);

        // 3) 윈도우 안에 들어오는 데이터만 필터링 + 정렬
        List<PaymentEventDto> recentList = rawList.stream()
                .filter(p -> p.getApprovedAt() != null &&
                        !p.getApprovedAt().isBefore(windowStart))
                .sorted(Comparator.comparing(PaymentEventDto::getApprovedAt))
                .toList();

        if (recentList.size() < thresholdCount) {
            return Collections.emptyList();
        }

        List<Long> paymentIdsInWindow = recentList.stream()
                .map(PaymentEventDto::getPaymentId)
                .toList();

        List<String> authNosInWindow = recentList.stream()
                .map(PaymentEventDto::getAuthorizationNo)
                .toList();

        log.info("[DETECTOR] storeId={}, redisKey={}, windowCount={}, paymentIdsInWindow={}",
                event.getStoreId(), redisKey, recentList.size(), paymentIdsInWindow);

        String description = String.format(
                "[중복 거래] 동일한 금액(%s원)과 결제수단(%s)으로 %d분 내 %d회 결제 발생\n" +
                        "- 가맹점: %s (매장ID: %s)\n" +
                        "- 첫 결제 시각: %s\n" +
                        "- 마지막 결제 시각: %s\n" +
                        "- 관련 결제 ID: %s\n" +
                        "- 관련 결제 승인번호: %s",
                event.getAmount(),
                event.getPaymentMethod(),
                windowMinutes,
                recentList.size(),
                event.getStoreName(),
                event.getStoreId(),
                recentList.get(0).getApprovedAt(),
                recentList.get(recentList.size() - 1).getApprovedAt(),
                paymentIdsInWindow, authNosInWindow
        );

        LocalDateTime detectedAt = LocalDateTime.now();
        String groupId = generateGroupId(redisKey, recentList);

        AlertEvent alertEvent = AlertEvent.builder()
                .groupId(groupId)
                .storeId(event.getStoreId())
                .alertUuid(generateAlertId(alertRepository))
                .storeName(event.getStoreName())
                .alertType(getAlertType())
                .description(description)
                .detectedAt(detectedAt)
                .paymentId(event.getPaymentId())
                .relatedPaymentIds(paymentIdsInWindow)
                .build();

        return List.of(alertEvent);
    }

    @Override
    public AlertType getAlertType() {
        return AlertType.REPEATED_PAYMENT;
    }

    private String generateRedisKey(PaymentEventDto event) {
        return String.format("%s%d-%s-%d-%s",
                REDIS_KEY_PREFIX,
                event.getStoreId(),
                event.getPaymentMethod(),
                event.getAmount(),
                event.getPaymentUuid()
        );
    }

    private String generateGroupId(String redisKey, List<PaymentEventDto> payments) {
        return String.format("DUP-%s-%d",
                redisKey.replace(REDIS_KEY_PREFIX, ""),
                payments.get(0).getPaymentId());
    }

    @Override
    public void cleanup() {
        log.debug("Redis TTL이 자동으로 만료 처리합니다.");
    }


}