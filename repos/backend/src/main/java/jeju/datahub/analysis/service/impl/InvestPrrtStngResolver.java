package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.analysis.service.AnalysisWeightService;
import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.AnlsVrblOvrd;
import lombok.RequiredArgsConstructor;

/**
 * 투자우선순위 분석 설정 로더 (TSK-4036 Step 5) — 가중치 + 계수 4개를 한 벌로 읽는다.
 *
 * <p>설정 마스터({@code parking.TB_ANLS_VRBL_STNG}, {@code anls_type_cd='INVEST'})의 소유자는
 * TSK-4020 이다 — 여기서는 <b>조회만</b> 한다.
 *
 * <h2>가중치({@code WGVL}) — {@link AnalysisWeightService#selectEffectiveWeights(String)}</h2>
 * 🔴 <b>재정규화 산식을 여기 다시 구현하지 않는다</b>({@link HotspotWeightResolver} 와 같은 기준).
 * 그 창구가 {@code use_yn='N'} 을 뺀 뒤 합계 100 으로 다시 맞춰 돌려준다. 이 클래스는 거기서
 * <b>산식이 아는 5개</b>({@link InvestPrrtCalculator#KNOWN_VRBL_CDS}) 중 <b>값이 0 보다 큰 것</b>만
 * 남긴다 — 0 이거나 꺼진 지표는 점수 계산에서도 응답 {@code weights} 에서도 빠진다(공통 규약 §2-1:
 * 실제 반영한 변수만 되비춘다). 산식에 항이 없는 변수는 WARN 1줄을 남기고 무시한다.
 * ({@code ACCIDENT} 는 TSK-4296 부터 산식이 아는 변수다.)
 *
 * <p>🔴 <b>남는 가중치가 하나도 없으면 409</b> 다 — 조용히 전부 0점을 만들지 않는다. 0점 결과는
 * "자료가 없다" 와 구분되지 않아 설정 실수가 정상 응답으로 위장된다.
 *
 * <h2>계수({@code CFC}) 4개 — {@link AnalysisWeightMapper#selectWeightsByType(String)}</h2>
 * {@code ALTMNT_RNG_VL}(배정 반경 m) · {@code UP_GRD_CRTR_SCR}('높음' 임계) ·
 * {@code MD_GRD_CRTR_SCR}('중간' 임계) · {@code INDCT_PKLT_CNT}(표시 주차장 수).
 *
 * <p>🔴 <b>계수 단건 조회 창구({@code AnalysisWeightService} 의 계수 메서드)를 쓰지 않는다</b> —
 * 그 구현은 행 자체가 없으면 <b>{@code 1.0}</b> 을 돌려준다. 여기서 {@code 1.0} 로 떨어지면 임계가 1점이 되어 <b>거의 전부 '높음'</b>,
 * 반경이 1 m 가 되어 <b>민원·단속이 전부 0건</b>이 되는데 예외도 500 도 없이 조용히 틀린다.
 * 그래서 행 유무를 이 클래스가 판정하고, 폴백은 아래 시드 기본값 상수로만 떨어진다
 * (행 없음 → 기본값 / 꺼짐·빈 값 → {@code bsc_stng_vl} → 기본값 / 범위 밖 → 기본값. 각각 WARN 1줄).
 *
 * <p>🔴 <b>임계 역전({@code 중간 ≥ 높음})은 409</b> 다. 설정 화면이 행마다 범위(0~100)만 검사해
 * 역전된 값이 저장될 수 있다 — 그대로 계산하면 '중간' 등급이 영영 나오지 않는다.
 * ⚠️ 저장 시점 검증({@code AnalysisWeightServiceImpl} 의 LIFEZONE 단조 검증과 같은 방식)은 후속 과제다.
 *
 * <p>🔴 {@code INDCT_PKLT_CNT} 는 <b>계산에 쓰지 않는다</b> — 화면 표시 설정이며 응답
 * {@code crtrVls.indctPkltCnt} 로 되비추기만 한다.
 *
 * <h2>일시 입력 (TSK-4325) — {@link #resolve(AnlsVrblOvrd)}</h2>
 * 입력 검증(범위·합계 100·{@code 중간 < 상위})은 {@code AnlsVrblOvrdResolver} 가 끝낸 뒤다. 여기서는 값을 얹기만 한다.
 * 🔴 설정 표에 쓰지 않는다.
 */
