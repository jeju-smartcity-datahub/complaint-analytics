package jeju.datahub.policysim.service.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Component;

import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.AnlsVrblOvrd;
import jeju.datahub.analysis.service.impl.AnalysisWeightMapper;
import lombok.RequiredArgsConstructor;

/**
 * 정책 시뮬레이션 기준선 산정 — C0·E0·U0·S·k·H0 (TSK-4037 Step 2 · 명세 1~5장 + 수정 4·5).
 *
 * <pre>
 * C0_e  = 주차민원(cvlcpt_type_cd &lt;&gt; '99') 건수 ÷ M_민원          (건/월)
 * Enf_e = (N_PDA + N_현장 + w_fixed × N_CCTV) ÷ M_단속              (건/월)
 * E0_e  = m × Enf_e ÷ 30 × g_sgg                                  (대)
 * U0_e  = S_e × occ                                               (대)
 * S_e   = sum(pklt_cpcty) — 민영 제외 · 소멸 제외
 * k_e   = C0_e ÷ E0_e          ← E0_e = 0 이면 NO_ENF (k = null)
 * H0_e  = (U0_e + E0_e) ÷ S_e  ← S_e = 0 이면 null. E0_e &gt; 0 이면 NO_SPLY
 * </pre>
 *
 * <p>🔴 <b>민원·단속이 같은 구간을 쓴다 — 최근 완결 {@value #MONTHS}개월</b>(TSK-4309 — 명세는 12개월, 잠정 결정).
 * 끝은 전월 말이고, 원천의 최신 달이 전월보다 앞서면 그 달까지로 당긴다. 진행 중인 달과 미래 날짜는 쓰지 않는다.
 * 민원을 구간 전체만큼 보유하지 못했으면 보유 개월수로 나눈다.
 * 🔴 개월수를 바꾸면 {@code g_sgg}(좌표 결측 보정)도 함께 본다 — 좌표 보유율이 기간마다 달라 E0 를 좌우한다.
 *
 * <p>🔴 계수는 {@code AnalysisWeightService.coefficient()} 13회가 아니라 {@code selectWeightsByType} <b>1회</b>로 읽고
 * {@link PolicySimParams#resolve} 가 13종 존재를 확인한다(없으면 503 — 1.0 폴백 금지).
 *
 * <p>산식은 DB 없이 검증할 수 있도록 정적 메서드({@link #cvlcptWindow}·{@link #computeEmd})로 분리했다.
 */
@Component
@RequiredArgsConstructor
public class PolicySimBaselineCalculator {

    /** 관측 개월수 M (명세는 12개월 — TSK-4309 잠정값) */
    public static final int MONTHS = 3;

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 월 → 일 환산 (E0 = m × Enf ÷ 30) */
    private static final double DAYS_PER_MONTH = 30.0;

    private final AnalysisWeightMapper analysisWeightMapper;
    private final PolicySimMapper policySimMapper;

    /**
     * 전 읍면동 기준선을 산정한다.
     *
     * @throws jeju.datahub.policysim.service.PolicySimParamMissingException 필수 계수 누락 (503)
     */
    public PolicySimBaseline calculate() {
        return calculate(null);
    }

    /**
     * 지정한 읍면동만 기준선을 산정한다 (TSK-4038 Step 3 — 인접 읍면동 {@code E0_n}·{@code C0_n}·{@code k_n}).
     *
     * <p>🔴 <b>구간·계수·산식은 {@link #calculate()} 와 같은 코드 경로다</b> — 단속 구간은 상수, 민원 구간은 같은
     * {@code selectCvlcptPeriod} 로 정하므로 같은 요청 안에서 대상 기준선과 같은 구간이 잡힌다.
     * 🔴 {@code g_sgg} 는 행마다 <b>자기 시</b>의 값이다({@link #computeEmd} 가 {@code r.getSggNm()} 으로 고른다) —
     * 대상 지역의 값을 물려주지 않는다(제주시 대상의 서귀포 인접에 2.890 을 쓰면 E0_n 이 2.09배 부푼다).
     *
     * @param emdCds 읍면동 코드 — null 이면 전 읍면동(= {@link #calculate()}), 빈 목록이면 집계 쿼리 없이 빈 {@code emds}
     * @throws jeju.datahub.policysim.service.PolicySimParamMissingException 필수 계수 누락 (503)
     */
    public PolicySimBaseline calculate(List<String> emdCds) {
        return calculate(emdCds, PolicySimParams.REQUIRED_VRBL_CDS);
    }

