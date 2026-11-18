package jeju.datahub.batch.risknoti;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 알림 × 수신자 매핑 적재 Tasklet — {@code parking.TB_NOTI_HSTRY_RCVR}
 * (TSK-4202 Step 6 · D7·D7-1·D8).
 *
 * <h2>🔵 rule_persistence(JPA+MyBatis) 의 예외</h2>
 * 백엔드 규칙은 "쓰기(CUD)는 JPA" 지만 <b>이 배치의 쓰기는 Spring JDBC 로만 한다</b>
 * ({@link RiskAlertItemWriter} 와 같은 근거). 여기는 백엔드 애플리케이션이 아니고
 * ({@code build.gradle} 이 {@code starter-jdbc} 만 쓴다) 연산이 <b>집합 단위 upsert</b>
 * ({@code INSERT ... SELECT ... ON CONFLICT DO NOTHING})라 엔티티 생명주기가 들어갈 자리가 없다.
 * 즉 규칙의 적용 대상 밖이다({@code rule_batch-project.md} — "예외 근거를 클래스 주석에 남긴다").
 *
 * <h2>🔴 왜 Writer 가 아니라 후행 Tasklet 인가 (D8)</h2>
 * {@code JdbcBatchItemWriter} 는 upsert 후 {@code noti_id} 를 돌려받지 않는다. 그리고
 * {@code rule_batch-project.md} 가 <b>"전량을 봐야 판정되는 것은 별도 Tasklet 으로 뒤에 둔다"</b>
 * 고 못박았다 — 공영주차장 소멸 마킹({@code PblpkltSoftDeleteMarkingTasklet})과 같은 자리다.
 * 🔴 <b>전량 판정이 끝난 뒤에만 돈다</b> — 부분 적재분으로 수신 대상을 굳히면 그날 알림이
 * 일부에게만 간다. 그 보장은 {@code RiskAlertJudgeJobConfig} 의 {@code .next()} 가 진다
 * (앞 Step 이 {@code COMPLETED} 일 때만 다음 Step 이 돈다).
 *
 * <h2>🔴 지역 3단 규칙 SQL 은 <b>두 곳</b>에 같은 모양으로 있다 — 한쪽만 고치지 말 것 (D7-1 · TSK-4250 R7)</h2>
 * <ol>
 *   <li>이 클래스의 {@link #INSERT_HEAD} (판정 Job 후행 Step · 백필 Job
 *       {@code POST /api/batch/risk-alert-receiver-backfill} 이 <b>같은 상수를 재사용</b>한다)</li>
 *   <li>백엔드 테스트 {@code RiskNotiIntegrationTest.restoreBackfillMapping} (레포가 달라 규칙을 복제한다)</li>
 * </ol>
 * 🔁 종전 시드({@code 10-TB_NOTI_ALERT.sql}) 말미의 백필은 2026-10-14 에 걷혔다.
 *
 * <p>🔴 <b>지역 판정의 정본은 {@code parking.TB_NOTI_RCVR_RGN}</b> 이다(TSK-4250) — 수신자 한 명이
 * 지역 행을 여러 개 가진다. {@code sgg_nm}·{@code emd_cd} <b>둘 다 NULL 인 행 = 전체 지역</b>이고,
 * 지역 행이 <b>0개면 아무 지역도 받지 않는다</b>(R3 — fail-closed. 저장 누락이 알림 확산으로 번지지 않게).
 * 수신자 관리 화면의 {@code regionNm} 도 이제 백엔드 서비스가 같은 표로 조립하므로, 뜻이 갈리면
 * <b>화면에 "제주시 전체" 로 보이는 수신자가 알림을 못 받는다.</b>
 *
 * <h2>🔴 read/write 카운트를 올리지 않는다</h2>
 * {@code RiskAlertJudgeHistoryListener.afterJob} 이 <b>전 Step 의 read/write 를 합산</b>해 이력
 * ({@code batch.tb_btch_excn_hstry})에 남기고, {@code RiskAlertJudgeJobTest} 가
 * {@code writeCount < readCount} 를 단언한다. 이 Step 이 {@code contribution.incrementWriteCount()}
 * 로 적재 건수를 더하면 기존 단언과 운영 지표가 함께 흔들린다 — 적재 건수는
 * <b>로그와 {@code ExecutionContext}</b> 에만 둔다.
 *
 * <h2>⚠️ 백필은 "그때 누구에게 갔는가" 가 아니라 "지금 담당은 누구인가" 다 (D7-1)</h2>
 * 수신자 표의 <b>현재 상태</b>로 과거 알림을 매핑한다. 과거 발송 대상은 복원할 수 없다
 * (발송 기록 자체가 없다).
 */
public class RiskAlertReceiverMappingTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(RiskAlertReceiverMappingTasklet.class);

    /** 판정 Job 이 쓰는 Job 파라미터 — 기준일의 <b>정본</b>이다(진입점이 확정해 넣는다). */
    static final String TARGET_DATE_PARAM = "targetDate";

    /** 백필 Job 이 쓰는 선택 Job 파라미터(포함). 없으면 하한 없음. */
    static final String FROM_DT_PARAM = "fromDt";

    /** 백필 Job 이 쓰는 선택 Job 파라미터(<b>미포함</b>). 없으면 상한 없음. */
    static final String TO_DT_PARAM = "toDt";

    /**
     * 🔴 <b>지역 3단 규칙은 이 상수 하나다</b> — 기간 {@code WHERE} 만 가변으로 붙인다
     * (아래 {@link #FROM_CLAUSE}·{@link #TO_CLAUSE}). 백필 Job 도 같은 상수를 재사용하므로
     * 규칙이 두 벌로 갈리지 않는다.
     *
     * <p>🔴 지역은 JOIN 이 아니라 <b>{@code EXISTS}</b> 로 본다(TSK-4250) — 수신자의 지역 행 수만큼
     * 알림×수신자 쌍이 불어나지 않게 한다({@code ON CONFLICT} 가 결과는 지키지만 의도를 SQL 에 드러낸다).
     *
     * <p>🔴 {@code ON CONFLICT (noti_id, rcvr_id) DO NOTHING} — 재판정·재백필이 정상 경로다.
     * 멱등하지 않으면 재실행이 PK 위반으로 죽는다.
     *
     * <p>🔴 {@code risk_lvl_cd} 로 거르지 않는다. {@code TB_NOTI_RCVR_LVL}(수신자가 고른 단계)은
     * TSK-4028 D14 가 <i>"알림 생성 대상을 거르지 않는다"</i> 로 못박았다 — 발송 방식(메일/SMS)을
     * 가르는 용도다.
     */
    static final String INSERT_HEAD = """
            INSERT INTO parking.TB_NOTI_HSTRY_RCVR (noti_id, rcvr_id)
            SELECT h.noti_id, r.rcvr_id
              FROM parking.TB_RISK_STP_NOTI_HSTRY h
              JOIN parking.TB_ADMDST a ON a.emd_cd = h.emd_cd
              JOIN parking.TB_NOTI_RCVR r
                ON r.use_yn = 'Y' AND r.del_yn = 'N'
               AND (EXISTS (SELECT 1 FROM parking.TB_NOTI_RCVR_RGN g
                             WHERE g.rcvr_id = r.rcvr_id
                               AND (g.emd_cd = h.emd_cd                                   -- ① 읍면동 지정
                                 OR g.emd_cd IS NULL AND g.sgg_nm = a.sgg_nm              -- ② 시군구 전체
                                 OR g.sgg_nm IS NULL AND g.emd_cd IS NULL)))              -- ③ 전체 지역(NULL 행, R3)
             WHERE 1 = 1""";

    /**
     * 🔴 기간은 <b>반열림 {@code [from, to)}</b> 다 — {@code <=} 로 적으면 시각이 섞인 행이 빠진다
     * ({@code selectDailyAlerts} 가 같은 함정을 주석으로 남겼다).
     *
     * <p>🔴 {@code CAST(:fromDt AS date)} 의 {@code CAST} 를 벗기지 않는다 —
     * {@link RiskAlertTargetItemReader} 와 같은 근거(PostgreSQL 이 파라미터 타입을 주변 연산자로 추론한다).
     */
    static final String FROM_CLAUSE = "\n   AND h.ocrn_dt >= CAST(:" + FROM_DT_PARAM + " AS date)";

    static final String TO_CLAUSE = "\n   AND h.ocrn_dt <  CAST(:" + TO_DT_PARAM + " AS date)";

    static final String ON_CONFLICT = "\nON CONFLICT (noti_id, rcvr_id) DO NOTHING";

    /**
     * 요약 로그용 집계. 🔴 <b>지역 3단 규칙을 다시 쓰지 않는다</b> — 이미 적재된 매핑을 기간으로만 센다
     * (네 번째 복사본을 만들면 규칙이 또 갈린다).
     */
    static final String SUMMARY_HEAD = """
            SELECT count(DISTINCT m.noti_id) AS alert_cnt,
                   count(DISTINCT m.rcvr_id) AS rcvr_cnt
              FROM parking.TB_NOTI_HSTRY_RCVR m
              JOIN parking.TB_RISK_STP_NOTI_HSTRY h ON h.noti_id = m.noti_id
             WHERE 1 = 1""";

    /** {@code ExecutionContext} 키 — 적재 건수는 여기와 로그에만 남는다(write 카운트를 올리지 않는다). */
    static final String INSERTED_COUNT_KEY = "risknoti.rcvrMappingInsertedCount";

    /**
     * 기간을 어디서 읽을지 — 같은 Tasklet 을 두 Job 이 공유하는 지점이다.
     *
     * <ul>
     *   <li>{@link #DAILY} — 판정 Job 후행 Step. 기준일은 Job 파라미터 {@code targetDate} <b>하나가
     *       정본</b>이고 기간은 {@code [targetDate, targetDate+1일)} 이다. Tasklet 이 "오늘" 을 다시
     *       계산하면 자정 근처에서 판정과 적재가 하루 어긋난다 — 그래서
     *       {@link RiskAlertProperties#resolveTargetDate(LocalDate)} 로 <b>판정 Step 과 같은 값</b>을 읽는다.</li>
     *   <li>{@link #RANGE} — 백필 Job. 선택 파라미터 {@code fromDt}·{@code toDt} 가 있으면 그 구간,
     *       <b>없으면 전 기간</b>이다(D7-1 ②).</li>
     * </ul>
     */
    enum PeriodMode {
        DAILY, RANGE
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final PeriodMode periodMode;

    public RiskAlertReceiverMappingTasklet(DataSource dataSource, PeriodMode periodMode) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.periodMode = periodMode;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        StepExecution stepExecution = chunkContext.getStepContext().getStepExecution();
        JobParameters jobParameters = stepExecution.getJobParameters();

        LocalDate from;
        LocalDate to;
        if (periodMode == PeriodMode.DAILY) {
            // 🔴 판정 Step 과 같은 값을 읽는다 — 기준일은 Job 파라미터 하나가 정본이다.
            LocalDate baseDt = RiskAlertProperties.resolveTargetDate(
                    parseDate(jobParameters.getString(TARGET_DATE_PARAM)));
            from = baseDt;
            to = baseDt.plusDays(1);            // 반열림 [baseDt, baseDt+1일)
        } else {
            from = parseDate(jobParameters.getString(FROM_DT_PARAM));
            to = parseDate(jobParameters.getString(TO_DT_PARAM));   // 둘 다 null 이면 전 기간
        }

        Map<String, Object> params = new HashMap<>();
        String periodClause = "";
        if (from != null) {
            periodClause += FROM_CLAUSE;
            params.put(FROM_DT_PARAM, java.sql.Date.valueOf(from));
        }
        if (to != null) {
            periodClause += TO_CLAUSE;
            params.put(TO_DT_PARAM, java.sql.Date.valueOf(to));
        }

        int inserted = jdbc.update(INSERT_HEAD + periodClause + ON_CONFLICT, params);

        Map<String, Object> summary = jdbc.queryForMap(SUMMARY_HEAD + periodClause, params);
        Object alertCnt = summary.get("alert_cnt");
        Object rcvrCnt = summary.get("rcvr_cnt");

        // 🔴 적재 건수는 여기(ExecutionContext)와 로그에만 — contribution 의 read/write 는 건드리지 않는다.
        stepExecution.getExecutionContext().putInt(INSERTED_COUNT_KEY, inserted);

        log.info("알림×수신자 매핑 적재 {}건 / 알림 {}건 / 수신자 {}명 — 기간 [{}, {}) mode={}",
                inserted, alertCnt, rcvrCnt,
                from == null ? "전체" : from, to == null ? "전체" : to, periodMode);

        return RepeatStatus.FINISHED;
    }

    /**
     * ⚠️ Job 파라미터는 문자열이다({@code addString}). 빈 값은 "없음" 으로 본다 —
     * 백필의 "기간 생략 = 전 기간" 이 그 경로다.
     */
    static LocalDate parseDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value.trim());
    }
}
