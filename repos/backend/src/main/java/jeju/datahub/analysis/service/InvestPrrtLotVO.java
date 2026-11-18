package jeju.datahub.analysis.service;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 투자우선순위 주차장 1곳의 <b>점수·순위·등급</b>. 응답 {@code lots[]} 의 한 건이다.
 *
 * <p>MyBatis 결과 타입이 아니다 — 매퍼가 낸 원값({@link InvestPrrtLotBaseVO})을 받아
 * {@code InvestPrrtCalculator} 가 채운다. <b>JPA 엔티티도 아니다.</b>
 *
 * <p>🔴 <b>순위·점수는 "전 지역 대상 전체" 기준이다.</b> 행정구역 필터는 표시만 거르므로, 필터를 건
 * 응답에서도 {@link #rnk} 는 1부터 연속이 아닐 수 있다(같은 주차장의 점수·순위가 조회 조건마다
 * 달라지지 않게 하기 위해서다).
 *
 * <p>🔴 {@link #cvlcptCnt}·{@link #crdnCnt} 는 <b>그 주차장 안의 건수가 아니다</b> — 반경 안에서
 * 대상 주차장 중 가장 가까운 곳에 배정한 건수다. 반면 {@link #acdntCnt} 는 배정이 아니라
 * <b>그 주차장에 기록된</b> 건수다.
 *
 * <p>⚠️ 지표별 점수 5개({@link #sftyRiskScr}·{@link #cvlcptFrqScr}·{@link #utztnPttnScr}·
 * {@link #crdnScr}·{@link #acdntScr})는 <b>가중치가 0 이거나 꺼진 지표라도 채운다</b> — 종합점수에 들어갔는지는 응답의
 * {@code weights} 가 말한다.
 */
@Schema(description = "투자우선순위 주차장 1곳 (종합점수 순)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class InvestPrrtLotVO {

    // --- 순위 ---

    /** 전체 순위(1부터). 종합점수 내림차순, 동점이면 {@code pklt_id} 오름차순 */
    @Schema(description = "전체 순위 (전 지역 대상 기준)", example = "1")
    private Integer rnk;

    /**
     * 같은 시군구 안에서의 등수(1부터) — {@link #rnk} 오름차순으로 다시 센 것이다.
     * 시군구명이 비었으면({@code emd_cd} 없음) {@code null} 이다.
     */
    @Schema(description = "같은 시군구 안 등수 — 시군구가 없으면 null", example = "1", nullable = true)
    private Integer sggRnk;

    /**
     * 단속({@code ENFORCE}) 가중치를 뺀 나머지 지표로 같은 방식으로 다시 매긴 순위.
     *
     * <p>단속이 애초에 반영되지 않았으면 {@link #rnk} 와 같다. 단속이 <b>유일한</b> 반영 지표라
     * 빼면 남는 것이 없을 때만 {@code null} 이다.
     */
    @Schema(description = "단속을 뺀 순위 — 단속만 반영 중이면 null", example = "1", nullable = true)
    private Integer crdnExclRnk;

    // --- 주차장 기본 정보 (InvestPrrtLotBaseVO 에서 그대로 옮긴다) ---

    /** 주차장 ID. {@code TB_PBLPKLT.pklt_id}(IDENTITY) — 🔴 값에 업무적 의미가 없다 */
    @Schema(description = "주차장 ID", example = "12869")
    private Long pkltId;

    /** 주차장명 */
    @Schema(description = "주차장명", example = "매일올레시장 공영주차빌딩")
    private String pkltNm;

    /** 주차장 주소(원천 원문) */
    @Schema(description = "주차장 주소")
    private String pkltAddr;

    /** 시군구명({@code TB_ADMDST}). {@code emd_cd} 가 비었으면 {@code null} */
    @Schema(description = "시군구명 (행정구역 마스터)", example = "서귀포시", nullable = true)
    private String sggNm;

    /** 읍면동 코드(8자리) */
    @Schema(description = "읍면동 코드", example = "50130520", nullable = true)
    private String emdCd;

    /** 읍면동명({@code TB_ADMDST.emd_nm}) */
    @Schema(description = "읍면동명 (행정구역 마스터)", example = "중앙동", nullable = true)
    private String emdNm;

    /** 주차면수. 원천이 비우면 {@code null} */
    @Schema(description = "주차면수", example = "232", nullable = true)
    private Integer pkltCpcty;

    /** 설치연월({@code YYYYMM}). 🔴 대부분 결측이다 */
    @Schema(description = "설치연월 (YYYYMM) — 결측이면 null", example = "200901", nullable = true)
    private String instlYm;

    /** 위도 */
    @Schema(description = "위도", example = "33.25")
    private BigDecimal lat;

    /** 경도 */
    @Schema(description = "경도", example = "126.56")
    private BigDecimal lot;

    // --- 종합 ---

    /**
     * 종합점수(0~100, <b>소수 9자리</b>) = Σ(지표 점수 × 가중치) ÷ Σ가중치.
     * 🔴 순위는 이 9자리 값으로 매긴다 — 더 줄여서 정렬하면 동점이 생겨 순위가 달라진다.
     */
    @Schema(description = "종합점수 (0~100, 소수 9자리)", example = "97.214673913")
    private BigDecimal snthsScr;

    /** 우선순위 등급 코드({@code IVST_PRRT_LVL_CD} — {@code H} 높음 / {@code M} 중간 / {@code L} 낮음) */
    @Schema(description = "우선순위 등급 코드 (H | M | L)", example = "H")
    private String prrtLvlCd;

    /**
     * 살펴볼 사유 코드 — {@code SAFETY}(안전점검 주의·불량) / {@code DEMAND}(민원·이용 백분위
     * 평균이 전체 상위 25%) / {@code BOTH} / {@code NONE}.
     */
    @Schema(description = "사유 코드 (SAFETY | DEMAND | BOTH | NONE)", example = "BOTH")
    private String rsnCd;

    // --- 안전 ---

    /** 안전점검 결과 코드({@code A}/{@code B}/{@code C}). 점검이 없으면 {@code null} */
    @Schema(description = "안전점검 결과 코드 (A/B/C) — 점검이 없으면 null", example = "C", nullable = true)
    private String sftyChckRsltCd;

    /** 위 결과의 점검일자({@code YYYYMMDD}). 점검이 없으면 {@code null} */
    @Schema(description = "안전점검 일자 (YYYYMMDD) — 점검이 없으면 null", example = "20260901", nullable = true)
    private String sftyChckYmd;

    // --- 지표별 점수 (0~100) ---

    /**
     * 안전위험 점수 — 코드 → 점수({@code A} 0 / {@code B} 50 / {@code C} 100). 백분위가 아니다.
     * 🔴 점검이 없거나({@link #sftyChckRsltCd} {@code null}) 대응표에 없는 코드면 <b>0 으로 계산</b>
     * 하고, 그 사실은 응답 {@code warnings} 가 건수와 함께 알린다.
     */
    @Schema(description = "안전위험 점수 (A 0 / B 50 / C 100)", example = "100")
    private BigDecimal sftyRiskScr;

    /** 민원빈도 점수 — {@link #cvlcptCnt} 의 백분위(자기보다 적은 대상 수 ÷ (n−1) × 100) */
    @Schema(description = "민원빈도 점수 (백분위 0~100)", example = "94.021739")
    private BigDecimal cvlcptFrqScr;

    /** 이용패턴 점수 — {@link #dayAvgTrnvrRt} 의 백분위. 회전율이 없으면 0 으로 보고 매긴다 */
    @Schema(description = "이용패턴 점수 (백분위 0~100)", example = "98.641304")
    private BigDecimal utztnPttnScr;

    /** 단속 점수 — {@link #crdnCnt} 의 백분위 */
    @Schema(description = "단속 점수 (백분위 0~100)", example = "96.195652")
    private BigDecimal crdnScr;

    /**
     * 사고 점수 — {@link #acdntCnt} 의 백분위. 사고가 없으면 건수 0 으로 매긴다 —
     * 전 주차장이 0 이면 전부 0 이다.
     */
    @Schema(description = "사고 점수 (백분위 0~100)", example = "88.586957")
    private BigDecimal acdntScr;

    // --- 지표 원값 ---

    /** 배정 민원 수(최근접 배정). 없으면 0 */
    @Schema(description = "배정 민원 수 (최근접 배정)", example = "98")
    private Long cvlcptCnt;

    /** 일평균 회전율(이용률 집계 달(시작일이 1일이 아니면 시작 달 제외)의 평균). 🔴 자료가 없으면 {@code null} — 0 으로 바꿔 내리지 않는다 */
    @Schema(description = "일평균 회전율 — 자료가 없으면 null", example = "4.7776", nullable = true)
    private BigDecimal dayAvgTrnvrRt;

    /** 배정 단속 수(최근접 배정). 없으면 0 */
    @Schema(description = "배정 단속 수 (최근접 배정)", example = "173")
    private Long crdnCnt;

    /** 사고 건수 — 🔴 반경 배정이 아니라 <b>그 주차장에 기록된</b> 창 안 건수다. 없으면 0 */
    @Schema(description = "사고 건수 (그 주차장에 기록된 창 안 건수)", example = "2")
    private Long acdntCnt;
}
