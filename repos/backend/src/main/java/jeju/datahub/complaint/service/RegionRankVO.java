package jeju.datahub.complaint.service;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 읍면동 순위 카드용 조회 전용 VO. (TB_CVLCPT JOIN TB_ADMDST — MyBatis 전용)
 *
 * <p>응답 필드: {@code emdCd, emdNm, count, deltaPct, rankDelta, breakdown}. {@link #prevCount} 는
 * deltaPct·rankDelta 계산에만 쓰는 내부값이라 응답에서 숨긴다.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class RegionRankVO {

    /** 읍면동코드(8자리) */
    private String emdCd;

    /** 읍면동명 (TB_ADMDST.emd_nm) */
    private String emdNm;

    /** 현재 기간 민원수 */
    private long count;

    /** 직전 동일 길이 기간 민원수(내부 계산용, 응답 미노출) */
    @JsonIgnore
    private long prevCount;

    /** 증감률(%). round((count-prevCount)/prevCount*100). prevCount=0 이면 0. */
    private int deltaPct;

    /**
     * 직전 기간 대비 순위 변동. **양수면 상승**(순위가 올라갔다), 음수면 하락, 0 이면 동일.
     * 🔴 직전 기간 0건이면 순위를 매기지 않고 {@code null} 이다 — 화면이 `신규` 로 표기한다
     *    (0건을 공동 꼴찌로 보면 "70위 → 5위" 같은 과장이 된다). TSK-4169.
     *
     * <p>🔴 {@link #deltaPct} 와 **다른 지표**다 — deltaPct 는 건수 증감률, 이쪽은 순위 변동 칸 수다.
     */
    private Integer rankDelta;

    /** 유형별 분해([{typeCd, count}]). 서비스에서 그룹핑해 주입. */
    private List<TypeCountVO> breakdown;
}