    /**
     * 필수 계수 목록을 지정해 산정한다 (TSK-4038 Step 4-2) — REPAIR 요청은
     * {@link PolicySimParams#REPAIR_REQUIRED_VRBL_CDS}(17종)를 넘겨 이면도로 4종까지 <b>같은 1회 조회</b>로 확인·해석한다.
     * 산식·구간은 위와 같다(이면도로 계수는 기준선 산식에 쓰이지 않는다).
     *
     * @param emdCds          null = 전 읍면동
     * @param requiredVrblCds 각각 존재를 확인할 {@code vrbl_cd} 목록
     * @throws jeju.datahub.policysim.service.PolicySimParamMissingException 필수 계수 누락 (503)
     */
    public PolicySimBaseline calculate(List<String> emdCds, List<String> requiredVrblCds) {
        return calculate(emdCds, requiredVrblCds, null);
    }

    /**
     * 분석 계수 일시 조정을 반영한 기준선 (TSK-4332) — 설정 행 목록에 {@link AnlsVrblOvrd#apply} 를 끼운다.
     * 기준선·확충·이면도로·요금제·민감도가 모두 이 {@code params} 를 따른다.
     *
     * @param ovrd null = 설정값 그대로(기존 오버로드와 같다)
     */
    public PolicySimBaseline calculate(List<String> emdCds, List<String> requiredVrblCds, AnlsVrblOvrd ovrd) {
        // 1) 계수 먼저 — 누락이면 집계 쿼리를 돌리지 않고 끊는다
        List<AnalysisWeightVO> weightRows = analysisWeightMapper.selectWeightsByType(PolicySimParams.ANLS_TYPE_CD);
        PolicySimParams params = PolicySimParams.resolve(
                ovrd == null ? weightRows : ovrd.apply(weightRows), requiredVrblCds);

        // 2) 기준 구간 — 민원·단속 공통, 최근 완결 M개월
        PolicySimCvlcptPeriodRow period = policySimMapper.selectCvlcptPeriod();
        LocalDateTime minOcrnDt = period != null ? period.getMinOcrnDt() : null;
        LocalDateTime maxOcrnDt = period != null ? period.getMaxOcrnDt() : null;
        BaselineWindow w = baselineWindow(LocalDate.now(ZONE), maxOcrnDt, policySimMapper.selectMaxCrdnDt());
        int cvlcptMonths = cvlcptMonths(minOcrnDt, w);

        // 3) 읍면동별 원자료 — 빈 코드 목록은 IN () 문법 오류라 쿼리를 돌리지 않는다(인접 공집합 — 섬·제주 전체 선택)
        List<PolicySimEmdBaseline> emds;
        if (emdCds != null && emdCds.isEmpty()) {
            emds = List.of();
        } else {
            LocalDateTime bgng = w.bgngYmd().atStartOfDay();
            LocalDateTime end = w.endYmdExcl().atStartOfDay();
            List<PolicySimEmdCountRow> rows = policySimMapper.selectEmdCounts(new PolicySimBaselineQueryParam(
                    bgng, end, bgng, end,
                    emdCds != null ? List.copyOf(emdCds) : null));

            // 4) 산식
            emds = rows.stream()
                    .map(r -> computeEmd(r, params, cvlcptMonths, MONTHS))
                    .toList();
        }

        LocalDate endYmd = w.endYmdExcl().minusDays(1);
        // 민원 0건이면 민원 구간을 비운다 — 응답 cvlcptPrd = null 계약
        return new PolicySimBaseline(params,
                w.bgngYmd(), endYmd, MONTHS,
                minOcrnDt != null ? w.bgngYmd() : null, minOcrnDt != null ? endYmd : null, cvlcptMonths,
                emds);
    }

