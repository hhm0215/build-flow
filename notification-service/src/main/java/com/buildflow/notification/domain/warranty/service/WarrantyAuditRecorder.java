package com.buildflow.notification.domain.warranty.service;

import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.entity.WarrantyAuditAction;
import com.buildflow.notification.domain.warranty.entity.WarrantyAuditEvent;
import com.buildflow.notification.domain.warranty.repository.WarrantyAuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/** 보증 값이 바뀌는 곳에서 호출해, 같은 트랜잭션 안에 변경 후 값을 이력으로 남긴다. */
@Component
@RequiredArgsConstructor
public class WarrantyAuditRecorder {

    static final String SYSTEM_ACTOR = "system";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");
    // 게이트웨이가 JWT subject로 채우는 헤더. 형식이 다르면 기록하지 않고 미상으로 둔다
    private static final Pattern SAFE_ACTOR = Pattern.compile("[A-Za-z0-9._@-]{1,64}");

    private final WarrantyAuditEventRepository repository;

    @Transactional
    public void record(WarrantyAuditAction action, DefectWarranty warranty) {
        repository.save(WarrantyAuditEvent.snapshot(action, currentActor(), warranty,
                LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.SECONDS)));
    }

    static String currentActor() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            return SYSTEM_ACTOR;
        }
        String header = servlet.getRequest().getHeader("X-User-Id");
        if (header == null || header.isBlank()) {
            return "unknown";
        }
        return SAFE_ACTOR.matcher(header).matches() ? header : "unknown";
    }
}
