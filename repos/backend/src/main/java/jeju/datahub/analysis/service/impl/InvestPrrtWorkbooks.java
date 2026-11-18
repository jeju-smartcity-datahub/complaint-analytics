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
import jeju.datahub.analysis.service.InvestPrrtLotVO;
import jeju.datahub.analysis.service.InvestPrrtWorkbookRowVO;
import jeju.datahub.analysis.service.InvestPrrtWorkbookVO;
import jeju.datahub.analysis.service.impl.InvestPrrtStngResolver.InvestPrrtStng;

/**
 * 투자우선순위 {@code wbk} 조립.
 *
 * <p>🔴 새 조회가 없다 — 백분위 개수({@link InvestPrrtCalculator.Result#lessCnts()})·계수 설정 행({@link AnlsVrblOvrd#row})·
 * 적용값({@link InvestPrrtStng}) 모두 같은 실행이 이미 계산·읽은 것이다.
 */
final class InvestPrrtWorkbooks {

    private static final Logger log = LoggerFactory.getLogger(InvestPrrtWorkbooks.class);

    /** 가중치 4개 — 엑셀은 위 3개만 입력 셀, 마지막 {@code ACCIDENT} 는 {@code =100-나머지 3개} 수식 */
    static final List<String> WEIGHT_CDS = List.of(
            InvestPrrtCalculator.VRBL_SAFETY, InvestPrrtCalculator.VRBL_COMPLAINT,
            InvestPrrtCalculator.VRBL_USAGE, InvestPrrtCalculator.VRBL_ACCIDENT);

    /**
     * 🔴 {@code vrblCd → 적용값} 대응의 유일한 정의다 — 8개를 흩어 적으면 하나가 빠져도 컴파일된다.
     * 키 집합이 {@code INVEST.getVrblCds()} 와 같은지는 {@code InvestPrrtWorkbooksTest} 가 단언한다.
     *
     * <p>🔴 가중치는 {@code stng.weights()} 에서 찾고 <b>없으면 0</b> 이다 — {@link InvestPrrtStngResolver} 가 꺼진 행과
     * 0 이하 가중치를 Map 에서 뺀다(그대로 꺼내면 {@code (0,50,50,0)} 조합에서 {@code aplcnVl} 이 {@code null} 이다).
     */
    static final Map<String, Function<InvestPrrtStng, BigDecimal>> APLCN_VL;

    static {
        Map<String, Function<InvestPrrtStng, BigDecimal>> m = new LinkedHashMap<>();
        for (String cd : WEIGHT_CDS) {
            m.put(cd, s -> s.weights().getOrDefault(cd, BigDecimal.ZERO));
        }
        m.put(InvestPrrtStngResolver.VRBL_ALTMNT_RNG, InvestPrrtStng::altmntRngVl);
        m.put(InvestPrrtStngResolver.VRBL_UP_GRD, InvestPrrtStng::upGrdCrtrScr);
        m.put(InvestPrrtStngResolver.VRBL_MD_GRD, InvestPrrtStng::mdGrdCrtrScr);
        m.put(InvestPrrtStngResolver.VRBL_INDCT_CNT, s -> BigDecimal.valueOf(s.indctPkltCnt()));
        APLCN_VL = Map.copyOf(m);
    }

    /** 항상 조건 셀(입력 아님) — 공간 배정 · 표시 수 · 사고이력(수식 셀) */
    private static final Set<String> LOCKED = Set.of(
            InvestPrrtStngResolver.VRBL_ALTMNT_RNG, InvestPrrtStngResolver.VRBL_INDCT_CNT,
            InvestPrrtCalculator.VRBL_ACCIDENT);

    private InvestPrrtWorkbooks() {
    }

