package jeju.datahub.batch.ops;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * 잡 카탈로그 — 운영 화면이 그리는 잡 10개의 한글명·설명·파라미터 명세·연쇄·Step 순서의 정본.
 *
 * <p>백엔드는 이 목록을 {@code GET /api/batch/ops/jobs} 로 받아 DB 실행 이력과 합친다 — 🔴 백엔드에 두 벌로
 * 두지 않는다. 잡을 추가하면 여기 한 줄 + JobConfig 의 {@code .listener(runningJobRegistry)} 를 함께 한다.
 *
 * <h2>🔴 두 벌이 된 것 — 테스트가 지킨다</h2>
 * <ul>
 *   <li>{@code steps} ↔ 각 JobConfig 의 실제 Step 이름·순서 — {@code BatchJobCatalogTest} 가 Job 빈의
 *       {@code getStepNames()} 와 대조한다(Step 이름 상수가 package-private 이라 문자열로 둔다).</li>
 *   <li>{@code chainNext} ↔ 스케줄러가 연쇄로 부르는 잡({@code ComplaintSyncScheduler}·{@code PblpkltSyncScheduler})</li>
 *   <li>{@code schedulable} ↔ {@code BatchSchedulerConfig} 의 스케줄 대상 8개({@code batch.tb_btch_schdl} 8행)</li>
 * </ul>
 *
 * <h2>파라미터 화이트리스트</h2>
 * {@code params} 에 없는 키는 운영 API 가 400 으로 거절한다(임의 Job 파라미터 주입 차단). 날짜는 {@code yyyy-MM-dd} 만.
 */
@Component
public class BatchJobCatalog {

    /** 파라미터 형식. */
    public enum ParamType {
        /** {@code yyyy-MM-dd}. */
        DATE,
        /** {@code options} 중 하나. */
        ENUM
    }

    /**
     * 파라미터 명세 한 줄.
     *
     * @param name        Job 파라미터 이름(요청 {@code params} 의 키)
     * @param type        형식
     * @param required    필수 여부(지금은 전부 선택 — 비우면 {@code defaultDesc})
     * @param defaultDesc 비웠을 때의 동작 설명
     * @param options     {@link ParamType#ENUM} 의 허용값(그 밖은 빈 목록)
     * @param description 화면 라벨
     */
    public record ParamSpec(String name, ParamType type, boolean required, String defaultDesc,
            List<String> options, String description) {
    }

    /** Step 한 줄 — 진행 단계 표시용(순서 = 실행 순서). 미등록 Step 은 화면이 원래 이름으로 보인다. */
    public record StepSpec(String stepNm, String stepKorNm) {
    }

    /**
     * 잡 한 줄.
     *
     * @param chainNext      스케줄 실행에서 이 잡이 {@code COMPLETED} 로 끝나면 이어서 도는 잡(없으면 null)
     * @param chainCondition 연쇄 조건 문구
     * @param schedulable    스케줄 표({@code batch.tb_btch_schdl})를 가질 수 있나 — 연쇄 꼬리 전용 잡·수동 전용 백필은 false
     * @param countLabel     처리 건수({@code write_cnt}) 의 화면 문구
     */
    public record JobSpec(String jobNm, String jobKorNm, String description, String countLabel,
            List<ParamSpec> params, boolean schedulable, String chainNext, String chainCondition,
            List<StepSpec> steps) {

        public Optional<ParamSpec> param(String name) {
            return params.stream().filter(p -> p.name().equals(name)).findFirst();
        }
    }

    private static final String CHAIN_CONDITION = "스케줄 실행이 COMPLETED 로 끝났을 때만 이어서 실행"
            + "(중지·실패·운영 화면 수동 실행에는 연쇄가 없다)";

    private final Map<String, JobSpec> jobs = new LinkedHashMap<>();

