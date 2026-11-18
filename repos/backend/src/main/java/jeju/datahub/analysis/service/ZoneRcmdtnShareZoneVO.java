package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 구역추천(공유주차제 적용) 읍면동 판정 1건 — 응답 {@code zones} 의 원소다.
 *
 * <p>이 화면이 답하는 질문은 <b>"주차 민원 기준으로, 공유주차제를 검토해 볼 만한 읍면동은 어디인가"</b>
 * 다. 그래서 결과 이름은 "공유주차 검토 후보" 이고, 이 VO 의 등급은 <b>적합·필요·추천을
 * 말하지 않는다</b> — 공유할 부설주차장이 있는지, 어느 시간대에 부족한지, 시행하면 민원이 얼마나
 * 줄어드는지는 이 값들로 답할 수 없다.
 *
 * <p>🔴 <b>숫자는 전부 "응답에 실을 때 한 번" 반올림한 값이다</b>(HALF_UP — {@link #rvwScr} 만 버림). 판정·순번·주의 표시는
 * 반올림 전 값(소수 6자리)으로 이미 끝났다 — 이 VO 의 {@link #cvlcptMmAvg}·{@link #cvlcptRt} 를
 * 기준값과 다시 비교해 등급을 재현하려 하지 않는다(외도일동 14.207… 같은 경계 값이 뒤집힌다).
 *
 * <p>🔴 <b>등급 라벨(한글)은 여기 없다</b> — 공통코드 {@code SHRN_RVW_GRD_CD} 가 출처다.
 */
@Schema(description = "구역추천 공유주차제 읍면동 판정 1건")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ZoneRcmdtnShareZoneVO {

    /**
     * 표시 순번. 조회 결과 안에서 {@link #rvwScr}(검토 지수)가 있는 읍면동 <b>전부</b>에 매긴다 — 판정 불가
     * ({@code UNABLE})는 {@code null} 이다.
     *
     * <p>순서는 추세 반영 지수(우선도 · {@link #incrsRfltRvwScr}) 내림차순 → 민원 건수
     * 내림차순 → {@code emd_cd} 오름차순이다(지수는 2자리로 줄이기 전 값으로 비교한다 — 표시값이 같아도
     * 순번이 갈릴 수 있다). 🔴 <b>후보 여부를 1차 키로 두지 않는다</b> — 기준 미달 동이 후보 앞에, 핵심 후보가
     * 후보 뒤에 설 수 있다(등급·후보는 검토 지수로 정한다). (~2026-10-26)은 후보 먼저 → 추세 반영
     * 지수 순, 그 전에는 검토 지수 내림차순이었고, 종전(~2026-10-13)에는 후보에만 매겼다.
     *
     * <p>🔴 <b>사업 우선순위가 아니라 표시 순번이다.</b> "몇 번까지" 를 가르는 근거가 없다(분석 보고서
     * 8절). 지도 깃발 숫자와 목록 행을 잇는 번호로만 쓴다.
     *
     * <p>🔴 <b>조회 결과(필터 적용 후) 안에서 매긴 번호다</b> — 지역 필터({@code sggNm}·{@code emdCd})를
     * 걸면 걸린 읍면동 안에서 1 부터 다시 이어진다. 그래서 같은 읍면동의 번호가 조회 조건에 따라
     * 달라진다(등급·{@code rvwScr} 는 달라지지 않는다). 필터가 없으면 도 전체 번호다.
     * 🔁 종전(~2026-10-13)에는 필터를 걸어도 도 전체 번호였다.
     */
    @Schema(description = "표시 순번 — 추세 반영 지수(incrsRfltRvwScr · 화면 표기 우선도) "
            + "내림차순 → 민원 건수 내림차순 → 읍면동코드 순으로, 지수가 있는 읍면동 전부에 매긴다(판정 불가는 null). "
            + "후보 여부는 순서 키가 아니다(기준 미달 읍면동이 후보 앞에 설 수 있다). 조회 결과(sggNm·emdCd 필터 적용 후) 안에서 1 부터 매긴다",
            example = "1", nullable = true)
    private Integer rnk;

    /** 읍면동 코드(8자리) */
    @Schema(description = "읍면동 코드", example = "50110137")
    private String emdCd;

    /** 시군구명 */
    @Schema(description = "시군구명", example = "제주시")
    private String sggNm;

    /** 읍면동명 */
    @Schema(description = "읍면동명", example = "연동")
    private String emdNm;

    /** 읍면동 중심 위도 — 지도 깃발 좌표다({@code TB_ADMDST.ctr_lat}). ⚠️ nullable */
    @Schema(description = "읍면동 중심 위도", example = "33.48", nullable = true)
    private BigDecimal lat;

    /** 읍면동 중심 경도({@code TB_ADMDST.ctr_lot}). ⚠️ nullable */
    @Schema(description = "읍면동 중심 경도", example = "126.49", nullable = true)
    private BigDecimal lot;

    /** 조회 구간의 주차 민원 건수(합계 — 미분류 유형 {@code '99'} 제외) */
    @Schema(description = "조회 구간 주차 민원 건수", example = "6549")
    private Long cvlcptCnt;

    /** 월평균 민원(건/월) = 민원 건수 ÷ 조회 개월 수. 소수 1자리 */
    @Schema(description = "월평균 민원 (건/월, 소수 1자리)", example = "818.6")
    private BigDecimal cvlcptMmAvg;

    /** 공영주차장 개소 수(소멸분 제외) */
    @Schema(description = "공영주차장 개소 수", example = "129")
    private Long pkltCnt;

    /**
     * 공영 주차면수 합(소멸분 제외, 유형 전부 포함 — 공한지도 센다).
     *
     * <p>⚠️ 면수 결측인 주차장은 합에서 빠진다(0 으로 대체하지 않는다).
     */
    @Schema(description = "공영 주차면수 합 (공한지 포함)", example = "2315")
    private Long pkltCpcty;

    /**
     * 공영 100면당 월평균 민원 = 월평균 민원 ÷ 면수 × 100. 소수 2자리.
     *
     * <p>🔴 면수가 0 이면 {@code null} 이다(0 이 아니다) — 그 동은 등급이 {@code UNABLE} 이다.
     * <p>🔴 <b>월 단위다.</b> 분석 보고서의 "100면당 70건" 은 10개월 합계 기준이라 값이 다르다.
     */
    @Schema(description = "공영 100면당 월평균 민원 (소수 2자리, 면수 0 이면 null)", example = "35.36", nullable = true)
    private BigDecimal cvlcptRt;

    /**
     * 검토 지수 = min(월평균 민원 ÷ 민원 월기준값, 100면당 월평균 민원 ÷ 민원 비율기준값). 소수 2자리.
     *
     * <p>두 기준을 <b>몇 배 넘었는지</b>를 값 하나로 적은 것이다 — <b>1 이상 = 후보, 2 이상 = 핵심 후보</b>
     * ({@link #rvwGrdCd} 와 같은 뜻이다. 등급을 이 값으로 다시 판정하지 않는다 — 등급이 정본이다).
     *
     * <p>🔴 <b>필드명이 {@code rvwScr} 인 이유</b> — 데이터표준사전에 "지수" 단독 단어가 없어 표준 단어
     * 검토 {@code RVW} + 점수 {@code SCR} 을 쓴다. 화면 표기는 "검토 지수" 다.
     *
     * <p>🔴 <b>소수 2자리로 줄일 때 반올림이 아니라 버림이다.</b> 반올림이면 {@code 0.996} 이
     * {@code 1.00} 으로 보여 "지수 1.00 인데 후보가 아님" 이 생긴다. 버림이라 표시값 ≥ 1.00 ⇔ 후보,
     * ≥ 2.00 ⇔ 핵심 후보가 항상 성립한다. ⚠️ 그래서 응답의 {@link #cvlcptMmAvg}·{@link #cvlcptRt}
     * (반올림된 값)로 다시 계산하면 끝자리가 0.01 쯤 다를 수 있다.
     *
     * <p>🔴 {@code null} 인 경우 — 면수가 0 이라 비율이 없을 때({@code UNABLE}), 그리고 두 기준값이
     * 모두 0 일 때(응답 {@code warnings} 에 {@code CRTR_ALL_ZERO}). 기준값이 한쪽만 0 이면 그 항은
     * "항상 충족" 이라 빼고 다른 항만으로 낸다.
     *
     * <p>🔴 <b>"기준을 넘은 정도" 이지 공유주차 적합도·효과·우선순위가 아니다</b> — 공유할 부설주차장이
     * 있는지, 어느 시간대에 부족한지는 이 값이 보지 않는다.
     */
    @Schema(description = "검토 지수 = min(월평균 민원 ÷ 민원 월기준값, 100면당 월평균 민원 ÷ 민원 비율기준값). "
            + "기준을 몇 배 넘었는지다 — 1 이상 = 후보, 2 이상 = 핵심 후보. 소수 2자리 버림(반올림 아님). "
            + "면수 0(UNABLE)이면 null. 공유주차 적합도·효과·우선순위가 아니다",
            example = "2.03", nullable = true)
    private BigDecimal rvwScr;

    /**
     * 검토 등급 — {@code CORE}(두 기준의 2배 이상) | {@code CAND}(두 기준 모두 이상) |
     * {@code CVLCPT_ONLY}(민원 기준만 충족) | {@code UNABLE}(면수 0) | {@code NONE}(그 밖).
     *
     * <p>🔴 경계는 전부 "이상(≥)" 이다. 라벨은 공통코드 {@code SHRN_RVW_GRD_CD} 에서 읽는다.
     */
    @Schema(description = "검토 등급 (CORE | CAND | CVLCPT_ONLY | UNABLE | NONE) — 라벨은 공통코드 SHRN_RVW_GRD_CD",
            example = "CORE")
    private String rvwGrdCd;

    /** 검토대상여부 — 등급이 {@code CORE}·{@code CAND} 면 {@code "Y"}, 그 밖은 {@code "N"} */
    @Schema(description = "검토대상여부 (Y | N) — 후보(CORE·CAND)면 Y", example = "Y")
    private String rvwTrgtYn;

    /**
     * 주의 표시 코드 목록. 후보에만 붙고 그 밖은 <b>빈 배열</b>이다.
     * <ul>
     *   <li>{@code TOP_PNT} — 민원 최다 좌표 1곳을 뺀 건수로 다시 판정하면 후보 조건에 못 미친다
     *       (한 지점에 몰린 민원이 후보를 만들었다)</li>
     *   <li>{@code CRTR_BNDRY} — 월평균 또는 비율이 적용 기준의 +5% 미만 여유다
     *       ({@code CORE} 면 핵심 기준도 본다)</li>
     * </ul>
     */
    @Schema(description = "주의 표시 코드 (TOP_PNT 한 지점 쏠림 | CRTR_BNDRY 기준 경계) — 후보가 아니면 빈 배열")
    private List<String> cutnCds = new ArrayList<>();

    /**
     * 민원 최다 좌표 1곳의 비중(%) = 최다 지점 건수 ÷ 민원 건수 × 100. 소수 1자리.
     *
     * <p>민원이 0건이면 {@code null} 이다. ⚠️ 좌표 없는 민원은 "한 지점" 으로 묶지 않으므로
     * 좌표 보유 민원이 없으면 0 이다.
     */
    @Schema(description = "민원 최다 좌표 1곳의 비중 (%, 소수 1자리, 민원 0건이면 null)", example = "6.9", nullable = true)
    private BigDecimal topPntRt;

    /**
     * 공한지 주차장을 뺀 면수로 계산한 100면당 월평균 민원. 소수 2자리.
     *
     * <p>공한지를 뺀 면수가 0 이면 {@code null} 이다.
     */
    @Schema(description = "공한지를 뺀 면수 기준 100면당 월평균 민원 (소수 2자리)", example = "43.50", nullable = true)
    private BigDecimal vaclotExclCvlcptRt;

    /**
     * 공한지를 뺀 면수로 판정했을 때의 후보 여부({@code "Y"}/{@code "N"}).
     *
     * <p>기본 판정은 공한지를 <b>포함</b>한다. 화면은 {@link #rvwTrgtYn} 이 {@code N} 인데 이 값이
     * {@code Y} 인 읍면동을 "공한지를 빼면 후보" 로 따로 묶는다.
     */
    @Schema(description = "공한지를 뺀 면수로 판정했을 때의 후보 여부 (Y | N)", example = "Y")
    private String vaclotExclRvwTrgtYn;

    /**
     * 증가율의 분모로 쓴 이전 블록 민원 건수 — 1차면 직전 동일 기간(D일), 반 분할 폴백이면 조회 기간
     * 앞 D/2일.
     *
     * <p>🔴 {@code null} 은 <b>기간 하한 미달</b>(조회 기간 14일 미만 — {@code crtrVls.trendDayCnt} 가
     * {@code null})뿐이다. 이전 블록이 0건이면 {@code 0} 이다.
     */
    @Schema(description = "증가율 분모로 쓴 이전 블록 민원 건수 (1차 = 직전 동일 기간, 폴백 = 조회 기간 앞 D/2). "
            + "조회 기간 14일 미만이면 null, 0건이면 0", example = "512", nullable = true)
    private Long bfrCvlcptCnt;

    /**
     * 민원 증가율(%) = (최근 블록 − 이전 블록) ÷ 이전 블록 × 100. 소수 1자리(HALF_UP — 표시값이다).
     *
     * <p>{@code null} 인 경우 — 이전 블록이 {@code crtrVls.minTrendBase}(10건) 미만(분모 하한 미달)이거나
     * 조회 기간 14일 미만(기간 하한 미달). 판정 불가({@code UNABLE}) 행도 건수로 계산한다.
     */
    @Schema(description = "민원 증가율 (%, 소수 1자리). 이전 블록 10건 미만이거나 조회 기간 14일 미만이면 null",
            example = "-12.3", nullable = true)
    private BigDecimal cvlcptIncrsRt;

    /**
     * 증가계수 = 1 + 가중치 × clamp(증가율, ±상한). 소수 3자리(표시값 — 계산은 반올림 전 정밀값으로 한다).
     *
     * <p>증가율이 {@code null}(분모 하한 미달 · 기간 하한 미달)이면 {@code 1}(추세 중립)이다.
     * 기본 설정(가중치 0.5 · 상한 100%)이면 0.5 ~ 1.5 다.
     */
    @Schema(description = "증가계수 = 1 + 가중치 × clamp(증가율, ±상한) (소수 3자리). 증가율이 null 이면 1",
            example = "0.938")
    private BigDecimal incrsCfc;

    /**
     * 추세 반영 지수 = 검토 지수({@link #rvwScr}) × 증가계수. 소수 2자리 <b>버림</b>({@link #rvwScr} 와 같은 관례).
     *
     * <p>🔴 <b>순서({@link #rnk})에만 쓴다</b> — 등급·후보 판정은 여전히 {@link #rvwScr} 다(1 이상이어도 후보가
     * 아닐 수 있고, 1 미만이어도 후보일 수 있다). 판정 불가({@code UNABLE})·두 기준 0 이면 {@code null}.
     */
    @Schema(description = "추세 반영 지수 = 검토 지수 × 증가계수 (소수 2자리 버림). 순서에만 쓰고 등급은 rvwScr 로 정한다. "
            + "판정 불가(UNABLE)면 null", example = "2.42", nullable = true)
    private BigDecimal incrsRfltRvwScr;
}
