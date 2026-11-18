package jeju.datahub.complaint.service;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 읍면동별 유형 분해(breakdown) 항목. RegionRankVO.breakdown 의 원소.
 *
 * <p>매퍼는 (emd_cd, type_cd, count) 평면 행으로 읽고, 서비스가 emdCd 기준으로 그룹핑해
 * 읍면동별 리스트로 재구성한다. 응답 JSON 에는 {@code {typeCd, count}} 만 노출한다(emdCd 는 그룹핑 키라 숨김).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class TypeCountVO {

    /** 그룹핑 키(응답 미노출). 평면 조회 결과를 읍면동별로 묶는 데만 쓴다. */
    @JsonIgnore
    private String emdCd;

    /** 민원유형코드(01~05) */
    private String typeCd;

    /** 해당 유형 건수(현재 기간) */
    private long count;
}
