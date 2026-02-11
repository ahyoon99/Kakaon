package com.s310.kakaon.domain.fraud.detector;

import com.s310.kakaon.domain.alert.dto.AlertEvent;
import com.s310.kakaon.domain.alert.entity.AlertType;
import com.s310.kakaon.domain.alert.repository.AlertRepository;
import com.s310.kakaon.domain.payment.dto.PaymentEventDto;
import com.s310.kakaon.domain.payment.dto.PaymentMethod;
import com.s310.kakaon.domain.payment.entity.Payment;
import com.s310.kakaon.domain.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static com.s310.kakaon.global.util.Util.generateAlertId;

@Slf4j
@Component
@RequiredArgsConstructor
public class DuplicatePaymentDetectorUsingDBAndWhereAndIndex implements FraudDetector{
    private final AlertRepository alertRepository;
    private final PaymentRepository paymentRepository;

    @Value("${fraud.duplicate.window-minutes}")
    private int windowMinutes;

    @Value("${fraud.duplicate.threshold-count}")
    private int thresholdCount;

    @Override
    public List<AlertEvent> detect(PaymentEventDto event) {
        long startTime = System.nanoTime();

        // 필수 정보 없으면 탐지 스킵
        if (event.getPaymentUuid() == null) {
            return Collections.emptyList();
        }

        // 윈도우 시간 계산
        LocalDateTime windowStart = event.getApprovedAt().minusMinutes(windowMinutes);
        LocalDateTime windowEnd = event.getApprovedAt();

        // PaymentMethod Enum으로 변환
        PaymentMethod paymentMethod = PaymentMethod.valueOf(event.getPaymentMethod());

        // Full Table Scan + WHERE 절로 필터링
        List<Payment> recentPayments = paymentRepository.findPaymentsUsingWhereAndIndex(
                event.getStoreId(),
                paymentMethod,
                event.getAmount(),
                event.getPaymentUuid(),
                windowStart,
                windowEnd
        );

        long endTime = System.nanoTime();
        double milliseconds = (endTime - startTime) / 1_000_000.0;

        log.info("[DB-AND-WHERE-INDEX] 중복 결제 탐지 소요 시간: {}ms (windowCount={})",
                String.format("%.2f", milliseconds),
                recentPayments.size());

        if (recentPayments.size() < thresholdCount) {
            return Collections.emptyList();
        }

        // 메일에 넣을 관련 결제 ID 조회
        List<Long> paymentIdsInWindow = recentPayments.stream()
                .map(Payment::getId)
                .toList();

        // 메일에 넣을 관련 결제 승인번호 조회
        List<String> authNosInWindow = recentPayments.stream()
                .map(Payment::getAuthorizationNo)
                .toList();

        log.info("[DETECTOR-DB-AND-WHERE-AND-INDEX] storeId={}, windowCount={}, paymentIdsInWindow={}",
                event.getStoreId(), recentPayments.size(), paymentIdsInWindow);

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
                recentPayments.size(),
                event.getStoreName(),
                event.getStoreId(),
                recentPayments.get(0).getApprovedAt(),
                recentPayments.get(recentPayments.size() - 1).getApprovedAt(),
                paymentIdsInWindow,
                authNosInWindow
        );

        // 이상거래가 탐지되었을 때 알림(Alert) 이벤트 객체 생성
        LocalDateTime detectedAt = LocalDateTime.now();
        String groupId = generateGroupId(event, recentPayments);

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

    private String generateGroupId(PaymentEventDto event, List<Payment> payments) {
        return String.format("DUP-V2-%d-%s-%d-%s-%d",
                event.getStoreId(),
                event.getPaymentMethod(),
                event.getAmount(),
                event.getPaymentUuid(),
                payments.get(0).getId());
    }
}
