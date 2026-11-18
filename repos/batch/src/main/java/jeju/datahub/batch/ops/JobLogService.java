package jeju.datahub.batch.ops;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import jeju.datahub.batch.history.SecretMasker;

/**
 * 실행별 텍스트 로그 조회 — {@code GET /api/batch/ops/executions/{id}/log}.
 *
 * <h2>경로</h2>
 * {@code {batch.ops.log-dir}/batch-jobs/{jobNm}/{jobExecutionId}.log} — {@code log4j2-spring.xml} 의 Routing appender 와
 * 같은 모양이다. 🔴 경로는 요청 값이 아니라 <b>숫자 ID + {@link JobExplorer} 가 준 잡 이름</b>으로만 조립하고, 정규화 뒤
 * {@code batch-jobs} 하위인지 다시 확인한다(경로 순회 차단).
 * 🔴 {@code batch.ops.log-dir} 는 Log4j2 {@code LOG_PATH} 와 같은 {@code LOG_DIR}·같은 기본값 {@code logs} 를 읽는다 — 갈리면
 * 파일이 있어도 404 다. 기동 시 {@link JobLogRetention} 이 일치 여부를 1줄 남긴다.
 *
 * <h2>두 방식</h2>
 * <ul>
 *   <li>{@code tailKb}(기본 {@value #DEFAULT_TAIL_KB}, {@value #MIN_TAIL_KB}~{@value #MAX_TAIL_KB}) — 마지막 N KB. 앞쪽 잘린 줄은 버린다.</li>
 *   <li>{@code fromByte} — 그 바이트 뒤 증분만, 1회 최대 {@value #MAX_CHUNK_BYTES} 바이트(실시간 로그 2초 폴링).</li>
 * </ul>
 * 두 방식 모두 🔴 <b>마지막 개행까지만</b> 돌려준다(줄 중간 절단 금지) — 응답 {@code nextByte} 가 다음 {@code fromByte} 다.
 * 예외: 실행이 끝났고 파일 끝까지 읽었으면 개행 없는 꼬리도 준다(더 이어질 줄이 없다) · 개행 없는 한 줄이 한 번에 읽는
 * 크기를 넘으면 그대로 준다(멈춤 방지).
 *
 * <h2>🔒 마스킹</h2>
 * 응답 직전에 {@link SecretMasker} 를 한 번 더 건다 — 파일 자체도 {@code %mask{}} 로 찍히지만 그 이전 파일·다른 경로로
 * 들어온 줄까지 막는 이중 장치. ⚠️ 그래서 {@code content} 길이와 {@code nextByte} 증가량은 다를 수 있다 — 위치는
 * 항상 <b>원본 파일 바이트</b> 기준이다.
 */
@Component
public class JobLogService {

    public static final int DEFAULT_TAIL_KB = 200;
    public static final int MIN_TAIL_KB = 1;
    public static final int MAX_TAIL_KB = 1024;
    /** 증분 1회 상한 256KB. */
    public static final int MAX_CHUNK_BYTES = 256 * 1024;

    /** 실행별 로그 하위 디렉터리 — {@code log4j2-spring.xml} Routing 의 {@code batch-jobs} 와 같아야 한다. */
    public static final String JOB_LOG_SUBDIR = "batch-jobs";

    /** 잡 이름은 Spring Bean 이름(영숫자)뿐이다 — 그 밖의 문자가 오면 경로에 쓰지 않는다. */
    private static final Pattern SAFE_JOB_NM = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    private final JobExplorer jobExplorer;
    private final RunningJobRegistryListener runningRegistry;
    private final Path baseDir;

    public JobLogService(JobExplorer jobExplorer, RunningJobRegistryListener runningRegistry,
            @Value("${batch.ops.log-dir:logs}") String logDir) {
        this.jobExplorer = jobExplorer;
        this.runningRegistry = runningRegistry;
        this.baseDir = Paths.get(logDir).toAbsolutePath().normalize().resolve(JOB_LOG_SUBDIR);
    }

    /** {@code {log-dir}/batch-jobs} 절대 경로. */
    public Path baseDir() {
        return baseDir;
    }

    /** 응답 한 덩어리. */
    public record LogChunk(String content, long nextByte, boolean jobRunning) {
    }

