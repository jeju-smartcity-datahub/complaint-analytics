package jeju.datahub.batch.rptp;

import jeju.datahub.batch.history.ExcnSe;
import java.time.LocalDate;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jeju.datahub.batch.ops.JobParameterFactory;
import jeju.datahub.batch.web.JobExecutionSummary;

/**
 * ④{@code reportAutoCrtJob} 수동 트리거.
 *
 * <pre>
 * # 오늘 기준 (baseYmd 생략)
 * curl -X POST 'http://localhost:8280/api/batch/report-auto-crt'
 *
 * # 특정 기준일로 — 배포일이 도래하는 날을 골라 검증할 때
 * curl -X POST 'http://localhost:8280/api/batch/report-auto-crt?baseYmd=2026-10-01'
 * </pre>
 *
 * <h2>🔴 기준일을 여기서 확정해 <b>항상</b> Job 파라미터로 넣는다</h2>
 * 비워 두면 {@link ReportAutoCrtProcessor} 와 {@link ReportAutoCrtWriter} 가 <b>각자</b> "오늘" 을
 * 계산한다 — 자정을 걸치면 둘이 하루가 갈려 <b>도래 판정과 스킵 기록의 {@code trgt_de} 가 다른 날</b>이
 * 된다. 게다가 Spring Batch 메타·실행 이력에도 "무슨 날짜를 기준으로 돌렸는지" 가 남지 않아 사후
 * 추적이 불가능하다. 그래서 {@link ReportAutoCrtJobConfig#SEOUL} 기준으로 <b>진입점 한 곳에서</b>
 * 확정한 뒤 {@code addString("baseYmd", ...)} 로 못박는다({@link ReportAutoCrtScheduler} 도 동일).
 * {@link ReportDueDateResolver#parseBaseYmd(String)} 는 이 값이 비면 <b>예외</b>다 — "오늘" 을
 * 정하는 곳은 진입점뿐이라는 계약을 코드로 강제한 것이다.
 *
 * <p>⚠️ <b>기본 기준일은 "오늘" 이다</b>(③ 위험단계 판정 잡의 "어제" 와 다르다). 배포일 자체가
 * 판정 대상이라 어제로 밀면 그날의 배포가 하루 늦게, 혹은 영영 나가지 않는다.
 *
 * <h2>⚠️ HTTP 파라미터가 SQL 로 흘러가는 경로를 Job 시작 전에 끊는다</h2>
 * {@code baseYmd} 는 {@code @DateTimeFormat(ISO.DATE)} 로 {@link LocalDate} 파싱을 통과한 뒤에만
 * Job 파라미터가 된다 — 형식이 틀리면 Job 이 시작되기도 전에 <b>400</b> 으로 끊긴다.
 *
 * <h2>응답은 {@link JobExecutionSummary} — {@code JobExecution} 직접 반환 금지</h2>
 * {@link JobExecution} 을 그대로 반환하면 {@code JobInstance}·{@code ExecutionContext}·
 * {@code StepExecution} 순환 참조로 Jackson 이 {@code Document nesting depth (1001) exceeds the
 * maximum allowed (1000)} 로 죽는다(2026-08-24 실측). 🔴 <b>같은 record 를 이 클래스 안에 다시
 * 정의하지 않는다</b> — {@code jeju.datahub.batch.web} 한곳으로 모았다.
 *
 * <h2>⚠️ 동기 실행 — 응답이 오래 걸린다</h2>
 * {@link JobLauncher} 기본이 {@code SyncTaskExecutor} 라 Job 이 끝날 때까지 응답이 블로킹된다.
 * 게다가 설정 1건 = 백엔드 집계 1회(읽기 타임아웃 <b>120초</b>)라 총 소요는 "도래한 설정 수 ×
 * 집계 시간" 이다. 도래하지 않는 날은 즉시 끝난다({@code written=0} 이 정상이다).
 *
 * <h2>⚠️ 로컬 개발용 엔드포인트 (cvlcpt·crackdown·pblpklt·risknoti 와 동일)</h2>
 * {@code batch.api.auth.enabled=false}(local 기본)면 무인증이다. dev/prod 는 프로파일이 true 로
 * 덮어쓴다({@code ApiKeyAuthFilter}).
 */
@RestController
@RequestMapping("/api/batch")
public class ReportAutoCrtController {

    // 🔴 Job 은 **빈 이름**으로 주입된다 — 파라미터명 reportAutoCrtJob 이
    //    ReportAutoCrtJobConfig 의 @Bean 메서드명(= JOB_NAME)과 같아야 한다.
    private final JobLauncher jobLauncher;
    private final Job reportAutoCrtJob;

    public ReportAutoCrtController(JobLauncher jobLauncher, Job reportAutoCrtJob) {
        this.jobLauncher = jobLauncher;
        this.reportAutoCrtJob = reportAutoCrtJob;
    }

    /**
     * {@code POST /api/batch/report-auto-crt?baseYmd=2026-10-01}
     *
     * @param baseYmd 기준일 {@code yyyy-MM-dd}(선택). 없으면 <b>오늘</b>(Asia/Seoul).
     *                ⚠️ 배포일이 도래하지 않는 날이면 {@code written=0} 이 정상이다 — 아무것도
     *                만들지 않는 것이 이 잡의 정상 동작이다
     * @throws Exception {@code JobExecutionException} 계열. 로컬 트리거라 그대로 500 으로 드러내는
     *                   편이 원인 파악에 낫다
     */
    @PostMapping("/report-auto-crt")
    public JobExecutionSummary run(
            @RequestParam(required = false)
            @DateTimeFormat(iso = ISO.DATE) LocalDate baseYmd) throws Exception {

        // 🔴 생략됐으면 '오늘'로 확정해 **항상** Job 파라미터에 넣는다 (위 Javadoc).
        //    🔁 확정·runAt 은 JobParameterFactory 한 곳(운영 API 와 공유). 실행 구분만 여기서 붙인다.
        JobParameters params = ExcnSe.tag(JobParameterFactory.reportAutoCrt(baseYmd), ExcnSe.API)
                .toJobParameters();

        JobExecution execution = jobLauncher.run(reportAutoCrtJob, params);
        return JobExecutionSummary.of(execution);
    }
}
