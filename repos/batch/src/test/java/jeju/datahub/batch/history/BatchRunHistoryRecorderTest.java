package jeju.datahub.batch.history;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

/**
 * {@code run_mode}/{@code target_range} 추출과 {@code failure_message} 안전장치의 단위 검증.
 *
 * <p>Job 을 띄우지 않는다 — 여기서 보는 것은 <b>파라미터 → 컬럼 값</b> 규칙 하나뿐이고, 그 규칙은
 * DB 도 배치 컨텍스트도 필요로 하지 않는다.
 */
class BatchRunHistoryRecorderTest {

    @Nested
    @DisplayName("run_mode")
    class RunMode {

        @Test
        @DisplayName("mode 파라미터가 있으면 그 값(소문자)")
        void usesModeParameter() {
            assertThat(BatchRunHistoryRecorder.runMode(params().addString("mode", "BACKFILL")
                    .toJobParameters())).isEqualTo("backfill");
        }

        @Test
        @DisplayName("mode 가 없으면 from/to → backfill, targetDate → daily, limit → manual")
        void infersFromOtherParameters() {
            assertThat(BatchRunHistoryRecorder.runMode(params()
                    .addString("from", "2025-01-01").addString("to", "2026-06-01")
                    .toJobParameters())).isEqualTo("backfill");
            assertThat(BatchRunHistoryRecorder.runMode(params()
                    .addString("targetDate", "2026-05-31").toJobParameters())).isEqualTo("daily");
            // limit 은 Long 파라미터다 — getString() 하나로는 읽히지 않는다.
            assertThat(BatchRunHistoryRecorder.runMode(params()
                    .addLong("limit", 200L).toJobParameters())).isEqualTo("manual");
        }

        @Test
        @DisplayName("🔴 파라미터가 runAt 뿐이면 NULL — 스케줄러 실행을 'manual' 로 적으면 이력이 거짓말한다")
        void returnsNullWhenNothingToInfer() {
            assertThat(BatchRunHistoryRecorder.runMode(params().toJobParameters())).isNull();
        }
    }

    @Nested
    @DisplayName("target_range")
    class TargetRange {

        @Test
        @DisplayName("from~to · 기준일 · limit=N 순으로 정한다")
        void formatsRange() {
            assertThat(BatchRunHistoryRecorder.targetRange(params()
                    .addString("from", "2025-01-01").addString("to", "2026-06-01")
                    .toJobParameters())).isEqualTo("2025-01-01~2026-06-01");
            // to 가 비면(= 실행일까지) 한쪽만 남긴다 — 구간이 열려 있다는 사실 자체가 정보다.
            assertThat(BatchRunHistoryRecorder.targetRange(params()
                    .addString("from", "2025-01-01").toJobParameters())).isEqualTo("2025-01-01~");
            assertThat(BatchRunHistoryRecorder.targetRange(params()
                    .addString("targetDate", "2026-05-31").toJobParameters())).isEqualTo("2026-05-31");
            assertThat(BatchRunHistoryRecorder.targetRange(params()
                    .addLong("limit", 200L).toJobParameters())).isEqualTo("limit=200");
            assertThat(BatchRunHistoryRecorder.targetRange(params().toJobParameters())).isNull();
        }

        @Test
        @DisplayName("컬럼 길이(50)를 넘기지 않는다")
        void neverExceedsColumnLength() {
            String range = BatchRunHistoryRecorder.targetRange(params()
                    .addString("from", "2025-01-01".repeat(4))
                    .addString("to", "2026-06-01".repeat(4))
                    .toJobParameters());
            assertThat(range).hasSizeLessThanOrEqualTo(BatchRunHistoryRecorder.MAX_TARGET_RANGE_CHARS)
                    .endsWith("...");
        }
    }

    @Nested
    @DisplayName("🔒 failure_message 마스킹")
    class Redaction {

        @Test
        @DisplayName("key/token/password 값은 *** 로 가린다")
        void masksSecrets() {
            assertThat(BatchRunHistoryRecorder.redact(
                    "GET https://api.vworld.kr/req/address?key=TOP-SECRET-123 실패"))
                    .contains("key=***").doesNotContain("TOP-SECRET-123");
            assertThat(BatchRunHistoryRecorder.redact("Authorization: Bearer eyJhbGciOiJIUzI1"))
                    .doesNotContain("eyJhbGciOiJIUzI1");
            assertThat(BatchRunHistoryRecorder.redact("password: hunter2"))
                    .isEqualTo("password: ***");
        }

