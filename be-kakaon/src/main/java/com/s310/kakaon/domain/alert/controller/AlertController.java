package com.s310.kakaon.domain.alert.controller;


import com.s310.kakaon.domain.alert.dto.AlertDetailResponseDto;
import com.s310.kakaon.domain.alert.dto.AlertResponseDto;
import com.s310.kakaon.domain.alert.dto.AlertSearchRequestDto;
import com.s310.kakaon.domain.alert.dto.AlertUnreadCountResponseDto;
import com.s310.kakaon.domain.alert.entity.AlertType;
import com.s310.kakaon.domain.alert.service.AlertService;
import com.s310.kakaon.domain.member.service.MemberService;
import com.s310.kakaon.global.dto.ApiResponse;
import com.s310.kakaon.global.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Alert", description = "이상 거래 알림 조회 / 읽음 처리 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/stores/{storeId}/alerts")
public class AlertController {

    private final AlertService alertService;
    private final MemberService memberService;

    // 매장 아이디
    // 조회 기간 오늘, 이번주, 이번달, 올해, 언제부터 언제 기간 검색
    // 유형
    // 상태 : 전체, 미확인, 확인됨
    @Operation(
            summary = "이상 거래 알림 목록 조회",
            description = """
                    특정 가맹점(storeId)의 이상 거래 알림 목록을 조회합니다.  
                    - Query Parameters:  
                      • startDate, endDate: 조회 기간 (yyyy-MM-dd)  
                      • alertType: 알림 유형 (ANOMALY 등)  
                      • checked: 상태 필터 (null: 전체, false: 미확인, true: 확인됨)  
                    - Pageable 지원 (page, size, sort)
                    """
    )
    @Parameters({
            @Parameter(name = "storeId", description = "가맹점 ID", required = true),
            @Parameter(name = "startDate", description = "조회 시작일 (yyyy-MM-dd)", example = "2025-11-01"),
            @Parameter(name = "endDate", description = "조회 종료일 (yyyy-MM-dd)", example = "2025-11-07"),
            @Parameter(name = "alertType", description = "알림 유형 (예: ANOMALY, PAYMENT_FAILURE 등)"),
            @Parameter(name = "checked", description = "확인 상태 (true: 확인, false: 미확인)")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AlertResponseDto>>> getAnomalyAlerts(
            @AuthenticationPrincipal String kakaoId,
            @PathVariable Long storeId,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,

            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate,

            @RequestParam(required = false) AlertType alertType,
            @RequestParam(required = false) Boolean checked,
            Pageable pageable,
            HttpServletRequest httpRequest
    ) {

        Long memberId = memberService.getMemberByProviderId(kakaoId).getId();

        AlertSearchRequestDto request = AlertSearchRequestDto.builder()
                .startDate(startDate)
                .endDate(endDate)
                .alertType(alertType)
                .checked(checked)
                .build();

        PageResponse<AlertResponseDto> response = alertService.getAnomalyAlerts(storeId, memberId, request, pageable);

        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.of(HttpStatus.OK, "이상 거래 알림 목록 조회 성공", response, httpRequest.getRequestURI()));
    }

    //단건 읽음 처리
    @Operation(
            summary = "단건 알림 읽음 처리",
            description = """
                    특정 가맹점(storeId)의 단건 미확인 알림을 읽음 처리합니다.  
                    - PATCH /api/v1/stores/{storeId}/alerts/{alertId}/read  
                    - 반환값: 변경된 단건 알림   
                    """
    )
    @Parameters({
            @Parameter(name = "storeId", description = "가맹점 ID", required = true),
            @Parameter(name = "alertId", description = "알림 ID", required = true)
    })
    @PatchMapping("/{alertId}/read")
    public ResponseEntity<ApiResponse<AlertResponseDto>> readAlert(
            @AuthenticationPrincipal String kakaoId,
            @PathVariable Long storeId,
            @PathVariable Long alertId,
            HttpServletRequest httpRequest
    ) {
        Long memberId = memberService.getMemberByProviderId(kakaoId).getId();
        AlertResponseDto response = alertService.checkedAnomalyAlert(memberId, storeId, alertId);
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.of(HttpStatus.OK, "알림 읽음 처리 성공", response, httpRequest.getRequestURI()));
    }

    //모두 읽음 표시
    @Operation(
            summary = "모든 알림 읽음 처리",
            description = """
                    특정 가맹점(storeId)의 모든 미확인 알림을 읽음 처리합니다.  
                    - PATCH /api/v1/stores/{storeId}/alerts/read-all  
                    - 반환값: 변경된 알림 목록 또는 처리 결과 요약  
                    """
    )
    @Parameter(name = "storeId", description = "가맹점 ID", required = true)
    @PatchMapping("/read-all")
    public ResponseEntity<ApiResponse<PageResponse<AlertResponseDto>>> readAllAlerts(
            @AuthenticationPrincipal String kakaoId,
            @PathVariable Long storeId,
            Pageable pageable,
            HttpServletRequest httpRequest
    ) {

        Long memberId = memberService.getMemberByProviderId(kakaoId).getId();
        PageResponse<AlertResponseDto> response = alertService.checkedAnomalyAlerts(storeId, memberId, pageable);
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.of(HttpStatus.OK, "모두 읽음 처리 성공", response, httpRequest.getRequestURI()));
    }


    //단건 조회
    @Operation(
            summary = "이상 거래 알림 단건 조회",
            description = """
                    특정 알림(alertId)의 상세 정보를 조회합니다.  
                    - PathVariable: storeId, alertId  
                    - 반환값: 알림 상세 내용 (발생 시각, 유형, 관련 결제 정보 등)
                    """
    )
    @Parameters({
            @Parameter(name = "storeId", description = "가맹점 ID", required = true),
            @Parameter(name = "alertId", description = "알림 ID", required = true)
    })
    @GetMapping("/{alertId}")
    public ResponseEntity<ApiResponse<AlertDetailResponseDto>> getAnomalyAlert(
            @AuthenticationPrincipal String kakaoId,
            @PathVariable Long storeId,
            @PathVariable Long alertId,
            HttpServletRequest httpRequest
    ) {
        Long memberId = memberService.getMemberByProviderId(kakaoId).getId();
        AlertDetailResponseDto response = alertService.getAnomalyAlert(memberId, storeId, alertId);

        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.of(HttpStatus.OK, "이상 거래 알림 상세 조회 성공", response, httpRequest.getRequestURI()));
    }

    @Operation(
            summary = "미확인 알림 개수 조회",
            description = """
                    특정 가맹점(storeId)의 미확인 알림 개수를 조회합니다.  
                    - PathVariable: storeId  
                    - 반환값: unreadCount (미확인 알림 수)
                    """
    )
    @Parameter(name = "storeId", description = "가맹점 ID", required = true)
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<AlertUnreadCountResponseDto>> getUnreadAlertCount(
            @AuthenticationPrincipal String kakaoId,
            @PathVariable Long storeId,
            HttpServletRequest httpRequest
    ) {
        Long memberId = memberService.getMemberByProviderId(kakaoId).getId();
        AlertUnreadCountResponseDto response = alertService.getUnreadAlertCount(memberId, storeId);
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.of(HttpStatus.OK, "미확인 알림 개수 조회 성공", response, httpRequest.getRequestURI()));
    }

}
