package com.buildflow.site.domain.profit.entity;

import com.buildflow.site.domain.profit.repository.SiteProfitRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@ActiveProfiles("test")
class SiteProfitMarginJpaTest {

    @Autowired private SiteProfitRepository repository;

    private static BigDecimal won(String value) {
        return new BigDecimal(value);
    }

    @Test
    void 마진과_마진율은_견적과_매입으로_계산된다() {
        SiteProfit profit = SiteProfit.builder().siteId(1L).build();
        profit.addEstimateAmount(won("3000000"));
        profit.adjustPurchaseAmount(won("800000"));

        assertEquals(0, won("2200000").compareTo(profit.getMargin()));
        assertEquals(0, won("73.33").compareTo(profit.getMarginRate()));
    }

    @Test
    void 견적이_없으면_마진율은_0이다() {
        SiteProfit profit = SiteProfit.builder().siteId(1L).build();
        profit.adjustPurchaseAmount(won("500000"));

        assertEquals(0, won("-500000").compareTo(profit.getMargin()));
        assertEquals(0, BigDecimal.ZERO.compareTo(profit.getMarginRate()));
    }

    @Test
    void 매입이_견적을_크게_넘는_손실_현장도_저장된다() {
        SiteProfit profit = SiteProfit.builder().siteId(2L).build();
        profit.addEstimateAmount(won("1000000"));
        profit.adjustPurchaseAmount(won("12000000"));

        SiteProfit saved = repository.saveAndFlush(profit);

        // 마진 금액은 정확히 유지하고, DECIMAL(5,2) 범위를 넘는 마진율만 하한으로 표시한다
        assertEquals(0, won("-11000000").compareTo(saved.getMargin()));
        assertEquals(0, won("-999.99").compareTo(saved.getMarginRate()));
    }
}
