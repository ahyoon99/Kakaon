package com.s310.kakaon.domain.fraud.detector;

import com.s310.kakaon.domain.alert.dto.AlertEvent;
import com.s310.kakaon.domain.alert.entity.AlertType;
import com.s310.kakaon.domain.alert.repository.AlertRepository;
import com.s310.kakaon.domain.payment.dto.PaymentEventDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import static com.s310.kakaon.global.util.Util.generateAlertId;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
@Slf4j
//@Component
@RequiredArgsConstructor
public class SamePaymentMethodDetector implements FraudDetector {
    private final AlertRepository alertRepository;
    @Qualifier("paymentEventRedisTemplate")
    private final RedisTemplate<String, PaymentEventDto> paymentEventRedisTemplate;

    @Value("${fraud.same-payment.window-minutes}")
    private int windowMinutes;            // 동일 결제수단 원거리 사용 탐지 윈도우 (분)

    @Value("${fraud.same-payment.threshold-km}")
    private double thresholdKm;           // 동일 결제수단 탐지 km threshold (km)

    private static final String REDIS_KEY_PREFIX = "fraud:same:";

    @Override
    public List<AlertEvent> detect(PaymentEventDto event) {

        // 필수 정보 없으면 탐지 스킵
        if (event.getPaymentUuid() == null ||
                event.getStoreLatitude() == null ||
                event.getStoreLongitude() == null ||
                event.getApprovedAt() == null) {
            return Collections.emptyList();
        }

        String redisKey = generateRedisKey(event);

        // 1) 현재 이벤트 적재 + TTL 설정
        paymentEventRedisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.multi();

            paymentEventRedisTemplate.opsForList().rightPush(redisKey, event);
            // 윈도우 + 버퍼
            paymentEventRedisTemplate.expire(redisKey, Duration.ofMinutes(windowMinutes + 5));

            connection.exec();
            return null;
        });

        // 2) 윈도우 내 이벤트 조회
        List<PaymentEventDto> rawList =
                paymentEventRedisTemplate.opsForList().range(redisKey, 0, -1);

        if (rawList == null || rawList.isEmpty()) {
            return Collections.emptyList();
        }

        LocalDateTime windowStart = event.getApprovedAt().minusMinutes(windowMinutes);

        List<PaymentEventDto> recentList = rawList.stream()
                .filter(p -> p.getApprovedAt() != null &&
                        !p.getApprovedAt().isBefore(windowStart))
                .sorted(Comparator.comparing(PaymentEventDto::getApprovedAt))
                .toList();

        if (recentList.size() <= 1) {
            // 나 혼자만 있으면 비교 대상 없음
            return Collections.emptyList();
        }

        // 3) 거리 + 시간 차 조건 만족하는 다른 매장 찾기
        List<PaymentEventDto> suspiciousList = new ArrayList<>();

        for (PaymentEventDto other : recentList) {
            if (other.getPaymentId().equals(event.getPaymentId())) {
                continue; // 자기 자신 제외
            }
            if (other.getStoreId().equals(event.getStoreId())) {
                continue; // 같은 매장이면 패스
            }
            if (other.getStoreLatitude() == null || other.getStoreLongitude() == null) {
                continue;
            }

            long minutesDiff = Math.abs(
                    ChronoUnit.MINUTES.between(event.getApprovedAt(), other.getApprovedAt())
            );
            if (minutesDiff > windowMinutes) {
                continue;
            }

            double distanceKm = calculateDistanceKm(
                    event.getStoreLatitude(), event.getStoreLongitude(),
                    other.getStoreLatitude(), other.getStoreLongitude()
            );

            if (distanceKm >= thresholdKm) {
                suspiciousList.add(other);
            }
        }

        if (suspiciousList.isEmpty()) {
            return Collections.emptyList();
        }

        // 4) 알림 생성 (현재 이벤트와 '원거리 다른 매장'으로 감지된 결제들만)
        List<Long> relatedPaymentIds = suspiciousList.stream()
                .map(PaymentEventDto::getPaymentId)
                .toList();

        // 가장 멀리 떨어진 매장 하나를 대표로 사용 (설명용)
        PaymentEventDto farthest = suspiciousList.stream()
                .max(Comparator.comparingDouble(other ->
                        calculateDistanceKm(
                                event.getStoreLatitude(), event.getStoreLongitude(),
                                other.getStoreLatitude(), other.getStoreLongitude()
                        )))
                .orElse(suspiciousList.get(0));

        double farthestDistance = calculateDistanceKm(
                event.getStoreLatitude(), event.getStoreLongitude(),
                farthest.getStoreLatitude(), farthest.getStoreLongitude()
        );

        String description = String.format(
                "[원거리 결제] 동일 결제수단(paymentUuid=%s)이 %d분 이내 서로 %.2fkm 이상 떨어진 다른 매장에서 사용되었습니다.\n" +
                        "- 기준 결제 매장: %s (매장ID: %d)\n" +
                        "- 다른 매장 예시: %s (매장ID: %d)\n" +
                        "- 기준 결제 시각: %s\n" +
                        "- 다른 매장 결제 시각: %s\n" +
                        "- 관련 결제 ID: %s",
                event.getPaymentUuid(),
                windowMinutes,
                farthestDistance,
                event.getStoreName(), event.getStoreId(),
                farthest.getStoreName(), farthest.getStoreId(),
                event.getApprovedAt(),
                farthest.getApprovedAt(),
                relatedPaymentIds
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
                .relatedPaymentIds(relatedPaymentIds)
                .build();

        log.info("[DETECTOR-DISTANT] Detected distant store usage: groupId={}, paymentUuid={}",
                groupId, event.getPaymentUuid());

        return List.of(alertEvent);
    }

    @Override
    public AlertType getAlertType() {
        return AlertType.SAME_PAYMENT_METHOD;
    }

    private String generateRedisKey(PaymentEventDto event) {
        return String.format("%s%s",
                REDIS_KEY_PREFIX,
                event.getPaymentUuid()
        );
    }

    private String generateGroupId(String redisKey, List<PaymentEventDto> payments) {
        return String.format("DIST-%s-%d",
                redisKey.replace(REDIS_KEY_PREFIX, ""),
                payments.get(0).getPaymentId()
        );
    }

    /**
     * Haversine 공식으로 두 점 사이 거리(km) 계산
     */
    private double calculateDistanceKm(BigDecimal lat1, BigDecimal lon1,
                                       BigDecimal lat2, BigDecimal lon2) {
        double R = 6371.0; // 지구 반지름 (km)

        double dLat = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double dLon = Math.toRadians(lon2.doubleValue() - lon1.doubleValue());

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1.doubleValue())) *
                        Math.cos(Math.toRadians(lat2.doubleValue())) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return R * c;
    }

    @Override
    public void cleanup() {
        log.debug("Redis TTL에 의해 자동 정리됩니다.");
    }
}
