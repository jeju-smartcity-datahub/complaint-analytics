package jeju.datahub.batchops.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 실행 중 단계별 수치 — "API 수집 → 지오코딩 → DB 적재".
 *
 * <p>배치 {@code GET /api/batch/ops/executions/{id}/live} 의 {@code metrics} 를 그대로 옮긴다(키 이름이 계약 —
 * 배치 {@code CvlcptJobSummaryListener.liveSnapshot}). 지금은 {@code complaintFetchJob} 만 내린다.
 * DB 적재 수는 여기 없다 — 같은 응답의 {@code progress.writeCount}(청크 커밋마다 갱신) 가 정본이다.
 *
 * <p>⚠️ 원천 총건수는 모른다(일자별 keyset 수집) — 진행률은 {@code dayIdx / dayCnt} 다.
 * ⚠️ 이 값은 청크 안에서도 늘고 {@code writeCount} 는 커밋 때만 늘어, 잠깐 {@code fetchCnt > prcsCnt > writeCount} 차이가 벌어지는 것이 정상이다.
 */
@Schema(description = "실행 중 단계별 수치 (지원 잡이 실행 중일 때만 — 지금은 민원 수집)")
@Getter
@Setter
@ToString
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BatchOpsLiveVO {

    @Schema(description = "지금 읽는 일자 순번 (1부터)", example = "3")
    private Integer dayIdx;

    @Schema(description = "구간 전체 일수 (daily 는 1)", example = "30")
    private Integer dayCnt;

    @Schema(description = "지금 읽는 일자 (yyyy-MM-dd)", example = "2026-11-03")
    private String crntYmd;

    @Schema(description = "API 수집 — 원천에서 받은 행 수 (페이지 단위)", example = "6800")
    private Long fetchCnt;

    @Schema(description = "가공(분류·지오코딩) 처리 행 수 — 스킵·필터 포함", example = "6750")
    private Long prcsCnt;

    @Schema(description = "좌표·행정동 확보 행 수", example = "6500")
    private Long geocodedCnt;

    @Schema(description = "미분류(99) 적재 — 지오코딩을 건너뛴 행 수", example = "200")
    private Long unclsfCnt;

    @Schema(description = "일시 장애 제외 (다음 실행에 다시 들어온다)", example = "0")
    private Long filteredTransient;

    @Schema(description = "지오코딩 캐시 히트", example = "5900")
    private Long geocodeCacheHits;

    @Schema(description = "지오코딩 API 호출", example = "850")
    private Long geocodeApiLookups;

    @Schema(description = "지오코딩 실패 스킵", example = "12")
    private Long skipGeocodeFailed;

    @Schema(description = "행정동 미해석 스킵", example = "3")
    private Long skipEmdUnresolved;
}
