package com.buildflow.site.domain.dashboard.service;

import com.buildflow.site.domain.dashboard.dto.DashboardStatsResponse;
import com.buildflow.site.domain.dashboard.dto.DashboardStatsResponse.SiteProfitSummary;
import com.buildflow.site.domain.site.entity.SiteStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 대시보드 AI 요약에 넘길 사실을 서버가 결정적으로 분류한다. 모델은 이 사실을 문장으로 옮기기만 한다.
 *
 * <ul>
 *   <li>견적 합계 0 → 견적 미등록(데이터 없음). 손익·마진율 평가와 주의 판정에서 뺀다.</li>
 *   <li>수주 전(BIDDING) → 계약 전 견적. 손익 평가에서 뺀다.</li>
 *   <li>매입 합계 0 → 매입 미입력(견적만 등록). "매입 0원, 마진율 100%"로 보이지 않게 손익·마진율 평가와 주의 판정에서 뺀다.</li>
 *   <li>그 외 → 마진 &lt; 0이면 적자, 마진율 &lt; 10%면 저마진, 나머지는 정상.</li>
 *   <li>마진·마진율 합계는 손익 평가 대상(매입이 입력된 현장)만으로 서버가 계산한다.</li>
 *   <li>다음 할 일도 적자 → 저마진 → 매입 입력 → 견적 미등록 순으로 서버가 정한다.</li>
 * </ul>
 * 금액·비율은 실행 환경 로케일과 무관하게 Locale.ROOT로 미리 문자열화한다.
 */
