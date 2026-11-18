package jeju.datahub.batchops.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.batchops.service.BatchOpsExcnDetailVO;
import jeju.datahub.batchops.service.BatchOpsExcnSearchVO;
import jeju.datahub.batchops.service.BatchOpsExcnVO;
import jeju.datahub.batchops.service.BatchOpsJobVO;
import jeju.datahub.batchops.service.BatchOpsJobsVO;
import jeju.datahub.batchops.service.BatchOpsParamVO;
import jeju.datahub.batchops.service.BatchOpsProgressVO;
import jeju.datahub.batchops.service.BatchOpsSchdlVO;
import jeju.datahub.batchops.service.BatchOpsService;
import jeju.datahub.batchops.service.BatchOpsStepSpecVO;
import jeju.datahub.batchops.service.BatchOpsStepVO;
import jeju.datahub.batchops.service.BatchOpsSubRsltVO;
import jeju.datahub.common.PageResponse;
import lombok.RequiredArgsConstructor;

/**
 * 배치 운영 조회 구현 (TSK-4337 Step 6 · D2). 전자정부 {@link EgovAbstractServiceImpl} 상속.
 *
 * <p>🔴 <b>카탈로그 병합(Step 7)</b> — {@link #selectJobs()} 는 배치 카탈로그를 받으면 <b>카탈로그 순서·9개 기준</b>으로
 * DB 요약(이름 → 요약)을 붙이고 카탈로그 칸을 채운다. 한 번도 안 돈 잡도 행이 생기고, 카탈로그에 없는 DB 잡
 * 이름(폐지 잡)은 <b>제외</b>한다. 카탈로그를 못 받을 때만 DB 잡 이름으로 그리고 {@code catalogAvailable=false} 다
 * (조회는 200 유지 — 배치가 죽어도 "왜 죽었는지" 는 보여야 한다).
 */
@Service
@RequiredArgsConstructor
public class BatchOpsServiceImpl extends EgovAbstractServiceImpl implements BatchOpsService {

    /** 목록·하위 결과 메시지 절단 길이 (상세는 전체) */
    static final int LIST_MESSAGE_MAX = 500;

    /** 실행 중으로 보는 상태 — 진행 단계를 싣는다 */
    private static final Set<String> RUNNING = Set.of("STARTING", "STARTED", "STOPPING");

    private final BatchOpsMapper batchOpsMapper;
    private final BatchOpsClient batchOpsClient;

    // ---------------------------------------------------------------- 잡 목록

    @Override
    public BatchOpsJobsVO selectJobs() {
        Map<String, BatchOpsJobVO> db = selectDbJobs();
        Optional<List<BatchOpsCatalogJob>> catalog = batchOpsClient.catalog();
        if (catalog.isEmpty()) {
            return new BatchOpsJobsVO(false, new ArrayList<>(db.values()));
        }
        List<BatchOpsJobVO> jobs = new ArrayList<>();
        for (BatchOpsCatalogJob c : catalog.orElseThrow()) {
            BatchOpsJobVO job = db.get(c.getJobNm());
            if (job == null) {
                // 한 번도 안 돈 잡 — DB 칸은 비운 채 카탈로그만으로 행을 만든다
                job = new BatchOpsJobVO();
                job.setJobNm(c.getJobNm());
            }
            mergeCatalog(job, c);
            jobs.add(job);
        }
        return new BatchOpsJobsVO(true, jobs);
    }

