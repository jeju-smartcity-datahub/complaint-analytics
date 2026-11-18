package jeju.datahub.lifezone.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 순위 목록 1행 (TSK-4088 D9 · D10 · D16).
 *
 * <p>{@link LifezoneCellVO} 와 <b>같은 격자</b>를 가리키지만 목록이 다르다 — 셀은 값&gt;0 전량,
 * 순위는 심각·경고·주의 셀 전부(🔁 TSK-4227 D21 — {@code limit} 지정 시 그 값까지)다. {@link #gridCd} 로 화면이 둘을 이어 셀 ↔ 목록
 * 양방향 연동을 만든다.
 *
 * <p>순위 정본은 {@code anlsVl 내림차순, gridCd 오름차순}이다 — 동점 tie-break 를 격자코드로
 * 굳혀 같은 조건이면 항상 같은 순위가 나오게 한다(보고서 스냅샷 재현의 전제).
 *
 * <p>{@link #cntrPoiNm} 은 <b>이 목록에서만</b> VWorld POI 이름으로 대체된다(D10). 격자
 * 중심을 넘겨 조회하며(격자 중심 고정 — D10 개정 TSK-4339, 조건이 바뀌어도 캐시 키가 같아 외부 조회 상한에 걸리지 않는다), 실패하면 셀과 같은 폴백({@code {읍면동명} 격자 {격자코드}})으로 되돌린다.
 */
@Schema(description = "순위 1행")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class LifezoneRankVO {

    /** 순위. 분석값 내림차순 1부터 */
    @Schema(description = "순위 (분석값 내림차순)", example = "1")
    private int rnk;

    /** 격자코드. 지도 셀({@code cells[].gridCd})과 목록을 잇는 키다 */
    @Schema(description = "격자코드", example = "다사5108")
    private String gridCd;

    /** 구역명. POI 조회 성공 시 실제 지명, 실패 시 {@code {읍면동명} 격자 {격자코드}} (D10) */
    @Schema(description = "구역명", example = "연동 신광로 제원사거리")
    private String cntrPoiNm;

    /** 분석값 */
    @Schema(description = "분석값", example = "386")
    private int anlsVl;

    /** 등급코드 */
    @Schema(description = "등급코드 (SEVERE | WARN | CAUTION | NORMAL | LOW)", example = "SEVERE")
    private String grade;

    /** 격자 중심 위도 (EPSG:4326). 순위 행 클릭 시 지도 이동용 — POI 좌표가 아니라 격자 중심이다 */
    @Schema(description = "격자 중심 위도 (EPSG:4326)", example = "33.4869")
    private BigDecimal ctrLat;

    /** 격자 중심 경도 (EPSG:4326) */
    @Schema(description = "격자 중심 경도 (EPSG:4326)", example = "126.4901")
    private BigDecimal ctrLot;

    /**
     * 세부 내역. 건수 상위 <b>최대 2건</b>이다(D16).
     *
     * <p>화면·문서가 {@code · } 로 잇는다 — 서버가 문자열로 이어 붙이지 않는 이유는
     * {@link LifezoneDtlVO} 주석 참고. 매퍼가 {@code null} 을 줘도 빈 배열로 둔다.
     */
    @Schema(description = "세부 내역 (건수 상위 최대 2건)")
    private List<LifezoneDtlVO> dtl = new ArrayList<>();
}
