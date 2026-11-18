package jeju.datahub.batch.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.scheduling.TaskScheduler;

/** 실행별 로그 보존 정리 (TSK-4337 Step 5 · D7 90일). */
class JobLogRetentionTest {

    @TempDir
    Path logDir;

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");

    private final RunningJobRegistryListener running = new RunningJobRegistryListener();

    private JobLogRetention retention(int days) {
        JobLogService service = new JobLogService(mock(JobExplorer.class), running, logDir.toString());
        return new JobLogRetention(service, running, mock(TaskScheduler.class), logDir.toString(), days,
                "0 30 5 * * *", Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
    }

    private Path file(String job, String name, Duration age) throws Exception {
        Path dir = logDir.resolve("batch-jobs").resolve(job);
        Files.createDirectories(dir);
        Path f = Files.writeString(dir.resolve(name), "x\n");
        Files.setLastModifiedTime(f, FileTime.from(NOW.minus(age)));
        return f;
    }

    @Test
    @DisplayName("90일 초과만 삭제 · 90일 이내 유지 · 빈 잡 디렉터리 정리 · .log 아닌 파일 유지 · 실행 중 파일 유지")
    void deletesOlderThanRetention() throws Exception {
        Path old1 = file("crackdownFetchJob", "1.log", Duration.ofDays(91));
        Path old2 = file("riskAlertJudgeJob", "2.log", Duration.ofDays(200));
        Path fresh = file("crackdownFetchJob", "3.log", Duration.ofDays(89));
        Path other = file("crackdownFetchJob", "note.txt", Duration.ofDays(300));
        Path runningOld = file("zoneNmPrefillJob", "4.log", Duration.ofDays(120));
        running.beforeJob(new JobExecution(new JobInstance(4L, "zoneNmPrefillJob"), 4L, new JobParameters()));

        assertThat(retention(90).cleanup()).isEqualTo(2);

        assertThat(old1).doesNotExist();
        assertThat(old2).doesNotExist();
        assertThat(old2.getParent()).doesNotExist();          // riskAlertJudgeJob/ 가 비어 지워졌다
        assertThat(fresh).exists();
        assertThat(other).exists();
        assertThat(runningOld).exists();
        assertThat(logDir.resolve("batch-jobs")).isDirectory(); // base 는 지우지 않는다
    }

    @Test
    @DisplayName("보존 일수 1 미만이면 아무것도 지우지 않는다 · base 가 없어도 0")
    void guards() throws Exception {
        assertThat(retention(90).cleanup()).isZero();          // batch-jobs 없음
        Path old = file("crackdownFetchJob", "1.log", Duration.ofDays(1000));
        assertThat(retention(0).cleanup()).isZero();
        assertThat(old).exists();
    }
}