    /**
     * @param tailKb   null 이면 기본 {@value #DEFAULT_TAIL_KB}
     * @param fromByte null 이 아니면 증분 모드
     * @throws BatchOpsException 400 {@code INVALID_PARAM} · 404 {@code EXECUTION_NOT_FOUND} / {@code LOG_NOT_FOUND}
     */
    public LogChunk read(long jobExecutionId, Integer tailKb, Long fromByte) {
        if (tailKb != null && fromByte != null) {
            throw BatchOpsException.badRequest("INVALID_PARAM", "tailKb 와 fromByte 는 함께 쓸 수 없습니다");
        }
        if (tailKb != null && (tailKb < MIN_TAIL_KB || tailKb > MAX_TAIL_KB)) {
            throw BatchOpsException.badRequest("INVALID_PARAM",
                    "tailKb 는 " + MIN_TAIL_KB + "~" + MAX_TAIL_KB + " 사이여야 합니다: " + tailKb);
        }
        if (fromByte != null && fromByte < 0) {
            throw BatchOpsException.badRequest("INVALID_PARAM", "fromByte 는 0 이상이어야 합니다: " + fromByte);
        }
        JobExecution execution = jobExplorer.getJobExecution(jobExecutionId);
        if (execution == null) {
            throw BatchOpsException.notFound("EXECUTION_NOT_FOUND", "없는 실행입니다: " + jobExecutionId);
        }
        Path file = resolve(execution.getJobInstance().getJobName(), jobExecutionId);
        boolean running = runningRegistry.isRunning(jobExecutionId);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            if (fromByte != null) {
                if (fromByte > size) {
                    throw BatchOpsException.badRequest("INVALID_PARAM",
                            "fromByte 가 로그 크기(" + size + ")를 넘습니다: " + fromByte);
                }
                return chunk(channel, fromByte, Math.min(size, fromByte + MAX_CHUNK_BYTES), size, false, running);
            }
            long want = (long) (tailKb == null ? DEFAULT_TAIL_KB : tailKb) * 1024L;
            long start = Math.max(0, size - want);
            return chunk(channel, start, size, size, start > 0 && !lineStart(channel, start), running);
        } catch (NoSuchFileException e) {
            throw logNotFound(jobExecutionId);
        } catch (IOException e) {
            throw new BatchOpsException(HttpStatus.INTERNAL_SERVER_ERROR, "LOG_READ_FAILED",
                    "로그 파일을 읽지 못했습니다(실행 #" + jobExecutionId + "): " + e.getClass().getSimpleName());
        }
    }

    /**
     * 경로 조립 — 잡 이름 허용 문자 검사 + 정규화 뒤 base 하위 확인.
     *
     * @throws BatchOpsException 404 {@code LOG_NOT_FOUND}(잡 이름이 경로로 쓸 수 없는 값이면 파일도 없다고 본다)
     */
    Path resolve(String jobNm, long jobExecutionId) {
        if (jobNm == null || !SAFE_JOB_NM.matcher(jobNm).matches()) {
            throw logNotFound(jobExecutionId);
        }
        Path file = baseDir.resolve(jobNm).resolve(jobExecutionId + ".log").normalize();
        if (!file.startsWith(baseDir) || !Files.isRegularFile(file)) {
            throw logNotFound(jobExecutionId);
        }
        return file;
    }

    private static BatchOpsException logNotFound(long jobExecutionId) {
        return BatchOpsException.notFound("LOG_NOT_FOUND",
                "로그 없음 — 로그 기능 도입 전 실행이거나 보존기간이 지나 정리됐습니다(실행 #" + jobExecutionId + ")");
    }

    /**
     * [start, end) 를 읽어 줄 경계로 다듬는다.
     *
     * @param dropLeadingPartial true 면 첫 개행까지(잘린 앞줄)를 버린다 — tail 이 파일 중간에서 시작할 때
     */
    private static LogChunk chunk(FileChannel channel, long start, long end, long size,
            boolean dropLeadingPartial, boolean running) throws IOException {
        int len = (int) (end - start);
        ByteBuffer buf = ByteBuffer.allocate(len);
        while (buf.hasRemaining()) {
            int n = channel.read(buf, start + buf.position());
            if (n < 0) {
                break;
            }
        }
        byte[] bytes = buf.array();
        int read = buf.position();

        int from = 0;
        if (dropLeadingPartial) {
            int nl = indexOf(bytes, read, (byte) '\n');
            from = nl < 0 ? read : nl + 1;
        }
        int to;
        boolean atEof = start + read >= size;
        if (!running && atEof) {
            to = read;                                   // 끝난 실행의 마지막 꼬리 — 더 이어질 줄이 없다
        } else {
            int lastNl = lastIndexOf(bytes, from, read, (byte) '\n');
            if (lastNl >= 0) {
                to = lastNl + 1;
            } else if (read - from >= MAX_CHUNK_BYTES) {
                to = read;                               // 한 줄이 상한보다 길다 — 멈추지 않게 그대로 준다
            } else {
                to = from;                               // 아직 줄이 완성되지 않았다 — 다음 폴링에서
            }
        }
        String text = new String(bytes, from, to - from, StandardCharsets.UTF_8);
        return new LogChunk(SecretMasker.mask(text), start + to, running);
    }

    /** {@code pos} 가 줄 첫 바이트인가(바로 앞 바이트가 개행) — 그렇다면 tail 의 첫 줄을 버리지 않는다. */
    private static boolean lineStart(FileChannel channel, long pos) throws IOException {
        ByteBuffer one = ByteBuffer.allocate(1);
        return channel.read(one, pos - 1) == 1 && one.get(0) == '\n';
    }

    private static int indexOf(byte[] bytes, int len, byte target) {
        for (int i = 0; i < len; i++) {
            if (bytes[i] == target) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(byte[] bytes, int from, int len, byte target) {
        for (int i = len - 1; i >= from; i--) {
            if (bytes[i] == target) {
                return i;
            }
        }
        return -1;
    }
}
