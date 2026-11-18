package jeju.datahub.batch.history;

import java.util.Map;

/**
 * {@code batch.tb_btch_excn_hstry} 의 <b>Job 고유 지표</b> — 종료 요약 로그가 뱉는 값 그대로다.
 *
 * <p>Spring Batch 메타가 이미 갖고 있는 값(status·read·write·시각)은 여기 없다. 그건
 * {@link org.springframework.batch.core.JobExecution} 에서 {@link BatchRunHistoryRecorder} 가
 * 직접 읽는다 — 두 벌로 들고 다니면 어긋난다.
 *
 * <h2>🔴 필드가 전부 {@code Long}(박싱)인 이유 — {@code null} 과 {@code 0} 은 다르다</h2>
 * {@code null} 은 <b>"그 Job 은 그걸 하지 않는다"</b>이고 {@code 0} 은 <b>"했는데 0건"</b>이다.
 * 그 지표를 만들지 않는 잡은 해당 필드에 {@code null} 을 넣는다(전부 없으면 {@link #none()}).
 * 여기에 0 을 채우면 "캐시 히트가 0건인 실행"으로 집계돼 캐시 효율 추이가 조용히 왜곡된다.
 * 🔁 {@code pblpkltFetchJob}·{@code crackdownFetchJob}(주소 단위 지오코딩 Step 추가)은
 * 이제 {@code none()} 이 아니라 자기 지표를 채우고, 하지 않는 지표(분류·이름 폴백 등)만 {@code null} 로 둔다.
 *
 * <p>🔁 2026-08-24 — 파이프라인이 1단으로 합쳐지면서 <b>{@code complaintFetchJob} 이 지오코딩·분류까지
 * 맡게 됐다.</b> 그래서 이 잡은 이제 {@code none()} 이 아니라 <b>전체 지표를 채워 넘긴다</b>
 * ({@code CvlcptJobSummaryListener}). 종전 "①은 수집만 하므로 none()" 서술은 더 이상 사실이 아니다.
 *
 * @param geocodePlaceRecoveries 3단(장소 POI 검색)으로 살아난 건(D7 2026-08-05 재개정).
 *                     {@code geocodeStripped}(4단 부번제거)와 <b>합치지 않는다</b> — 부번제거는
 *                     정밀도를 잃는 좌표라 규모를 따로 봐야 한다
 * @param unknownTypes 미지 유형 값별 누계. {@code JSONB} 컬럼으로 직렬화된다({@code null} 이면 NULL)
 * @param emdNameFallback 좌표 공간매칭이 실패해 <b>이름({@code emd_nm}) 조회로</b> 채운 건
 *                     ({@code pblpkltFetchJob} 전용). 공간매칭을 쓰지 않거나
 *                     이름 폴백이 없는 잡은 {@code null} — 🔴 <b>새 필드는 맨 끝에 붙인다</b>.
 *                     중간에 끼우면 같은 타입({@code Long})이 연달아 있어 기존 호출부의 인자가
 *                     한 칸 밀려도 컴파일되는 자리가 생긴다
 */
public record BatchRunMetrics(
        Long skipGeocodeFailed,
        Long skipEmdUnresolved,
        Long filteredTransient,
        Long geocodeApiLookups,
        Long geocodeCacheHits,
        Long geocodePlaceRecoveries,
        Long geocodeStripped,
        Long emdContains,
        Long emdBuffer,
        Long unclassifiedCount,
        Map<String, Long> unknownTypes,
        Long emdNameFallback) {

    private static final BatchRunMetrics NONE =
            new BatchRunMetrics(null, null, null, null, null, null, null, null, null, null, null, null);

    /**
     * 지오코딩·분류 지표가 없는 Job 용 — 해당 컬럼을 전부 NULL 로 둔다.
     *
     * <p>①{@code complaintFetchJob} 이 이 경우다. 수집만 하므로 남길 것은
     * {@code read_cnt}/{@code write_cnt}/{@code run_mode}/{@code target_range} 뿐이고,
     * 그 넷은 {@link org.springframework.batch.core.JobExecution} 에서 나온다.
     */
    public static BatchRunMetrics none() {
        return NONE;
    }
}
