package jeju.datahub.batch.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 실행별 로그 조회 API — {@code GET /api/batch/ops/executions/{id}/log} (TSK-4337 Step 5).
 * 실 컨트롤러·실 {@link JobLogService}·임시 디렉터리 파일, {@link JobExplorer} 만 대역이다.
 */
class JobLogApiTest {

    @TempDir
    Path logDir;

    private final JobExplorer explorer = mock(JobExplorer.class);
    private final RunningJobRegistryListener running = new RunningJobRegistryListener();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        JobLogService logService = new JobLogService(explorer, running, logDir.toString());
        BatchOpsService service = mock(BatchOpsService.class);
        mvc = MockMvcBuilders.standaloneSetup(new BatchOpsController(service, logService, new JobLiveMetrics()))
                .setControllerAdvice(new BatchOpsExceptionHandler()).build();
    }

    private void execution(long id, String jobNm) {
        when(explorer.getJobExecution(id)).thenReturn(new JobExecution(new JobInstance(id, jobNm), id, new JobParameters()));
    }

    private Path write(String jobNm, long id, String content) throws Exception {
        Path dir = logDir.resolve("batch-jobs").resolve(jobNm);
        Files.createDirectories(dir);
        return Files.writeString(dir.resolve(id + ".log"), content, StandardCharsets.UTF_8);
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("tailKb 생략 — 전체(200KB 미만) · text/plain UTF-8 · X-Next-Byte = 파일 크기 · X-Job-Running=false")
    void tailDefault() throws Exception {
        execution(7, "crackdownFetchJob");
        String text = "첫 줄 시작\n둘째 줄\n";
        write("crackdownFetchJob", 7, text);
        MvcResult r = mvc.perform(get("/api/batch/ops/executions/7/log"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"))
                .andExpect(header().string(BatchOpsController.HEADER_NEXT_BYTE,
                        String.valueOf(text.getBytes(StandardCharsets.UTF_8).length)))
                .andExpect(header().string(BatchOpsController.HEADER_JOB_RUNNING, "false"))
                .andReturn();
        assertThat(body(r)).isEqualTo(text);
    }

    @Test
    @DisplayName("tailKb=1 — 마지막 1KB · 잘린 앞줄은 버린다 · 줄 단위로 시작")
    void tailCutsLeadingPartial() throws Exception {
        execution(8, "riskAlertJudgeJob");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("line-").append(String.format("%04d", i)).append(" 한글로그\n");
        }
        write("riskAlertJudgeJob", 8, sb.toString());
        String out = body(mvc.perform(get("/api/batch/ops/executions/8/log").param("tailKb", "1"))
                .andExpect(status().isOk()).andReturn());
        assertThat(out.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1024);
        assertThat(out).startsWith("line-").endsWith("line-0199 한글로그\n");
        assertThat(out.split("\n")).allMatch(l -> l.matches("line-\\d{4} 한글로그"));
    }

    @Test
    @DisplayName("fromByte 증분 — 그 뒤만 · 실행 중이면 미완성 줄은 다음 번(마지막 개행까지) · 헤더로 이어 읽기")
    void incremental() throws Exception {
        execution(9, "crackdownFetchJob");
        Path file = write("crackdownFetchJob", 9, "a1\nb2\nc3-미완");
        running.beforeJob(new JobExecution(new JobInstance(9L, "crackdownFetchJob"), 9L, new JobParameters()));

        MvcResult r1 = mvc.perform(get("/api/batch/ops/executions/9/log").param("fromByte", "3"))
                .andExpect(status().isOk())
                .andExpect(header().string(BatchOpsController.HEADER_NEXT_BYTE, "6"))
                .andExpect(header().string(BatchOpsController.HEADER_JOB_RUNNING, "true"))
                .andReturn();
        assertThat(body(r1)).isEqualTo("b2\n");

        // 줄이 아직 완성되지 않았다 — 빈 응답, 위치 그대로
        MvcResult r2 = mvc.perform(get("/api/batch/ops/executions/9/log").param("fromByte", "6"))
                .andExpect(header().string(BatchOpsController.HEADER_NEXT_BYTE, "6")).andReturn();
        assertThat(body(r2)).isEmpty();

        Files.writeString(file, "a1\nb2\nc3-미완료\nd4\n", StandardCharsets.UTF_8);
        long size = Files.size(file);
        MvcResult r3 = mvc.perform(get("/api/batch/ops/executions/9/log").param("fromByte", "6"))
                .andExpect(header().string(BatchOpsController.HEADER_NEXT_BYTE, String.valueOf(size))).andReturn();
        assertThat(body(r3)).isEqualTo("c3-미완료\nd4\n");

        // 실행이 끝났으면 개행 없는 꼬리도 준다
        Files.writeString(file, "a1\nb2\nc3-미완료\nd4\n끝", StandardCharsets.UTF_8);
        running.afterJob(new JobExecution(new JobInstance(9L, "crackdownFetchJob"), 9L, new JobParameters()));
        MvcResult r4 = mvc.perform(get("/api/batch/ops/executions/9/log").param("fromByte", String.valueOf(size)))
                .andExpect(header().string(BatchOpsController.HEADER_JOB_RUNNING, "false")).andReturn();
        assertThat(body(r4)).isEqualTo("끝");
    }

    @Test
    @DisplayName("fromByte 1회 상한 256KB — 마지막 개행까지 · 다음 위치로 이어진다")
    void incrementalCap() throws Exception {
        execution(10, "crackdownFetchJob");
        String line = "x".repeat(99) + "\n";            // 100 바이트
        write("crackdownFetchJob", 10, line.repeat(3000)); // 300,000 바이트
        MvcResult r = mvc.perform(get("/api/batch/ops/executions/10/log").param("fromByte", "0")).andReturn();
        long next = Long.parseLong(r.getResponse().getHeader(BatchOpsController.HEADER_NEXT_BYTE));
        assertThat(next).isLessThanOrEqualTo(JobLogService.MAX_CHUNK_BYTES).isEqualTo(262_100L);
        assertThat(body(r)).endsWith("\n").hasSize(262_100);
        MvcResult r2 = mvc.perform(get("/api/batch/ops/executions/10/log").param("fromByte", String.valueOf(next)))
                .andReturn();
        assertThat(r2.getResponse().getHeader(BatchOpsController.HEADER_NEXT_BYTE)).isEqualTo("300000");
    }

    @Test
    @DisplayName("🔒 응답 마스킹 — 파일에 비밀이 있어도 값은 *** · 위치는 원본 바이트 기준")
    void masks() throws Exception {
        execution(11, "crackdownFetchJob");
        String text = "call url=https://x/req?key=TOP-SECRET Authorization: Bearer abc.def\npassword=hunter2\n";
        write("crackdownFetchJob", 11, text);
        MvcResult r = mvc.perform(get("/api/batch/ops/executions/11/log"))
                .andExpect(header().string(BatchOpsController.HEADER_NEXT_BYTE,
                        String.valueOf(text.getBytes(StandardCharsets.UTF_8).length)))
                .andReturn();
        assertThat(body(r)).doesNotContain("TOP-SECRET", "abc.def", "hunter2")
                .contains("key=***", "Bearer ***", "password=***");
    }

    @Test
    @DisplayName("400 — id 숫자 아님 INVALID_PATH · tailKb 범위·형식 · fromByte 음수·크기 초과 · 둘 다 지정 INVALID_PARAM")
    void badRequests() throws Exception {
        execution(12, "crackdownFetchJob");
        write("crackdownFetchJob", 12, "a\n");
        mvc.perform(get("/api/batch/ops/executions/abc/log")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PATH"));
        mvc.perform(get("/api/batch/ops/executions/..%2F..%2Fetc/log")).andExpect(status().isBadRequest());
        for (String tail : new String[] { "0", "1025", "-1" }) {
            mvc.perform(get("/api/batch/ops/executions/12/log").param("tailKb", tail))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAM"));
        }
        mvc.perform(get("/api/batch/ops/executions/12/log").param("tailKb", "x"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAM"));
        mvc.perform(get("/api/batch/ops/executions/12/log").param("fromByte", "-1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAM"));
        mvc.perform(get("/api/batch/ops/executions/12/log").param("fromByte", "999"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAM"));
        mvc.perform(get("/api/batch/ops/executions/12/log").param("fromByte", "0").param("tailKb", "1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAM"));
        mvc.perform(get("/api/batch/ops/executions/12/log").param("tailKb", "1024")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("404 두 갈래 — 실행 없음 EXECUTION_NOT_FOUND · 실행은 있는데 파일 없음 LOG_NOT_FOUND")
    void notFound() throws Exception {
        when(explorer.getJobExecution(anyLong())).thenReturn(null);
        mvc.perform(get("/api/batch/ops/executions/404/log")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXECUTION_NOT_FOUND"));
        execution(13, "crackdownFetchJob");
        mvc.perform(get("/api/batch/ops/executions/13/log")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOG_NOT_FOUND"));
    }

    @Test
    @DisplayName("🔴 경로 순회 차단 — 잡 이름이 경로 문자를 품으면 base 밖 파일이 있어도 404")
    void pathTraversal() throws Exception {
        // base(=logDir/batch-jobs) 밖에 같은 이름 파일을 만들어 둔다
        Files.writeString(logDir.resolve("14.log"), "outside secret\n");
        Files.createDirectories(logDir.resolve("batch-jobs"));
        execution(14, "..");
        mvc.perform(get("/api/batch/ops/executions/14/log")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOG_NOT_FOUND"));
        execution(15, "../../etc");
        mvc.perform(get("/api/batch/ops/executions/15/log")).andExpect(status().isNotFound());
        // 디렉터리는 파일이 아니다
        Files.createDirectories(logDir.resolve("batch-jobs/crackdownFetchJob/16.log"));
        execution(16, "crackdownFetchJob");
        mvc.perform(get("/api/batch/ops/executions/16/log")).andExpect(status().isNotFound());
    }
}
