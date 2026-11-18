package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.AnlsVrblOvrd;
import jeju.datahub.analysis.service.AnlsVrblScope;
import jeju.datahub.analysis.service.AnlsWorkbookVrblVO;
import jeju.datahub.analysis.service.ZoneRcmdtnCrtrVls;
import jeju.datahub.analysis.service.ZoneRcmdtnFeeWorkbookFixedVO;
import jeju.datahub.analysis.service.ZoneRcmdtnFeeWorkbookVO;

/**
 * 구역추천 요금제 {@code wbk} 조립 (TSK-4340 Step 1 · D1·D2·D3·D5·D6).
 *
 * <p>🔴 새 조회가 없다 — 기준선 행·계수 설정 행({@link AnlsVrblOvrd#row})·적용값({@code crtrVls}) 모두
 * 같은 실행이 이미 읽은 것이다. 생활권 {@code LifezoneWorkbookVrbls} 는 package-private 이라 재사용하지 않는다.
 */
final class ZoneRcmdtnFeeWorkbooks {

    private static final Logger log = LoggerFactory.getLogger(ZoneRcmdtnFeeWorkbooks.class);

    private static final String Y = "Y";
    private static final String N = "N";

    static final String CNT_WGVL = "FEE_CVLCPT_CNT_WGVL";
    static final String RT_WGVL = "FEE_CVLCPT_RT_WGVL";

    /**
     * 🔴 {@code vrblCd → crtrVls 칸} 대응의 유일한 정의다 — 10개를 흩어 적으면 하나가 빠져도 컴파일된다.
     * 키 집합이 {@code RECOMMEND_FEE.getVrblCds()} 와 같은지는 {@code ZoneRcmdtnFeeWorkbooksTest} 가 단언한다.
     */
    static final Map<String, Function<ZoneRcmdtnCrtrVls, BigDecimal>> APLCN_VL;

    static {
        Map<String, Function<ZoneRcmdtnCrtrVls, BigDecimal>> m = new LinkedHashMap<>();
        m.put("ALTMNT_RNG_VL", ZoneRcmdtnCrtrVls::getAltmntRngVl);
        m.put("SWTC_TRGT_MIN_CPCTY", ZoneRcmdtnCrtrVls::getSwtcTrgtMinCpcty);
        m.put(CNT_WGVL, ZoneRcmdtnCrtrVls::getFeeCvlcptCntWgvl);
        m.put(RT_WGVL, ZoneRcmdtnCrtrVls::getFeeCvlcptRtWgvl);
        m.put("FEE_CVLCPT_MM_CRTR_JEJU", ZoneRcmdtnCrtrVls::getFeeCvlcptMmCrtrJeju);
        m.put("FEE_CVLCPT_MM_CRTR_SEOGWIPO", ZoneRcmdtnCrtrVls::getFeeCvlcptMmCrtrSeogwipo);
        m.put("FEE_CVLCPT_RT_CRTR_JEJU", ZoneRcmdtnCrtrVls::getFeeCvlcptRtCrtrJeju);
        m.put("FEE_CVLCPT_RT_CRTR_SEOGWIPO", ZoneRcmdtnCrtrVls::getFeeCvlcptRtCrtrSeogwipo);
        m.put("FEE_CVLCPT_INCRS_WGVL", ZoneRcmdtnCrtrVls::getFeeCvlcptIncrsWgvl);
        m.put("FEE_CVLCPT_INCRS_RT_UPLMT_VL", ZoneRcmdtnCrtrVls::getFeeCvlcptIncrsRtUplmtVl);
        APLCN_VL = Map.copyOf(m);
    }

    /** D1 — 공간 배정 계수라 엑셀에서 다시 계산할 수 없다(항상 조건 셀). */
    private static final Set<String> SPATIAL = Set.of("ALTMNT_RNG_VL", "SWTC_TRGT_MIN_CPCTY");

    private ZoneRcmdtnFeeWorkbooks() {
    }

