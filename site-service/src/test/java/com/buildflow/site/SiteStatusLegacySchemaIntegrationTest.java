package com.buildflow.site;

import com.buildflow.site.domain.site.entity.Site;
import com.buildflow.site.domain.site.entity.SiteStatus;
import com.buildflow.site.domain.site.repository.SiteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 운영(ddl-auto=update) 방식 회귀: 기존 enum 컬럼 스키마(V1과 동일)가 이미 있는 DB에
 * 새 코드를 update로 기동한 뒤 BIDDING 현장을 저장·재조회한다.
 * 실행 전 격리 MySQL의 buildflow_site_legacy_test에 V1__site_baseline.sql을 적용해 둔다.
 */
@EnabledIfEnvironmentVariable(named = "SITE_LEGACY_SCHEMA_INTEGRATION", matches = "true")
@EnabledIfEnvironmentVariable(
        named = "SPRING_DATASOURCE_URL",
        matches = "^jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/buildflow_site_legacy_test\\?.*$")
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "eureka.client.register-with-eureka=false",
        "eureka.client.fetch-registry=false",
        "spring.kafka.listener.auto-startup=false",
        "management.tracing.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=update"
})
class SiteStatusLegacySchemaIntegrationTest {

    @Autowired
    private SiteRepository siteRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void legacyEnumSchemaAcceptsBiddingAfterUpdateStartup() {
        Site site = Site.builder().siteName("[QA] 합성 입찰 현장").build();
        site.changeStatus(SiteStatus.BIDDING);
        Long id = siteRepository.saveAndFlush(site).getId();

        assertEquals("BIDDING", jdbc.queryForObject(
                "SELECT status FROM sites WHERE id = ?", String.class, id));
        assertEquals(SiteStatus.BIDDING, siteRepository.findById(id).orElseThrow().getStatus());
    }
}