        @Test
        @DisplayName("평범한 메시지는 건드리지 않는다")
        void keepsOrdinaryText() {
            assertThat(BatchRunHistoryRecorder.redact("VWorld 호출 한도 초과(OVER_REQUEST_LIMIT)"))
                    .isEqualTo("VWorld 호출 한도 초과(OVER_REQUEST_LIMIT)");
        }
    }

    @Nested
    @DisplayName("TSK-4337 excn_se_cd · excutr_id · up_excn_id")
    class ExcnSeColumns {

        @Test
        @DisplayName("파라미터가 없으면 세 칸 모두 null")
        void nullWhenAbsent() {
            JobParameters p = params().toJobParameters();
            assertThat(BatchRunHistoryRecorder.excnSe(p)).isNull();
            assertThat(BatchRunHistoryRecorder.excutrId(p)).isNull();
            assertThat(BatchRunHistoryRecorder.prntExcnId(p)).isNull();
        }

        @Test
        @DisplayName("연쇄 — CHAIN + 앞 잡 ID, 비식별로 실린다")
        void readsChain() {
            JobParameters p = ExcnSe.chain(params(), 77L).toJobParameters();
            assertThat(BatchRunHistoryRecorder.excnSe(p)).isEqualTo(ExcnSe.CHAIN);
            assertThat(BatchRunHistoryRecorder.prntExcnId(p)).isEqualTo(77L);
            assertThat(BatchRunHistoryRecorder.excutrId(p)).isNull();
            // 🔴 식별 파라미터로 들어가면 JobInstance 판정이 바뀐다
            assertThat(p.getParameter(ExcnSe.PARAM_EXCN_SE).isIdentifying()).isFalse();
            assertThat(p.getParameter(ExcnSe.PARAM_PRNT_EXCN_ID).isIdentifying()).isFalse();
        }

        @Test
        @DisplayName("수동 — MANUAL + 실행자, 비식별")
        void readsManual() {
            JobParameters p = ExcnSe.manual(params(), "user01").toJobParameters();
            assertThat(BatchRunHistoryRecorder.excnSe(p)).isEqualTo(ExcnSe.MANUAL);
            assertThat(BatchRunHistoryRecorder.excutrId(p)).isEqualTo("user01");
            assertThat(p.getParameter(ExcnSe.PARAM_EXCUTR_ID).isIdentifying()).isFalse();
        }

        @Test
        @DisplayName("prntExcnId 가 숫자가 아니면 그 칸만 null(예외 없음)")
        void nullWhenPrntNotNumeric() {
            JobParameters p = params().addString(ExcnSe.PARAM_PRNT_EXCN_ID, "abc", false)
                    .toJobParameters();
            assertThat(BatchRunHistoryRecorder.prntExcnId(p)).isNull();
        }

        @Test
        @DisplayName("prntExcnId 가 문자열 숫자여도 읽힌다")
        void readsStringPrnt() {
            JobParameters p = params().addString(ExcnSe.PARAM_PRNT_EXCN_ID, "12", false)
                    .toJobParameters();
            assertThat(BatchRunHistoryRecorder.prntExcnId(p)).isEqualTo(12L);
        }

        @Test
        @DisplayName("excutrId 가 20자를 넘으면 ... 없이 앞 20자")
        void truncatesExcutrId() {
            JobParameters p = ExcnSe.manual(params(), "a".repeat(25)).toJobParameters();
            assertThat(BatchRunHistoryRecorder.excutrId(p)).isEqualTo("a".repeat(20));
        }
    }

    /** 모든 실행이 갖는 유일 파라미터. */
    private static JobParametersBuilder params() {
        return new JobParametersBuilder().addLong("runAt", 1L);
    }

    @Test
    @DisplayName("빈 파라미터도 안전하게 처리한다")
    void handlesEmptyParameters() {
        JobParameters empty = new JobParameters();
        assertThat(BatchRunHistoryRecorder.runMode(empty)).isNull();
        assertThat(BatchRunHistoryRecorder.targetRange(empty)).isNull();
    }
}
