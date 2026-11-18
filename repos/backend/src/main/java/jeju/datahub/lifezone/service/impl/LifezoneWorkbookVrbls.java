package jeju.datahub.lifezone.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.AnlsVrblOvrd;
import jeju.datahub.analysis.service.AnlsVrblScope;
import jeju.datahub.analysis.service.AnlsWorkbookVrblVO;

/**
 * 수급·혼잡도 {@code wbk.vrbls[]} 조립. 두 서비스가 같은 규칙을 쓰도록 한 곳에 둔다.
 *
 * <p>순서는 {@link AnlsVrblScope#getVrblCds()} 그대로다(화면 팝오버·엑셀 입력 셀 순서와 같다).
 * 이름·범위는 {@link AnlsVrblOvrd#row} — 🔴 resolver 가 이미 읽은 설정 행이다(새 조회 없음).
 */
final class LifezoneWorkbookVrbls {

    private static final Logger log = LoggerFactory.getLogger(LifezoneWorkbookVrbls.class);

    private static final String Y = "Y";
    private static final String N = "N";

    private LifezoneWorkbookVrbls() {
    }

    /**
     * @param aplcnVls 변수코드 → 이번 분석이 실제로 쓴 값. 스코프의 전 코드가 들어 있어야 한다
     * @param intOnly  정수 전용 여부(수급 {@code true} · 혼잡도 {@code false})
     */
    static List<AnlsWorkbookVrblVO> of(AnlsVrblScope scope, AnlsVrblOvrd ovrd,
                                           Map<String, BigDecimal> aplcnVls, boolean intOnly) {
        List<AnlsWorkbookVrblVO> vrbls = new ArrayList<>(scope.getVrblCds().size());
        List<String> missing = new ArrayList<>();
        for (String vrblCd : scope.getVrblCds()) {
            AnalysisWeightVO row = ovrd.row(vrblCd);
            AnlsWorkbookVrblVO vrbl = new AnlsWorkbookVrblVO();
            vrbl.setVrblCd(vrblCd);
            vrbl.setAplcnVl(plain(aplcnVls.get(vrblCd)));
            vrbl.setIntOnlyYn(intOnly ? Y : N);
            if (row == null) {
                missing.add(vrblCd);
                vrbl.setVrblNm(vrblCd);
                // (구역추천) 설정 행이 없으면 입력 셀이 아니다(설계 결정과 같은 뜻 · 키 추가뿐)
                vrbl.setInptYn(N);
            } else {
                // 숨김(expsr_yn='N') 계수는 회색 조건 셀로 잠근다(이름·범위는 그대로 싣는다 · null 은 노출)
                vrbl.setInptYn(N.equals(row.getExpsrYn()) ? N : Y);
                vrbl.setVrblNm(row.getVrblNm() != null ? row.getVrblNm() : vrblCd);
                vrbl.setMinVl(plain(row.getMinVl()));
                vrbl.setMaxVl(plain(row.getMaxVl()));
            }
            vrbls.add(vrbl);
        }
        if (!missing.isEmpty()) {
            log.warn("엑셀 계수 설정 행을 찾지 못해 이름·범위 없이 싣습니다: scope={} vrblCds={}", scope, missing);
        }
        return vrbls;
    }

    /** {@code stripTrailingZeros} 만 쓰면 {@code 100} 이 {@code 1E+2} 가 된다({@code AnlsVrblOvrd.plain} 과 같은 규칙). */
    static BigDecimal plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
