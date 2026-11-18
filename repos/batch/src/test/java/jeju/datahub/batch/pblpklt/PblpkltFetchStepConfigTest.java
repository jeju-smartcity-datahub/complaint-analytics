package jeju.datahub.batch.pblpklt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 스텝 설정 회귀 — {@code processorNonTransactional()} 이 켜져 있는가.
 *
 * <h2>🔴 왜 설정 자체를 단언하나</h2>
 * 이 설정이 빠져도 <b>결과는 같다</b> — 스킵 스캔이 같은 청크의 앞선 항목을 Processor 로 다시 흘릴 뿐
 * 적재 행과 스킵 행은 그대로다. 그래서 기능 테스트로는 잡히지 않고, 지오코딩 API 호출만 조용히
 * 늘어난다(주차장 2026-09-12 실측 — 롤백 102회로 재처리가 3배). <b>이 테스트가 유일한 방어선이다.</b>
 *
 * <h2>reflection 을 쓰는 이유</h2>
 * Spring Batch 5.2.1 은 이 값을 읽는 공개 getter 가 없다. 그래서
 * {@code (TaskletStep) step → getTasklet() → chunkProcessor → processorTransactional} 경로를
 * {@link ReflectionTestUtils} 로 직접 본다. 상위 버전에서 필드명이 바뀌면 이 테스트가 먼저 깨지는데,
 * 그때는 <b>테스트를 지우지 말고</b> 새 경로로 고친다 — 지우면 방어선이 사라진다.
 *
 * <h2>단속도 함께 본다</h2>
 * {@code crackdownGeocodeStep}(단속의 지오코딩 Step — 수집 Step {@code crackdownFetchStep} 이 아니다)은
 * 때부터 같은 설정을 쓴다. 회귀하면 증상이 같으므로(주소 단위 재지오코딩) 두 스텝을 한 자리에서
 * 단언한다.
 *
 * <p>Job 을 돌리지 않는다 — 컨텍스트가 조립한 <b>빈의 모양</b>만 본다. 그래서 DB·외부 API 픽스처가 없다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // 테스트 컨텍스트에 02:00 크론이 등록될 필요는 없다.
        "complaint.sync.scheduled=false",
        "pblpklt.sync.scheduled=false",
        "utztn.sync.scheduled=false",
        "sftychck.sync.scheduled=false",
})
class PblpkltFetchStepConfigTest {

    @Autowired
    private ApplicationContext ctx;

    @Test
    @DisplayName("pblpkltFetchStep · crackdownGeocodeStep 은 processorNonTransactional 이다 — 청크 롤백 재처리 방지")
    void fetchStepsAreProcessorNonTransactional() {
        assertThat(processorTransactional("pblpkltFetchStep"))
                .as("pblpkltFetchStep 의 processorNonTransactional() 이 빠졌다 — 스킵 청크 재처리가 돌아온다")
                .isFalse();

        assertThat(processorTransactional("crackdownGeocodeStep"))
                .as("crackdownGeocodeStep 의 processorNonTransactional() 이 빠졌다 — 주소 재지오코딩이 돌아온다")
                .isFalse();
    }

    /** {@code TaskletStep → ChunkOrientedTasklet.chunkProcessor → processorTransactional}. */
    private boolean processorTransactional(String stepBeanName) {
        Step step = ctx.getBean(stepBeanName, Step.class);
        assertThat(step).as("%s 가 TaskletStep 이 아니다 — 청크 스텝 조립이 바뀌었다", stepBeanName)
                .isInstanceOf(TaskletStep.class);

        Object tasklet = ((TaskletStep) step).getTasklet();
        Object chunkProcessor = ReflectionTestUtils.getField(tasklet, "chunkProcessor");
        assertThat(chunkProcessor).as("%s 의 tasklet 에 chunkProcessor 필드가 없다", stepBeanName).isNotNull();

        Object value = ReflectionTestUtils.getField(chunkProcessor, "processorTransactional");
        assertThat(value)
                .as("%s 의 chunkProcessor 에 processorTransactional 필드가 없다 — "
                        + "faultTolerant() 가 빠졌거나 Spring Batch 내부 구조가 바뀌었다", stepBeanName)
                .isInstanceOf(Boolean.class);
        return (Boolean) value;
    }
}
