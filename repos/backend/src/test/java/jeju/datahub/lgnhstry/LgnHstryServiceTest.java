package jeju.datahub.lgnhstry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jeju.datahub.common.SesnIdHasher;
import jeju.datahub.lgnhstry.service.LgnHstryService;
import jeju.datahub.lgnhstry.service.impl.LgnHstryServiceImpl;

/**
 * 로그인 이력 기록 서비스 통합 테스트.
 *
 * <p>이 레포에는 H2·Testcontainers 가 없다(build.gradle 실측 — starter-test 만) — {@code @SpringBootTest}
 * 는 <b>실 PostgreSQL</b>({@code ./local.sh infra} 로 띄운 로컬 DB)에 붙는다.
 *
 * <p>🔴 <b>{@code @Transactional} 롤백을 쓰지 않는다.</b> 검증 대상이 {@code REQUIRES_NEW} 자체라
 * 테스트를 트랜잭션으로 감싸면 "롤백돼도 남는다"는 회귀 테스트가 성립하지 않는다. 대신 실제로 커밋하고
 * {@link #cleanUp()} 이 <b>이 테스트가 만든 행만</b>({@code lgn_sn > startSn}) 지운다 — 기존 데이터는 건드리지 않는다.
 *
 * <p>🔴 <b>픽스처 규격</b>: {@code sesnIdHash} 는 반드시 {@link SesnIdHasher#shortHash(String)} 로 만든다.
 * {@code "test-session"} 같은 리터럴은 DDL 의 {@code CHECK (length(sesn_id) = 16)} 에 걸려 INSERT 가 실패한다.
 */
@SpringBootTest
class LgnHstryServiceTest {

    private static final String USER_ID = "tst-lgn";
    private static final String IP = "127.0.0.1";

    @Autowired
    private LgnHstryService lgnHstryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 이 테스트가 만든 행의 경계. 이 값보다 큰 lgn_sn 만 정리한다. */
    private long startSn;

    private final WarnCapturingAppender warnAppender = new WarnCapturingAppender();

