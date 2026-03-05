package com.s310.kakaon.domain.fraud.detector;

import com.s310.kakaon.domain.alert.dto.AlertEvent;
import com.s310.kakaon.domain.alert.entity.AlertType;
import com.s310.kakaon.domain.alert.repository.AlertRepository;
import com.s310.kakaon.domain.payment.dto.PaymentEventDto;
import com.s310.kakaon.global.redis.DuplicatePaymentDetectionRedisTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import static com.s310.kakaon.global.util.Util.generateAlertId;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;

@Slf4j
@Component
@RequiredArgsConstructor
public class DuplicatePaymentDetector implements FraudDetector {
    private final AlertRepository alertRepository;

    @Qualifier("duplicateDetectionRedisTemplate")
    private final DuplicatePaymentDetectionRedisTemplate duplicateDetectionRedisTemplate;

    @Value("${fraud.duplicate.window-minutes}")
    private int windowMinutes;          // 중복 결제 탐지 윈도우 (분)

    @Value("${fraud.duplicate.threshold-count}")
    private int thresholdCount;         // 중복 결제 탐지 횟수 threshold (횟수)

    private static final String REDIS_KEY_PREFIX = "fraud:duplicate:";

    // TTL은 윈도우보다 여유있게 설정 (만료 경계에서 데이터 유실 방지)
    private static final int TTL_BUFFER_MINUTES = 1;

    @Override
    public List<AlertEvent> detect(PaymentEventDto event) {

        long startTime = System.nanoTime();

        // 필수 정보 없으면 탐지 스킵
        if (event.getPaymentUuid() == null) {
            return Collections.emptyList();
        }

        String redisKey = generateRedisKey(event);

        long nowMillis = event.getApprovedAt()
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();

        long windowStartMillis = event.getApprovedAt()
                .minusMinutes(windowMinutes)
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();

        long ttlSeconds = Duration.ofMinutes(windowMinutes + TTL_BUFFER_MINUTES).getSeconds();

        // 1) Pipeline 실행 - 직렬화/역직렬화는 Template 내부에서 처리
        List<PaymentEventDto> recentList;
        try{
            recentList = duplicateDetectionRedisTemplate.executeDuplicateDetectionPipeline(
                    redisKey,
                    event,
                    nowMillis,
                    windowStartMillis,
                    ttlSeconds
            );
        } catch (Exception e){
            log.warn("[REDIS-ZSET-PIPELINE-DUPLICATE-DETECTOR] Redis 장애로 탐지 스킵. key={}, error={}",
                    redisKey, e.getMessage());
            return Collections.emptyList();
        }

        long endTime = System.nanoTime();
        double milliseconds = (endTime - startTime) / 1_000_000.0;

        log.info("[REDIS-ZSET-PIPELINE-DUPLICATE-DETECTOR] 탐지 소요 시간: {}ms (windowCount={})",
                String.format("%.2f", milliseconds),
                recentList.size());

        // 2) 임계값 판단 - 정상인 경우, 빈 List를 리턴
        if (recentList.size() < thresholdCount) {
            return Collections.emptyList();
        }

        // 3) 이상거래 탐지된 경우, 관련 결제 정보 뽑아오기
        // 3-1) 이상거래 결제건의 paymentId 뽑기
        List<Long> paymentIdsInWindow = recentList.stream()
                .map(PaymentEventDto::getPaymentId)
                .toList();

        // 3-2) 이상거래 결제건의 결제 승인번호(authorizationNo) 뽑기
        List<String> authNosInWindow = recentList.stream()
                .map(PaymentEventDto::getAuthorizationNo)
                .toList();

        // 4) 알림 메시지 생성
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

        log.info("[REDIS-ZSET-PIPELINE-DUPLICATE-DETECTOR] 이상거래 탐지 storeId={}, count={}, paymentIds={}",
                event.getStoreId(), recentList.size(), paymentIdsInWindow);

        // 5) AlertEvent 객체 생성
        String groupId = generateGroupId(redisKey, recentList); // 이상거래 알림 식별자 생성 - "DUP-1-CARD-50000-abc123-101"
        AlertEvent alertEvent = AlertEvent.builder()
                .groupId(groupId)
                .storeId(event.getStoreId())
                .alertUuid(generateAlertId(alertRepository))
                .storeName(event.getStoreName())
                .alertType(getAlertType())
                .description(description)
                .detectedAt(LocalDateTime.now())
                .paymentId(event.getPaymentId())    // 현재 결제 ID (트리거가 된 결제)
                .relatedPaymentIds(paymentIdsInWindow)  // 윈도우 내 전체 관련 결제 ID
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