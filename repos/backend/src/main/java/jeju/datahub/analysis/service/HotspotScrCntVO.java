package jeju.datahub.analysis.service;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 민원 가중치별 0점 제외 군집 수 1행. (MyBatis 조회 전용 VO — {@code selectHotspotWorkbookConst})
 *
 * <p>엑셀이 B3 에 해당하는 행을 {@code INDEX/MATCH} 로 찾아 등급 모집단 N 으로 쓴다.
 * {@link #scrCnt} 는 {@code ROUND(…,2)} 뒤 종합점수 &gt; 0 으로 센 값이다 — 지표 유무로 세면 N 이 과대 산정된다.
 */
@Schema(description = "민원 가중치별 0점 제외 군집 수")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class HotspotScrCntVO {

    /** 민원 가중치(%). 0~100 정수 또는 적용 민원 가중치(비정수, 예 62.5) */
    @Schema(description = "민원 가중치(%)", example = "60")
    private BigDecimal cvlcptWgvl;

    /** 그 가중치에서 종합점수가 0 보다 큰 군집 수 (등급 모집단 N) */
    @Schema(description = "0점 제외 군집 수", example = "244")
    private long scrCnt;
}
