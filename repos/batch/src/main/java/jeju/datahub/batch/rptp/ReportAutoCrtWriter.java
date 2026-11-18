package jeju.datahub.batch.rptp;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * 설정 1건 = 백엔드 호출 1건. <b>응답 상태코드를 정책 5종으로 가른다</b>.
 *
 * <h2>🔴 이 잡의 Writer 는 DB 에 쓰지 않는다 — 백엔드를 부른다</h2>
 * 보고서 적재({@code TB_RPTP})는 백엔드가 한다. 배치가 직접 INSERT 하면 집계·이름짓기·멱등키가
 * 두 벌이 되어 갈린다. 그래서 다른 잡과 달리 {@code JdbcBatchItemWriter} 를 상속하지 않는다.
 * 이 Writer 가 DB 에 쓰는 것은 아래 {@code last_crt_dt} 한 칸뿐이다.
 *
 * <h2>🔴 상태코드 5종 분기</h2>
 * <table>
 *   <tr><th>응답</th><th>처리</th><th>이유</th></tr>
 *   <tr><td><b>201</b> {@code created=true}</td><td>{@code last_crt_dt} 갱신 · {@code written++}</td><td>성공</td></tr>
 *   <tr><td><b>200</b> {@code created=false}</td><td>갱신 <b>안 함</b> · 통과</td>
 *       <td>이미 있다(D5). 🔴 여기서 갱신하면 <b>"안 만들었는데 만든 것"</b> 이 된다</td></tr>
 *   <tr><td><b>400</b></td><td>{@link ReportAutoCrtSkippableException#confirmed} → 스킵 + 사유 기록</td>
 *       <td>그 설정만의 확정 실패. 한 설정 때문에 나머지를 죽이지 않는다</td></tr>
 *   <tr><td><b>그 밖 5xx · 타임아웃 · 연결 실패</b></td><td>{@link ReportAutoCrtSkippableException#transientFailure} → 스킵 + <b>사유 기록 안 함</b></td>
 *       <td>일시 실패. 다음 실행이 다시 집어 온다(D8-②) — 굳히면 그 주기가 영구 유실된다</td></tr>
 *   <tr><td><b>401 · 403 · 503</b></td><td>🔴 <b>Skippable 로 감싸지 않는다</b> → Job FAILED</td>
 *       <td>키 오류·백엔드 다운. 전 설정이 같은 이유로 실패할 상황이라 즉시 끊는다(D8-③)</td></tr>
 * </table>
 * 🔴 <b>{@code 503} 은 5xx 지만 일시 실패가 아니다</b> — 백엔드가 "내부 API 키가 설정돼 있지
 * 않다" 고 알려 주는 코드라 다시 돌려도 같은 결과다. 그래서 5xx 판정보다 <b>먼저</b> 가른다.
 * 🔴 그 밖의 예상 밖 상태코드(404·415 등)도 즉시 중단이다 — 경로·계약이 어긋났다는 뜻이라
 * 전 설정이 같은 이유로 실패한다. 행 단위로 삼키면 Job 이 COMPLETED 로 끝나며 아무것도 안 만든다.
 *
 * <h2>🔴 설정 테이블에 쓰는 컬럼은 {@code last_crt_dt} 하나뿐이다 (D9)</h2>
 * 나머지 칸은 화면이 편집한다 — 배치가 건드리면 사용자가 방금 고친 주기·지역을 덮어쓴다.
 *
 * <h2>⚠️ {@code last_crt_dt} 갱신은 청크 트랜잭션에 얹지 않는다</h2>
 * {@code faultTolerant} 스킵이 나면 Spring Batch 는 <b>청크를 롤백하고 한 건씩 다시 처리</b>한다
 * (scan). 그때 앞서 201 을 받았던 설정은 <b>백엔드 멱등 처리로 200({@code created=false})</b> 이
 * 되어 갱신 경로를 타지 않는다 — 갱신을 청크 트랜잭션에 얹어 두면 그 롤백에 휩쓸려
 * <b>보고서는 있는데 {@code last_crt_dt} 는 비는</b> 상태가 된다. 보고서 적재 자체가 이미 백엔드
 * 쪽에서 커밋된 사실이므로, 그 사실을 적는 UPDATE 도 <b>별도 트랜잭션에서 즉시 커밋</b>한다
 * (스킵 기록이 {@code REQUIRES_NEW} 인 것과 같은 이유).
 * <p>⚠️ {@code @Transactional(REQUIRES_NEW)} 를 쓰지 않고 {@link TransactionTemplate} 을 쓴다 —
 * 같은 빈 안에서 부르면 <b>자기호출이라 프록시를 타지 않아</b> 애노테이션이 조용히 무시된다
 * (선례 {@code geocode/GeocodeSkipRecorder} 가 겪은 형태).
 */
@Component
@StepScope
public class ReportAutoCrtWriter implements ItemWriter<ReportAutoCrtCommand> {

    private static final Logger log = LoggerFactory.getLogger(ReportAutoCrtWriter.class);

    /**
     * 🔴 <b>이 컬럼 하나만</b> SET 한다(D9). 다른 칸을 넣으면 화면 입력을 덮는다.
     */
    static final String UPDATE_LAST_CRT_DT_SQL =
            "UPDATE parking.tb_rptp_auto_crt_stng SET last_crt_dt = ? WHERE stng_id = ?";

    private final BackendReportClient client;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;

    /** 이번 실행의 기준일 — 스킵 표의 {@code trgt_de}. Job 파라미터 하나가 정본이다. */
    private final LocalDate baseDate;

    /** 새로 만든 보고서 수(201). 이력 리스너가 읽는다. */
    private final AtomicLong createdCount = new AtomicLong();

    /** 이미 있어 건너뛴 수(200 {@code created=false}). 재실행의 정상 경로라 스킵이 아니다. */
    private final AtomicLong alreadyExistsCount = new AtomicLong();

    /**
     * @param client       백엔드 내부 API 클라이언트
     * @param jdbc         {@code last_crt_dt} 갱신용
     * @param txManager    {@code REQUIRES_NEW} 트랜잭션 템플릿의 원천
     * @param baseYmdParam Job 파라미터 {@code baseYmd}({@code yyyy-MM-dd}) — 🔴 <b>필수</b>다
     *                     ({@link ReportDueDateResolver#parseBaseYmd}). Processor 와 <b>같은 값</b>을
     *                     읽어야 스킵 기록의 {@code trgt_de} 가 판정일과 어긋나지 않는다
     */
    public ReportAutoCrtWriter(
            BackendReportClient client,
            JdbcTemplate jdbc,
            PlatformTransactionManager txManager,
            @Value("#{jobParameters['baseYmd']}") String baseYmdParam) {
        this.client = client;
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.baseDate = ReportDueDateResolver.parseBaseYmd(baseYmdParam);
    }

    /**
     * 청크의 설정들을 <b>1건씩 순차로</b> 백엔드에 보낸다.
     *
     * <p>⚠️ 순차인 것이 의도다 — 백엔드가 집계를 동기로 돌려 한 건이 길면 총 소요가
     * <b>설정 수 × 집계 시간</b>이다(read timeout 120초). 동시 호출로 줄이지 않는다:
     * 같은 DB 를 무겁게 때리고, 실패 시 어느 건이 나갔는지 추적이 어려워진다.
     */
    @Override
    public void write(Chunk<? extends ReportAutoCrtCommand> chunk) {
        for (ReportAutoCrtCommand command : chunk) {
            send(command);
        }
    }

    private void send(ReportAutoCrtCommand command) {
        BackendReportClient.Result result;
        try {
            result = client.autoCreate(command);
        } catch (ResourceAccessException e) {
            // 응답 자체를 받지 못했다(연결 실패·읽기 타임아웃). 일시 실패 — 사유를 굳히지 않는다.
            log.warn("보고서 자동 생성 호출이 응답을 받지 못했습니다(일시 실패). stngId={} 원인={}",
                    command.stngId(), e.getClass().getSimpleName());
            throw ReportAutoCrtSkippableException.transientFailure(command, baseDate, 0, e);
        }

        int status = result.status();

        // ── 201: 새로 만들었다 ────────────────────────────────────────────────
        if (status == 201) {
            if (Boolean.FALSE.equals(result.created())) {
                // 상태코드와 본문이 어긋났다. 판정은 상태코드가 지고(D8) 사실만 남긴다.
                log.warn("201 인데 본문 created=false 입니다. 상태코드를 따릅니다. stngId={}", command.stngId());
            }
            touchLastCrtDt(command);
            createdCount.incrementAndGet();
            log.info("보고서 자동 생성 완료: stngId={} rptpId={} trgtPrdCd={} 이름={}",
                    command.stngId(), result.rptpId(), command.trgtPrdCd(), result.rptpNm());
            return;
        }

        // ── 200: 이미 있다 — 🔴 last_crt_dt 를 갱신하지 않는다 (D5) ──────────────
        if (status == 200) {
            alreadyExistsCount.incrementAndGet();
            log.info("보고서가 이미 있어 건너뜁니다(정상). stngId={} rptpId={} trgtPrdCd={}",
                    command.stngId(), result.rptpId(), command.trgtPrdCd());
            return;
        }

        // ── 401·403·503: 전 설정 공통 실패 — 🔴 스킵하지 않고 Job 을 끊는다 (D8-③) ──
        //    503 은 5xx 지만 "내부 API 키 미설정" 이라 다시 돌려도 같은 결과다.
        if (status == 401 || status == 403 || status == 503) {
            throw new IllegalStateException(
                    "백엔드 내부 API 호출이 인증·가용성 문제로 실패했습니다(status=" + status
                            + "). 전 설정이 같은 이유로 실패할 상황이라 Job 을 중단합니다."
                            + " batch.rptp.backend.api-key(INTERNAL_API_KEY)와 백엔드 기동 상태를 확인하세요."
                            + " stngId=" + command.stngId());
        }

        // ── 400: 그 설정만의 확정 실패 — 스킵 + 사유 기록 ─────────────────────────
        if (status == 400) {
            throw ReportAutoCrtSkippableException.confirmed(
                    ReportAutoCrtSkippableException.BAD_REQUEST, command, baseDate, status);
        }

        // ── 그 밖 5xx: 일시 실패 — 스킵하되 사유를 굳히지 않는다 (D8-②) ────────────
        if (status >= 500) {
            log.warn("백엔드가 일시 실패를 돌려줬습니다(다음 실행이 다시 집어 옵니다). status={} stngId={}",
                    status, command.stngId());
            throw ReportAutoCrtSkippableException.transientFailure(command, baseDate, status, null);
        }

        // ── 예상 밖(404·415·204 등): 경로·계약이 어긋났다 — 즉시 중단 ──────────────
        throw new IllegalStateException(
                "백엔드 내부 API 가 예상 밖의 상태코드를 돌려줬습니다(status=" + status
                        + "). 경로·계약이 어긋났다는 뜻이라 전 설정이 같은 이유로 실패합니다."
                        + " batch.rptp.backend.base-url 과 백엔드 버전을 확인하세요."
                        + " stngId=" + command.stngId());
    }

    /**
     * {@code last_crt_dt} 를 지금 시각으로 갱신한다 — 🔴 <b>201 일 때만</b> 부른다.
     *
     * <p>⚠️ 시각은 {@code LocalDateTime.now()}(JVM 기본 시간대)다 — 백엔드가 같은 표의 다른
     * 시각 칸({@code crt_dt}·{@code del_dt})을 쓰는 방식과 맞춘다. 배치만 다른 시간대로 적으면
     * 같은 화면에서 두 칸이 어긋나 보인다.
     *
     * <p>⚠️ 갱신 실패는 <b>스킵이 아니다</b> — 보고서는 이미 만들어졌다. 그 사실을 못 적은 것은
     * 표시용 칸의 문제라 WARN 으로 드러내고 넘어간다(여기서 예외를 던지면 이미 만든 보고서를
     * "실패" 로 뒤집어 재실행이 200 만 받게 된다).
     */
    private void touchLastCrtDt(ReportAutoCrtCommand command) {
        try {
            Integer updated = requiresNew.execute(status -> jdbc.update(
                    UPDATE_LAST_CRT_DT_SQL,
                    Timestamp.valueOf(LocalDateTime.now()),
                    command.stngId()));
            if (updated == null || updated == 0) {
                // 설정이 그 사이 물리 삭제됐거나 stng_id 가 어긋났다. 보고서는 이미 있으므로 끊지 않는다.
                log.warn("last_crt_dt 를 갱신할 설정 행이 없습니다. stngId={}", command.stngId());
            }
        } catch (DataAccessException | TransactionException e) {
            // 시큐어코딩 4.3 — REQUIRES_NEW 트랜잭션 + JDBC UPDATE 실패만.
            log.warn("last_crt_dt 갱신에 실패했습니다(보고서 생성 자체는 성공). stngId={} 원인={}",
                    command.stngId(), e.getClass().getSimpleName(), e);
        }
    }

    /** 이번 실행에서 새로 만든 보고서 수(201). */
    public long createdCount() {
        return createdCount.get();
    }

    /** 이번 실행에서 이미 있어 건너뛴 수(200 {@code created=false}) — 재실행의 정상 경로다. */
    public long alreadyExistsCount() {
        return alreadyExistsCount.get();
    }

    /**
     * 카운터를 0 으로 되돌린다 — <b>Job 시작 시점에 호출한다</b>.
     *
     * <p>{@code @StepScope} 라 실행마다 새 인스턴스지만, 한 Job 안에서 Step 을 다시 태우는
     * 구성이 생기면 누계가 섞인다. 선례({@code RiskAlertSkipListener#resetStats})와 같은 계약을 둔다.
     */
    public void resetStats() {
        createdCount.set(0);
        alreadyExistsCount.set(0);
    }
}
