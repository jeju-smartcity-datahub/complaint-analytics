package jeju.datahub.analysis.service;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 투자우선순위 대상 주차장 1곳의 <b>지표 원값</b>(TSK-4036). (MyBatis 조회 전용 VO — 대상 주차장
 * 1곳 = 이 VO 1건)
 *
 * <p>컬럼(스네이크) → 필드(카멜) 매핑은 mybatis.configuration.map-underscore-to-camel-case=true 로
 * 자동 처리한다. <b>JPA 엔티티가 아니다</b> — 조회 전용이다.
 *
 * <p>🔴 <b>여기에는 점수가 없다.</b> 정규화(백분위)·종합점수·순위·등급은 계산기가 이 원값으로 만든다 —
 * 매퍼는 원값만 낸다. 그리고 매퍼는 <b>전 지역 대상 전체</b>를 돌려준다(행정구역 필터는 표시 단계의 몫).
 *
 * <p>🔴 {@link #cvlcptCnt}·{@link #crdnCnt} 는 <b>그 주차장 안의 건수가 아니다</b> — 반경 안에서
 * 대상 주차장 중 가장 가까운 곳에 배정한 건수다(사건 1건은 주차장 1곳에만 센다).
 * 반면 {@link #acdntCnt} 는 배정이 아니라 <b>그 주차장에 기록된</b> 건수다(TSK-4296).
 */
@Schema(description = "투자우선순위 대상 주차장 1곳의 지표 원값")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class InvestPrrtLotBaseVO {

    /** 주차장 ID. {@code TB_PBLPKLT.pklt_id}(IDENTITY) — 🔴 값에 업무적 의미가 없다 */
    @Schema(description = "주차장 ID", example = "12869")
    private Long pkltId;

    /** 주차장명 */
    @Schema(description = "주차장명", example = "매일올레시장 공영주차빌딩")
    private String pkltNm;

    /** 주차장 주소(원천 원문) */
    @Schema(description = "주차장 주소")
    private String pkltAddr;

    /**
     * 시군구명 — 🔴 {@code TB_ADMDST} 를 {@code emd_cd} 로 JOIN 해 읽는다({@code TB_PBLPKLT} 에는
     * {@code sgg_nm} 이 없다). {@code emd_cd} 가 비었으면 {@code null} 이다.
     */
    @Schema(description = "시군구명 (행정구역 마스터)", example = "서귀포시")
    private String sggNm;

    /** 읍면동 코드(8자리) — 화면 필터 {@code emdCd} 와 같은 값이다 */
    @Schema(description = "읍면동 코드", example = "50130520")
    private String emdCd;

    /**
     * 읍면동명 — 🔴 {@code TB_ADMDST.emd_nm} 이다. {@code TB_PBLPKLT.emd_nm}(원천 표기)을 쓰지
     * 않는다 — 둘이 다른 주차장이 많아(예: {@code 중앙동} ↔ {@code 서귀동}) 필터 목록이 갈린다.
     */
    @Schema(description = "읍면동명 (행정구역 마스터)", example = "중앙동")
    private String emdNm;

    /** 주차면수. 원천이 비우면 {@code null} 이다 */
    @Schema(description = "주차면수", example = "280")
    private Integer pkltCpcty;

    /** 설치연월({@code YYYYMM}). 🔴 대부분 결측이다 — 있을 때만 표시한다 */
    @Schema(description = "설치연월 (YYYYMM) — 결측이면 null", example = "200901")
    private String instlYm;

    /** 위도. {@code tb_pblpklt.lat} 은 NOT NULL 이라 비지 않는다 */
    @Schema(description = "위도", example = "33.25")
    private BigDecimal lat;

    /** 경도. {@code tb_pblpklt.lot} 은 NOT NULL 이라 비지 않는다 */
    @Schema(description = "경도", example = "126.56")
    private BigDecimal lot;

    /**
     * 안전점검 결과 코드({@code SFTY_CHCK_RSLT_CD} — {@code A} 양호 / {@code B} 주의 / {@code C} 불량).
     *
     * <p>창 종료일 이전 <b>가장 최근 점검일</b>의 결과이며, 같은 날 여러 건이면 <b>가장 나쁜 것</b>
     * ({@code C > B > A})이다. 🔴 그날까지 점검이 한 건도 없으면 <b>{@code null}</b> 이다
     * (양호로 채우지 않는다 — 점검하지 않은 것과 양호한 것은 다르다).
     */
    @Schema(description = "안전점검 결과 코드 (A/B/C) — 점검이 없으면 null", example = "B")
    private String sftyChckRsltCd;

    /** 위 결과의 점검일자({@code YYYYMMDD}). 점검이 없으면 {@code null} */
    @Schema(description = "안전점검 일자 (YYYYMMDD) — 점검이 없으면 null", example = "20260901")
    private String sftyChckYmd;

    /** 배정 민원 수(창 안 · 좌표 있음 · 유형 01~05 · 최근접 배정). 없으면 0 */
    @Schema(description = "배정 민원 수 (최근접 배정)", example = "120")
    private Long cvlcptCnt;

    /**
     * 일평균 회전율의 월 평균 — 이용률 집계 달(시작일이 1일이 아니면 시작 달 제외 — D1) 중 <b>값이 있는 달만</b> 평균한다.
     * 🔴 그런 달이 없으면 <b>{@code null}</b> 이다(0 이 아니다 — 0 은 "이용이 없었다" 로 읽힌다).
     */
    @Schema(description = "일평균 회전율 (이용률 집계 달의 평균 — 시작일이 1일이 아니면 시작 달 제외) — 자료가 없으면 null", example = "1.84")
    private BigDecimal dayAvgTrnvrRt;

    /** 위 평균에 들어간 달 수. 없으면 0 */
    @Schema(description = "회전율 평균에 들어간 달 수", example = "3")
    private Integer utztnMmCnt;

    /** 배정 단속 수(창 안 · 좌표 있음 · 최근접 배정). 없으면 0 */
    @Schema(description = "배정 단속 수 (최근접 배정)", example = "35")
    private Long crdnCnt;

    /**
     * 창 안 사고 건수(TSK-4296) — 🔴 반경 배정이 아니라 <b>그 주차장에 기록된</b> 건수다
     * ({@code tb_pklt_acdnt_hstry.pklt_id} 직접 집계). 없으면 0(값 없음 = 0).
     */
    @Schema(description = "사고 건수 (그 주차장에 기록된 창 안 건수)", example = "2")
    private Long acdntCnt;
}