public record DashboardSummaryFacts(
        int totalSites,
        String statusCounts,
        List<String> evaluatedSites,
        List<String> warningSites,
        List<String> noEstimateSites,
        List<String> noPurchaseSites,
        List<String> biddingSites,
        List<String> nextActions,
        String totalEstimate,
        String totalPurchase,
        String evaluatedEstimate,
        String evaluatedPurchase,
        String totalMargin,
        String averageMarginRate,
        boolean hasData,
        List<String> siteNames
) {

    static final BigDecimal LOW_MARGIN_RATE = BigDecimal.TEN;

    static final String NO_EVALUATED_RATE = "평가 대상 없음";
    static final String NO_PURCHASE_RATE = "매입이 입력된 현장이 없어 마진율을 계산하지 않음";

    static final String NO_SITE_MESSAGE =
            "아직 등록된 현장이 없습니다. 현장을 만들고 견적·매입을 등록하면 손익 요약을 볼 수 있습니다.";
    static final String NO_DATA_MESSAGE_PREFIX = "아직 견적·매입이 등록된 현장이 없습니다.";

    public static DashboardSummaryFacts from(DashboardStatsResponse stats) {
        List<SiteProfitSummary> sites = stats.getSiteProfits() == null ? List.of() : stats.getSiteProfits();
        List<String> evaluated = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> noEstimate = new ArrayList<>();
        List<String> noPurchase = new ArrayList<>();
        List<String> bidding = new ArrayList<>();
        List<String> lossActions = new ArrayList<>();
        List<String> lowMarginActions = new ArrayList<>();
        List<String> estimateActions = new ArrayList<>();
        List<String> names = new ArrayList<>();
        boolean hasData = false;
        BigDecimal evaluatedEstimate = BigDecimal.ZERO;
        BigDecimal evaluatedPurchase = BigDecimal.ZERO;

        for (SiteProfitSummary site : sites) {
            BigDecimal estimate = orZero(site.getEstimateAmount());
            BigDecimal purchase = orZero(site.getPurchaseAmount());
            BigDecimal margin = orZero(site.getMargin());
            BigDecimal rate = orZero(site.getMarginRate());
            if (estimate.signum() != 0 || purchase.signum() != 0) {
                hasData = true;
            }
            if (site.getSiteName() != null && !site.getSiteName().isBlank()) {
                names.add(site.getSiteName());
            }
            String label = site.getSiteName() + " (" + SiteStatus.labelOf(site.getStatus()) + ")";

            if (estimate.signum() <= 0) {
                noEstimate.add(purchase.signum() == 0
                        ? label + ": 견적·매입 미등록"
                        : label + ": 견적 미등록, 매입 " + won(purchase) + " 등록됨");
                estimateActions.add(site.getSiteName() + ": 견적 등록");
                continue;
            }
            if (SiteStatus.BIDDING.name().equals(site.getStatus())) {
                bidding.add(label + ": 계약 전 견적 " + won(estimate));
                continue;
            }
            if (purchase.signum() == 0) {
                // 과거 견적만 등록하고 매입을 아직 넣지 않은 현장. 마진율 100%로 합산하면 손익을 부풀린다.
                noPurchase.add(label + ": 견적 " + won(estimate) + ", 매입 미입력");
                continue;
            }
            evaluatedEstimate = evaluatedEstimate.add(estimate);
            evaluatedPurchase = evaluatedPurchase.add(purchase);
            String line = label + ": 견적 " + won(estimate) + ", 매입 " + won(purchase)
                    + ", 마진 " + won(margin) + ", 마진율 " + percent(rate);
            evaluated.add(line);
            if (margin.signum() < 0) {
                warnings.add(label + ": 적자 (마진 " + won(margin) + ", 마진율 " + percent(rate) + ")");
                lossActions.add(site.getSiteName() + ": 매입 내역과 추가공사 견적 누락 여부 확인");
            } else if (rate.compareTo(LOW_MARGIN_RATE) < 0) {
                warnings.add(label + ": 저마진 (마진율 " + percent(rate) + ", 기준 10% 미만)");
                lowMarginActions.add(site.getSiteName() + ": 남은 매입 계획과 마진 확인");
            }
        }

        boolean anyEvaluated = !evaluated.isEmpty();
        BigDecimal evaluatedMargin = evaluatedEstimate.subtract(evaluatedPurchase);
        String rate;
        if (anyEvaluated) {
            rate = percent(evaluatedMargin.multiply(BigDecimal.valueOf(100))
                    .divide(evaluatedEstimate, 2, RoundingMode.HALF_UP));
        } else {
            rate = noPurchase.isEmpty() ? NO_EVALUATED_RATE : NO_PURCHASE_RATE;
        }
        // 우선순위: 적자 → 저마진 → 매입 입력 → 견적 미등록. 모델이 할 일을 새로 만들지 않도록 서버가 정한다.
        List<String> actions = new ArrayList<>(lossActions);
        actions.addAll(lowMarginActions);
        if (!noPurchase.isEmpty()) {
            actions.add("매입 입력 필요(" + noPurchase.size() + "개)");
        }
        actions.addAll(estimateActions);
        return new DashboardSummaryFacts(
                stats.getTotalSites(),
                statusCounts(stats.getSitesByStatus()),
                List.copyOf(evaluated),
                List.copyOf(warnings),
                List.copyOf(noEstimate),
                List.copyOf(noPurchase),
                List.copyOf(bidding),
                List.copyOf(actions),
                won(orZero(stats.getTotalEstimateAmount())),
                // 매입 미입력 현장만 있어 매입 합계가 0이면 "매입 0원" 대신 미입력으로 적는다.
                orZero(stats.getTotalPurchaseAmount()).signum() == 0 && !noPurchase.isEmpty()
                        ? "미입력" : won(orZero(stats.getTotalPurchaseAmount())),
                won(evaluatedEstimate),
                won(evaluatedPurchase),
                won(evaluatedMargin),
                rate,
                hasData,
                List.copyOf(names));
    }

    /** 견적·매입이 하나도 없을 때 모델 대신 돌려줄 결정적 안내. */
    public String noDataMessage() {
        if (totalSites == 0) {
            return NO_SITE_MESSAGE;
        }
        return NO_DATA_MESSAGE_PREFIX + " 등록된 현장 " + totalSites + "개는 손실이 아니라 아직 데이터가 없는 상태입니다."
                + " 견적을 등록하면 손익 요약을 볼 수 있습니다.";
    }

    /**
     * 모델 출력이 사후 검증에 걸렸을 때 대신 돌려줄 결정적 요약. 사실 목록을 템플릿 문장으로만 옮긴다.
     * 구성은 시스템 프롬프트와 같은 1. 전체 현황 2. 손익 3. 주의 현장 4. 다음 할 일.
     */
    public String deterministicSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("### 1. 전체 현황\n\n");
        sb.append("- 전체 현장 ").append(totalSites).append("개 (").append(statusCounts).append(")\n");
        sb.append("- 손익 평가 대상 ").append(evaluatedSites.size()).append("개, 매입 미입력 ")
                .append(noPurchaseSites.size()).append("개, 견적 미등록 ")
                .append(noEstimateSites.size()).append("개, 수주 전 ").append(biddingSites.size()).append("개\n\n");
        sb.append("### 2. 손익\n\n");
        sb.append("- 합계(수주 전 제외): 견적 ").append(totalEstimate).append(", 매입 ").append(totalPurchase).append('\n');
        sb.append("- ").append(marginLine()).append('\n');
        if (!noPurchaseSites.isEmpty()) {
            sb.append("- ").append(noPurchaseExclusion()).append('\n');
        }
        if (!noEstimateSites.isEmpty()) {
            sb.append("- 견적 미등록 현장은 손실이 아니라 아직 데이터가 없는 상태라 손익 평가에서 뺐습니다.\n");
        }
        if (!biddingSites.isEmpty()) {
            sb.append("- 수주 전 현장은 계약 전 견적이라 손익 평가에서 뺐습니다.\n");
        }
        sb.append('\n');
        sb.append("### 3. 주의 현장\n\n");
        bullets(sb, warningSites, noPurchaseSites.isEmpty()
                ? "주의할 현장이 없습니다."
                : "적자·저마진으로 판정된 현장은 없습니다. 매입 미입력 " + noPurchaseSites.size()
                        + "개 현장은 매입을 입력해야 손익을 판단할 수 있습니다.");
        sb.append("### 4. 다음 할 일\n\n");
        bullets(sb, nextActions, "지금 따로 조치할 일은 없습니다.");
        return sb.toString().strip();
    }

    /** 마진·마진율 문장. 손익 평가 대상(매입 입력 현장)이 없으면 계산하지 않았다는 사실만 적는다. */
    private String marginLine() {
        if (evaluatedSites.isEmpty()) {
            return "마진율: " + averageMarginRate;
        }
        return "마진(매입 입력 현장 " + evaluatedSites.size() + "개 기준): 견적 " + evaluatedEstimate
                + ", 매입 " + evaluatedPurchase + ", 마진 " + totalMargin + ", 마진율 " + averageMarginRate;
    }

    private String noPurchaseExclusion() {
        return "매입 미입력 " + noPurchaseSites.size() + "개 현장은 마진율 계산에서 제외";
    }

    private static void bullets(StringBuilder sb, List<String> lines, String emptyLine) {
        if (lines.isEmpty()) {
            sb.append("- ").append(emptyLine).append("\n\n");
            return;
        }
        for (String line : lines) {
            sb.append("- ").append(line).append('\n');
        }
        sb.append('\n');
    }

    /** 모델에 넘길 사실 목록. 판단은 모두 여기서 끝나 있다. */
    public String toPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 확정 사실 (서버 계산)\n\n");
        sb.append("- 전체 현장 수: ").append(totalSites).append("개\n");
        sb.append("- 상태별: ").append(statusCounts).append('\n');
        sb.append("- 손익 평가 대상 현장: ").append(evaluatedSites.size()).append("개")
                .append(" (매입 미입력 ").append(noPurchaseSites.size()).append("개, 견적 미등록 ")
                .append(noEstimateSites.size()).append("개, 수주 전 ")
                .append(biddingSites.size()).append("개는 평가 제외)\n");
        sb.append("- 합계(수주 전 제외, 견적 미등록 현장의 매입 포함): 견적 ").append(totalEstimate).append(", 매입 ").append(totalPurchase)
                .append('\n');
        sb.append("- ").append(marginLine()).append('\n');
        if (!noPurchaseSites.isEmpty()) {
            sb.append("- ").append(noPurchaseExclusion()).append('\n');
        }
        sb.append('\n');

        section(sb, "손익 평가 대상 현장", evaluatedSites);
        section(sb, "주의 현장 (서버 판정: 적자 또는 마진율 10% 미만)", warningSites);
        section(sb, "매입 미입력 현장 (견적만 등록, 마진율 계산 제외)", noPurchaseSites);
        section(sb, "견적 미등록 현장 (손실 아님, 데이터 없음)", noEstimateSites);
        section(sb, "수주 전 현장 (계약 전, 손익 평가 제외)", biddingSites);
        section(sb, "다음 할 일 (서버 판정, 이 목록만 옮길 것)", nextActions);

        sb.append("위 사실만 사용해 요약을 작성하세요.");
        return sb.toString();
    }

    /** 상태별 건수를 enum 순서(수주 전→완료)의 한국어 라벨로 적는다. */
    static String statusCounts(Map<String, Integer> byStatus) {
        if (byStatus == null || byStatus.isEmpty()) {
            return "없음";
        }
        List<String> parts = new ArrayList<>();
        for (SiteStatus status : SiteStatus.values()) {
            Integer count = byStatus.get(status.name());
            if (count != null) {
                parts.add(status.getLabel() + " " + count + "개");
            }
        }
        byStatus.keySet().stream()
                .filter(name -> Arrays.stream(SiteStatus.values()).noneMatch(s -> s.name().equals(name)))
                .sorted()
                .forEach(name -> parts.add(name + " " + byStatus.get(name) + "개"));
        return String.join(", ", parts);
    }

    private static void section(StringBuilder sb, String title, List<String> lines) {
        sb.append("## ").append(title).append("\n\n");
        if (lines.isEmpty()) {
            sb.append("- 없음\n\n");
            return;
        }
        for (String line : lines) {
            sb.append("- ").append(line).append('\n');
        }
        sb.append('\n');
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    static String won(BigDecimal amount) {
        BigDecimal plain = amount.stripTrailingZeros();
        String pattern = plain.scale() > 0 ? "#,##0.##" : "#,##0";
        return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT)).format(plain) + "원";
    }

    static String percent(BigDecimal rate) {
        return rate.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
