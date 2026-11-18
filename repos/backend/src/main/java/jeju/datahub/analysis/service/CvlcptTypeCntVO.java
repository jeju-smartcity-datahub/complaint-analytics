package jeju.datahub.analysis.service;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 민원유형별 건수 1건. ({@link HotspotRankingZoneVO#getTypeCnts()} 원소)
 *
 * <p>유형 이름은 {@code parking.TB_COM_CD}({@code group_cd='CVLCPT_TYPE_CD'})의 {@code cd_nm} 에서 온다 —
 * 하드코딩하지 않는다. 유형이 늘어도 VO·SQL·화면을 고칠 필요가 없게 고정 컬럼이 아니라 목록으로 내린다.
 *
 * <p>건수 기준은 <b>행 단위 {@code COUNT(*)}</b>(🔁 2026-10-02 DISTINCT 기준 폐기,
 * 근거는 {@code mapper/complaint/ComplaintMapper.xml} 상단 「민원 집계 기준」 주석). 같은 zone 의
 * {@code typeCnts} 합은 항상 {@link HotspotRankingZoneVO#getCvlcptCnt()} 와 같다.
 *
 * <p>{@link #emdCd} 는 매퍼 결과를 zone 에 묶기 위한 키일 뿐이라 응답 JSON 에는 내리지 않는다
 * (zone 이 이미 {@code emdCd} 를 갖는다).
 */
@Schema(description = "민원유형별 건수 (유형명은 TB_COM_CD.cd_nm)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class CvlcptTypeCntVO {

    /** 매핑 키 (zone 의 읍면동코드). 응답에는 내리지 않는다. */
    @JsonIgnore
    @Schema(hidden = true)
    private String emdCd;

    /** 민원유형코드 */
    @Schema(description = "민원유형코드 (CVLCPT_TYPE_CD, '99' 제외)", example = "02")
    private String cvlcptTypeCd;

    /** 민원유형명 — TB_COM_CD.cd_nm */
    @Schema(description = "민원유형명 (TB_COM_CD.cd_nm)", example = "인도·횡단보도 점유")
    private String cvlcptTypeNm;

    /** 그 유형의 민원 건수 — COUNT(*) (유형별 GROUP BY 안) */
    @Schema(description = "그 유형의 민원 건수", example = "91")
    private long cvlcptCnt;
}
