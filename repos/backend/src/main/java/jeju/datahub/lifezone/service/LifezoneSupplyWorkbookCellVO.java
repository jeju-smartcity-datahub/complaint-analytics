package jeju.datahub.lifezone.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 수급 엑셀 후보 격자 1행 (TSK-4340 D5) — 최소 월평균 민원 = 범위 하한({@code min_vl}) 기준 {@code selectSupplyCells} 결과 1행.
 *
 * <p>🔴 부족률·등급·순위는 싣지 않는다 — 엑셀이 입력 셀(N·경계)로 계산하고, 저장 당시 결과의 정본은 기존 {@code cells} 다.
 * 🔴 격자 키는 {@code gridId} 다(수급 · 혼잡도·민원건수는 {@code gridCd}).
 */
@Schema(description = "수급 엑셀 후보 격자 1행")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class LifezoneSupplyWorkbookCellVO {

    /** 격자ID {@code {격자크기}m_{gx}_{gy}} */
    @Schema(description = "격자ID", example = "500m_1863_3005")
    private String gridId;

    /** 구역명 — 응답 셀 이름(같은 gridId) → 구역명 캐시 이름 → {@code null}(화면이 읍면동명·격자ID 로 폴백) */
    @Schema(description = "구역명 (없으면 null — 화면이 읍면동명 → gridId 로 폴백)", example = "제주시청", nullable = true)
    private String cntrPoiNm;

    /** 읍면동명 */
    @Schema(description = "읍면동명", example = "연동")
    private String emdNm;

    /** 주차 민원 건수 */
    @Schema(description = "민원 건수", example = "12")
    private int cvlcptCnt;

    /** 공영주차면 합 */
    @Schema(description = "공영주차면 합", example = "40")
    private int pkltCpctySum;

    /** 공영주차장 수 */
    @Schema(description = "공영주차장 수", example = "2")
    private int pkltCnt;
}