    /** 카탈로그 칸을 옮긴다. 스케줄의 cron·사용여부는 DB 행이 정본이고, 배치 프로세스만 아는 칸만 덧씌운다. */
    private static void mergeCatalog(BatchOpsJobVO job, BatchOpsCatalogJob c) {
        job.setJobKorNm(c.getJobKorNm());
        job.setDescription(c.getDescription());
        job.setCountLabel(c.getCountLabel());
        job.setSchedulable(c.getSchedulable());
        job.setChainNext(c.getChainNext());
        job.setChainCondition(c.getChainCondition());
        job.setRunningJobExecutionIds(c.getRunningJobExecutionIds());
        job.setParams(c.getParams());
        job.setSteps(c.getSteps());

        BatchOpsSchdlVO cs = c.getSchedule();
        if (cs == null) {
            return;
        }
        BatchOpsSchdlVO s = job.getSchedule();
        if (s == null) {
            // DB 요약에 스케줄이 없다(한 번도 안 돈 잡 — selectJobRows 는 인스턴스가 있는 잡만 본다) → 카탈로그 값 그대로
            job.setSchedule(cs);
            return;
        }
        s.setRegistered(cs.getRegistered());
        s.setBlockedByEnv(cs.getBlockedByEnv());
        s.setEnvKey(cs.getEnvKey());
        s.setNextRuns(cs.getNextRuns());
    }

    /** DB 기준 잡 요약 — 잡 이름 → 요약(이름순). Step 7 병합이 이 맵에 카탈로그를 맞댄다. */
    Map<String, BatchOpsJobVO> selectDbJobs() {
        List<BatchOpsJobRowVO> rows = batchOpsMapper.selectJobRows();
        List<Long> lastIds = rows.stream().map(BatchOpsJobRowVO::getLastExcnId).filter(Objects::nonNull).toList();
        Map<Long, BatchOpsExcnVO> lastById = lastIds.isEmpty() ? Map.of()
                : summarize(batchOpsMapper.selectExcnsByIds(lastIds)).stream()
                        .collect(Collectors.toMap(BatchOpsExcnVO::getJobExecutionId, Function.identity()));

        Map<String, BatchOpsJobVO> out = new LinkedHashMap<>();
        for (BatchOpsJobRowVO row : rows) {
            out.put(row.getJobNm(), toJobVO(row, lastById.get(row.getLastExcnId())));
        }
        return out;
    }

    private static BatchOpsJobVO toJobVO(BatchOpsJobRowVO row, BatchOpsExcnVO lastExcn) {
        BatchOpsJobVO job = new BatchOpsJobVO();
        job.setJobNm(row.getJobNm());
        job.setLastExcn(lastExcn);
        job.setLastSuccessExcnId(row.getLastSuccessExcnId());
        job.setLastSuccessDt(row.getLastSuccessDt());
        job.setSchedule(row.getSchedule());
        return job;
    }

    // ---------------------------------------------------------------- 실행 이력

    @Override
    public PageResponse<BatchOpsExcnVO> selectExecutions(BatchOpsExcnSearchVO search, int page) {
        long total = batchOpsMapper.countExcns(search);
        List<BatchOpsExcnVO> content = total == 0 ? List.of() : summarize(batchOpsMapper.selectExcnPage(search));
        return PageResponse.of(content, page, search.getSize(), total);
    }

    /** 목록 행 후처리 — 메시지 마스킹·절단 + 하위 결과(연쇄 실행) 붙이기. */
    private List<BatchOpsExcnVO> summarize(List<BatchOpsExcnVO> rows) {
        for (BatchOpsExcnVO r : rows) {
            r.setExitMessage(BatchOpsSecretMasker.maskAndCut(r.getExitMessage(), LIST_MESSAGE_MAX));
            r.setFailureMessage(BatchOpsSecretMasker.maskAndCut(r.getFailureMessage(), LIST_MESSAGE_MAX));
        }
        attachSubRslts(rows);
        return rows;
    }

    /**
     * 하위 결과를 상위 실행에 붙인다. 지금 근거는 이력 {@code up_excn_id}(연쇄) 하나다 —
     * 후속의 다른 하위 결과(경로별 결과)도 여기서 같은 {@link BatchOpsSubRsltVO} 로 합쳐 붙인다.
     */
    private void attachSubRslts(List<? extends BatchOpsExcnVO> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<Long> ids = rows.stream().map(BatchOpsExcnVO::getJobExecutionId).toList();
        Map<Long, List<BatchOpsSubRsltVO>> byUp = batchOpsMapper.selectChainSubRslts(ids).stream()
                .peek(s -> s.setMessage(BatchOpsSecretMasker.maskAndCut(s.getMessage(), LIST_MESSAGE_MAX)))
                .collect(Collectors.groupingBy(BatchOpsSubRsltVO::getUpExcnId, LinkedHashMap::new, Collectors.toList()));
        for (BatchOpsExcnVO r : rows) {
            r.setSubRslts(byUp.getOrDefault(r.getJobExecutionId(), List.of()));
        }
    }

