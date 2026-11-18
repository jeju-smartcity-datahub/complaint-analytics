package jeju.datahub.analysis.service;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * 구역추천(요금제 적용) 순위에 <b>실제로 적용된</b> 기준값 한 벌 (TSK-4207 Step 4).
 *
 * <p>응답의 {@code crtrVls} 블록이자 {@code anls_param_json} 에 굳히는 스냅샷이다.
 * 🔴 <b>적용값을 응답에 담는 이유</b> — 설정 변수({@code parking.TB_ANLS_VRBL_STNG})가 바뀌면 같은
 * 조회 조건이라도 결과가 달라진다. 특히 {@link #altmntRngVl}(배정 반경)은 값을 바꾸면 순위 자체가
 * 뒤집히므로(이도이동 {@code 1994-1} 은 100m 13위 · 300m 1위) 어느 기준으로 낸 목록인지 남지 않으면
 * 재현할 수 없다.
 *
 * <p>값의 출처는 {@code RECOMMEND} 다 — 공통 2종({@link #altmntRngVl} · {@link #swtcTrgtMinCpcty}) +
 * {@code FEE_} 8종.
 *
 * <p>🔁 <b>TSK-4340(2026-11-11 사용자 결정) — 읍면동 요금제 대상 판정을 제거했다.</b> 판정에만 쓰이던
 * {@code stsfRtThrsh}·{@code cvlcptMmCrtrVl}·{@code ilglCrtrVl}({@code RECOMMEND})과 {@code @JsonIgnore}
 * {@code pcshrCrtrVl}·{@code paidDmndRt}({@code POLICY_SIM})를 지웠다. 🔴 공유주차제의 동명
 * {@code cvlcptMmCrtrVl} 은 이 클래스가 아니라 {@code ZoneRcmdtnShareResultVO.CrtrVls} 다(별개 — 그대로).
 * 🔴 구 스냅샷의 그 키는 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 로 무시한다(되읽기 500 방지).
 *
 * <p>⚠️ {@link #trgtTypeSe} 는 설정이 아니라 <b>요청 파라미터</b>다(§0-6 — 설정으로 두면 "원하는
 * 숫자를 만드는 칸" 이 된다). 그래도 같은 블록에 담는다 — 결과를 재현하는 데 필요한 입력이다.
 *
 * <p>설정에서 온 값은 모두 {@link BigDecimal} 이다 — 컬럼이 {@code NUMERIC(12,4)} 다({@link AnalysisWeightVO} 와
 * 같은 기준). ⚠️ 예외는 둘이다 — {@link #minTrendBase} 는 설정이 아니라 <b>코드 상수이자 건수</b>라
 * {@code int} 이고, {@link #trendDayCnt} 는 설정이 아니라 <b>조회 기간에서 파생한 일수</b>라
 * {@code Integer} 다(추세를 내지 않는 경로에서 {@code null}).
 *
 * <p>🆕 <b>TSK-4282 — 요금제 검토지수 설정 8종</b>({@code feeCvlcpt*})이 뒤에 붙는다. {@code RECOMMEND} 의
 * {@code FEE_} 행이며 {@code ZoneRcmdtnCalculator.scoreLots} 가 이 getter 를 직접 읽는다. 구 스냅샷을
 * 되읽으면 이 8칸은 {@code null} 이다(그 스냅샷은 {@code anlsMthd} 도 없어 구 방식으로 그린다 — D11).
 */
@Schema(description = "구역추천 요금제 순위에 적용된 기준값")
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@ToString
@AllArgsConstructor
public class ZoneRcmdtnCrtrVls {

    /** 후보 주차장 유형 범위 — {@code NARROW}(기본) | {@code WIDE}. 🔴 설정이 아니라 요청 파라미터다 */
    @Schema(description = "후보 주차장 유형 범위 (NARROW | WIDE)", example = "NARROW")
    private final String trgtTypeSe;

    /** 최근접 배정 반경(m). {@code RECOMMEND.ALTMNT_RNG_VL} — 🔴 바꾸면 순위가 바뀐다 */
    @Schema(description = "최근접 배정 반경 (m)", example = "300")
    private final BigDecimal altmntRngVl;

    /** 후보 주차장 최소 면수 하한. {@code RECOMMEND.SWTC_TRGT_MIN_CPCTY} — ⚠️ {@code 0} 이 적법하다 */
    @Schema(description = "후보 주차장 최소 면수 하한", example = "20")
    private final BigDecimal swtcTrgtMinCpcty;

    /**
     * 증가율 산출의 <b>분모 하한</b>(건) — 이전 블록이 이 값 미만이면 {@code cvlcptIncrsRt} 가
     * {@code null} 이다(§9-1).
     *
     * <p>🔴 <b>설정 변수가 아니라 코드 상수</b>({@code ZoneRcmdtnCalculator.MIN_TREND_BASE})다 —
     * {@code RECOMMEND} 에 해당 행이 없다. 그래도 결과와 함께 굳히는 이유는, 나중에 하한을 바꾸면
     * 같은 조회가 다른 증가율(또는 {@code null})을 내므로 <b>어느 하한으로 낸 값인지</b> 남지 않으면
     * 재현할 수 없기 때문이다({@code altmntRngVl} 과 같은 근거).
     *
     * <p>⚠️ 다른 칸과 달리 {@code int} 다 — {@code NUMERIC} 설정값이 아니라 <b>건수</b>이고
     * 경계 비교를 정수로 한다(소수 하한은 의미가 없다).
     */
    @Schema(description = "증가율 분모 하한 (건) — 이전 블록이 이 값 미만이면 증가율 null", example = "10")
    private final int minTrendBase;

    /**
     * 🔁 <b>증가율 비교 블록 길이(일수)</b> — TSK-4207 Step 13. <b>선택한 민원 창</b>에서 파생한다.
     *
     * <p>파생 규칙 — 민원 창의 총 일수({@code D}, 양끝 포함)를 반으로 가른 {@code D / 2}
     * (정수 나눗셈)다. 🔴 {@code D} 가 홀수면 <b>가운데 1일을 버려</b> 두 블록 일수를 같게 둔다 —
     * 길이가 다르면 증가율이 비교가 아니다.
     *
     * <p>🔁 <b>Step 12 의 월 단위(완전한 달 ÷ 2)는 폐지됐다.</b> 그 규칙은 "완전한 달" 을 전제해
     * {@code 최근1개월}(9/12~10/12)을 <b>완전한 달 0개 = 증가율 영구 {@code null}</b> 로 만들었다 —
     * 오늘이 말일이고 시작이 1일일 때만 동작하는 셈이라 실질적으로 쓸 수 없었다.
     *
     * <p>🔴 <b>응답에 싣는 이유</b> — 같은 조회 조건이라도 이 길이를 모르면 "어느 블록과 어느 블록을
     * 비교한 값인지" 가 남지 않아 재현할 수 없다({@link #minTrendBase}·{@link #altmntRngVl} 과 같은
     * 근거). 화면의 "최근 N일(약 M개월) …건" · "이전 N일 10건 미만" 의 {@code N} 도 이 값이다.
     *
     * <p>⚠️ {@code null} 이거나 {@code 0} 이면 <b>블록이 7일 미만</b>(= 조회 기간 14일 미만)이라
     * 증가율을 내지 않은 것이다({@code warnings} 에 {@code TREND_WINDOW_SHORT}).
     * {@code /zones}(읍면동 목록)처럼 추세를 내지 않는 경로는 {@code null} 로 남는다 —
     * {@code Integer} 인 이유다.
     *
     * <p>🔴 <b>이름은 데이터표준사전을 따른다</b> — 표준용어 {@code 일수 = DAY_CNT}
     * (표준단어 {@code 일 DAY} + 형식어 {@code 수 CNT}). {@code trendDays} 로 적지 않는다
     * ({@code asgnCvlcptCntBfr} 가 {@code Prev} 가 아닌 것과 같은 근거).
     */
    @Schema(description = "증가율 비교 블록 길이(일수) — 조회 기간 총 일수 ÷ 2. 7일 미만이면 null",
            example = "121")
    private final Integer trendDayCnt;

    // --- 🆕 TSK-4282 — 요금제 검토지수 설정 8종 ({@code RECOMMEND} · {@code anls_trgt_cd='FEE'}) ---
    // 🔴 이름은 vrbl_cd 의 camelCase 다(평평한 필드 — 별도 레코드로 묶지 않는다). 응답·스냅샷에 싣는다(재현성).

    /** 민원강도 가중치(%). {@code RECOMMEND.FEE_CVLCPT_CNT_WGVL} — 면당민원 가중치와 합 100 */
    @Schema(description = "요금제 검토지수 — 민원강도 가중치 (%)", example = "70")
    private final BigDecimal feeCvlcptCntWgvl;

    /** 면당민원 가중치(%). {@code RECOMMEND.FEE_CVLCPT_RT_WGVL} — 민원강도 가중치와 합 100 */
    @Schema(description = "요금제 검토지수 — 면당민원 가중치 (%)", example = "30")
    private final BigDecimal feeCvlcptRtWgvl;

    /** 제주시 민원 월 기준값(건/월). {@code RECOMMEND.FEE_CVLCPT_MM_CRTR_JEJU} — 이 값이면 민원강도 100점 */
    @Schema(description = "요금제 검토지수 — 제주시 민원 월 기준값 (건/월)", example = "22")
    private final BigDecimal feeCvlcptMmCrtrJeju;

    /** 서귀포시 민원 월 기준값(건/월). {@code RECOMMEND.FEE_CVLCPT_MM_CRTR_SEOGWIPO} */
    @Schema(description = "요금제 검토지수 — 서귀포시 민원 월 기준값 (건/월)", example = "10")
    private final BigDecimal feeCvlcptMmCrtrSeogwipo;

    /** 제주시 면당민원 기준값(건/100면·월). {@code RECOMMEND.FEE_CVLCPT_RT_CRTR_JEJU} — 이 값이면 면당압력 100점 */
    @Schema(description = "요금제 검토지수 — 제주시 면당민원 기준값 (건/100면·월)", example = "72")
    private final BigDecimal feeCvlcptRtCrtrJeju;

    /** 서귀포시 면당민원 기준값(건/100면·월). {@code RECOMMEND.FEE_CVLCPT_RT_CRTR_SEOGWIPO} */
    @Schema(description = "요금제 검토지수 — 서귀포시 면당민원 기준값 (건/100면·월)", example = "28")
    private final BigDecimal feeCvlcptRtCrtrSeogwipo;

    /**
     * 민원증가 가중치(배). {@code RECOMMEND.FEE_CVLCPT_INCRS_WGVL} — 🔴 이름에 {@code WGVL} 이 있지만
     * {@code vrbl_se_cd} 는 {@code CFC} 다(가중치 합 100 검증에 끼지 않는다). 0 이면 추세 미반영.
     */
    @Schema(description = "요금제 순번 — 민원증가 가중치 (배)", example = "0.5")
    private final BigDecimal feeCvlcptIncrsWgvl;

    /** 민원증가율 상한(%). {@code RECOMMEND.FEE_CVLCPT_INCRS_RT_UPLMT_VL} — 증가율을 ±이 값에서 자른다 */
    @Schema(description = "요금제 순번 — 민원증가율 상한 (%)", example = "100")
    private final BigDecimal feeCvlcptIncrsRtUplmtVl;

    /**
     * {@link #trendDayCnt} 만 채운 <b>사본</b>을 돌려준다 (Step 13).
     *
     * <p>🔴 이 칸만 생성 시점에 알 수 없어서다 — 계수는 설정에서 바로 오지만 블록 일수는
     * <b>조회 기간에서 파생</b>하므로 창을 만든 뒤에야 정해진다. 그렇다고 필드를 가변으로 열면
     * "응답에 적힌 기준" 이 나중에 바뀔 수 있는 객체가 되므로, <b>불변을 유지하고 사본</b>을 만든다.
     *
     * @param trendDayCnt 블록 길이(일수). 7일 미만이면 {@code 0} 이 아니라 {@code null}
     */
    public ZoneRcmdtnCrtrVls withTrendDayCnt(Integer trendDayCnt) {
        return new ZoneRcmdtnCrtrVls(trgtTypeSe, altmntRngVl, swtcTrgtMinCpcty, minTrendBase, trendDayCnt,
                feeCvlcptCntWgvl, feeCvlcptRtWgvl, feeCvlcptMmCrtrJeju, feeCvlcptMmCrtrSeogwipo,
                feeCvlcptRtCrtrJeju, feeCvlcptRtCrtrSeogwipo, feeCvlcptIncrsWgvl, feeCvlcptIncrsRtUplmtVl);
    }
}