    @BeforeEach
    void markStart() {
        startSn = jdbcTemplate.queryForObject(
                "SELECT coalesce(max(lgn_sn), 0) FROM parking.tb_lgn_hstry", Long.class);
        warnAppender.start();
        ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(LgnHstryServiceImpl.class))
                .addAppender(warnAppender);
    }

    @AfterEach
    void cleanUp() {
        ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(LgnHstryServiceImpl.class))
                .removeAppender(warnAppender);
        warnAppender.stop();
        jdbcTemplate.update("DELETE FROM parking.tb_lgn_hstry WHERE lgn_sn > ?", startSn);
    }

    // ---------------------------------------------------------------- 기록

    @Test
    @DisplayName("1. 성공 기록 — lgn_yn='Y' · cntn_end_dt IS NULL 행 1건")
    void recordSuccess_savesOpenRow() {
        String hash = SesnIdHasher.shortHash("any-session-id");

        lgnHstryService.recordSuccess(USER_ID, IP, hash);

        List<Map<String, Object>> rows = newRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("user_id", USER_ID)
                .containsEntry("lgn_yn", "Y")
                .containsEntry("sesn_id", hash)
                .containsEntry("lgn_ip_addr", IP);
        assertThat(rows.get(0).get("cntn_end_dt")).isNull();
        assertThat(hash).hasSize(16);   // CHECK 계약
    }

    @Test
    @DisplayName("2. 성공 기록 — 세션 해시가 null 이면 행은 남고 sesn_id 만 NULL + WARN")
    void recordSuccess_nullHash_keepsRowWithNullSesnId() {
        lgnHstryService.recordSuccess(USER_ID, IP, null);

        List<Map<String, Object>> rows = newRows();
        assertThat(rows).hasSize(1);                       // 🔴 행 자체를 버리지 않는다
        assertThat(rows.get(0).get("sesn_id")).isNull();
        assertThat(rows.get(0)).containsEntry("lgn_yn", "Y");
        assertThat(warnAppender.messages()).anyMatch(m -> m.contains("[lgn-hstry]"));
    }

    @Test
    @DisplayName("3. 실패 기록 — user_id 가 null 이어도 저장된다 (회귀)")
    void recordFailure_nullUserId_isSaved() {
        lgnHstryService.recordFailure(null, IP, "STATE_MISMATCH");

        List<Map<String, Object>> rows = newRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("user_id")).isNull();
        assertThat(rows.get(0)).containsEntry("lgn_yn", "N")
                .containsEntry("lgn_fail_rsn", "STATE_MISMATCH");
        assertThat(rows.get(0).get("sesn_id")).isNull();   // 실패에는 확정된 세션이 없다
    }

    @Test
    @DisplayName("4. 실패 사유는 사유 코드로 마스킹되고 200자를 넘지 않는다")
    void recordFailure_masksReason() {
        String leaky = "org.springframework.security.oauth2.core.OAuth2AuthenticationException: "
                + "token_exchange_failed access_token=eyJhbGciOiJIUzI1NiJ9.SECRET "
                + "at https://idp.example.com/oauth2/token?client_secret=abcdefg "
                + "x".repeat(500);

        lgnHstryService.recordFailure(USER_ID, IP, leaky);
        lgnHstryService.recordFailure(USER_ID, IP, "무엇인지 알 수 없는 원문 메시지");

        List<Map<String, Object>> rows = newRows();
        assertThat(rows).hasSize(2);
        List<String> codes = List.of("STATE_MISMATCH", "SUB_MISMATCH", "USERINFO_EMPTY",
                "USER_DISABLED", "TOKEN_EXCHANGE_FAILED", "UNKNOWN");
        for (Map<String, Object> row : rows) {
            String rsn = (String) row.get("lgn_fail_rsn");
            assertThat(rsn).isIn(codes);
            assertThat(rsn.length()).isLessThanOrEqualTo(200);
            assertThat(rsn).doesNotContain("access_token").doesNotContain("client_secret");
        }
        assertThat((String) rows.get(0).get("lgn_fail_rsn")).isEqualTo("TOKEN_EXCHANGE_FAILED");
        assertThat((String) rows.get(1).get("lgn_fail_rsn")).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("5. 🔴 호출부 트랜잭션이 롤백돼도 이력은 남는다 (REQUIRES_NEW 회귀)")
    void record_survivesCallerRollback() {
        new TransactionTemplate(transactionManager).execute(status -> {
            lgnHstryService.recordFailure(USER_ID, IP, "USER_DISABLED");
            lgnHstryService.recordSuccess(USER_ID, IP, SesnIdHasher.shortHash("rollback-session"));
            status.setRollbackOnly();          // 호출부 트랜잭션 전체 롤백
            return null;
        });

        List<Map<String, Object>> rows = newRows();
        assertThat(rows).hasSize(2);           // 별도 트랜잭션이라 살아남는다
        assertThat(rows.get(0)).containsEntry("lgn_yn", "N");
        assertThat(rows.get(1)).containsEntry("lgn_yn", "Y");
    }

    // ---------------------------------------------------------------- 접속 종료

    @Test
    @DisplayName("6. closeSession — 같은 세션의 가장 최근 1건만 채우고 이전 행은 그대로")
    void closeSession_fillsOnlyLatestRow() {
        String hash = SesnIdHasher.shortHash("reused-session-id");
        lgnHstryService.recordSuccess(USER_ID, IP, hash);
        lgnHstryService.recordSuccess(USER_ID, IP, hash);

        // 앞 행을 1시간 전으로 밀어 "가장 최근"을 결정적으로 만든다
        Long olderSn = jdbcTemplate.queryForObject(
                "SELECT min(lgn_sn) FROM parking.tb_lgn_hstry WHERE lgn_sn > ?", Long.class, startSn);
        jdbcTemplate.update(
                "UPDATE parking.tb_lgn_hstry SET lgn_dt = lgn_dt - interval '1 hour' WHERE lgn_sn = ?", olderSn);

        lgnHstryService.closeSession(hash);

        List<Map<String, Object>> rows = newRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("cntn_end_dt")).as("이전 행은 그대로 열려 있다").isNull();
        assertThat(rows.get(1).get("cntn_end_dt")).as("가장 최근 행만 닫힌다").isNotNull();
    }

    @Test
    @DisplayName("7. closeSession — 세션 해시가 없으면 no-op + WARN (남의 행을 닫지 않는다)")
    void closeSession_blankHash_isNoOp() {
        String hash = SesnIdHasher.shortHash("kept-open-session");
        lgnHstryService.recordSuccess(USER_ID, IP, hash);

        lgnHstryService.closeSession(null);
        lgnHstryService.closeSession("-");

        List<Map<String, Object>> rows = newRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("cntn_end_dt")).isNull();
        assertThat(warnAppender.messages()).anyMatch(m -> m.contains("[lgn-hstry]"));
    }

    // ---------------------------------------------------------------- helper

    /** 이 테스트가 만든 행만 lgn_sn 오름차순으로 읽는다. */
    private List<Map<String, Object>> newRows() {
        return jdbcTemplate.queryForList(
                "SELECT * FROM parking.tb_lgn_hstry WHERE lgn_sn > ? ORDER BY lgn_sn", startSn);
    }

    /**
     * WARN 로그 확인용 인메모리 Log4j2 appender.
     *
     * <p>{@code OutputCaptureExtension} 을 쓰지 않는 이유 — Log4j2 Console appender 는 기본
     * {@code follow=false} 라 설정 시점의 {@code System.out} 을 붙잡는다. 캡처 확장이 스트림을
     * 갈아끼워도 로그가 그리로 가지 않아 단언이 조용히 실패한다.
     */
    private static final class WarnCapturingAppender extends AbstractAppender {

        private final List<String> messages = Collections.synchronizedList(new ArrayList<>());

        private WarnCapturingAppender() {
            super("lgnHstryWarnCapture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        }

        List<String> messages() {
            return List.copyOf(messages);
        }
    }
}
