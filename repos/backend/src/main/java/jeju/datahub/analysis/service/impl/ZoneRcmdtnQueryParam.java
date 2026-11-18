package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 구역추천 매퍼 전용 파라미터. 서비스가 요청값을 검증·변환해 채운 뒤 매퍼로 넘긴다.
 * 모든 값은 XML 에서 {@code #{}} 로만 바인딩된다({@code ${}} 미사용 → 인젝션 방어).
 *
 * <p>🔴 <b>민원·단속 둘 다 {@code timestamp} 라 날짜는 모두 {@link LocalDateTime} 이다 —
 * 문자열로 넘기면 조용히 0행이 아니라 런타임 오류다.</b>
 * <ul>
 *   <li>{@code tb_cvlcpt.ocrn_dt}(발생일시)는 {@code timestamp} 라 {@link #cvFrom}·{@link #cvToExcl}
 *       가 {@link LocalDateTime} 이다. 민원 구간도 <b>반열림</b>
 *       ({@code ocrn_dt >= cvFrom AND ocrn_dt < cvToExcl})이다 — {@code BETWEEN … AND '2026-08-31'}
 *       로 적으면 종료일 <b>00:00:00 이후가 통째로 빠진다</b>.
 *       (🔁 2026-10-12 — 종전에는 {@code dclr_ymd}({@code CHAR(10)})를 대시 포함 문자열로 넘겼다.
 *        바꾼 이유는 ① {@code dclr_ymd} 가 {@code NULL} 인 행이 <b>17,811건(18.5%)</b> 들어와
 *        그만큼이 어떤 기간을 골라도 빠졌고, ② 다른 민원 화면이 전부 {@code ocrn_dt} 라 기준이
 *        갈리면 같은 기간을 골라도 한쪽만 결과가 나왔기 때문이다. 생활권 수급이 2026-09-13 에
 *        같은 전환을 한 선례다 — {@code LifezoneSupplyQueryParam} 주석.)</li>
 *   <li>{@code tb_crdn_dsctn.crdn_dt} 는 {@code timestamp} 라 {@link #crdnFrom}·{@link #crdnTo} 는
 *       {@link LocalDateTime} 이다. 문자열로 넘기면 JDBC 가 {@code varchar} 로 보내
 *       <b>{@code operator does not exist: timestamp >= character varying}</b> 로 터진다.
 *       단속 구간은 <b>반열림</b>({@code crdn_dt >= crdnFrom AND crdn_dt < crdnTo})이다.</li>
 * </ul>
 *
 * <p>⚠️ <b>민원 창과 단속 창은 서로 다른 구간이다</b>(§6) — 보유 구간이 달라 일부러 갈라 둔다.
 * 한쪽을 고칠 때 다른 쪽을 같이 옮기지 않는다.
 *
 * <p>🔴 필드명이 매퍼 XML 의 {@code #{…}} 과 <b>정확히 일치</b>해야 한다 — 어긋나면 컴파일은
 * 통과하고 런타임에 바인딩만 실패한다.
 */
@Getter
@Setter
@ToString
public class ZoneRcmdtnQueryParam {

    /**
     * 선택한 읍면동 코드(8자리) — {@code selectLotRanking} 전용.
     *
     * <p>🔴 주차장 후보·민원·단속 <b>세 CTE 모두</b>에 같은 값으로 걸린다(배정을 동 경계 안으로
     * 가둔다). 비면 {@code emd_cd = NULL} 이라 조용히 0행이다.
     *
     * <p>⚠️ {@code selectBaseline} 은 이 값을 쓰지 않는다 — 그쪽은 행정동 전체를 돌려준다.
     */
    private String emdCd;

    /**
     * 🔁 TSK-4259 — 선택한 시군구명(시군구 모드). {@link #emdCd} 와 <b>배타</b>다(둘 중 정확히 하나).
     *
     * <p>매퍼 조각 {@code scopeWhere} 가 {@code emdCd != null} 이면 읍면동, 아니면
     * {@code emd_cd IN (SELECT emd_cd FROM parking.tb_admdst WHERE sgg_nm = #{sggNm})} 로 범위를 건다.
     * 🔴 배정은 그래도 <b>읍면동 경계를 유지</b>한다(D2 — 배정 LATERAL 의 동 일치 조건).
     *
     * <p>🔴 둘 다 null 이면 {@code sgg_nm = NULL} 이라 <b>조용히 0행</b>이다 — 입력 검증은 서비스가 먼저 끊는다.
     * 기본값 null 이라 종전(읍면동 전용) 호출은 동작이 바뀌지 않는다.
     */
    private String sggNm;

    /**
     * 민원 월평균의 분모 — 조회 구간 개월수. 🔴 항상 {@code > 0}(0 이면 SQL 이 0 으로 나눈다).
     *
     * <p>🔁 TSK-4284 — {@code PrdMonths.of} 의 달별 조회 비율 합이라 <b>소수</b>다(예: {@code 1.005376}).
     * XML 은 {@code jdbcType=NUMERIC} 으로 받아 정수 캐스팅이 끼지 않는다.
     */
    private BigDecimal months;

    /** 민원 조회 시작 일시(<b>포함</b>) — {@code ocrn_dt >= cvFrom}. 요청 {@code bgngYmd} 의 00:00 */
    private LocalDateTime cvFrom;

    /**
     * 민원 조회 종료 일시(<b>미포함</b> — 반열림 구간의 상한) — {@code ocrn_dt < cvToExcl}.
     *
     * <p>🔴 요청 {@code endYmd} 의 <b>다음 날 00:00</b> 이다({@code crdnTo} 와 같은 방식).
     * 종료일 00:00 으로 두면 그 날 하루가 통째로 빠진다.
     */
    private LocalDateTime cvToExcl;

    /**
     * 🔁 <b>1차 최근 블록</b> 시작 일시(<b>포함</b>) — {@code ocrn_dt >= trendFrom}. Step 15.
     *
     * <p>🔁 <b>Step 15 — 선택한 민원 창 전체가 "최근" 이다</b>({@code = cvFrom}). Step 13 의
     * "마지막 {@code D/2} 일" 은 <b>폴백</b>({@link #splitTrendFrom})으로 밀려났다. 바꾼 이유는
     * 반 분할의 블록이 절반이라 ① 분모가 작아 행이 대량 제외되고(이도이동 {@code 최근1개월}
     * 40 → <b>2행</b>) ② 화면 보조줄의 {@code 기간 31일} 과 증가율이 보는 구간(15일)이 어긋났기
     * 때문이다.
     *
     * <p>🔴 <b>비교 단위가 날짜다</b> — 🔁 Step 13 이 월 단위({@code to_char(ocrn_dt,'YYYY-MM')} 와
     * {@code YYYY-MM} 문자열 비교)를 걷어냈다. 월 단위는 "완전한 달만 센다" 를 전제했고 그 전제가
     * {@code 최근1개월}(9월 12일~10월 12일)을 <b>완전한 달 0개 = 증가율 영구 null</b> 로 만들었다.
     *
     * <p>🔴 <b>반열림이다</b>({@link #trendToExcl} 미포함) — {@code ocrn_dt} 는 {@code timestamp} 라
     * {@code BETWEEN … AND '2026-08-31'} 로 적으면 종료일 00:00 이후가 통째로 빠진다
     * ({@link #cvFrom} 과 같은 함정. Step 11 에서 42건이 빠질 수 있음을 실측했다).
     *
     * <p>⚠️ {@code null} 이어도 죽지 않는다 — XML 이 {@code jdbcType=TIMESTAMP} 를 달아 두었고
     * {@code ocrn_dt >= NULL} 은 거짓이라 건수가 0 이 된다. {@link #trendDayCnt} 가 0 일 때
     * (= 블록 일수가 {@code MIN_TREND_DAYS} 미만) 네 칸 모두 {@code null} 로 남는 것이 그 경로다.
     */
    private LocalDateTime trendFrom;

    /** 1차 최근 블록 종료 일시(<b>미포함</b> — 반열림 상한) = 민원 창 종료일의 <b>다음 날 00:00</b> */
    private LocalDateTime trendToExcl;

    /**
     * 🔁 <b>1차 이전 블록</b> 시작 일시(<b>포함</b>) — 증가율 <b>분모</b> 구간의 시작.
     * Step 15 에서 <b>{@code 민원 창 시작일 − D}</b>(= 직전 같은 길이의 기간)가 됐다.
     *
     * <p>🔴 <b>민원 창 밖이다</b> — 그래서 {@code selectLotTrend} 의 {@code cv} 스캔 범위를
     * {@link #getScanFrom()} 으로 넓힌다. 🔴 {@code selectLotRanking} 은 넓히지 않는다
     * (넓히면 정렬 기준 {@code asgnCvlcptCnt} 가 2배가 된다 — 15-2).
     *
     * <p>🔴 이름이 {@code prev} 가 아니라 {@code bfr} 인 이유는 데이터표준사전이다 —
     * 표준단어 <b>{@code 이전 = BFR}</b>(Before)이고 {@code PREV} 는 사전에 0건이다
     * (응답 필드 {@code asgnCvlcptCntBfr} 와 같은 근거).
     */
    private LocalDateTime bfrFrom;

    /**
     * 1차 이전 블록 종료 일시(<b>미포함</b> — 반열림 상한) = <b>민원 창 시작일 00:00</b>
     * (= {@link #trendFrom}). 두 블록이 맞붙어 선택 기간의 앞을 꽉 채운다.
     */
    private LocalDateTime bfrToExcl;

    /**
     * 🔁 <b>폴백(반 분할) 최근 블록</b> 시작 일시(<b>포함</b>) — Step 13 의 규칙 그대로다
     * (= {@code 종료일 − half + 1}).
     *
     * <p>🔴 <b>1차와 함께 매퍼로 넘긴다</b> — {@code selectLotTrend} 가 네 칸(1차 최근/이전 ·
     * 폴백 최근/이전)을 <b>한 번에</b> 내고 서비스가 고른다. 쿼리를 두 번 쏘면 같은 스캔을 두 번 한다.
     */
    private LocalDateTime splitTrendFrom;

    /** 폴백 최근 블록 종료 일시(<b>미포함</b>) = 민원 창 상한과 같다 */
    private LocalDateTime splitTrendToExcl;

    /** 폴백 이전 블록 시작 일시(<b>포함</b>) = 민원 창 하한과 같다 */
    private LocalDateTime splitBfrFrom;

    /**
     * 폴백 이전 블록 종료 일시(<b>미포함</b>) = {@code 시작일 + }{@link #splitDayCnt} 일 00:00.
     *
     * <p>🔴 {@link #splitTrendFrom} 과 <b>같지 않다</b> — {@code D} 가 홀수면 가운데 1일이 두 블록
     * 사이에 떠 있다(어느 블록에도 속하지 않는다. Step 13 ②).
     */
    private LocalDateTime splitBfrToExcl;

    /**
     * 🔁 <b>1차 추세 블록 길이(일수)</b> — Step 15 에서 <b>선택 기간 일수 {@code D} 그대로</b>다
     * (Step 13 의 {@code D ÷ 2} 는 {@link #splitDayCnt} 로 옮겼다).
     *
     * <p>⚠️ <b>{@code 0} 이면 증가율을 내지 않는다</b> — 판정은 {@code D} 가 아니라
     * {@link #splitDayCnt}({@code = D/2})가 {@code MIN_TREND_DAYS}(7일) 이상인가로 한다
     * (Step 15 규칙 ④ "그대로"). 두 칸은 함께 0 이 되므로 어느 쪽을 봐도 같다.
     *
     * <p>🔴 이름은 데이터표준사전을 따른다 — 표준용어 {@code 일수 = DAY_CNT}
     * (표준단어 {@code 일 DAY} + 형식어 {@code 수 CNT}). {@code trendDays} 는 사전 밖 이름이다.
     */
    private int trendDayCnt;

    /**
     * 🔁 <b>폴백(반 분할) 블록 길이(일수)</b> — {@code D ÷ 2}(정수 나눗셈). Step 13 의
     * {@code trendDayCnt} 가 이 자리로 내려왔다.
     *
     * <p>🔴 <b>창 판정의 기준이 이 값이다</b> — {@code MIN_TREND_DAYS}(7) 미만이면 {@code 0} 이고,
     * 서비스가 {@code TREND_WINDOW_SHORT} 를 담고 추세 쿼리 자체를 부르지 않는다. 1차를 쓰더라도
     * 이 하한을 유지하는 이유는, 폴백으로 내려앉을 수 있는 창이면 그 블록도 비교가 성립해야 하기
     * 때문이다(그리고 {@code D < 14} 는 어느 방식이든 요일 하나가 증가율을 만든다).
     */
    private int splitDayCnt;

    /**
     * 🔴 <b>{@code cv} 스캔 범위를 넓힐지</b> — {@code selectLotTrend} 호출 직전에만 {@code true} 다.
     *
     * <p>공용 조각 {@code <sql id="lotsAndCvAsgn">} 의 민원 CTE 는 {@link #getScanFrom()} ·
     * {@link #getScanToExcl()} 를 읽는다. 🔴 <b>기본값 {@code false}(= 민원 창)를 지킨다</b> —
     * {@code selectLotRanking} 까지 넓히면 전 구간 배정 민원({@code asgnCvlcptCnt})이 <b>2배</b>가
     * 되고 그것이 <b>정렬 기준</b>이라 순위가 통째로 바뀐다(15-2).
     */
    private boolean trendScan;

    /** 단속 월평균의 분모 — 단속 창 개월수({@link #months} 와 같은 식 · 소수, TSK-4284). 🔴 항상 {@code > 0} */
    private BigDecimal crdnMonths;

    /** 단속 창 시작 일시(포함) */
    private LocalDateTime crdnFrom;

    /** 단속 창 종료 일시(<b>미포함</b> — 반열림 구간의 상한) */
    private LocalDateTime crdnTo;

    /**
     * 후보 주차장 최소 주차면수 하한({@code SWTC_TRGT_MIN_CPCTY}).
     *
     * <p>🔴 <b>0 이 적법한 설정값</b>이라 "비었으면 기본값" 가드를 태우지 않는다 — 분석변수의
     * {@code min_vl} 이 0 이다. {@link BigDecimal} 인 이유는 분석변수 {@code stng_vl} 이
     * {@code NUMERIC} 이라 서비스가 그 타입으로 조달해서다({@code integer >= numeric} 비교는 적법).
     */
    private BigDecimal minCpcty;

    /**
     * 후보 주차장 유형 범위 — {@code NARROW}(기본) | {@code WIDE}.
     *
     * <p>🔴 화이트리스트 밖의 값은 서비스가 <b>400</b> 으로 끊는다. 매퍼의 {@code <choose>} 는
     * {@code WIDE} 가 아니면 {@code NARROW} 로 떨어지므로, 검증 없이 흘리면 오타가
     * <b>조용히 {@code NARROW}</b> 로 동작한다.
     */
    private String trgtTypeSe;

    /**
     * 최근접 배정 반경({@code ALTMNT_RNG_VL}, <b>미터</b>) — {@code ST_DWithin(…, #{altmntRng})} 의 거리.
     *
     * <p>🔴 {@code geography} 거리 인자는 <b>미터</b>이고 함수 시그니처는 {@code double precision} 이다.
     * {@link BigDecimal} 로 두는 이유는 분석변수 {@code stng_vl} 이 {@code NUMERIC} 이라 서비스가 그
     * 타입으로 조달해서다 — PostgreSQL 이 {@code numeric → double precision} 암묵 캐스트로 해석한다.
     *
     * <p>🔴 <b>값을 바꾸면 순위가 바뀐다</b>(이도이동 {@code 1994-1} 은 100m 에서 13위 · 300m 에서
     * 1위다). 그래서 응답에 적용값을 함께 담아야 한다 — 같은 동이라도 반경이 다르면 다른 목록이다.
     */
    private BigDecimal altmntRng;

    /**
     * 🔴 공용 조각 {@code <sql id="lotsAndCvAsgn">} 의 민원 스캔 <b>하한</b>(포함).
     *
     * <p>{@link #trendScan} 이 {@code true} 면 <b>1차 이전 블록까지</b> 넓힌다
     * ({@code bfrFrom} = 민원 창 시작일 − {@code D}) — 1차 "이전" 이 민원 창 밖이라 넓히지 않으면
     * 분모가 항상 0 이 되어 영원히 폴백한다. 그 밖에는 <b>민원 창 그대로</b>다.
     *
     * <p>⚠️ 스캔량이 최대 2배다(이도이동 정본 창 243일 → 486일). {@code selectLotTrend} 한 쿼리에만
     * 걸리며 {@code selectLotRanking} 은 종전 범위를 쓴다.
     */
    public LocalDateTime getScanFrom() {
        return trendScan && bfrFrom != null ? bfrFrom : cvFrom;
    }

    /**
     * 공용 조각의 민원 스캔 <b>상한</b>(미포함) — 양쪽 모두 민원 창 상한이다.
     * 1차·폴백의 "최근" 이 모두 민원 창 안이라 상한은 넓힐 필요가 없다.
     */
    public LocalDateTime getScanToExcl() {
        return cvToExcl;
    }
}
