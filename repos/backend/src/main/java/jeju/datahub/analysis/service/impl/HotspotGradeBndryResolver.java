package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.AnlsVrblOvrd;
import jeju.datahub.analysis.service.HotspotGradeBndry;
import lombok.RequiredArgsConstructor;

/**
 * 집중구역 등급 순위 비율 로더 (TSK-4321). 요청마다 {@code parking.TB_ANLS_VRBL_STNG} 를 읽어 화면 저장이 바로 반영된다.
 *
 * <p>🔴 <b>예외를 던지지 않는다</b> — 설정 행 하나가 빠졌다고 분석 화면이 500 이 되면 안 된다. 쓸 수 없으면
 * WARN 1줄과 함께 기본값으로 떨어진다. 저장 API 가 막는 조건(정수 · 심각 &lt; 경고 &lt; 주의)도 여기서
 * 한 번 더 본다 — DB 직접 수정은 그 가드 밖이고, 순서가 뒤집히면 등급이 조용히 틀어진다.
 * 🔴 예외는 일시 입력이 있을 때 하나다({@link #resolve(AnlsVrblOvrd)}).
 */
@Component
@RequiredArgsConstructor
public class HotspotGradeBndryResolver {

    private static final Logger log = LoggerFactory.getLogger(HotspotGradeBndryResolver.class);

    private static final String ANLS_TYPE_CD = "HOTSPOT";
    private static final String USE_Y = "Y";

    private final AnalysisWeightMapper analysisWeightMapper;

    /** @return 항상 정수 · {@code 1 ≤ 심각 < 경고 < 주의 ≤ 100} 을 만족하는 한 벌 */
    public HotspotGradeBndry resolve() {
        HotspotGradeBndry bndry = toBndry(analysisWeightMapper.selectWeightsByType(ANLS_TYPE_CD));
        return bndry != null ? bndry : HotspotGradeBndry.DEFAULT;
    }

    /**
     * 설정값 위에 일시 입력을 얹어 읽는다 (TSK-4325). 입력이 없으면 {@link #resolve()} 와 같다.
     *
     * <p>🔴 입력이 있는데 기본값으로 떨어지면 안 된다 — 적용 내역({@code vrblAjmt})은 입력값을 적는데 등급은 기본값으로
     * 갈린다. 입력 검증은 {@code stng_vl} 위에서 하지만 이 판정은 꺼진 행에 {@code bsc_stng_vl} 을 쓰므로
     * 검증을 통과하고도 순서가 깨질 수 있어 그때는 400 으로 끊는다.
     *
     * @throws ResponseStatusException 400 — 입력을 적용하면 등급 비율이 성립하지 않을 때
     */
    public HotspotGradeBndry resolve(AnlsVrblOvrd ovrd) {
        if (ovrd.isEmpty()) {
            return resolve();
        }
        HotspotGradeBndry bndry = toBndry(ovrd.apply(analysisWeightMapper.selectWeightsByType(ANLS_TYPE_CD)));
        if (bndry == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "입력한 등급 비율을 적용할 수 없습니다. 심각 < 경고 < 주의 순서를 확인하세요.");
        }
        return bndry;
    }

    /** @return 쓸 수 없으면 {@code null} */
    private static HotspotGradeBndry toBndry(List<AnalysisWeightVO> rows) {
        Integer severe = resolveOne(rows, "GRD_BNDRY1");
        Integer warn = resolveOne(rows, "GRD_BNDRY2");
        Integer caution = resolveOne(rows, "GRD_BNDRY3");

        if (severe == null || warn == null || caution == null
                || severe < 1 || severe >= warn || warn >= caution || caution > 100) {
            log.warn("⚠️ 집중구역 등급 비율을 쓸 수 없습니다(설정만이면 기본값 {} · 일시 입력이 있으면 400): 심각={}, 경고={}, 주의={}. "
                            + "parking.tb_anls_vrbl_stng 의 GRD_BNDRY1~3 을 확인하세요.",
                    HotspotGradeBndry.DEFAULT, severe, warn, caution);
            return null;
        }
        return new HotspotGradeBndry(severe, warn, caution);
    }

    /** 행이 없거나 정수가 아니면 {@code null}. 꺼져 있거나 값이 비면 {@code bsc_stng_vl} 을 쓴다. */
    private static Integer resolveOne(List<AnalysisWeightVO> rows, String vrblCd) {
        if (rows == null) {
            return null;
        }
        for (AnalysisWeightVO row : rows) {
            if (row != null && vrblCd.equals(row.getVrblCd())) {
                BigDecimal value = USE_Y.equals(row.getUseYn()) && row.getStngVl() != null
                        ? row.getStngVl()
                        : row.getBscStngVl();
                if (value == null || value.stripTrailingZeros().scale() > 0) {
                    return null;
                }
                return value.intValue();
            }
        }
        return null;
    }
}