    /** 오늘(Asia/Seoul) 기준 구간 — 기준선과 같은 구간이 필요한 곳(통합테스트 대조 등)이 쓴다. */
    public BaselineWindow currentWindow() {
        PolicySimCvlcptPeriodRow period = policySimMapper.selectCvlcptPeriod();
        return baselineWindow(LocalDate.now(ZONE), period != null ? period.getMaxOcrnDt() : null,
                policySimMapper.selectMaxCrdnDt());
    }

    /**
     * 기준 구간 — 끝 달은 {@code min(전월, 민원 최신 달, 단속 최신 달)}, 길이는 {@value #MONTHS}개월(반열림).
     * 🔴 최신 달이 미래여도 전월까지만 쓴다(로컬 시드에 미래 날짜가 있다). null(0건)인 원천은 판정에서 뺀다.
     */
    public static BaselineWindow baselineWindow(LocalDate today, LocalDateTime maxOcrnDt, LocalDateTime maxCrdnDt) {
        YearMonth end = YearMonth.from(today).minusMonths(1);
        if (maxOcrnDt != null && YearMonth.from(maxOcrnDt).isBefore(end)) {
            end = YearMonth.from(maxOcrnDt);
        }
        if (maxCrdnDt != null && YearMonth.from(maxCrdnDt).isBefore(end)) {
            end = YearMonth.from(maxCrdnDt);
        }
        return new BaselineWindow(end.minusMonths(MONTHS - 1L).atDay(1), end.plusMonths(1).atDay(1));
    }

    /** 민원 나눗수 — 구간 중 민원 첫 달 이후 개월수. 민원이 없거나 구간 뒤에만 있으면 0(C0 = 0). */
    static int cvlcptMonths(LocalDateTime minOcrnDt, BaselineWindow w) {
        if (minOcrnDt == null) {
            return 0;
        }
        YearMonth first = YearMonth.from(minOcrnDt);
        YearMonth bgng = YearMonth.from(w.bgngYmd());
        YearMonth from = first.isAfter(bgng) ? first : bgng;
        long held = ChronoUnit.MONTHS.between(from, YearMonth.from(w.endYmdExcl()));
        return (int) Math.max(0, held);
    }

    /**
     * 읍면동 1곳의 기준선 (순수 계산).
     *
     * @param cvlcptMonths 민원 나눗수 (0 이면 C0 = 0)
     * @param crdnMonths   단속 나눗수 (M)
     */
    public static PolicySimEmdBaseline computeEmd(PolicySimEmdCountRow r, PolicySimParams p,
                                                  int cvlcptMonths, int crdnMonths) {
        double c0 = cvlcptMonths > 0 ? (double) r.getCvlcptCnt() / cvlcptMonths : 0.0;
        double enf = (r.getPdaCrdnCnt() + r.getFldCrdnCnt() + p.fixWgvl() * r.getCctvCrdnCnt()) / crdnMonths;
        double g = p.geoCvrgCrct(r.getSggNm());
        double e0 = p.dsclMltp() * enf / DAYS_PER_MONTH * g;
        long s = r.getPkltCpctySum();
        double u0 = s * p.occ();

        Double k = e0 > 0 ? c0 / e0 : null;
        Double h0 = s > 0 ? (u0 + e0) / s : null;
        PolicySimCalcStts stts = e0 <= 0 ? PolicySimCalcStts.NO_ENF
                : s <= 0 ? PolicySimCalcStts.NO_SPLY
                : null;

        return new PolicySimEmdBaseline(r.getEmdCd(), r.getSggNm(), r.getEmdNm(),
                c0, enf, e0, u0, s, g, k, h0, stts);
    }

    /**
     * 기준 구간 (민원·단속 공통).
     *
     * @param bgngYmd    시작 (포함)
     * @param endYmdExcl 끝 (제외)
     */
    public record BaselineWindow(LocalDate bgngYmd, LocalDate endYmdExcl) {
    }
}
