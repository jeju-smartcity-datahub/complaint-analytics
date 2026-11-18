package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 구역추천(공유주차제 적용) 분석 결과 — 응답 본문이다 (TSK-4208 Step 3).
 *
 * <p>요금제 트랙({@link ZoneRcmdtnResultVO})과 <b>단위가 다르다</b> — 그쪽은 고른 읍면동 안의 주차장
 * 순위이고, 이쪽은 <b>읍면동 목록</b>이다(기간만 주고 지역은 필터다).
 *
 * <p>🔴 <b>{@link #smry} 는 지역 필터와 무관하게 도 전체 값이다.</b> {@link #zones} 만 필터에 걸린
 * 읍면동으로 줄어든다. 판정은 필터 <b>앞</b>에서 끝나므로 같은 읍면동의 등급·{@code rvwScr} 는
 * 필터를 걸어도 같다. 🔴 순번({@code rnk})만 다르다 — <b>조회 결과(필터 적용 후) 안에서</b> 1 부터
 * 매긴 표시 순번이다.
 *
 * <p>🔴 <b>후보 0곳도 정상 응답이다</b> — {@link #zones} 가 비는 것이 아니라 전 읍면동이 후보 아님으로
 * 온다. 기간에 민원이 한 건도 없으면 {@link #warnings} 에 {@code NO_CVLCPT_IN_RANGE} 가 담긴다.
 */
@Schema(description = "구역추천 공유주차제 분석 결과 (읍면동 판정 목록)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ZoneRcmdtnShareResultVO {

    /**
     * 분석실행 ID. {@code parking.TB_ANLS_EXCN.excn_id} — <b>Step 4(API 계층)가 채운다.</b>
     *
     * <p>조회만 하는 경로에서는 {@code null} 이다 — 실행이력 적재는 API 경계의 책임이다.
     */
    @Schema(description = "분석실행 ID (실행이력 적재 후 채워진다)", example = "31", nullable = true)
    private Long excnId;

    /** 판정에 <b>실제로 적용된</b> 기준값. 🔴 설정이 바뀌면 결과가 달라지므로 응답에 굳힌다 */
    @Schema(description = "적용된 기준값")
    private CrtrVls crtrVls;

    /**
     * 분석 계수 적용 내역 (TSK-4325 D10) — {@code {ajmtYn, vrbls:[{vrblCd, vrblNm, unitNm, stngVl, aplcnVl}]}}.
     *
     * <p>조회 응답에만 채운다. 🔴 <b>필드 초기값을 두지 않는다</b> — 보고서 본문은 {@code rslt_json} 을 이 VO 로
     * 되읽으므로 이 칸이 {@code null} 이고, 보고서의 적용 내역은 응답 머리({@link ZoneRcmdtnReportVO#getVrblAjmt()})에 있다.
     */
    @Schema(description = "분석 계수 적용 내역 {ajmtYn, vrbls:[{vrblCd, vrblNm, unitNm, stngVl, aplcnVl}]} — "
            + "보고서 본문에서는 null (응답 머리 vrblAjmt 를 본다)", nullable = true)
    private Map<String, Object> vrblAjmt;

    /**
     * 이 화면이 세는 민원(유형 99 제외 · {@code emd_cd} 있음)의 {@code ocrn_dt} 최소일({@code YYYYMMDD}).
     *
     * <p>조회 기간·지역 필터와 무관한 <b>자료 구간</b>이다. 자료가 없으면 {@code null}.
     */
    @Schema(description = "자료 구간 시작일 (YYYYMMDD, 조회 기간과 무관)", example = "20260101", nullable = true)
    private String dataBgngYmd;

    /** 자료 구간 종료일({@code YYYYMMDD}). 자료가 없으면 {@code null} */
    @Schema(description = "자료 구간 종료일 (YYYYMMDD, 조회 기간과 무관)", example = "20261031", nullable = true)
    private String dataEndYmd;

    /**
     * 경고 코드 목록. 정상 경로에서는 <b>빈 배열</b>이다. 같은 코드는 한 번만 담는다.
     * <ul>
     *   <li>{@code CRTR_FALLBACK} — 설정 행이 없어 코드 폴백 상수로 판정했다(기준값 50 · 7.0 · 증가
     *       가중치 0.5 · 상한 100). <b>평시 경로가 아니다</b></li>
     *   <li>{@code NO_CVLCPT_IN_RANGE} — 조회 기간에 민원이 한 건도 없다(자료 구간 밖을 조회했다)</li>
     *   <li>{@code CRTR_ALL_ZERO} — 두 기준값이 모두 0 이라 검토 지수({@code rvwScr})를 낼 수 없다.
     *       전 읍면동의 {@code rvwScr}·{@code rnk} 가 {@code null} 이다. <b>평시 경로가 아니다</b></li>
     *   <li>{@code TREND_WINDOW_SHORT} — 조회 기간을 반 갈랐을 때 한 블록이 7일 미만(= 조회 기간 14일 미만)이라
     *       증가율을 내지 않았다. 전 읍면동의 증가율·{@code bfrCvlcptCnt} 가 {@code null}, 증가계수가 1 이다</li>
     *   <li>{@code TREND_BFR_FALLBACK} — 직전 동일 기간의 민원이 <b>전 읍면동 합 0건</b>이라 증가율을 조회 기간의
     *       반 분할(앞 D/2 ↔ 뒤 D/2)로 냈다. {@code crtrVls.trendDayCnt} 가 D/2 다</li>
     * </ul>
     */
    @Schema(description = "경고 코드 목록 (CRTR_FALLBACK | NO_CVLCPT_IN_RANGE | CRTR_ALL_ZERO | TREND_WINDOW_SHORT | "
            + "TREND_BFR_FALLBACK) — 정상이면 빈 배열")
    private List<String> warnings = new ArrayList<>();

    /** 요약. 🔴 지역 필터와 무관하게 <b>도 전체</b> 값이다 */
    @Schema(description = "요약 (지역 필터와 무관한 도 전체 값)")
    private Smry smry;

    /**
     * 읍면동 판정 목록 — 필터에 걸린 읍면동 <b>전부</b>다(후보만이 아니다).
     *
     * <p>순서는 {@code rnk} 오름차순(= 추세 반영 지수 {@code incrsRfltRvwScr} 내림차순 → 민원 건수
     * 내림차순 → {@code emd_cd}, 🔁 TSK-4287 — 후보 먼저 키 삭제) → 그 뒤에 판정 불가({@code UNABLE} · 민원
     * 내림차순, 동률은 {@code emd_cd} 오름차순)다. 🔴 기준 미달 읍면동이 후보 앞에 설 수 있다 — 등급은 검토
     * 지수로 정하고 순서는 추세 반영 지수로 정한다. 🔁 TSK-4283 은 후보 먼저, 그 전에는 검토 지수 내림차순이었다.
     */
    @Schema(description = "읍면동 판정 목록 — rnk 오름차순(추세 반영 지수(우선도) 내림차순 → 민원 건수 내림차순 → "
            + "읍면동코드), 판정 불가(UNABLE)는 맨 뒤")
    private List<ZoneRcmdtnShareZoneVO> zones = new ArrayList<>();

    /**
     * 🆕 TSK-4340 — 공유주차제 엑셀 재계산 데이터. <b>보고서 경로({@code selectShareWithWorkbook})만</b> 채운다.
     *
     * <p>🔴 <b>필드 초기값을 두지 않는다</b>(이 값을 저장하기 전 보고서는 {@code null} — D12). 🔴 NON_NULL 은 <b>필드 단위</b>다 —
     * 전역 NON_NULL 설정이 없어 안 붙이면 조회 GET 에 {@code "wbk": null} 키가 생긴다. 클래스 단위로 걸면 다른 null 칸까지 사라진다.
     */
    @Schema(description = "공유주차제 엑셀 재계산 데이터 (보고서 경로만 · 조회 응답에는 없다)", nullable = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ZoneRcmdtnShareWorkbookVO wbk;

    /**
     * 판정에 적용된 기준값 블록. {@code anls_param_json} 에도 <b>같은 값</b>을 굳힌다(Step 4).
     *
     * <p>🔴 기준값 두 칸은 <b>꼬리 0 을 걷은 값</b>이다 — 설정 컬럼이 {@code NUMERIC(12,4)} 라 다듬지
     * 않으면 {@code 7.0000} 으로 직렬화된다. 판정은 다듬기 전 값으로 했으며 두 값은 수치로 같다.
     */
    @Schema(description = "공유주차제 판정에 적용된 기준값")
    @Getter
    @Setter
    @ToString
    @NoArgsConstructor
    public static class CrtrVls {

        /** 민원 월기준값(건/월). {@code RECOMMEND.CVLCPT_MM_CRTR_VL} — 요금제 트랙과 함께 쓰는 행이다 */
        @Schema(description = "민원 월기준값 (건/월)", example = "50")
        private BigDecimal cvlcptMmCrtrVl;

        /** 민원 비율기준값(건/100면·월). {@code RECOMMEND.CVLCPT_RT_CRTR_VL} */
        @Schema(description = "민원 비율기준값 (공영 100면당 월평균 민원)", example = "7")
        private BigDecimal cvlcptRtCrtrVl;

        /**
         * 핵심 후보 배수. 🔴 <b>설정이 아니라 코드 상수</b>(2)다 — 설정으로 열면 "원하는 숫자를 만드는
         * 칸" 이 된다. 그래도 결과를 재현하는 데 필요한 입력이라 같은 블록에 담는다.
         */
        @Schema(description = "핵심 후보 배수 (코드 상수)", example = "2")
        private BigDecimal coreMltp;

        /**
         * 월평균의 분모 — {@code PrdMonths} — 달별 조회 비율 합(TSK-4284). 소수 허용(최대 6자리),
         * 온전한 달만 고르면 정수로 보인다(꼬리 0 제거).
         */
        @Schema(description = "조회 개월 수 (월평균의 분모 — 달별 조회 비율 합, 소수 허용. 온전한 달이면 정수)", example = "8")
        private BigDecimal months;

        /**
         * 민원증가가중치. {@code RECOMMEND.CVLCPT_INCRS_WGVL}(TSK-4283) — 증가계수 = 1 + 이 값 × clamp(증가율).
         * 0 이면 추세 미반영(계수가 항상 1). 꼬리 0 을 걷은 값이다.
         */
        @Schema(description = "민원증가가중치 (증가계수 = 1 + 가중치 × clamp(증가율, ±상한). 0 이면 추세 미반영)", example = "0.5")
        private BigDecimal incrsWgvl;

        /** 민원증가율상한값(%). {@code RECOMMEND.CVLCPT_INCRS_RT_UPLMT_VL} — 증가율을 ±이 값으로 자른다 */
        @Schema(description = "민원증가율상한값 (%, 증가율을 ±이 값으로 잘라 증가계수에 넣는다)", example = "100")
        private BigDecimal incrsRtUplmtVl;

        /**
         * 증가율 분모 하한(건) — 이전 블록이 이 값 미만이면 증가율 {@code null} · 증가계수 1.
         * 🔴 설정이 아니라 코드 상수(요금제와 같은 10)다. 재현에 필요해 함께 굳힌다.
         */
        @Schema(description = "증가율 분모 하한 (건, 코드 상수) — 이전 블록이 이 값 미만이면 증가율 null · 증가계수 1", example = "10")
        private Integer minTrendBase;

        /**
         * 증가율 블록 일수 — 1차(직전 동일 기간)면 조회 일수 D, 반 분할 폴백({@code TREND_BFR_FALLBACK})이면
         * D/2. 기간 하한 미달({@code TREND_WINDOW_SHORT})이면 {@code null} 이다.
         */
        @Schema(description = "증가율 블록 일수 (1차 = 조회 일수 D, 반 분할 폴백 = D/2, 조회 기간 14일 미만이면 null)",
                example = "31", nullable = true)
        private Integer trendDayCnt;
    }

    /** 요약 블록. 🔴 지역 필터를 걸어도 줄어들지 않는다(도 전체 값) */
    @Schema(description = "공유주차제 판정 요약 (도 전체)")
    @Getter
    @Setter
    @ToString
    @NoArgsConstructor
    public static class Smry {

        /** 읍면동 수(행정동 마스터 행 수) — 등급별 개수의 합과 같다 */
        @Schema(description = "읍면동 수", example = "74")
        private Integer emdCnt;

        /** 후보 수({@code CORE} + {@code CAND}) */
        @Schema(description = "후보 읍면동 수 (CORE + CAND)", example = "18")
        private Integer rvwTrgtCnt;

        /** 핵심 후보 수({@code CORE}) */
        @Schema(description = "핵심 후보 읍면동 수", example = "6")
        private Integer coreCnt;

        /** 민원 기준만 충족한 읍면동 수({@code CVLCPT_ONLY}) */
        @Schema(description = "민원 기준만 충족한 읍면동 수", example = "8")
        private Integer cvlcptOnlyCnt;

        /** 판정 불가 읍면동 수({@code UNABLE} — 면수 0) */
        @Schema(description = "판정 불가 읍면동 수", example = "9")
        private Integer unableCnt;

        /**
         * 공한지를 뺀 면수로 판정했을 때의 후보 수 — {@code vaclotExclRvwTrgtYn='Y'} 인 읍면동 <b>전부</b>다
         * (기본 판정의 후보를 포함한다. 늘어난 수가 아니다).
         */
        @Schema(description = "공한지를 뺀 면수로 판정했을 때의 후보 읍면동 수", example = "23")
        private Integer vaclotExclRvwTrgtCnt;

        /** 조회 구간의 주차 민원 총건수(읍면동 전체 합) */
        @Schema(description = "주차 민원 총건수", example = "40875")
        private Long cvlcptTot;

        /**
         * 후보 읍면동의 민원이 전체 민원에서 차지하는 비중(%). 소수 1자리.
         *
         * <p>민원이 0건이면 {@code null} 이다.
         */
        @Schema(description = "후보 읍면동 민원 비중 (%, 소수 1자리, 민원 0건이면 null)", example = "74.4", nullable = true)
        private BigDecimal rvwTrgtCvlcptRt;
    }
}
