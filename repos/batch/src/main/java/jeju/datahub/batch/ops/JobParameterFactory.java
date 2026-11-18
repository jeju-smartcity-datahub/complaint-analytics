package jeju.datahub.batch.ops;

import jeju.datahub.batch.collect.SourceLoadRange;
import java.time.Clock;
import java.time.LocalDate;

import org.springframework.batch.core.JobParametersBuilder;

import jeju.datahub.batch.cvlcpt.api.DhubSql;
import jeju.datahub.batch.risknoti.RiskAlertProperties;
import jeju.datahub.batch.rptp.ReportAutoCrtJobConfig;
import jeju.datahub.batch.zonenm.ZoneNmPrefillTasklet;

/**
 * 잡별 Job 파라미터 조립의 정본.
 *
 * <p>기존 HTTP 트리거({@code POST /api/batch/*})와 운영 API({@code POST /api/batch/ops/jobs/{jobNm}/executions})가
 * <b>같은 메서드</b>를 부른다 — 기본값(어제·오늘)·{@code runAt}·null 가드를 두 벌로 두면 한쪽만 고쳐져 갈린다.
 *
 * <p>반환은 <b>실행 구분을 싣기 전</b>의 {@link JobParametersBuilder} 다. 호출자가
 * {@link jeju.datahub.batch.history.ExcnSe#tag}(API) 또는 {@link jeju.datahub.batch.history.ExcnSe#manual}
 * (MANUAL + 실행자)을 붙인 뒤 {@code toJobParameters()} 한다.
 *
 * <h2>🔴 공통 함정 두 개</h2>
 * <ul>
 *   <li>{@code runAt} 이 항상 들어간다 — 없으면 2회차가 같은 JobInstance 라
 *       {@code JobInstanceAlreadyCompleteException} 으로 시작조차 못 한다.</li>
 *   <li>선택 파라미터는 값이 있을 때만 넣는다 — Spring Batch 5 의 {@code JobParameter} 는 null 을 거부한다.</li>
 * </ul>
 */
public final class JobParameterFactory {

    private JobParameterFactory() {
    }

    /** {@code runAt} 만 — 파라미터 없는 잡(주차장·이용패턴·안전점검). */
    public static JobParametersBuilder base() {
        return new JobParametersBuilder().addLong("runAt", System.currentTimeMillis());
    }

    /**
     * {@code complaintFetchJob}. 일자는 {@link DhubSql#date(String)} 로 파싱·정규화한 뒤 넣는다
     * (HTTP 파라미터가 SQL 리터럴로 흘러가는 경로 — 형식이 틀리면 {@link IllegalArgumentException}).
     *
     * @param mode       {@code daily}|{@code backfill}(선택 — 없으면 {@code dhub.query-mode})
     * @param targetDate daily 기준일(선택 — 없으면 전일)
     * @param from       backfill 시작일(포함, 선택)
     * @param to         backfill 끝일(미포함, 선택)
     */
    public static JobParametersBuilder complaintFetch(String mode, String targetDate, String from, String to) {
        if (hasText(mode) && !java.util.Set.of("incremental", "daily", "backfill").contains(mode.trim().toLowerCase(java.util.Locale.ROOT)))
            throw new IllegalArgumentException("mode는 incremental, daily, backfill 중 하나여야 합니다.");
        if (!hasText(mode) || "incremental".equalsIgnoreCase(mode))
            SourceLoadRange.resolve(from, to, () -> null, LocalDate.now(java.time.ZoneId.of("Asia/Seoul")));
        JobParametersBuilder builder = base();
        if (hasText(mode)) {
            builder.addString("mode", mode.trim());
        }
        addDate(builder, "targetDate", targetDate);
        addDate(builder, "from", from);
        addDate(builder, "to", to);
        return builder;
    }

