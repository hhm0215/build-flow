package com.buildflow.purchase.global.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 현장 미배정 매입(2026-11-01)을 위해 기존 DB의 {@code purchases.site_id} NOT NULL 제약을 푼다.
 *
 * <p>기본 프로파일은 {@code ddl-auto: update}인데 Hibernate update는 기존 컬럼의 nullability를 바꾸지 않아
 * 이미 만들어진 로컬·운영 DB에서는 미배정 매입 저장이 실패한다. Flyway 프로파일(V3)과 같은 변경을,
 * Flyway가 꺼져 있을 때만 한 번 적용한다. 이미 NULL 허용이면 아무것도 하지 않는다(멱등).
 * 제약 완화만 하므로 기존 행 값은 바뀌지 않는다. 실패해도 기존 기능은 그대로 쓸 수 있어 기동을 막지 않는다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "false")
public class PurchaseSiteIdNullableSchemaPatch implements ApplicationRunner {

    static final String NULLABILITY_SQL = "SELECT IS_NULLABLE FROM information_schema.COLUMNS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchases' AND COLUMN_NAME = 'site_id'";
    static final String ALTER_SQL = "ALTER TABLE `purchases` MODIFY COLUMN `site_id` bigint DEFAULT NULL";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<String> nullable = jdbcTemplate.queryForList(NULLABILITY_SQL, String.class);
            if (nullable.size() != 1 || !"NO".equalsIgnoreCase(nullable.get(0))) {
                return;
            }
            jdbcTemplate.execute(ALTER_SQL);
            log.info("purchases.site_id NOT NULL 제약을 해제했습니다(현장 미배정 매입 허용).");
        } catch (DataAccessException e) {
            log.error("purchases.site_id NULL 허용 전환 실패 — 현장 미배정 매입 저장이 실패합니다: {}",
                    e.getMostSpecificCause().getMessage());
        }
    }
}
