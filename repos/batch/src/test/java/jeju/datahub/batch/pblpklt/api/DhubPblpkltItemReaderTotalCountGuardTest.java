package jeju.datahub.batch.pblpklt.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import jeju.datahub.batch.cvlcpt.api.DhubApiException;
import jeju.datahub.batch.cvlcpt.api.DhubDatasetCount;
import jeju.datahub.batch.cvlcpt.api.DhubKeysetPage;
import jeju.datahub.batch.cvlcpt.api.DhubProperties;

/**
 * 총건수 교차검증 가드 회귀 (🔁 keyset 페이징 전환 후).
 *
 * <h2>무엇을 막는가</h2>
 * 페이징은 "중간까지만 받는 상태" 가 정상적으로 존재한다. 커서가 건너뛰고도 {@code hasMore=false} 로 끝나면 그대로
 * {@code COMPLETED} 되고, 같은 Job 의 소멸 마킹 Tasklet 이 "이번에 못 본 행" 을 지운다 — 2026-09-16 에 같은 성질의
 * 사고로 1,682행이 소멸 마킹됐다(job 685). 판정은 {@code open()} 안(= 청크 쓰기·소멸 마킹 Step 이전)에서 난다.
 *
 * <h2>🔴 세 진입점을 모두 검증한다</h2>
 * {@code open()}(무인자)·{@code open(ExecutionContext)}·{@code read()}(미오픈 시 open) — 한쪽에만 가드가 들어가면
 * 다른 경로로 들어올 때 조용히 통과한다.
 */
class DhubPblpkltItemReaderTotalCountGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DhubPblpkltApiClient client;
    private DhubProperties properties;
    private DhubPblpkltItemReader reader;

    @BeforeEach
    void setUp() {
        client = mock(DhubPblpkltApiClient.class);
        properties = new DhubProperties();
        properties.setEmail("user@test.local");
        properties.setPassword("pw");
        properties.setPblpkltSourceTable("`jeju_mini`.`tb_pblpklt`");
        reader = new DhubPblpkltItemReader(client, properties);
    }

    @Test
    @DisplayName("🔴 원천 총건수보다 적게 받으면 open() 에서 예외 — 한 행도 흘리지 않고 seen set 도 비어 있다")
    void throwsWhenReceivedFewerThanSourceTotal() {
        when(client.count()).thenReturn(2_272L);
        when(client.fetchPage(isNull(), anyInt())).thenReturn(page(true, "1", "10"));
        when(client.fetchPage(eq("10"), anyInt())).thenReturn(page(false, "100"));

        assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("전량 수집 실패")
                .hasMessageContaining("원천 2272행 중 3행");
        assertThat(reader.getSeenPkltIds()).as("부분 수집분이 소멸 마킹 근거로 남지 않는다").isEmpty();
    }

    @Test
    @DisplayName("🔴 무인자 open() 도 같은 가드를 탄다 — 본문 복제 시절엔 이쪽이 뚫려 있었다")
    void throwsOnNoArgOpenPathToo() {
        when(client.count()).thenReturn(2_272L);
        when(client.fetchPage(isNull(), anyInt())).thenReturn(page(false, "1"));

        assertThatThrownBy(() -> reader.open())
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("전량 수집 실패");
    }

    @Test
    @DisplayName("🔴 read() 로 진입해도 가드가 걸리고, 다음 read() 가 null(정상 종료)로 넘어가지 않는다")
    void throwsWhenEnteredThroughReadAndDoesNotSilentlyFinish() {
        when(client.count()).thenReturn(2_272L);
        when(client.fetchPage(isNull(), anyInt())).thenReturn(page(false, "1"));

        assertThatThrownBy(() -> reader.read())
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("전량 수집 실패");
        // 실패한 준비 상태를 "열림" 으로 남기면 빈 버퍼 → null → 부분 수집이 COMPLETED 로 보인다.
        assertThatThrownBy(() -> reader.read())
                .as("재진입도 다시 판정해 실패해야 한다")
                .isInstanceOf(DhubApiException.class);
    }

    @Test
    @DisplayName("🔴 페이지 중간 실패는 그대로 전파된다 — 앞 페이지 수신분을 흘리지 않는다")
    void propagatesMidPagingFailure() {
        when(client.count()).thenReturn(4L);
        when(client.fetchPage(isNull(), anyInt())).thenReturn(page(true, "1", "10"));
        when(client.fetchPage(eq("10"), anyInt()))
                .thenThrow(new DhubApiException("DHub 조회 실패(HTTP 500)."));

        assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("HTTP 500");
        assertThat(reader.getSeenPkltIds()).isEmpty();
    }

    @Test
    @DisplayName("🔴 page-size 0(종전 '전량' 관례)이면 어떤 호출도 하기 전에 실패한다 — LIMIT 0 · 0행 성공 금지")
    void zeroPageSizeFailsBeforeAnyCall() {
        properties.setPblpkltPageSize(0);

        assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dhub.pblpklt-page-size");
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    @DisplayName("총건수 확인 불가(UNAVAILABLE)면 행수와 무관하게 통과한다 — 교차검증은 best-effort 다")
    void passesWhenTotalUnavailable() throws Exception {
        when(client.count()).thenReturn(DhubDatasetCount.UNAVAILABLE);
        when(client.fetchPage(isNull(), anyInt())).thenReturn(page(false, "1", "2"));

        assertThatCode(() -> reader.open(new ExecutionContext())).doesNotThrowAnyException();

        assertThat(reader.read()).isNotNull();
        assertThat(reader.read()).isNotNull();
        assertThat(reader.read()).as("버퍼가 비면 null 로 Step 을 끝낸다").isNull();
    }

    @Test
    @DisplayName("총건수와 수신 행수가 같으면 통과 — 여러 페이지를 모두 모은 정상 경로다")
    void passesWhenCountsMatchAcrossPages() throws Exception {
        when(client.count()).thenReturn(3L);
        when(client.fetchPage(isNull(), anyInt())).thenReturn(page(true, "1", "10"));
        when(client.fetchPage(eq("10"), anyInt())).thenReturn(page(false, "100"));

        assertThatCode(() -> reader.open(new ExecutionContext())).doesNotThrowAnyException();
        assertThat(reader.getSeenPkltIds()).isEmpty();   // emit 전이므로 아직 빈 집합

        while (reader.read() != null) {
            // 전 행 emit
        }
        assertThat(reader.getSeenPkltIds()).containsExactlyInAnyOrder("1", "10", "100");
    }

    @Test
    @DisplayName("총건수보다 많이 받으면 통과 — 가드는 '적게 받은 것' 만 본다(총건수 질의와 수집 사이 원천 증가)")
    void passesWhenReceivedMoreThanTotal() {
        when(client.count()).thenReturn(2L);
        when(client.fetchPage(isNull(), anyInt())).thenReturn(page(false, "1", "2", "3"));
        when(client.fetchPage(anyString(), anyInt())).thenThrow(new AssertionError("마지막 페이지 뒤 추가 요청 금지"));

        assertThatCode(() -> reader.open(new ExecutionContext())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("🔴 원천이 hasMore 를 끝없이 주면 페이지 상한에서 끊는다 — 무한 루프 방지(시큐어코딩 3.2)")
    void stopsAtMaxPagesWhenSourceNeverEnds() {
        when(client.count()).thenReturn(DhubDatasetCount.UNAVAILABLE);
        when(client.fetchPage(any(), anyInt())).thenReturn(page(true));

        assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("페이지 상한");
        verify(client, times(DhubPblpkltItemReader.MAX_PAGES)).fetchPage(any(), anyInt());
        assertThat(reader.getSeenPkltIds()).isEmpty();
    }

    private static DhubKeysetPage page(boolean hasMore, String... pkltIds) {
        return new DhubKeysetPage(rows(pkltIds), hasMore, pkltIds.length == 0 ? null : pkltIds[pkltIds.length - 1]);
    }

    private static ArrayNode rows(String... pkltIds) {
        ArrayNode array = MAPPER.createArrayNode();
        for (String id : pkltIds) {
            array.addObject().put("pklt_id", id).put("pklt_addr", "제주특별자치도 제주시 " + id);
        }
        return array;
    }
}
