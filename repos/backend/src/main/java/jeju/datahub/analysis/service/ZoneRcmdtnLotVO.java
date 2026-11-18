package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 구역추천 후보 주차장 1건. (MyBatis 조회 전용 VO — 후보 주차장 1개소 = 이 VO 1건)
 *
 * <p>컬럼(스네이크) → 필드(카멜) 매핑은 mybatis.configuration.map-underscore-to-camel-case=true 로
 * 자동 처리한다. <b>JPA 엔티티가 아니다</b> — 조회 전용이다.
 *
 * <h2>🔴 매퍼가 채우는 14필드 + 서비스가 채우는 4필드</h2>
 * {@code selectLotRanking} 이 채우는 것은 {@link #pkltId}~{@link #cvlcptPerSlot} <b>14필드</b>이고 (🔁 TSK-4259 {@link #emdCd}·{@link #emdNm} 추가)
 * ({@link #asgnCvlcptCntRcnt}·{@link #asgnCvlcptCntBfr} 포함),
 * {@link #rnk}·{@link #cumCpcty}·{@link #cvlcptIncrsRt}·{@link #trendMm}
 * <b>4필드</b>는 {@code ZoneRcmdtnServiceImpl}/{@code ZoneRcmdtnCalculator} 가 채운다
 * (매퍼 {@code ORDER BY} 는 <b>순서만</b> 정하고 번호 칸은 비어 있다).
 * 🔴 그 4필드의 <b>setter 가 반드시 있어야 한다</b> — 없으면 서비스의
 * {@code setRnk}/{@code setCumCpcty}/{@code setCvlcptIncrsRt}/{@code setTrendMm}
 * 가 컴파일되지 않는다.
 *
 * <p>🔴 <b>주차장별 "효과(감소 건수)" 필드는 두지 않는다.</b> 한 주차장을 유료화했을 때 민원이 몇 건
 * 줄어드는지는 이 분석이 말할 수 있는 범위가 아니다 — 순위 근거는 <b>배정 민원 수</b>({@link
 * #asgnCvlcptCnt})까지다.
 *
 * <p>🔴 {@link #asgnCvlcptCnt}·{@link #asgnCrdnCnt} 는 <b>최근접 배정</b> 집계다 — 민원·단속 1건은
 * 가장 가까운 후보 주차장 <b>1곳에만</b> 센다(반경 집계가 아니라 중복이 없다). 그래서 한 읍면동의
 * {@code asgnCvlcptCnt} 합은 그 동의 좌표 보유 민원 수를 <b>넘지 않는다</b>.
 *
 * <p>🔴 {@code @JsonIgnoreProperties(ignoreUnknown = true)} — 구 보고서 스냅샷의 {@code lots[].reachNeed} 를 무시한다(TSK-4340).
 */
@Schema(description = "구역추천 후보 주차장 1건 (배정 민원 순)")
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ZoneRcmdtnLotVO {

    // --- 매퍼가 채우는 14필드 (🔁 TSK-4259 emdCd·emdNm) ---

    /** 주차장 ID. {@code TB_PBLPKLT.pklt_id}(IDENTITY) — 🔴 값에 업무적 의미가 없다 */
    @Schema(description = "주차장 ID", example = "11513")
    private Long pkltId;

    /** 주차장명 */
    @Schema(description = "주차장명", example = "이도이동 1994-1")
    private String pkltNm;

    /** 주차장 유형명(원천 문자열 — 코드그룹이 없다) */
    @Schema(description = "주차장 유형명", example = "공영")
    private String pkltTypeNm;

    /**
     * 주차면수. {@code integer} 라 {@link Integer} 다.
     *
     * <p>🔴 서비스의 누적 계산은 {@code BigDecimal.valueOf(getPkltCpcty())} 로 감싼다 —
     * {@code BigDecimal.add(Integer)} 는 컴파일되지 않는다.
     */
    @Schema(description = "주차면수", example = "28")
    private Integer pkltCpcty;

    /** 주차장 주소 */
    @Schema(description = "주차장 주소", example = "제주특별자치도 제주시 이도이동 1994-1")
    private String pkltAddr;

    /**
     * 🔁 TSK-4259 — 주차장이 속한 읍면동 코드({@code tb_pblpklt.emd_cd}). 두 모드 공통(D7 · 하위 호환 추가).
     * 시군구 모드에서 행마다 어느 동인지 보이기 위한 칸이다.
     */
    @Schema(description = "읍면동 코드", example = "50110630")
    private String emdCd;

    /** 🔁 TSK-4259 — 읍면동명({@code tb_admdst.emd_nm} JOIN). 두 모드 공통(D7) */
    @Schema(description = "읍면동명", example = "이도이동")
    private String emdNm;

    /** 위도. 화면 지도 핀의 좌표다({@code tb_pblpklt.lat} 은 NOT NULL 이라 비지 않는다) */
    @Schema(description = "위도", example = "33.49")
    private BigDecimal lat;

    /** 경도. {@code tb_pblpklt.lot} 은 NOT NULL 이라 비지 않는다 */
    @Schema(description = "경도", example = "126.53")
    private BigDecimal lot;

    /** 배정 민원 수 — 🔴 <b>이 값이 순위의 1차 키</b>다. 배정이 없으면 0 이다({@code COALESCE}) */
    @Schema(description = "배정 민원 수 (최근접 배정)", example = "218")
    private Long asgnCvlcptCnt;

    /**
     * 🔴 <b>최근 블록</b> 배정 민원 수 — 증가율의 <b>분자</b> (TSK-4207 Step 13).
     *
     * <p>🔁 <b>고정 3개월도, 완전한 달 단위도 아니다</b>(Step 12·13 이 차례로 폐지했다). 선택한
     * 민원 창의 총 일수 {@code D} 를 반으로 갈라 <b>마지막 {@code D/2} 일</b>이 이 칸이다. 적용된
     * 블록 일수는 {@code crtrVls.trendDayCnt} 로 함께 내려간다 — 그 값 없이는 재현이 안 된다.
     *
     * <p>🔴 {@link #asgnCvlcptCnt} 와 <b>다른 칸</b>이다 — 순위의 1차 키는 여전히 전 구간 건수다
     * (증가율로 정렬하지 않는다. §9-1).
     *
     * <p>블록이 <b>7일 미만</b>(= 조회 기간 14일 미만)이면 비교가 성립하지 않으므로 서비스가
     * <b>{@code null}</b> 로 비우고 {@code warnings} 에 {@code TREND_WINDOW_SHORT} 를 담는다
     * (0 을 넣으면 "최근 배정이 0건" 으로 읽힌다).
     *
     * <p>⚠️ 이름은 {@code 최근 = RCNT}(표준단어)이고 <b>단위와 무관</b>하다 — 블록이 월에서 일로
     * 바뀌어도 이 칸 이름은 그대로다({@link #asgnCvlcptCntBfr} 와 짝이다).
     */
    @Schema(description = "최근 블록 배정 민원 수 (블록 일수 = crtrVls.trendDayCnt)", example = "106")
    private Long asgnCvlcptCntRcnt;

    /**
     * 🔴 <b>이전 블록</b> 배정 민원 수 (최근 블록 길이와 <b>같은 길이</b> — 증가율의 <b>분모</b>).
     *
     * <p>🔁 Step 13 — 민원 창 총 일수 {@code D} 중 <b>처음 {@code D/2} 일</b>이다.
     * 🔴 {@code D} 가 홀수면 <b>가운데 1일을 버린다</b> — 두 블록 일수가 다르면 증가율이 비교가 아니다.
     *
     * <p>🔴 이 값이 작으면 증가율이 폭발한다(이도이동 {@code 1982-3} 은 3 → 17 건으로 <b>+466.7%</b>).
     * 그래서 {@code ZoneRcmdtnCalculator.MIN_TREND_BASE}(10) 미만이면 {@link #cvlcptIncrsRt} 를
     * 계산하지 않는다(§9-1).
     *
     * <p>⚠️ 이름의 {@code Bfr} 는 데이터표준사전 표준단어 <b>이전 = BFR</b>(Before)이다
     * ({@code PREV} 는 사전에 없다 — {@code 최근 = RCNT} 와 짝이다).
     */
    @Schema(description = "이전 블록 배정 민원 수 (증가율 분모)", example = "66")
    private Long asgnCvlcptCntBfr;

    /** 배정 단속 수. 순위에는 쓰지 않는 참고값이다 */
    @Schema(description = "배정 단속 수 (최근접 배정)", example = "120")
    private Long asgnCrdnCnt;

    /** 면당 민원 = 배정 민원 ÷ 주차면수(소수 2자리). {@code pklt_cpcty} 가 0 이면 null 이다 */
    @Schema(description = "면당 민원 (배정 민원 ÷ 주차면수)", example = "7.79")
    private BigDecimal cvlcptPerSlot;

    // --- 🔴 서비스(순위 루프·추세)가 채우는 4필드 — setter 필수 ---

    /**
     * 순위(1부터). 🔴 <b>매퍼가 아니라 서비스가 채운다</b> — 빠뜨리면 응답의 순위 열이 전부 null 이고
     * 컴파일은 통과한다.
     *
     * <p>🔁 TSK-4282 — 순서는 <b>추세 반영 검토지수({@link #incrsRfltRvwScr}) 내림차순 → 배정 민원 → 면수 →
     * {@code pklt_id}</b> 이다(§0-2). 매퍼 {@code ORDER BY} 는 결정적 입력 순서일 뿐이다(D9).
     */
    @Schema(description = "순위 (1부터, 추세 반영 검토지수 내림차순 → 배정 민원 → 면수 → 주차장 ID)", example = "1")
    private Integer rnk;

    /**
     * 누적 주차면수 — 이 순위까지 담았을 때의 합.
     *
     * <p>🔴 "이 순서대로 전환하면 몇 면이 되나" 를 보여줄 뿐 <b>목록을 자르지 않는다</b> — 몇 개소까지 할지는
     * 예산·현장 사정으로 담당자가 정한다. 🔁 TSK-4340(2026-11-11) — 필요 면수 도달 여부({@code reachNeed})는
     * 읍면동 판정과 함께 제거됐다.
     */
    @Schema(description = "누적 주차면수", example = "28")
    private BigDecimal cumCpcty;

    /**
     * 민원 증가율(%) = (최근 블록 − 이전 블록) ÷ 이전 블록 × 100. 🔴 <b>{@code null} 이 정상값이다.</b>
     *
     * <p>🔴 이전 블록이 {@code ZoneRcmdtnCalculator.MIN_TREND_BASE}(10) <b>미만이면 계산하지 않는다</b>
     * (0 건 포함) — 작은 분모에서 폭발하기 때문이다. 이도이동 {@code 1982-3} 은 3 → 17 건인데
     * 그대로 계산하면 <b>+466.7%</b> 로 1위가 된다. 실제로 늘어난 것은 14건이다(§9-1).
     * 적용된 하한은 {@code crtrVls.minTrendBase} 로 함께 내려간다.
     *
     * <p>🔁 TSK-4282 — <b>단독으로 순위를 정하지 않는다.</b> 증가계수({@link #incrsCfc})로 바뀌어 검토지수에
     * 곱해지고, 순위는 그 결과인 {@link #incrsRfltRvwScr} 내림차순이다({@code null} 이면 계수 1).
     * 화면은 {@code null} 을 {@code —} 로 표시한다.
     */
    @Schema(description = "민원 증가율 (%) — 이전 블록이 하한 미달이면 null", example = "27.3")
    private BigDecimal cvlcptIncrsRt;

    /**
     * <b>최근 블록</b>의 월별 배정 민원(차트용) — 🔴 배정 0건인 달도 {@code cnt=0} 으로 들어 있어
     * <b>칸 수가 주차장마다 같고</b>, 합은 {@link #asgnCvlcptCntRcnt} 와 같다.
     *
     * <p>🔁 Step 13 — 칸 수는 블록 일수가 아니라 <b>블록이 걸친 달 수</b>다(15일이면 1~2칸 ·
     * 121일이면 4~5칸). 🔴 <b>양 끝은 부분 달이다</b> — Step 12 의 "부분 달은 버린다" 는 폐지됐다.
     * 그 규칙이 {@code 최근1개월}(양쪽 달이 다 불완전)을 통째로 쓸 수 없게 만들었다.
     *
     * <p>🔴 그래도 <b>합 = {@link #asgnCvlcptCntRcnt} 불변식은 유지된다</b> — 구간 집계와 월별
     * 집계가 같은 <b>날짜</b> 경계를 보기 때문이다(달 단위로 채우면 그 등식이 깨진다).
     *
     * <p>블록이 7일 미만이면 <b>빈 배열</b>이다({@code warnings} 에
     * {@code TREND_WINDOW_SHORT} 가 담긴다).
     */
    @Schema(description = "최근 블록 월별 배정 민원 (블록이 걸친 달 수 · 합 = asgnCvlcptCntRcnt)")
    private List<ZoneRcmdtnTrendVO> trendMm;

    // --- 🆕 TSK-4282 — 요금제 검토지수 8필드 (sggNm 은 매퍼 · 나머지 7필드는 ZoneRcmdtnCalculator.scoreLots) ---

    /**
     * 🆕 주차장이 속한 시군구명({@code tb_admdst.sgg_nm}). 기준값 A·B 를 시군구별로 고르는 키다(D3).
     *
     * <p>⚠️ {@code LEFT JOIN} 미매칭이면 {@code null} — 계산기가 제주시 기준값으로 떨어뜨리고
     * {@code warnings} 에 {@code COEF_FALLBACK} 을 담는다.
     */
    @Schema(description = "시군구명", example = "제주시")
    private String sggNm;

    /** 🆕 민원강도 점수(원점수 · 상한 없음) = 월배정민원 ÷ 시군구 민원 월 기준값 × 100. 표시 소수 1자리 */
    @Schema(description = "민원강도 점수 (상한 없음)", example = "136.4")
    private BigDecimal cvlcptCntScr;

    /** 🆕 면당압력 점수(원점수 · 상한 없음) = 100면당 월민원 ÷ 시군구 면당민원 기준값 × 100. 면수 0·null 이면 0 */
    @Schema(description = "면당압력 점수 (상한 없음)", example = "21.4")
    private BigDecimal cvlcptRtScr;

    /** 🆕 검토지수(원점수) = (w1 × 민원강도 + w2 × 면당압력) ÷ (w1 + w2). 🔴 100 을 넘을 수 있다(D1) */
    @Schema(description = "검토지수 (원점수 · 상한 없음)", example = "101.9")
    private BigDecimal rvwScr;

    /**
     * 🔁 우선도 단계코드(D14) — {@link #incrsRfltRvwScr}(우선도, 반올림 전 원값)로 매긴다:
     * {@code TOP} 최우선(100↑) · {@code HIGH} 우선(60↑) · {@code LOW} 후순위(60 미만).
     * 🔁 2026-10-26 사용자 요청 — 4단계(보통 {@code MID} 30↑ 포함) → 3단계, 30 경계 삭제(종전 {@code MID}·{@code LOW} 가 {@code LOW} 로 합쳐졌다).
     * 순위값과 같은 값이라 <b>응답 순서대로 단계가 비감소</b>다. 우선도가 {@code null} 이면 {@code null}.
     * ⚠️ 공유주차제 {@code rvwGrdCd} 와 키는 같지만 값 집합이 다르다.
     */
    @Schema(description = "우선도 단계코드 (TOP 100↑ | HIGH 60↑ | LOW 60 미만) — 우선도(incrsRfltRvwScr)로 판정",
            example = "HIGH", nullable = true)
    private String rvwGrdCd;

    /** 🆕 증가계수 = 1 + wi × clamp(증가율, ±상한). 증가율 null 이면 1. 표시 소수 3자리 */
    @Schema(description = "증가계수", example = "0.975")
    private BigDecimal incrsCfc;

    /** 🆕 우선도 = 검토지수 × 증가계수 — 🔴 <b>순번의 1차 키</b>(§0-2)이자 단계({@link #rvwGrdCd}) 판정값(D14) */
    @Schema(description = "우선도 = 검토지수 × 증가계수 (순번 1차 키 · 단계 판정값)", example = "99.3")
    private BigDecimal incrsRfltRvwScr;
}
