package com.buildflow.notification.domain.warranty.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 하자보증보험 수정 요청.
 *
 * <p>PATCH 시맨틱 3-state: {@link Optional} 필드는 JSON 표현에 따라 세 가지 상태를 구분한다.
 * <ul>
 *   <li>필드 자체가 JSON에 없음 → 필드 값 {@code null} → 서비스에서 skip (기존 값 유지)</li>
 *   <li>JSON 값이 {@code null} → {@code Optional.empty()} → 명시적으로 빈 값으로 clear</li>
 *   <li>JSON 값이 존재 → {@code Optional.of(...)} → 새 값으로 갱신</li>
 * </ul>
 *
 * <p>Spring Boot 기본 설정의 {@code Jdk8Module}이 이 3-state 매핑을 담당한다.
 * 필수 필드({@code insuranceCompany}, {@code startDate}, {@code endDate})는 nullable이 아니므로
 * 그대로 유지한다.
 */
@Getter
@NoArgsConstructor
public class WarrantyUpdateRequest {

    @NotBlank(message = "보험사는 필수입니다.")
    private String insuranceCompany;

    private Optional<String> policyNumber;

    @NotNull(message = "보험 시작일은 필수입니다.")
    private LocalDate startDate;

    @NotNull(message = "보험 만료일은 필수입니다.")
    private LocalDate endDate;

    /** 보증금액 (원). 3-state — clear/skip/update 시맨틱은 클래스 주석 참고 */
    private Optional<Long> coverageAmount;

    private Optional<String> memo;
}
