package jeju.datahub.region.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 읍면동 1건. {@link RegionVO#getEmds()} 의 원소다. (parking.TB_ADMDST — MyBatis 전용)
 */
@Schema(description = "읍면동")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class EmdVO {

    /** 읍면동코드 (emd_cd, 8자리) */
    @Schema(description = "읍면동코드", example = "50110560")
    private String emdCd;

    /** 읍면동명 (emd_nm) */
    @Schema(description = "읍면동명", example = "연동")
    private String emdNm;
}