@Component
@RequiredArgsConstructor
public class InvestPrrtStngResolver {

    private static final Logger log = LoggerFactory.getLogger(InvestPrrtStngResolver.class);

    /** 이 로더가 읽는 분석유형 (ANLS_TYPE_CD 공통코드) */
    static final String ANLS_TYPE_CD = "INVEST";

    /** 변수구분코드 — 가중치. 점수 산식에 넣는 것은 이 행뿐이다(TSK-4140 D2) */
    private static final String VRBL_SE_WGVL = "WGVL";
    /** 사용여부 컬럼({@code use_yn})의 '사용' 값. CHAR(1) 문자열이다 */
    private static final String USE_Y = "Y";

    static final String VRBL_ALTMNT_RNG = "ALTMNT_RNG_VL";
    static final String VRBL_UP_GRD = "UP_GRD_CRTR_SCR";
    static final String VRBL_MD_GRD = "MD_GRD_CRTR_SCR";
    static final String VRBL_INDCT_CNT = "INDCT_PKLT_CNT";

    // 🔴 아래 네 기본값은 마이그레이션 90(database/migrations/sql/87-INVEST-VRBL-STNG.sql)의
    //    bsc_stng_vl 과 같은 숫자여야 한다 — 어긋나면 "행이 없을 때" 와 "있을 때" 의 결과가 달라진다.
    private static final BigDecimal DEFAULT_ALTMNT_RNG = new BigDecimal("300");
    private static final BigDecimal DEFAULT_UP_GRD = new BigDecimal("80");
    private static final BigDecimal DEFAULT_MD_GRD = new BigDecimal("60");
    private static final BigDecimal DEFAULT_INDCT_CNT = new BigDecimal("50");

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** TSK-4020 산출물 — 재정규화된 가중치 창구(크로스-TSK 계약) */
    private final AnalysisWeightService analysisWeightService;

    /** 계수 원본 행 창구(읽기 전용). 서비스의 계수 단건 조회를 쓰지 않는 이유는 클래스 주석 참고 */
    private final AnalysisWeightMapper analysisWeightMapper;

    /**
     * 가중치와 계수 4개를 읽는다.
     *
     * @return 계산기에 넘길 가중치 Map · 응답에 되비출 가중치 목록 · 계수 4개
     * @throws ResponseStatusException 409 — 반영할 가중치가 하나도 없거나, 임계가 역전됐을 때
     */
    public InvestPrrtStng resolve() {
        return load(null);
    }

    /**
     * 설정값 위에 일시 입력을 얹어 읽는다 (TSK-4325). 입력이 없으면 {@link #resolve()} 와 같다.
     *
     * <p>0 인 가중치는 {@link #resolve()} 와 같이 {@code weights} 에서 빠진다(적용 내역 {@code vrblAjmt} 에는 남는다).
     *
     * @throws ResponseStatusException 400 — 등급 기준을 입력했는데 적용 결과가 역전될 때(D4) /
     *         409 — 입력이 등급 기준에 닿지 않았는데 설정이 역전됐을 때({@link #resolve()} 와 같다)
     */
    public InvestPrrtStng resolve(AnlsVrblOvrd ovrd) {
        return load(ovrd.isEmpty() ? null : ovrd);
    }