    // ---------------------------------------------------------------- 상세

    @Override
    public BatchOpsExcnDetailVO selectExecution(long jobExecutionId) {
        BatchOpsExcnDetailVO d = batchOpsMapper.selectExcnDetail(jobExecutionId);
        if (d == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "실행을 찾을 수 없습니다: " + jobExecutionId);
        }
        // 상세는 자르지 않는다 — 마스킹만
        d.setExitMessage(BatchOpsSecretMasker.mask(d.getExitMessage()));
        d.setFailureMessage(BatchOpsSecretMasker.mask(d.getFailureMessage()));

        List<BatchOpsParamVO> params = batchOpsMapper.selectParams(jobExecutionId);
        params.forEach(p -> p.setParameterValue(BatchOpsSecretMasker.mask(p.getParameterValue())));
        d.setParams(params);

        List<BatchOpsStepVO> steps = batchOpsMapper.selectSteps(jobExecutionId);
        steps.forEach(s -> s.setExitMessage(BatchOpsSecretMasker.mask(s.getExitMessage())));
        d.setSteps(steps);

        // Step 한글명·전체 Step 수는 카탈로그 값 — 배치가 꺼져 있으면 null(화면 n / ?)
        List<BatchOpsStepSpecVO> stepSpecs = batchOpsClient.catalog()
                .flatMap(list -> list.stream().filter(c -> Objects.equals(c.getJobNm(), d.getJobNm())).findFirst())
                .map(BatchOpsCatalogJob::getSteps)
                .orElse(null);
        // 🔴 HashMap — Map.of().get(null) 은 NPE 다(Step 전환 중이면 currentStep 이 null)
        Map<String, String> korNm = new HashMap<>();
        if (stepSpecs != null) {
            stepSpecs.stream().filter(sp -> sp.getStepNm() != null && sp.getStepKorNm() != null)
                    .forEach(sp -> korNm.putIfAbsent(sp.getStepNm(), sp.getStepKorNm()));
        }
        steps.forEach(s -> s.setStepKorNm(korNm.get(s.getStepName())));

        BatchOpsProgressVO progress = RUNNING.contains(d.getStatus()) ? progressOf(steps) : null;
        if (progress != null && stepSpecs != null) {
            progress.setTotalStepCnt(stepSpecs.size());
            progress.setCurrentStepKorNm(korNm.get(progress.getCurrentStep()));
        }
        if (progress != null) {
            // TSK-4337 추가 — 단계별 수치(민원 수집만 내린다 · 그 밖·배치 무응답은 null)
            progress.setLive(batchOpsClient.live(jobExecutionId).orElse(null));
        }
        d.setProgress(progress);
        attachSubRslts(List.of(d));
        return d;
    }

    /**
     * 진행 단계 — 현재 Step 은 상태 STARTED 인 Step 중 가장 나중 것이다(Step 사이 전환 중이면 null).
     * 전체 Step 수·현재 Step 한글명은 카탈로그 값이라 {@link #selectExecution} 이 채운다(Step 7).
     */
    static BatchOpsProgressVO progressOf(List<BatchOpsStepVO> steps) {
        BatchOpsProgressVO p = new BatchOpsProgressVO();
        BatchOpsStepVO current = null;
        int done = 0;
        for (BatchOpsStepVO s : steps) {
            if ("COMPLETED".equals(s.getStatus())) {
                done++;
            } else if ("STARTED".equals(s.getStatus())) {
                current = s;
            }
        }
        p.setCmptnStepCnt(done);
        if (current != null) {
            p.setCurrentStep(current.getStepName());
            p.setReadCount(current.getReadCount());
            p.setWriteCount(current.getWriteCount());
        }
        return p;
    }
}
