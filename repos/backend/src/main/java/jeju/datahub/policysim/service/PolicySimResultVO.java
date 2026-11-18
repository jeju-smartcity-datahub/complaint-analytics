package jeju.datahub.policysim.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 정책 시뮬레이션 결과 — 조회 범위 하나 (TSK-4037 Step 4 출력 지표 · Step 5 응답 · TSK-4038 Step 4-3 이면도로 정비).
 *
 * <p>이면도로 정비(REPAIR): {@link #vldAddPkltSlot} = 환산 확보 면수 ΔS · {@link #aftrCvlcptCnt}·{@link #cvlcptRdctRt} 는
 * 인접 증가분을 포함한 순효과(그래서 {@code aftr ÷ crtr − 1 = R}) · 혼잡 지수는 대상만 · {@link #cstAmt} null ·
 * {@link #cstLvlCd} {@code TBD}(미정). {@link #roadMtncType}·{@link #roadMtncLen}·{@link #adjcCvlcptCnt}·{@link #adjcEmdCnt}
 * 는 REPAIR 전용이며 확충에서는 null 이다.
 *
 * <p>요금제 적용(FEE, TSK-4039 Step 5-3): {@link #vldAddPkltSlot} null(공급 불변 S1 = S) · {@link #cstAmt} null ·
 * {@link #cstLvlCd} {@code TBD} · {@link #adjcCvlcptCnt}·{@link #adjcEmdCnt} 는 요금 회피 전이의 인접분(4038 정의 재사용) ·
 * {@link #aftrCvlcptCnt}·{@link #cvlcptRdctRt} 는 인접 포함 순효과. {@code fee*}·{@link #aplcnTrgtSlot}·{@link #lvngVhclCnt}·
 * {@link #absrpCnt}·{@link #trnsfCnt}·{@link #cvlcptRdctRtRng}·{@link #wrnngCn} 은 FEE 전용이며 다른 유형에서는 null 이다.
 * 🔴 {@link #cvlcptRdctRt} 양수 = 민원 증가 — 요금제는 전이가 흡수보다 크면 양수다(단일 읍면동 CONVERT 는 수천 % 도 정상).
 *
 * <p>값은 {@code PolicySimOutputCalculator} 한 곳에서 반올림·퍼센트 변환을 거친 <b>표시·저장용</b>이다.
 * 비율({@code R}·{@code I})은 퍼센트(×100, 소수 둘째 자리 HALF_UP)로 담는다 — DB {@code NUMERIC(5,2)} 에
 * 비율을 그대로 넣으면 {@code −0.09} 로 잘린다. 화면의 정수 % 변환은 프론트 몫이다.
 *
 * <p>🔴 <b>혼잡도는 주차 혼잡이며 도로 통행량이 아니다</b> — H = (정상 이용 + 불법주차 추정) ÷ 공영주차면수.
 *
 * <p>null 규칙({@link #calcSttsCd} — 조회 범위 단위):
 * <ul>
 *   <li>{@code NO_ENF}(범위 ΣE0 = 0): R·I·C1·H1 null. C0(범위 실제 합)·H0(ΣS &gt; 0 이면)는 값</li>
 *   <li>{@code NO_SPLY}(범위 ΣS = 0): I·H0·H1 null. R·C1 은 값</li>
 *   <li>포화({@link #excsYn})는 아무 값도 비우지 않는다(DEC-025)</li>
 * </ul>
 */
@Schema(description = "정책 시뮬레이션 결과 (주차장 확충 · 이면도로 정비 · 요금제 적용)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class PolicySimResultVO {

    @Schema(description = "조회 범위 라벨", example = "제주시 연동")
    private String anlsRngCn;

    @Schema(description = "기준 월평균 민원 C0 합계(건/월) — R 의 분모(NO_ENF 읍면동 제외). 범위 전체가 NO_ENF 면 실제 C0 합", example = "818.63")
    private BigDecimal crtrCvlcptCnt;

    @Schema(description = "기준 불법주차 추정 E0 합계(대)", example = "174.75")
    private BigDecimal crtrIlglCnt;

    @Schema(description = "기준 주차 혼잡 지수 H0 = Σ(U0+E0) ÷ ΣS (S>0 읍면동만). 주차 혼잡이며 도로 통행량이 아니다", example = "0.925")
    private BigDecimal crtrCngstIdx;

    @Schema(description = "기존 공영주차면수 S 합계", example = "2315")
    private Long exsPkltCpcty;

    @Schema(description = "유효 증설 면수 — 확충: ΔS_eff(공한지는 반환분 차감 후) · 이면도로: 환산 확보 면수 ΔS = 연장 ÷ 100 × n_100 · "
            + "요금제는 null(공급 불변)", example = "96.80", nullable = true)
    private BigDecimal vldAddPkltSlot;

    @Schema(description = "민원 감소율 R (%) — 음수 = 감소, 양수 = 민원 증가", example = "-27.70")
    private BigDecimal cvlcptRdctRt;

    @Schema(description = "주차 혼잡 개선율 I (%) — 양수 = 개선", example = "4.01")
    private BigDecimal cngstImpvRt;

    @Schema(description = "확충 후 주차 혼잡 지수 H1 (S>0 읍면동만)", example = "0.888")
    private BigDecimal aftrCngstIdx;

    @Schema(description = "정책 후 월평균 민원 C1 합계(건/월, NO_ENF 읍면동 제외). 이면도로는 인접 증가분(adjcCvlcptCnt)을 포함한 순효과 — "
            + "aftrCvlcptCnt ÷ crtrCvlcptCnt − 1 = cvlcptRdctRt", example = "591.89")
    private BigDecimal aftrCvlcptCnt;

    @Schema(description = "산출상태코드(CALC_STTS_CD) — null=정상 · NO_ENF · NO_SPLY (조회 범위 단위)", nullable = true)
    private String calcSttsCd;

    @Schema(description = "흡수 포화(SATURATED) 여부 — 범위 안 읍면동이 하나라도 전량 흡수로 잘렸다", example = "false")
    private boolean excsYn;

    @Schema(description = "예상 비용(원) = 면수 N × 방식별 단가. 이면도로·요금제는 단가가 없어 null", example = "187000000", nullable = true)
    private BigDecimal cstAmt;

    @Schema(description = "비용수준코드(CST_LVL_CD) — LOW · MID · HIGH · TBD(미정 — 이면도로·요금제)", example = "LOW")
    private String cstLvlCd;

    @Schema(description = "이면도로 정비 방식 (요청값 RSDNC · CMRC) — 확충은 null", example = "RSDNC", nullable = true)
    private String roadMtncType;

    @Schema(description = "이면도로 정비 구간 연장(m, 요청값) — 확충은 null", example = "600", nullable = true)
    private Integer roadMtncLen;

    @Schema(description = "인접 지역 민원 증가분 Σ(C1_n − C0_n) (건/월, 이면도로 풍선효과 · 요금제 요금 회피 전이) — cvlcptRdctRt·aftrCvlcptCnt 에 "
            + "이미 반영된 내역. 인접이 없거나 전부 E0=0 이면 0(인접분은 대상에 가산). 확충은 null", example = "85.52", nullable = true)
    private BigDecimal adjcCvlcptCnt;

    @Schema(description = "풍선효과·전이 배분에 쓴 인접 읍면동 수(E0 > 0) — 응답 전용(저장 안 함, 저장 목록에서는 null). 확충은 null",
            example = "6", nullable = true)
    private Integer adjcEmdCnt;

    @Schema(description = "요금 적용 방식 (요청값 RAISE · CONVERT) — FEE 만", example = "CONVERT", nullable = true)
    private String feeAplcnMthd;

    @Schema(description = "적용 급지 ALL · Z1 · Z2 — FEE 만. emdCd 지정 시 서버가 채운 그 읍면동의 급지(읍·면 → Z2, 동 → Z1)",
            example = "ALL", nullable = true)
    private String feeAplcnZone;

    @Schema(description = "요금 인상률(%) 10 · 20 · 30 — FEE·RAISE 만(CONVERT 는 null)", example = "10", nullable = true)
    private Integer feeIncrsRt;

    @Schema(description = "적용 대상 주차면수 — 대상 지역 ∩ 급지 ∩ 유무료(RAISE 유료 / CONVERT 무료) 공영주차장(민영 제외) 면수 합. "
            + "단속 0건(NO_ENF) 읍면동 면수를 포함한다 — FEE 만", example = "36160", nullable = true)
    private Long aplcnTrgtSlot;

    @Schema(description = "이탈 차량 수 V = Σ 면수_e × occ × (λ | −ε × p) (대) — 🔴 단속 0건(NO_ENF) 읍면동은 뺀 값이라 "
            + "aplcnTrgtSlot × occ × λ(인상이면 −ε × p) 보다 작을 수 있다 — FEE 만", example = "9221.00", nullable = true)
    private BigDecimal lvngVhclCnt;

    @Schema(description = "흡수 불법주차 수 Σ min(E0_e, α × V_e) (대) — occ < occ_th 면 0. crtrIlglCnt 이하 — FEE 만",
            example = "1022.90", nullable = true)
    private BigDecimal absrpCnt;

    @Schema(description = "전이 불법주차 수 = τ × lvngVhclCnt (대상 내 + 인접분, 요금 회피) — FEE 만", example = "1844.00", nullable = true)
    private BigDecimal trnsfCnt;

    @Schema(description = "민원 감소율 민감도 범위(%) — CONVERT {\"lambda\":[하한,상한], \"tau\":[..]} · RAISE {\"eps\":[..], \"tau\":[..]}. "
            + "계수 하나만 설정 행의 min_vl·max_vl 로 바꿔 다시 계산한 R 의 [min, max]. 범위가 없는 계수는 값 null · "
            + "범위 NO_ENF 면 전체 null — FEE 만", nullable = true)
    private Map<String, List<BigDecimal>> cvlcptRdctRtRng;

    @Schema(description = "경고 문구 — occ < occ_th 이면 '점유율이 포화 기준 미만이라 흡수 효과가 없습니다'. 응답 전용(저장 안 함) — FEE 만",
            nullable = true)
    private String wrnngCn;

    @Schema(description = "산출 시점 적용 계수·기준 구간")
    private PolicySimAplcnParam aplcnParam;

    /**
     * 분석 계수 적용 내역 (TSK-4332 D6) — {@code {ajmtYn, vrbls:[{vrblCd, vrblNm, unitNm, stngVl, aplcnVl}]}}.
     * 그 정책 유형 스코프 허용 목록 전체. 🔴 초기값을 두지 않는다 — 구 저장분은 키가 없다.
     */
    @Schema(description = "분석 계수 적용 내역 {ajmtYn, vrbls:[{vrblCd, vrblNm, unitNm, stngVl, aplcnVl}]} — "
            + "정책 유형 스코프 허용 목록 전체 (TSK-4332)", nullable = true)
    private Map<String, Object> vrblAjmt;
}
