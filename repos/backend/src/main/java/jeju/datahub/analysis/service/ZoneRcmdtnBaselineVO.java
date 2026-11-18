package jeju.datahub.analysis.service;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 구역추천 읍면동 기준선 1건. (MyBatis 조회 전용 VO — 읍면동 1개 = 이 VO 1건)
 *
 * <p>컬럼(스네이크) → 필드(카멜) 매핑은 mybatis.configuration.map-underscore-to-camel-case=true 로
 * 자동 처리한다. <b>JPA 엔티티가 아니다</b> — 조회 전용 참고 지표다.
 *
 * <p>🔴 <b>판정(요금제 전환 FEE / 공유주차 SHARE)·필요면수·충족률은 이 VO 에 없다</b> — 서비스
 * ({@code ZoneRcmdtnCalculator})가 이 네 지표를 입력으로 계산한다. 매퍼는 "원천이 말하는 사실"
 * 까지만 담는다.
 *
 * <p>🔴 {@link #ableCpcty}·{@link #paidCpcty} 를 {@link BigDecimal} 로 받는 이유는 서비스가
 * 충족률을 {@code ableCpcty.divide(need, …)} 로 계산해서다 — {@code long} 으로 받으면 그 지점에서
 * 매번 변환이 끼고 스케일 기준이 갈린다.
 */
@Schema(description = "구역추천 읍면동 기준선 1건 (참고 지표)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ZoneRcmdtnBaselineVO {

    /** 읍면동 코드(8자리). {@code TB_ADMDST.emd_cd} */
    @Schema(description = "읍면동 코드", example = "50110104")
    private String emdCd;

    /** 읍면동명 */
    @Schema(description = "읍면동명", example = "이도이동")
    private String emdNm;

    /** 시군구명. {@code TB_ADMDST.sgg_nm} 은 NOT NULL 이라 비지 않는다 */
    @Schema(description = "시군구명", example = "제주시")
    private String sggNm;

    /**
     * 민원 월평균(C0) = 조회 구간 민원 건수 ÷ 개월수. 미분류(유형 {@code '99'})는 제외한다.
     *
     * <p>조회 구간이 비면 0 이다({@code COALESCE}) — null 이 아니다.
     */
    @Schema(description = "민원 월평균 (미분류 99 제외)", example = "388.75")
    private BigDecimal cvlcptMmAvg;

    /**
     * 민원 원 건수 — 조회 구간 민원 건수(미분류 {@code '99'} 제외, {@link #cvlcptMmAvg} 의 분자).
     * 배정률 총건수({@code asgn.cvlcptTot})로 쓴다.
     *
     * <p>🔁 TSK-4284 · D3 — 월평균 × 개월수 되곱 대신 원 건수를 직접 받는다(개월 수가 소수라 되곱 반올림이
     * 어긋날 수 있다). 조회 구간이 비면 0 이다({@code COALESCE}).
     */
    @Schema(description = "민원 원 건수 (미분류 99 제외)", example = "3110")
    private Long cvlcptCnt;

    /** 단속 월평균(Enf) = 단속 창 건수 ÷ 개월수. 서비스가 적발배수를 곱해 E0 를 만든다 */
    @Schema(description = "단속 월평균", example = "148.95")
    private BigDecimal crdnMmAvg;

    /**
     * 전환 가능 면수 = 후보 주차장({@code convertibleLot} 조각)의 주차면수 합.
     *
     * <p>🔴 유형 범위({@code NARROW}/{@code WIDE})와 최소 면수 하한에 따라 값이 달라진다 —
     * 응답에 적용값을 함께 담는 근거다.
     */
    @Schema(description = "전환 가능 면수 (후보 주차장 주차면수 합)", example = "397")
    private BigDecimal ableCpcty;

    /** 이미 유료인 면수(소멸분 제외). 후보 조건과 무관한 <b>현황 참고값</b>이다 */
    @Schema(description = "기존 유료 면수 (소멸분 제외)", example = "668")
    private BigDecimal paidCpcty;
}