    /**
     * @param ovrd    이번 실행의 계수 입력({@code RECOMMEND_FEE}) — 이름·범위 출처(D5)
     * @param months  민원 창 개월수({@code param.getMonths()})
     * @param applied 응답 {@code crtrVls}(적용값 — 일시 조정 반영)
     */
    static ZoneRcmdtnFeeWorkbookVO of(AnlsVrblOvrd ovrd, BigDecimal months, ZoneRcmdtnCrtrVls applied) {
        ZoneRcmdtnFeeWorkbookVO wbk = new ZoneRcmdtnFeeWorkbookVO();
        wbk.setMonths(months);
        ZoneRcmdtnFeeWorkbookFixedVO fixed = new ZoneRcmdtnFeeWorkbookFixedVO();
        fixed.setPrrtTop(ZoneRcmdtnCalculator.PRRT_TOP);
        fixed.setPrrtHigh(ZoneRcmdtnCalculator.PRRT_HIGH);
        wbk.setFixed(fixed);
        wbk.setVrbls(vrbls(ovrd, applied));
        return wbk;
    }

    /** {@code RECOMMEND_FEE} 10행 — 순서는 스코프 그대로(화면 팝오버·엑셀 셀 순서와 같다). */
    static List<AnlsWorkbookVrblVO> vrbls(AnlsVrblOvrd ovrd, ZoneRcmdtnCrtrVls applied) {
        List<String> vrblCds = AnlsVrblScope.RECOMMEND_FEE.getVrblCds();
        // D3 예외 — 가중치 둘 중 하나라도 꺼졌으면(row null) 쌍 수식을 쓰지 않고 둘 다 조건 셀이다
        boolean wgtPairOn = ovrd.row(CNT_WGVL) != null && ovrd.row(RT_WGVL) != null;
        List<AnlsWorkbookVrblVO> vrbls = new ArrayList<>(vrblCds.size());
        List<String> missing = new ArrayList<>();
        for (String vrblCd : vrblCds) {
            AnalysisWeightVO row = ovrd.row(vrblCd);
            Function<ZoneRcmdtnCrtrVls, BigDecimal> getter = APLCN_VL.get(vrblCd);
            if (row == null) {
                missing.add(vrblCd);
            }
            vrbls.add(vrbl(vrblCd, row, getter != null ? getter.apply(applied) : null,
                    isInput(vrblCd, row != null, wgtPairOn)));
        }
        if (!missing.isEmpty()) {
            log.warn("엑셀 계수 설정 행을 찾지 못해 이름·범위 없이 싣습니다: scope={} vrblCds={}",
                    AnlsVrblScope.RECOMMEND_FEE, missing);
        }
        return vrbls;
    }

    /**
     * 계수 1행 — 이름·범위는 설정 행({@code row}), 적용값은 이번 실행 값, {@code intOnlyYn} 은 늘 {@code N}
     * (RECOMMEND 는 정수 전용 계수가 없다 — D4·D5). 🔴 {@code row} 가 {@code null} 이면 이름 {@code vrblCd} · 범위 {@code null}.
     * 공유주차제 조립({@code ZoneRcmdtnShareWorkbooks})도 이 메서드를 쓴다(입력 셀 규칙만 다르다).
     *
     * @param inpt 입력 셀 여부 — 🔴 {@code row} 가 {@code null} 이면 호출부가 무엇을 넘기든 {@code N} 이다(D5)
     */
    static AnlsWorkbookVrblVO vrbl(String vrblCd, AnalysisWeightVO row, BigDecimal aplcnVl, boolean inpt) {
        AnlsWorkbookVrblVO vrbl = new AnlsWorkbookVrblVO();
        vrbl.setVrblCd(vrblCd);
        vrbl.setAplcnVl(plain(aplcnVl));
        vrbl.setIntOnlyYn(N);
        if (row == null) {
            vrbl.setVrblNm(vrblCd);
        } else {
            vrbl.setVrblNm(row.getVrblNm() != null ? row.getVrblNm() : vrblCd);
            vrbl.setMinVl(plain(row.getMinVl()));
            vrbl.setMaxVl(plain(row.getMaxVl()));
        }
        vrbl.setInptYn(row != null && inpt ? Y : N);
        return vrbl;
    }

    /** 입력 셀 규칙(D1·D3·D5 — 🔁 D2 판정 계수는 스코프에서 빠졌다). */
    private static boolean isInput(String vrblCd, boolean rowOn, boolean wgtPairOn) {
        if (!rowOn || SPATIAL.contains(vrblCd) || RT_WGVL.equals(vrblCd)) {
            return false;
        }
        return !CNT_WGVL.equals(vrblCd) || wgtPairOn;
    }

    /** {@code stripTrailingZeros} 만 쓰면 {@code 100} 이 {@code 1E+2} 가 된다({@code AnlsVrblOvrd.plain} 과 같은 규칙). */
    private static BigDecimal plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