    /**
     * {@code crackdownFetchJob}. 둘 다 없으면 <b>넣지 않는다</b> — 그게 증분(적재된 마지막 날짜부터)이다.
     * 형식·{@code to} 단독·뒤집힌 구간은 Job 시작 전에 {@link IllegalArgumentException} 으로 끊는다.
     *
     * @param from 시작일(포함, 선택)
     * @param to   끝일(미포함, 선택 — {@code from} 이 있을 때만)
     */
    public static JobParametersBuilder crackdownFetch(String from, String to) {
        SourceLoadRange.resolve(from, to, () -> null, LocalDate.now(java.time.ZoneId.of("Asia/Seoul")));   // 검증만 — 워터마크는 Reader 가 Step 시작 시 본다
        JobParametersBuilder builder = base();
        addDate(builder, "from", from);
        addDate(builder, "to", to);
        return builder;
    }

    /** 사고일시 증분. from/to 생략 시 워터마크는 Reader가 Step 시작 시 확정한다. */
    public static JobParametersBuilder pkltAcdntFetch(String from, String to) {
        jeju.datahub.batch.acdnt.AccidentRange.resolve(from, to, () -> null,
                LocalDate.now(java.time.ZoneId.of("Asia/Seoul")));
        JobParametersBuilder builder = base();
        addDate(builder, "from", from);
        addDate(builder, "to", to);
        return builder;
    }

    /**
     * {@code riskAlertJudgeJob}. 🔴 판정일을 여기서 확정해 <b>항상</b> 넣는다 — 생략이면 어제(Asia/Seoul).
     * 비우면 Reader·Processor 가 각자 "어제" 를 계산해 자정 근처에서 갈린다(D1·D11).
     */
    public static JobParametersBuilder riskAlertJudge(LocalDate targetDate) {
        return base().addString("targetDate", RiskAlertProperties.resolveTargetDate(targetDate).toString());
    }

    /** {@code riskAlertReceiverBackfillJob}. "기간 생략 = 전 기간" 은 파라미터를 <b>넣지 않는 것</b>으로 표현한다. */
    public static JobParametersBuilder riskAlertReceiverBackfill(LocalDate fromDt, LocalDate toDt) {
        JobParametersBuilder builder = base();
        if (fromDt != null) {
            builder.addString("fromDt", fromDt.toString());
        }
        if (toDt != null) {
            builder.addString("toDt", toDt.toString());
        }
        return builder;
    }

    /** {@code reportAutoCrtJob}. 🔴 기준일을 항상 넣는다 — 생략이면 <b>오늘</b>(Asia/Seoul, 위험판정의 "어제" 와 다르다). */
    public static JobParametersBuilder reportAutoCrt(LocalDate baseYmd) {
        return reportAutoCrt(baseYmd, Clock.system(ReportAutoCrtJobConfig.SEOUL));
    }

    /** 테스트용 — "오늘" 을 고정한다. */
    static JobParametersBuilder reportAutoCrt(LocalDate baseYmd, Clock clock) {
        LocalDate base = baseYmd != null ? baseYmd : LocalDate.now(clock.withZone(ReportAutoCrtJobConfig.SEOUL));
        return base().addString("baseYmd", base.toString());
    }

    /**
     * {@code zoneNmPrefillJob}. {@code gridSz} 없으면 전 크기. 지원 밖 크기는 {@link IllegalArgumentException}
     * (기존 트리거는 그 전에 400 으로 끊는다).
     */
    public static JobParametersBuilder zoneNmPrefill(Long gridSz) {
        JobParametersBuilder builder = base();
        if (gridSz != null) {
            if (!ZoneNmPrefillTasklet.isSupported(gridSz)) {
                throw new IllegalArgumentException("gridSz 는 1000·500·250·100 중 하나여야 합니다: " + gridSz);
            }
            builder.addLong("gridSz", gridSz);
        }
        return builder;
    }

    private static void addDate(JobParametersBuilder builder, String name, String value) {
        if (hasText(value)) {
            builder.addString(name, DhubSql.date(value).toString());
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
