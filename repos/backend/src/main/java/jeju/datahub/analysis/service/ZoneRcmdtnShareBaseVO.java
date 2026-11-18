package jeju.datahub.analysis.service;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 구역추천(공유주차제 적용) 읍면동 판정 입력 1건. (MyBatis 조회 전용 VO — 읍면동 1개 = 이 VO 1건)
 *
 * <p>컬럼(스네이크) → 필드(카멜) 매핑은 mybatis.configuration.map-underscore-to-camel-case=true 로
 * 자동 처리한다. <b>JPA 엔티티가 아니다</b> — 조회 전용이다.
 *
 * <p>🔴 <b>판정(검토 등급·주의 표시)·월평균·100면당 비율은 이 VO 에 없다</b> — 서비스가 이 값들을
 * 입력으로 계산한다(TSK-4208 Step 3). 매퍼는 "원천이 말하는 사실"(건수·면수)까지만 담는다.
 * 월평균을 SQL 이 나누지 않는 이유도 같다 — 개월 수는 서비스가 만든다.
 *
 * <p>🔴 건수·면수를 {@link BigDecimal} 로 받는 이유는 서비스가 이 값으로 나눗셈·기준값 비교를 해서다.
 * 경계가 전부 "이상(≥)" 이고 기준값과 소수점까지 같은 값으로 걸린 전례(외도일동)가 있어, 정수형으로
 * 받아 계산 지점마다 변환하면 스케일 기준이 갈린다.
 */
@Schema(description = "구역추천 공유주차제 읍면동 판정 입력 1건 (건수·면수)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ZoneRcmdtnShareBaseVO {

    /** 읍면동 코드(8자리). {@code TB_ADMDST.emd_cd} */
    @Schema(description = "읍면동 코드", example = "50110137")
    private String emdCd;

    /** 시군구명. {@code TB_ADMDST.sgg_nm} 은 NOT NULL 이라 비지 않는다 */
    @Schema(description = "시군구명", example = "제주시")
    private String sggNm;

    /** 읍면동명 */
    @Schema(description = "읍면동명", example = "연동")
    private String emdNm;

    /**
     * 읍면동 중심 위도({@code TB_ADMDST.ctr_lat}). 지도 깃발의 좌표다.
     *
     * <p>⚠️ 컬럼이 nullable 이라 <b>null 일 수 있다</b> — 민원 좌표가 아니라 행정동 마스터의 중심점이다.
     */
    @Schema(description = "읍면동 중심 위도", example = "33.4576")
    private BigDecimal lat;

    /** 읍면동 중심 경도({@code TB_ADMDST.ctr_lot}). nullable 이다 */
    @Schema(description = "읍면동 중심 경도", example = "126.4952")
    private BigDecimal lot;

    /**
     * 조회 구간의 주차 민원 건수(합계). 미분류(유형 {@code '99'})는 제외한다.
     *
     * <p>🔴 <b>월평균이 아니다</b> — 서비스가 개월 수로 나눈다. 조회 구간이 비면 0 이다
     * ({@code COALESCE}) — null 이 아니다.
     */
    @Schema(description = "조회 구간 주차 민원 건수 (미분류 99 제외)", example = "6549")
    private BigDecimal cvlcptCnt;

    /**
     * 민원이 가장 많이 찍힌 <b>좌표 1곳</b>의 건수 — 주의 표시 {@code TOP_PNT}(한 지점 쏠림)의 입력이다.
     *
     * <p>🔴 좌표가 없는 민원은 "한 지점" 으로 묶지 않는다(같은 NULL 끼리 한 곳으로 세면 좌표 결측이
     * 곧 쏠림으로 보인다). 그 민원은 {@link #cvlcptCnt} 에는 센다. 그래서 항상
     * {@code topPntCvlcptCnt ≤ cvlcptCnt} 이고, 좌표 보유 민원이 없으면 0 이다.
     */
    @Schema(description = "민원 최다 좌표 1곳의 건수 (좌표 없는 민원은 제외)", example = "452")
    private BigDecimal topPntCvlcptCnt;

    /**
     * 1차 이전 블록(조회 시작 직전 D일 — {@code [trendFrom, cvFrom)}) 의 주차 민원 건수 (TSK-4283).
     *
     * <p>🔴 판정용 {@link #cvlcptCnt} 의 범위와 별개로 센다(D5) — 최근 블록 1차 값은 {@link #cvlcptCnt} 다.
     * 기간 하한 미달(경계 {@code null})이거나 원천이 0건이면 0 이다(null 이 아니다).
     */
    @Schema(description = "1차 이전 블록(직전 동일 기간) 주차 민원 건수", example = "512")
    private BigDecimal bfrCvlcptCnt;

    /** 폴백 앞 블록(조회 기간 처음 D/2일 — {@code [cvFrom, splitMid)}) 의 주차 민원 건수. 미달이면 0 */
    @Schema(description = "폴백 앞 D/2일 주차 민원 건수", example = "240")
    private BigDecimal halfBfrCvlcptCnt;

    /** 폴백 뒤 블록(조회 기간 마지막 D/2일 — {@code [splitMidExcl, cvToExcl)}) 의 주차 민원 건수. 미달이면 0 */
    @Schema(description = "폴백 뒤 D/2일 주차 민원 건수", example = "260")
    private BigDecimal halfAftrCvlcptCnt;

    /** 공영주차장 개소 수(소멸분 {@code extsh_dt IS NOT NULL} 제외, 유형 전부 포함) */
    @Schema(description = "공영주차장 개소 수 (소멸분 제외)", example = "129")
    private BigDecimal pkltCnt;

    /**
     * 공영 주차면수 합(소멸분 제외, 유형 전부 포함) — 100면당 비율의 분모다.
     *
     * <p>⚠️ 면수 결측인 주차장은 합에서 빠진다. <b>0 으로 대체한 것이 아니다</b>(분석 DEC-003) —
     * 개소 수({@link #pkltCnt})에는 세지만 면수는 "모른다" 로 둔다.
     * <p>🔴 0 이면 등록된 공영주차장이 없거나 면수를 전부 모르는 동이다 — 서비스가 판정 불가로 본다.
     */
    @Schema(description = "공영 주차면수 합 (소멸분 제외)", example = "2315")
    private BigDecimal pkltCpcty;

    /**
     * 공한지 주차장({@code pklt_type_cd = 'VACLOT'})을 뺀 공영 주차면수 합 — 공한지 제외 판정의 분모다.
     *
     * <p>🔴 유형 코드가 NULL 인 주차장은 <b>공한지가 아닌 것으로 본다</b>({@code IS DISTINCT FROM}) —
     * {@code <>} 로 거르면 NULL 행이 함께 빠져 면수가 줄어든다. 그래서 항상
     * {@code vaclotExclPkltCpcty ≤ pkltCpcty} 다.
     */
    @Schema(description = "공한지를 뺀 공영 주차면수 합", example = "1882")
    private BigDecimal vaclotExclPkltCpcty;
}