    public BatchJobCatalog() {
        add(new JobSpec("complaintFetchJob", "민원 수집",
                "D-Hub 민원 원천을 수집해 분류·지오코딩 후 parking.tb_cvlcpt 에 적재한다", "적재 민원",
                List.of(
                        new ParamSpec("mode", ParamType.ENUM, false, "증분: 최대 원천 load_dt부터 오늘까지",
                                List.of("incremental", "daily", "backfill"), "수집 방식"),
                        new ParamSpec("targetDate", ParamType.DATE, false, "전일(daily 일 때)", List.of(),
                                "daily 기준일"),
                        new ParamSpec("from", ParamType.DATE, false, "저장된 최대 load_dt(없으면 하한 없음)",
                                List.of(), "load_dt 시작일(포함)"),
                        new ParamSpec("to", ParamType.DATE, false, "오늘 포함(명시 to는 미포함)",
                                List.of(), "load_dt 끝일(미포함)")),
                true, "riskAlertJudgeJob", CHAIN_CONDITION + " · risknoti.sync.scheduled=true 일 때",
                List.of(new StepSpec("complaintFetchStep", "수집·지오코딩·적재"))));
        add(new JobSpec("riskAlertJudgeJob", "위험단계 판정",
                "읍면동별 민원 증가로 위험단계를 판정해 알림 이력에 적재하고 수신자를 매핑한다", "알림",
                List.of(new ParamSpec("targetDate", ParamType.DATE, false, "어제(Asia/Seoul)", List.of(), "판정 대상일")),
                false, null, null,
                List.of(new StepSpec("riskAlertJudgeStep", "판정"),
                        new StepSpec("riskAlertReceiverMappingStep", "수신자 매핑"))));
        add(new JobSpec("riskAlertReceiverBackfillJob", "알림 수신자 매핑 백필",
                "현재 수신자 설정으로 과거 알림의 수신자 매핑을 다시 채운다(멱등)", "매핑",
                List.of(new ParamSpec("fromDt", ParamType.DATE, false, "하한 없음(전 기간)", List.of(), "load_dt 시작일(포함)"),
                        new ParamSpec("toDt", ParamType.DATE, false, "상한 없음(전 기간)", List.of(), "load_dt 끝일(미포함)")),
                false, null, null,
                List.of(new StepSpec("riskAlertReceiverBackfillStep", "매핑 백필"))));
        add(new JobSpec("pblpkltFetchJob", "공영주차장 수집",
                "D-Hub 공영주차장 원천을 수집·지오코딩해 parking.tb_pblpklt 에 적재하고, 이번 수집에 없던 주차장을 소멸 처리한다",
                "적재 주차장", List.of(),
                true, "pkltUtztnFetchJob", CHAIN_CONDITION + " · utztn.sync.scheduled=true 일 때",
                List.of(new StepSpec("pblpkltFetchStep", "수집·지오코딩·적재"),
                        new StepSpec("pblpkltSoftDeleteStep", "소멸 마킹"))));
        add(new JobSpec("pkltUtztnFetchJob", "주차장 이용패턴 수집",
                "D-Hub 주차장 이용패턴 원천을 수집해 적재한다", "적재 행", List.of(),
                true, null, null,
                List.of(new StepSpec("pkltUtztnFetchStep", "수집·적재"))));
        add(new JobSpec("crackdownFetchJob", "단속 수집",
                "D-Hub 단속 원천을 수집하고 좌표 사전 전파·주소 단위 지오코딩을 한다", "적재 단속",
                List.of(
                        new ParamSpec("from", ParamType.DATE, false, "저장된 최대 원천 load_dt(없으면 하한 없음)",
                                List.of(), "load_dt 시작일(포함)"),
                        new ParamSpec("to", ParamType.DATE, false, "오늘 포함(명시 to는 미포함)", List.of(),
                                "load_dt 끝일(미포함)")),
                true, null, null,
                List.of(new StepSpec("crackdownFetchStep", "수집"),
                        new StepSpec("crackdownCoordDictStep", "좌표 전파"),
                        new StepSpec("crackdownGeocodeStep", "지오코딩"))));
        add(new JobSpec("reportAutoCrtJob", "보고서 자동 생성",
                "배포일이 도래한 자동 생성 설정만 골라 백엔드 내부 API 로 보고서를 만든다", "생성 보고서",
                List.of(new ParamSpec("baseYmd", ParamType.DATE, false, "오늘(Asia/Seoul)", List.of(), "기준일")),
                true, null, null,
                List.of(new StepSpec("reportAutoCrtStep", "보고서 생성"))));
        add(new JobSpec("pkltSftyChckFetchJob", "주차장 안전점검 수집",
                "주차장 안전점검을 실행별 관측 이력으로 추가한다", "적재 점검",
                List.of(), true, null, null,
                List.of(new StepSpec("pkltSftyChckFetchStep", "관측 이력 추가"))));
        add(new JobSpec("zoneNmPrefillJob", "생활권 구역명 채우기",
                "생활권 격자 구역명 캐시를 백엔드 내부 API 로 미리 채운다", "구역명",
                List.of(new ParamSpec("gridSz", ParamType.ENUM, false, "전 크기(1000→500→250→100)",
                        List.of("1000", "500", "250", "100"), "격자 크기(m)")),
                true, null, null,
                List.of(new StepSpec("zoneNmPrefillStep", "구역명 채우기"))));
        add(new JobSpec("pkltAcdntFetchJob", "주차장 사고이력 수집",
                "마지막 사고일시부터 오늘까지 D-Hub 사고이력을 수집·병합한다", "적재 사고",
                List.of(new ParamSpec("from", ParamType.DATE, false, "마지막 사고일시(첫 실행은 하한 없음)",
                                List.of(), "사고일시 시작일(포함)"),
                        new ParamSpec("to", ParamType.DATE, false, "오늘 다음날 00:00", List.of(), "사고일시 끝일(미포함)")),
                true, null, null,
                List.of(new StepSpec("pkltAcdntFetchStep", "수집·주차장 매칭·병합"),
                        new StepSpec("pkltAcdntSeedCleanupStep", "임의 사고 시드 정리"))));
    }

    private void add(JobSpec spec) {
        jobs.put(spec.jobNm(), spec);
    }

    /** 카탈로그 순서 그대로의 전 잡. */
    public List<JobSpec> all() {
        return List.copyOf(jobs.values());
    }

    public Optional<JobSpec> find(String jobNm) {
        return Optional.ofNullable(jobNm == null ? null : jobs.get(jobNm));
    }
}
