package jeju.datahub.analysis.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 원천이 실제로 보유한 데이터 구간(min/max). (MyBatis 조회 전용 VO)
 *
 * <p>0건 안내용이다 — 데이터 기간이 짧아 기본 기간(최근 1개월)으로는 결과가 비는 일이
 * 정상 상태라, 화면이 {@code 보유 구간: {dataBgngYmd} ~ {dataEndYmd}} 를 함께 띄운다.
 *
 * <p>원천이 아예 비어 있으면 두 값이 모두 {@code null} 이다.
 */
@Schema(description = "원천 보유 데이터 구간")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class DataRangeVO {

    /** 원천 보유 시작일 (YYYYMMDD). 데이터가 없으면 null */
    @Schema(description = "원천 보유 시작일 (YYYYMMDD)", example = "20251104")
    private String dataBgngYmd;

    /** 원천 보유 종료일 (YYYYMMDD). 데이터가 없으면 null */
    @Schema(description = "원천 보유 종료일 (YYYYMMDD)", example = "20251231")
    private String dataEndYmd;
}
