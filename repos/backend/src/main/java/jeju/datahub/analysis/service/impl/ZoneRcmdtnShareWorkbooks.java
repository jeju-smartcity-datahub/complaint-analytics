package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.AnlsVrblOvrd;
import jeju.datahub.analysis.service.AnlsVrblScope;
import jeju.datahub.analysis.service.AnlsWorkbookVrblVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareResultVO;

/**
 * 구역추천 공유주차제 {@code wbk.vrbls} 조립.
 *
 * <p>🔴 새 조회가 없다 — 계수 설정 행({@link AnlsVrblOvrd#row})·적용값(응답 {@code crtrVls}) 모두 같은 실행이 이미 읽은 것이다.
 * 행 1개 조립은 요금제 {@link ZoneRcmdtnFeeWorkbooks#vrbl} 를 그대로 쓴다. 공유주차제 4행은 공간·시군구 모드·쌍 수식
 * 예외가 없어 <b>설정 행이 있으면 전부 입력 셀</b>이다.
 */
final class ZoneRcmdtnShareWorkbooks {

    private static final Logger log = LoggerFactory.getLogger(ZoneRcmdtnShareWorkbooks.class);

    /**
     * 🔴 {@code vrblCd → crtrVls 칸} 대응의 유일한 정의다. 키 집합이 {@code RECOMMEND_SHARE.getVrblCds()} 와 같은지는
     * {@code ZoneRcmdtnShareWorkbookTest} 가 단언한다.
     */
    static final Map<String, Function<ZoneRcmdtnShareResultVO.CrtrVls, BigDecimal>> APLCN_VL;

    static {
        Map<String, Function<ZoneRcmdtnShareResultVO.CrtrVls, BigDecimal>> m = new LinkedHashMap<>();
        m.put(ZoneRcmdtnShareCalculator.VRBL_CVLCPT_MM_CRTR, ZoneRcmdtnShareResultVO.CrtrVls::getCvlcptMmCrtrVl);
        m.put(ZoneRcmdtnShareCalculator.VRBL_CVLCPT_RT_CRTR, ZoneRcmdtnShareResultVO.CrtrVls::getCvlcptRtCrtrVl);
        m.put(ZoneRcmdtnShareCalculator.VRBL_CVLCPT_INCRS_WGVL, ZoneRcmdtnShareResultVO.CrtrVls::getIncrsWgvl);
        m.put(ZoneRcmdtnShareCalculator.VRBL_CVLCPT_INCRS_RT_UPLMT, ZoneRcmdtnShareResultVO.CrtrVls::getIncrsRtUplmtVl);
        APLCN_VL = Map.copyOf(m);
    }

    private ZoneRcmdtnShareWorkbooks() {
    }

    /**
     * {@code RECOMMEND_SHARE} 4행 — 순서는 스코프 그대로.
     *
     * @param ovrd    이번 실행의 계수 입력 — 이름·범위 출처
     * @param applied 응답 {@code crtrVls}(적용값 — 일시 조정 반영)
     */
    static List<AnlsWorkbookVrblVO> vrbls(AnlsVrblOvrd ovrd, ZoneRcmdtnShareResultVO.CrtrVls applied) {
        List<String> vrblCds = AnlsVrblScope.RECOMMEND_SHARE.getVrblCds();
        List<AnlsWorkbookVrblVO> vrbls = new ArrayList<>(vrblCds.size());
        List<String> missing = new ArrayList<>();
        for (String vrblCd : vrblCds) {
            AnalysisWeightVO row = ovrd.row(vrblCd);
            if (row == null) {
                missing.add(vrblCd);
            }
            Function<ZoneRcmdtnShareResultVO.CrtrVls, BigDecimal> getter = APLCN_VL.get(vrblCd);
            vrbls.add(ZoneRcmdtnFeeWorkbooks.vrbl(vrblCd, row,
                    getter != null && applied != null ? getter.apply(applied) : null, true));
        }
        if (!missing.isEmpty()) {
            log.warn("엑셀 계수 설정 행을 찾지 못해 이름·범위 없이 싣습니다: scope={} vrblCds={}",
                    AnlsVrblScope.RECOMMEND_SHARE, missing);
        }
        return vrbls;
    }
}