    /** @param ovrd 일시 입력. 없으면 {@code null} */
    private InvestPrrtStng load(AnlsVrblOvrd ovrd) {
        // ---- 가중치 ----
        List<AnalysisWeightVO> effective = weightSource(ovrd);

        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        List<AnalysisWeightVO> applied = new ArrayList<>();
        Set<String> ignored = new LinkedHashSet<>();
        if (effective != null) {
            for (AnalysisWeightVO w : effective) {
                // 위 창구가 이미 걸렀지만, 계약이 바뀌어도 계수(CFC)·꺼진 행이 점수로 새지 않게 한 번 더 막는다.
                if (w == null || w.getStngVl() == null || !USE_Y.equals(w.getUseYn())
                        || !VRBL_SE_WGVL.equals(w.getVrblSeCd())) {
                    continue;
                }
                if (w.getStngVl().signum() <= 0) {
                    continue; // 가중치 0 = 반영하지 않는다. 응답 weights 에도 싣지 않는다
                }
                if (!InvestPrrtCalculator.KNOWN_VRBL_CDS.contains(w.getVrblCd())) {
                    ignored.add(w.getVrblCd());
                    continue;
                }
                weights.put(w.getVrblCd(), w.getStngVl());
                applied.add(w);
            }
        }
        if (!ignored.isEmpty()) {
            log.warn("⚠️ [{}] 점수 산식이 모르는 변수를 무시했습니다: {}. 이 변수를 반영하려면 "
                    + "InvestPrrtCalculator 의 산식에 항을 추가해야 합니다.", ANLS_TYPE_CD, ignored);
        }
        if (weights.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "분석에 사용할 가중치 변수가 없습니다 (anlsTypeCd=" + ANLS_TYPE_CD
                            + ", 필요=" + InvestPrrtCalculator.KNOWN_VRBL_CDS
                            + "). 변수가 모두 사용안함(use_yn='N') 이거나 가중치가 0 입니다.");
        }

        // ---- 계수 ---- (재정규화를 타지 않는 원본 행 — stng_vl 이 저장된 그대로다)
        List<AnalysisWeightVO> rows = analysisWeightMapper.selectWeightsByType(ANLS_TYPE_CD);
        if (ovrd != null) {
            rows = ovrd.apply(rows);
        }

        BigDecimal altmntRngVl = coefficient(rows, VRBL_ALTMNT_RNG, DEFAULT_ALTMNT_RNG, BigDecimal.ZERO, null, false);
        BigDecimal upGrdCrtrScr = coefficient(rows, VRBL_UP_GRD, DEFAULT_UP_GRD, BigDecimal.ZERO, HUNDRED, true);
        BigDecimal mdGrdCrtrScr = coefficient(rows, VRBL_MD_GRD, DEFAULT_MD_GRD, BigDecimal.ZERO, HUNDRED, true);
        BigDecimal indctRaw = coefficient(rows, VRBL_INDCT_CNT, DEFAULT_INDCT_CNT, BigDecimal.ONE, null, true);

