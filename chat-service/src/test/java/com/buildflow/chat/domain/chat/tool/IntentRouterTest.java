package com.buildflow.chat.domain.chat.tool;

import com.buildflow.chat.domain.chat.tool.IntentRouter.RoutedCall;
import com.buildflow.chat.domain.chat.tool.IntentRouter.SiteRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRouterTest {

    private static final List<SiteRef> SITES = List.of(
            new SiteRef(1, "강남 리모델링"),
            new SiteRef(2, "강남 리모델링 2차"),
            new SiteRef(3, "부산 창고"));

    @Test
    void 현장_이름과_업무_키워드로_현장별_도구를_고른다() {
        assertThat(IntentRouter.route("부산창고 마진이랑 미수금 얼마야?", SITES)).containsExactly(
                new RoutedCall("getSiteProfit", 3L),
                new RoutedCall("getOutstandingTax", 3L));
    }

    @Test
    void 겹치는_현장_이름은_긴_이름부터_매칭한다() {
        assertThat(IntentRouter.route("강남 리모델링 2차 견적 보여줘", SITES)).containsExactly(
                new RoutedCall("listEstimatesBySite", 2L),
                new RoutedCall("listEstimatesBySite", 1L));
    }

    @Test
    void 현장을_특정하지_못한_손익_질문은_전체_요약과_현장_목록을_넣는다() {
        assertThat(IntentRouter.route("이번 달 손익 어때?", SITES)).containsExactly(
                new RoutedCall("getDashboardSummary", null),
                new RoutedCall("listSites", null));
    }

    @Test
    void 현장을_특정하지_못한_매입_질문은_현장_목록만_넣는다() {
        assertThat(IntentRouter.route("자재 매입 내역 알려줘", SITES)).containsExactly(
                new RoutedCall("listSites", null));
    }

    @Test
    void 현장_목록_질문() {
        assertThat(IntentRouter.route("현장 목록 알려줘", SITES)).containsExactly(
                new RoutedCall("listSites", null));
    }

    @Test
    void 업무와_무관한_질문은_도구를_고르지_않는다() {
        assertThat(IntentRouter.route("안녕하세요", SITES)).isEmpty();
        assertThat(IntentRouter.needsSites("안녕하세요")).isFalse();
        assertThat(IntentRouter.needsSites("견적 몇 건이야")).isTrue();
    }

    @Test
    void 한_글자_현장_이름이나_빈_목록은_매칭하지_않는다() {
        assertThat(IntentRouter.matchSites("a 현장 마진", List.of(new SiteRef(9, "a")))).isEmpty();
        assertThat(IntentRouter.matchSites("마진", null)).isEmpty();
    }

    @Test
    void 언급된_현장은_더_긴_매칭_이름에_포함된_이름을_뺀다() {
        List<SiteRef> sites = List.of(new SiteRef(1, "강남"), new SiteRef(2, "강남 2차"), new SiteRef(3, "부산 창고"));

        assertThat(IntentRouter.mentionedSites("강남 2차 손익", sites)).extracting(SiteRef::id).containsExactly(2L);
        assertThat(IntentRouter.mentionedSites("강남이랑 부산 창고 손익", sites)).extracting(SiteRef::id)
                .containsExactlyInAnyOrder(1L, 3L);
    }
}