    /**
     * @param ovrd     이번 실행의 계수 입력({@code INVEST}) — 이름·범위 출처
     * @param stng     이번 실행의 적용 설정
     * @param trgtCnt  계산기에 넣은 대상 수(🔴 필터 <b>전</b>)
     * @param shown    필터 통과분(응답 {@code lots}) — {@code rows} 는 이 순서 그대로다
     * @param lessCnts 계산기의 {@code pklt_id → k}
     * @return 🔴 {@code stng.weights()} 에 {@code ENFORCE} 가 있으면 {@code null}(엑셀 산식에 단속 항이 없다)
     */
    static InvestPrrtWorkbookVO of(AnlsVrblOvrd ovrd, InvestPrrtStng stng, int trgtCnt,
                                   List<InvestPrrtLotVO> shown,
                                   Map<Long, InvestPrrtCalculator.LessCnt> lessCnts) {
        if (stng.weights().containsKey(InvestPrrtCalculator.VRBL_ENFORCE)) {
            log.warn("⚠️ [INVEST] ENFORCE 가중치가 있어 엑셀 재계산 데이터를 만들지 않습니다");
            return null;
        }
        InvestPrrtWorkbookVO wbk = new InvestPrrtWorkbookVO();
        wbk.setTrgtCnt(trgtCnt);
        wbk.setRows(rows(shown, lessCnts));
        wbk.setVrbls(vrbls(ovrd, stng));
        return wbk;
    }

    /** {@code shown} 과 같은 주차장·같은 순서. 🔴 대응이 없으면(계약상 나오지 않는다) 예외 — 0 으로 메우면 엑셀 점수가 조용히 틀린다. */
    static List<InvestPrrtWorkbookRowVO> rows(List<InvestPrrtLotVO> shown,
                                              Map<Long, InvestPrrtCalculator.LessCnt> lessCnts) {
        List<InvestPrrtWorkbookRowVO> rows = new ArrayList<>(shown.size());
        for (InvestPrrtLotVO lot : shown) {
            InvestPrrtCalculator.LessCnt k = lessCnts.get(lot.getPkltId());
            if (k == null) {
                throw new IllegalStateException("백분위 개수가 없는 주차장입니다: pkltId=" + lot.getPkltId());
            }
            InvestPrrtWorkbookRowVO row = new InvestPrrtWorkbookRowVO();
            row.setPkltId(lot.getPkltId());
            row.setCvlcptLtCnt(k.cvlcpt());
            row.setUtztnLtCnt(k.utztn());
            row.setAcdntLtCnt(k.acdnt());
            rows.add(row);
        }
        return rows;
    }

    /** {@code INVEST} 8행 — 순서는 스코프 그대로(화면 팝오버·엑셀 셀 순서와 같다). */
    static List<AnlsWorkbookVrblVO> vrbls(AnlsVrblOvrd ovrd, InvestPrrtStng stng) {
        List<String> vrblCds = AnlsVrblScope.INVEST.getVrblCds();
        // 가중치 4개 중 하나라도 꺼졌으면(row null) 서버가 재정규화하므로 `=100-…` 식이 갈린다 → 4개 모두 조건 셀
        boolean weightsOn = WEIGHT_CDS.stream().allMatch(cd -> ovrd.row(cd) != null);
        List<AnlsWorkbookVrblVO> vrbls = new ArrayList<>(vrblCds.size());
        List<String> missing = new ArrayList<>();
        for (String vrblCd : vrblCds) {
            AnalysisWeightVO row = ovrd.row(vrblCd);
            Function<InvestPrrtStng, BigDecimal> getter = APLCN_VL.get(vrblCd);
            if (row == null) {
                missing.add(vrblCd);
            }
            boolean inpt = !LOCKED.contains(vrblCd) && (weightsOn || !WEIGHT_CDS.contains(vrblCd));
            // 이름·범위·intOnlyYn(N)·row null 규칙은 구역추천 조립과 같다 — 같은 메서드를 쓴다
            vrbls.add(ZoneRcmdtnFeeWorkbooks.vrbl(vrblCd, row, getter != null ? getter.apply(stng) : null, inpt));
        }
        if (!missing.isEmpty()) {
            log.warn("엑셀 계수 설정 행을 찾지 못해 이름·범위 없이 싣습니다: scope={} vrblCds={}",
                    AnlsVrblScope.INVEST, missing);
        }
        return vrbls;
    }
}