        if (mdGrdCrtrScr.compareTo(upGrdCrtrScr) >= 0) {
            // 🔴 입력 검증은 stng_vl 위에서 하지만 위 폴백은 꺼진 행에 bsc_stng_vl 을 쓰므로 검증을 통과하고도 역전될 수 있다.
            //    그때는 설정 오류(409)가 아니라 입력 오류(400)다 — D4.
            if (hasInput(ovrd, VRBL_UP_GRD) || hasInput(ovrd, VRBL_MD_GRD)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "입력한 등급 기준을 적용할 수 없습니다. 중간등급기준점수는 상위등급기준점수보다 작아야 합니다.");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "등급 기준점수가 역전됐습니다 (anlsTypeCd=" + ANLS_TYPE_CD + ", " + VRBL_MD_GRD + "="
                            + mdGrdCrtrScr.stripTrailingZeros().toPlainString() + " ≥ " + VRBL_UP_GRD + "="
                            + upGrdCrtrScr.stripTrailingZeros().toPlainString()
                            + "). 중간등급기준점수는 상위등급기준점수보다 작아야 합니다.");
        }

        return new InvestPrrtStng(
                Collections.unmodifiableMap(weights),
                List.copyOf(applied),
                altmntRngVl, upGrdCrtrScr, mdGrdCrtrScr,
                indctRaw.setScale(0, RoundingMode.HALF_UP).intValue());
    }

    /**
     * 가중치 원천. 🔴 가중치 입력이 있으면 재정규화 창구가 아니라 <b>원본 행</b>에 얹는다 — 입력 합계 100 검사가 원본 행
     * 위에서 끝났고 재정규화하지 않는다(D4). 재정규화된 값에 얹으면 꺼진 지표가 있을 때 합계가 100 을 벗어난다.
     */
    private List<AnalysisWeightVO> weightSource(AnlsVrblOvrd ovrd) {
        if (ovrd != null) {
            List<AnalysisWeightVO> rows = analysisWeightMapper.selectWeightsByType(ANLS_TYPE_CD);
            boolean weightInput = rows.stream()
                    .anyMatch(r -> VRBL_SE_WGVL.equals(r.getVrblSeCd()) && hasInput(ovrd, r.getVrblCd()));
            if (weightInput) {
                return ovrd.apply(rows);
            }
        }
        return analysisWeightService.selectEffectiveWeights(ANLS_TYPE_CD);
    }

    private static boolean hasInput(AnlsVrblOvrd ovrd, String vrblCd) {
        return ovrd != null && ovrd.value(vrblCd, () -> null) != null;
    }

    /**
     * 계수 1건을 고른다. 폴백 3단 — 행 없음 → 기본값 / 꺼짐·빈 값 → {@code bsc_stng_vl} → 기본값 /
     * 범위 밖 → 기본값. 어느 경로도 예외를 던지지 않고 WARN 1줄을 남긴다(설정 한 행 때문에 분석
     * 화면 전체가 죽으면 안 된다 — 단 임계 역전은 호출부가 409 로 끊는다).
     *
     * @param min          하한
     * @param max          상한({@code null} 이면 상한 없음)
     * @param minInclusive 하한을 포함하는가(반경은 0 을 허용하지 않는다)
     */
    private static BigDecimal coefficient(List<AnalysisWeightVO> rows, String vrblCd, BigDecimal defaultVl,
                                          BigDecimal min, BigDecimal max, boolean minInclusive) {
        AnalysisWeightVO row = null;
        if (rows != null) {
            for (AnalysisWeightVO r : rows) {
                if (r != null && vrblCd.equals(r.getVrblCd())) {
                    row = r;
                    break;
                }
            }
        }
        if (row == null) {
            log.warn("⚠️ 투자우선순위 계수 행이 없어 시드 기본값 {} 로 진행합니다: anlsTypeCd={}, vrblCd={}. "
                    + "마이그레이션 90(87-INVEST-VRBL-STNG.sql) 적용 여부를 확인하세요.",
                    defaultVl, ANLS_TYPE_CD, vrblCd);
            return defaultVl;
        }

        BigDecimal value = row.getStngVl();
        if (!USE_Y.equals(row.getUseYn()) || value == null) {
            BigDecimal fallback = row.getBscStngVl() != null ? row.getBscStngVl() : defaultVl;
            log.warn("⚠️ 투자우선순위 계수를 쓸 수 없어 폴백값 {} 로 진행합니다: anlsTypeCd={}, vrblCd={}, "
                    + "useYn={}, stngVl={}", fallback, ANLS_TYPE_CD, vrblCd, row.getUseYn(), row.getStngVl());
            value = fallback;
        }

        int lo = value.compareTo(min);
        boolean belowMin = minInclusive ? lo < 0 : lo <= 0;
        boolean aboveMax = max != null && value.compareTo(max) > 0;
        if (belowMin || aboveMax) {
            // DB CHECK(min_vl·max_vl)가 막지만 과거 행·직접 UPDATE 는 가드 밖이다.
            log.warn("⚠️ 투자우선순위 계수가 범위 밖({})이라 시드 기본값 {} 로 대체합니다: anlsTypeCd={}, vrblCd={}",
                    value, defaultVl, ANLS_TYPE_CD, vrblCd);
            return defaultVl;
        }
        return value;
    }

    /**
     * 투자우선순위 분석에 <b>실제로 적용할</b> 설정 한 벌.
     *
     * @param weights        변수코드 → 가중치(퍼센트). 산식이 아는 5개 중 0 보다 큰 것만 —
     *                       {@link InvestPrrtCalculator#calculate} 에 그대로 넘긴다
     * @param appliedWeights 위와 같은 변수의 원본 행(퍼센트). 응답 {@code weights} 에 되비춘다
     * @param altmntRngVl    최근접 배정 반경(m) — 매퍼 파라미터 {@code rdsM}
     * @param upGrdCrtrScr   '높음' 임계
     * @param mdGrdCrtrScr   '중간' 임계. 항상 {@code upGrdCrtrScr} 보다 작다
     * @param indctPkltCnt   목록·지도에 표시할 상위 주차장 수 — 🔴 계산에 쓰지 않는다(되비추기 전용)
     */
    public record InvestPrrtStng(
            Map<String, BigDecimal> weights,
            List<AnalysisWeightVO> appliedWeights,
            BigDecimal altmntRngVl,
            BigDecimal upGrdCrtrScr,
            BigDecimal mdGrdCrtrScr,
            int indctPkltCnt) {
    }
}
