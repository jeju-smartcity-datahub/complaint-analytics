package jeju.datahub.batch.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Job 실행 1회를 {@code batch.tb_btch_excn_hstry} 한 행으로 남긴다.
 *
 * <p>Spring Batch 메타(`batch_job_execution`)에는 범용 카운트만 남고, 종료 요약 로그의 지표
 * (API 호출 수·캐시 히트·스킵 사유별·미분류 유형별·공간매칭 1/2단)는 <b>로그와 함께 사라진다.</b>
 * "지난주 실행 대비 캐시가 듣고 있나", "미분류가 튄 날이 언제인가"에 답하려면 그 값이 행으로
 * 남아야 한다. <b>새로 계산하는 지표는 없다</b> — 리스너가 이미 로그로 뱉는 값을 그대로 받는다.
 *
 * <p>모든 Job 이 이 컴포넌트 하나를 공유한다(2026-08-25 기준 3개).
 * <ul>
 *   <li>{@code complaintFetchJob} — {@link jeju.datahub.batch.cvlcpt.CvlcptJobSummaryListener}
 *       가 지오코딩·분류 지표를 채워 넘긴다. 🔁 파이프라인 1단화로 <b>수집 잡이 가공까지 맡게 되면서</b>
 *       종전 가공 잡에 있던 지표가 이 잡으로 옮겨 왔다</li>
 *   <li>{@code crackdownFetchJob} — {@link jeju.datahub.batch.crackdown.CrackdownFetchHistoryListener} 가
 *       주소 단위 지오코딩 Step 의 지오코딩·행정동·스킵 지표를 채워 넘긴다. ⚠️ read/write 는
 *       Step 합산이라 수집 행수 + 지오코딩 주소 수로 단위가 섞인다</li>
 *   <li>{@code pblpkltFetchJob} — {@link jeju.datahub.batch.pblpklt.PblpkltFetchHistoryListener} 가 전용
 *       카운터로 지표를 채운다. 하지 않는 지표만 NULL</li>
 * </ul>
 *
 * <h2>🔴 {@code REQUIRES_NEW} — FAILED 로 끝난 실행이야말로 기록이 필요하다</h2>
 * 이 기록의 존재 이유 절반은 <b>"왜 실패했는지"</b>다. 청크/스텝 트랜잭션에 얹히면 실패 경로의
 * 롤백에 같이 휩쓸려, 정작 남아야 할 실패 실행의 행이 사라진다. 별도 트랜잭션에서 즉시 커밋한다
 * ({@link jeju.datahub.batch.geocode.GeocodeSkipRecorder} 와 같은 방식 — {@code REQUIRES_NEW}·별도 빈).
 *
 * <p>⚠️ 그래서 {@code @Transactional} 은 <b>호출자가 프록시로 부르는 이 public 메서드에</b> 붙는다.
 * 내부 헬퍼로 옮기면 자기호출이라 프록시를 타지 않아 애노테이션이 조용히 무시된다.
 *
 * <h2>🔴 기록 실패가 Job 을 죽여선 안 된다 — 단, try/catch 는 <b>호출자</b>가 한다</h2>
 * 이 메서드 안에서 잡으면 <b>커밋 시점의 실패를 못 잡는다</b>(커밋은 프록시가 메서드 반환 뒤에
 * 수행한다). 그래서 예외를 그대로 올리고, 리스너가 트랜잭션 경계 <b>바깥</b>에서 감싸 WARN 만
 * 남긴다. 이력 때문에 15만 행 배치를 실패로 만들 수는 없다.
 *
 * <h2>🔒 {@code failure_message} 에 자격증명이 실리지 않게 한다</h2>
 * 예외 메시지에는 URL·헤더 조각이 섞여 들어올 수 있다. 마스킹({@link #redact})과 길이 제한을
 * 함께 걸어 <b>이력 테이블이 비밀 유출 경로가 되지 않게</b> 한다.
 */
@Component
public class BatchRunHistoryRecorder {

    private static final Logger log = LoggerFactory.getLogger(BatchRunHistoryRecorder.class);

    /** 코드베이스 관례 — 클라이언트들과 동일하게 정적 인스턴스를 쓴다(스레드 안전). */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@code run_mode} 컬럼 길이. */
    static final int MAX_RUN_MODE_CHARS = 20;

    /** {@code target_range} 컬럼 길이. */
    static final int MAX_TARGET_RANGE_CHARS = 50;

    /** {@code excn_se_cd} 컬럼 길이. */
    static final int MAX_EXCN_SE_CHARS = 10;

    /** {@code excutr_id} 컬럼 길이({@code TB_USER.user_id} 와 같다). */
    static final int MAX_EXCUTR_ID_CHARS = 20;

    /** 예외 1건에서 남기는 메시지 길이. 🔒 길수록 자격증명·응답 본문이 섞일 확률이 올라간다. */
    static final int MAX_EXCEPTION_MESSAGE_CHARS = 200;

    /** {@code failure_message} 전체 상한. TEXT 컬럼이지만 스택 전체를 담을 곳은 아니다(로그가 있다). */
    static final int MAX_FAILURE_MESSAGE_CHARS = 1000;

    /** 요약에 담는 실패 예외 수. 뒤로 갈수록 파생 예외라 앞 몇 개면 원인이 드러난다. */
    static final int MAX_FAILURE_EXCEPTIONS = 3;

    private static final String UPSERT_SQL = """
            INSERT INTO batch.tb_btch_excn_hstry (
              job_execution_id, job_nm, status, run_mode, target_range,
              started_dt, ended_dt, duration_ms, read_cnt, write_cnt,
              skip_geocode_failed, skip_emd_unresolved, filtered_transient,
              geocode_api_lookups, geocode_cache_hits,
              geocode_place_recoveries, geocode_stripped,
              emd_contains, emd_buffer, unclsf_cnt, unknown_types, failure_message,
              emd_name_fallback, excn_se_cd, excutr_id, up_excn_id)
            VALUES (?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?,
                    ?, ?,
                    ?, ?,
                    ?, ?, ?, CAST(? AS jsonb), ?,
                    ?, ?, ?, ?)
            ON CONFLICT (job_execution_id) DO UPDATE SET
              job_nm = EXCLUDED.job_nm, status = EXCLUDED.status,
              run_mode = EXCLUDED.run_mode, target_range = EXCLUDED.target_range,
              started_dt = EXCLUDED.started_dt, ended_dt = EXCLUDED.ended_dt,
              duration_ms = EXCLUDED.duration_ms,
              read_cnt = EXCLUDED.read_cnt, write_cnt = EXCLUDED.write_cnt,
              skip_geocode_failed = EXCLUDED.skip_geocode_failed,
              skip_emd_unresolved = EXCLUDED.skip_emd_unresolved,
              filtered_transient = EXCLUDED.filtered_transient,
              geocode_api_lookups = EXCLUDED.geocode_api_lookups,
              geocode_cache_hits = EXCLUDED.geocode_cache_hits,
              geocode_place_recoveries = EXCLUDED.geocode_place_recoveries,
              geocode_stripped = EXCLUDED.geocode_stripped,
              emd_contains = EXCLUDED.emd_contains, emd_buffer = EXCLUDED.emd_buffer,
              unclsf_cnt = EXCLUDED.unclsf_cnt,
              unknown_types = EXCLUDED.unknown_types,
              failure_message = EXCLUDED.failure_message,
              emd_name_fallback = EXCLUDED.emd_name_fallback,
              excn_se_cd = EXCLUDED.excn_se_cd, excutr_id = EXCLUDED.excutr_id,
              up_excn_id = EXCLUDED.up_excn_id,
              created_dt = now()
            """;

    private final JdbcTemplate jdbc;

    public BatchRunHistoryRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 실행 1건을 기록한다. <b>호출자는 try/catch 로 감싼다</b>(위 주석).
     *
     * @param jobExecution 종료 직전의 실행. {@code afterJob} 시점이면 {@code endTime}·{@code status}
     *                     가 이미 확정돼 있다(Spring Batch 가 {@code afterJob} 호출 전에 채운다)
     * @param metrics      Job 고유 지표. 지오코딩·분류가 없는 Job 은 {@link BatchRunMetrics#none()}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(JobExecution jobExecution, BatchRunMetrics metrics) {
        LocalDateTime startedAt = startedAt(jobExecution);
        LocalDateTime endedAt = jobExecution.getEndTime();
        long read = 0;
        long written = 0;
        for (StepExecution step : jobExecution.getStepExecutions()) {
            read += step.getReadCount();
            written += step.getWriteCount();
        }

        jdbc.update(UPSERT_SQL,
                jobExecution.getId(),
                jobExecution.getJobInstance().getJobName(),
                jobExecution.getStatus().name(),
                runMode(jobExecution.getJobParameters()),
                targetRange(jobExecution.getJobParameters()),
                startedAt,
                endedAt,
                durationMs(startedAt, endedAt),
                read,
                written,
                metrics.skipGeocodeFailed(),
                metrics.skipEmdUnresolved(),
                metrics.filteredTransient(),
                metrics.geocodeApiLookups(),
                metrics.geocodeCacheHits(),
                metrics.geocodePlaceRecoveries(),
                metrics.geocodeStripped(),
                metrics.emdContains(),
                metrics.emdBuffer(),
                metrics.unclassifiedCount(),
                toJson(metrics.unknownTypes()),
                failureMessage(jobExecution),
                metrics.emdNameFallback(),
                excnSe(jobExecution.getJobParameters()),
                excutrId(jobExecution.getJobParameters()),
                prntExcnId(jobExecution.getJobParameters()));
    }

    /**
     * 실행 구분({@link ExcnSe} 값). 파라미터가 없으면 {@code null} — 이 칸이 생기기 전 경로이거나
     * 진입점이 싣지 않은 실행이다(모르는 것을 지어내지 않는다 — {@link #runMode} 와 같은 원칙).
     */
    static String excnSe(JobParameters parameters) {
        String value = parameter(parameters, ExcnSe.PARAM_EXCN_SE);
        return value == null ? null : truncate(ExcnSe.PARAM_EXCN_SE, value, MAX_EXCN_SE_CHARS);
    }

    /** 실행자 {@code user_id}. 운영 API(MANUAL)만 싣는다 — 그 밖은 {@code null}. */
    static String excutrId(JobParameters parameters) {
        String value = parameter(parameters, ExcnSe.PARAM_EXCUTR_ID);
        return value == null ? null : truncate(ExcnSe.PARAM_EXCUTR_ID, value, MAX_EXCUTR_ID_CHARS);
    }

    /**
     * 연쇄를 일으킨 앞 잡의 실행 ID. 숫자로 읽히지 않으면 <b>그 칸만 NULL + WARN</b> —
     * 이 값 하나 때문에 이력 행 전체를 잃지 않는다({@link #toJson} 과 같은 원칙).
     */
    static Long prntExcnId(JobParameters parameters) {
        String value = parameter(parameters, ExcnSe.PARAM_PRNT_EXCN_ID);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            log.warn("{} 파라미터가 숫자가 아니어서 up_excn_id 를 NULL 로 기록합니다: '{}'",
                    ExcnSe.PARAM_PRNT_EXCN_ID, abbreviate(value, MAX_TARGET_RANGE_CHARS));
            return null;
        }
    }

    /**
     * 식별자 칸은 {@link #abbreviate} 의 {@code ...} 표시를 붙이지 않는다 — 붙이면 다른 값이 된다.
     * 넘치면 앞부분만 남기고 WARN 한다(넘친 채 INSERT 하면 행 전체가 사라진다).
     */
    private static String truncate(String name, String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        log.warn("{} 파라미터가 {}자를 넘어 앞 {}자만 기록합니다.", name, max, max);
        return value.substring(0, max);
    }

    /** {@code started_dt} 은 NOT NULL 이다 — 시작 시각이 비면(기동 직후 실패 등) 생성 시각으로 대신한다. */
    private static LocalDateTime startedAt(JobExecution jobExecution) {
        if (jobExecution.getStartTime() != null) {
            return jobExecution.getStartTime();
        }
        return jobExecution.getCreateTime() != null ? jobExecution.getCreateTime() : LocalDateTime.now();
    }

    private static Long durationMs(LocalDateTime startedAt, LocalDateTime endedAt) {
        return endedAt == null ? null : Duration.between(startedAt, endedAt).toMillis();
    }

    /**
     * 실행 모드. <b>Job 파라미터에서만 읽는다</b> — 프로퍼티 기본값(dhub.query-mode 등)까지 재현하려
     * 들면 Reader 의 판단 로직이 두 벌이 된다.
     *
     * <pre>
     * mode 파라미터           → 그 값 (daily | backfill)
     * from/to 가 있으면       → backfill
     * targetDate 가 있으면    → daily
     * limit 이 있으면         → manual   (표본 실행 — 수동 트리거 경로)
     * 그 외                   → null     (파라미터 없이 실행: 스케줄러·전량 실행. 프로퍼티 기본값을 따랐다)
     * </pre>
     *
     * <p>마지막 줄을 {@code manual} 로 채우지 않는다. 02:00 스케줄러는 {@code runAt} 하나만 넣는데
     * (날짜 계산을 Reader 에 맡기는 설계다) 그걸 "수동 실행"으로 적으면 <b>이력이 거짓말을 한다.</b>
     * 모르는 건 NULL 로 둔다.
     */
    static String runMode(JobParameters parameters) {
        String mode = parameter(parameters, "mode");
        if (mode != null) {
            return abbreviate(mode.toLowerCase(Locale.ROOT), MAX_RUN_MODE_CHARS);
        }
        if (parameter(parameters, "from") != null || parameter(parameters, "to") != null) {
            return "backfill";
        }
        if (parameter(parameters, "targetDate") != null) {
            return "daily";
        }
        return parameter(parameters, "limit") != null ? "manual" : null;
    }

    /**
     * 조회 구간. {@code from~to}(backfill) · 기준일(daily) · {@code limit=N}(표본) 중 하나.
     *
     * @return 파라미터가 없으면 {@code null} — 전량/기본 실행이다
     */
    static String targetRange(JobParameters parameters) {
        String from = parameter(parameters, "from");
        String to = parameter(parameters, "to");
        if (from != null || to != null) {
            return abbreviate(nullToEmpty(from) + "~" + nullToEmpty(to), MAX_TARGET_RANGE_CHARS);
        }
        String targetDate = parameter(parameters, "targetDate");
        if (targetDate != null) {
            return abbreviate(targetDate, MAX_TARGET_RANGE_CHARS);
        }
        String limit = parameter(parameters, "limit");
        return limit == null ? null : abbreviate("limit=" + limit, MAX_TARGET_RANGE_CHARS);
    }

    /**
     * 타입에 무관하게 파라미터 값을 문자열로 읽는다.
     *
     * <p>{@code limit}·{@code runAt} 은 {@code Long}, 나머지는 {@code String} 이라
     * {@code getString()} 하나로는 다 읽히지 않는다(타입이 다르면 값이 없는 것처럼 보이거나 터진다).
     */
    private static String parameter(JobParameters parameters, String name) {
        JobParameter<?> parameter = parameters.getParameter(name);
        if (parameter == null || parameter.getValue() == null) {
            return null;
        }
        String value = String.valueOf(parameter.getValue()).trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * 실패 원인 요약. FAILED 가 아니면 실패 예외가 없어 {@code null} 이다.
     *
     * <p>프레임워크가 원인을 감싸므로(예: {@code Non-skippable exception in recoverer})
     * <b>맨 앞 예외 + 최종 원인</b>을 함께 남긴다 — 감싼 쪽만 적으면 "왜 죽었는지"가 사라진다.
     */
    static String failureMessage(JobExecution jobExecution) {
        List<Throwable> failures = jobExecution.getAllFailureExceptions();
        if (failures == null || failures.isEmpty()) {
            return null;
        }
        String summary = failures.stream()
                .filter(java.util.Objects::nonNull)
                .limit(MAX_FAILURE_EXCEPTIONS)
                .map(BatchRunHistoryRecorder::describe)
                .collect(Collectors.joining(" | "));
        return abbreviate(summary, MAX_FAILURE_MESSAGE_CHARS);
    }

    /** {@code 클래스명: 메시지앞부분} (+ 원인이 다르면 {@code <- 원인클래스: 메시지앞부분}). */
    private static String describe(Throwable throwable) {
        String head = throwable.getClass().getSimpleName() + ": " + shortMessage(throwable);
        Throwable root = rootCause(throwable);
        return root == throwable
                ? head
                : head + " <- " + root.getClass().getSimpleName() + ": " + shortMessage(root);
    }

    /** 🔒 마스킹 뒤에 자른다 — 순서를 바꾸면 잘린 조각으로 비밀이 남을 수 있다. */
    private static String shortMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? "(메시지 없음)"
                : abbreviate(redact(message), MAX_EXCEPTION_MESSAGE_CHARS);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** 🔒 {@code token=abc} → {@code token=***} — {@link SecretMasker#mask}. */
    static String redact(String text) {
        return SecretMasker.mask(text);   // 규칙은 공용 유틸 한 곳(로그 파일·로그 API 와 같다)
    }

    /** 컬럼 길이를 넘기면 자르고 잘렸다는 표시를 남긴다 — 값이 조용히 사라지는 것보다 낫다. */
    static String abbreviate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(0, max - 3)) + "...";
    }

    /**
     * 미지 유형 누계 → JSONB 문자열.
     *
     * <p>직렬화가 깨져도 <b>행 전체를 잃지 않는다</b> — 나머지 지표가 더 중요하므로 이 컬럼만
     * NULL 로 두고 WARN 을 남긴다.
     */
    private static String toJson(Map<String, Long> unknownTypes) {
        if (unknownTypes == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(unknownTypes);
        } catch (JsonProcessingException e) {
            log.warn("unknown_types 직렬화 실패 — 이 컬럼만 NULL 로 기록합니다.", e);
            return null;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
