package jeju.datahub.region.service;

import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 시군구 → 읍면동 트리 1노드. (parking.TB_ADMDST — MyBatis 전용)
 *
 * <p>지역 select 를 쓰는 화면(보고서 설정 · 알림 조회 · 알림 수신자 관리)이 모두 이 목록을 쓴다.
 * 시안의 하드코딩 지역 목록을 그대로 옮기지 않기 위한 API 다
 * (TSK-4026 D12 · TSK-4028 D9 — <b>두 TSK 공용</b>).
 */
@Schema(description = "시군구 + 하위 읍면동 목록")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class RegionVO {

    /** 시군구명 (sgg_nm) */
    @Schema(description = "시군구명", example = "제주시")
    private String sggNm;

    /** 이 시군구에 속한 읍면동 목록. 매퍼 XML 의 resultMap 이 컬렉션으로 모은다 */
    @Schema(description = "하위 읍면동 목록")
    private List<EmdVO> emds = new ArrayList<>();
}
