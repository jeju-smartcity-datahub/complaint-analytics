package jeju.datahub.batch.pblpklt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.test.MetaDataInstanceFactory;

import jeju.datahub.batch.pblpklt.api.DhubPblpkltItemReader;

/**
 * 🔴 가드 C({@code upstreamHealthy}) — 앞 Step 이 정상 종료하지 않았으면 소멸 마킹 UPDATE 를 <b>DB 에 보내지 않는다</b>
 * (TSK-4161 Step 13 가드 표 C). 1차 방어는 수집 Step FAILED → Job 이 이 Step 에 오지 않는 것이고, 이 가드는 두 번째 겹이다.
 *
 * <p>DB 없이 돈다 — 가드가 막으면 {@link DataSource} 에 한 번도 닿지 않아야 하고, 통과하면 커넥션을 요청해야 한다
 * (mock 커넥션 요청이 실패로 드러나는 것으로 "UPDATE 경로에 진입했다" 를 확인한다).
 */
class PblpkltSoftDeleteMarkingTaskletTest {

    @Test
    @DisplayName("🔴 앞 Step(수집)이 FAILED 면 seen set 이 있어도 UPDATE 하지 않는다 — DataSource 무접촉")
    void skipsMarkingWhenUpstreamStepFailed() {
        DataSource dataSource = mock(DataSource.class);
        PblpkltSoftDeleteMarkingTasklet tasklet = new PblpkltSoftDeleteMarkingTasklet(dataSource, readerSeeing("P-1"));
        StepExecution marking = markingStepAfter(BatchStatus.FAILED);

        RepeatStatus status = tasklet.execute(new StepContribution(marking), new ChunkContext(new StepContext(marking)));

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(dataSource);
        assertThat(marking.getExecutionContext().containsKey("pblpklt.extshMarkedCount")).isFalse();
    }

    @Test
    @DisplayName("앞 Step 이 COMPLETED 면 가드를 통과해 UPDATE 경로(커넥션 요청)로 들어간다")
    void proceedsToMarkingWhenUpstreamCompleted() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("no db in unit test"));
        PblpkltSoftDeleteMarkingTasklet tasklet = new PblpkltSoftDeleteMarkingTasklet(dataSource, readerSeeing("P-1"));
        StepExecution marking = markingStepAfter(BatchStatus.COMPLETED);

        assertThatThrownBy(() -> tasklet.execute(new StepContribution(marking),
                new ChunkContext(new StepContext(marking))))
                .hasRootCauseInstanceOf(SQLException.class);
        verify(dataSource).getConnection();
    }

    @Test
    void skipsMarkingWhenAnySourceAddressIsMissing() {
        DataSource dataSource = mock(DataSource.class);
        DhubPblpkltItemReader reader = readerSeeing("주소");
        when(reader.hasUnaddressedRows()).thenReturn(true);
        StepExecution marking = markingStepAfter(BatchStatus.COMPLETED);
        new PblpkltSoftDeleteMarkingTasklet(dataSource, reader).execute(
                new StepContribution(marking), new ChunkContext(new StepContext(marking)));
        verifyNoInteractions(dataSource);
    }

    @Test
    void skipsMarkingWhenUpstreamStoppedOrStillRunning() {
        for (BatchStatus status : new BatchStatus[] {BatchStatus.STOPPED, BatchStatus.STARTED, BatchStatus.STOPPING}) {
            DataSource dataSource = mock(DataSource.class);
            StepExecution marking = markingStepAfter(status);
            new PblpkltSoftDeleteMarkingTasklet(dataSource, readerSeeing("주소")).execute(
                    new StepContribution(marking), new ChunkContext(new StepContext(marking)));
            verifyNoInteractions(dataSource);
        }
    }

    private static DhubPblpkltItemReader readerSeeing(String... ids) {
        DhubPblpkltItemReader reader = mock(DhubPblpkltItemReader.class);
        when(reader.getSeenAddresses()).thenReturn(Set.of(ids));
        return reader;
    }

    /** 수집 Step(주어진 상태) 뒤에 도는 마킹 Step 실행 — 같은 JobExecution 에 둘 다 등록된다. */
    private static StepExecution markingStepAfter(BatchStatus fetchStatus) {
        JobExecution job = MetaDataInstanceFactory.createJobExecution();
        StepExecution fetch = job.createStepExecution("pblpkltFetchStep");
        fetch.setStatus(fetchStatus);
        StepExecution marking = job.createStepExecution("pblpkltSoftDeleteStep");
        marking.setStatus(BatchStatus.STARTED);
        return marking;
    }
}
