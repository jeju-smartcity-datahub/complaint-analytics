package jeju.datahub.batch.pblpklt.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import jeju.datahub.batch.cvlcpt.api.DhubKeysetPage;
import jeju.datahub.batch.cvlcpt.api.DhubProperties;

/**
 * 🔴 B 회귀 (TSK-4142 §10-1) — {@code close()} 후에도 seen set 이 살아 있어야 한다.
 *
 * <h2>왜 이런 테스트가 필요한가</h2>
 * {@link org.springframework.batch.item.ItemStream#close()} 는 <b>수집 Step 이 끝나는 시점</b>에
 * 불리고, 소멸 마킹 Tasklet({@code PblpkltSoftDeleteMarkingTasklet})은 그 <b>뒤에 도는 별도
 * Step</b> 이다. {@code close()} 가 seen set 을 비우면 마킹 Step 이 항상 빈 집합을 받아
 * "seenPkltIds 가 비어 있습니다" WARN 으로 조기 반환한다 — 소멸 마킹이 매 실행 무동작이 된다
 * (1회차 E2E 실측). 단위 컴파일로는 드러나지 않아 테스트로 못박는다.
 *
 * <p>직전 실행 잔여가 다음 실행에 섞일 위험은 없다 — {@code open()} 이 <b>매번</b>
 * {@code new HashSet<>} 으로 재할당하기 때문이며, 그 사실도 아래에서 함께 단언한다.
 */
class DhubPblpkltItemReaderSeenSetTest {

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
    @DisplayName("close() 후에도 seen set 이 비지 않는다 — 뒤따르는 소멸 마킹 Step 이 이 값을 본다")
    void seenSetSurvivesClose() throws Exception {
        when(client.fetchPage(isNull(), anyInt())).thenReturn(lastPage("1", "2", "3"));

        reader.open(new org.springframework.batch.item.ExecutionContext());
        while (reader.read() != null) {
            // 전 행 emit
        }
        assertThat(reader.getSeenPkltIds()).containsExactlyInAnyOrder("1", "2", "3");

        reader.close();
        assertThat(reader.getSeenAddresses()).containsExactlyInAnyOrder(
                "제주특별자치도 제주시 1", "제주특별자치도 제주시 2", "제주특별자치도 제주시 3");

        assertThat(reader.getSeenPkltIds())
                .as("close() 가 seen set 을 비우면 소멸 마킹이 매 실행 무동작이 된다")
                .containsExactlyInAnyOrder("1", "2", "3");
    }

    @Test
    @DisplayName("다음 open() 이 seen set 을 새로 갈아끼운다 — 직전 실행 잔여가 섞이지 않는다")
    void nextOpenReplacesSeenSet() throws Exception {
        when(client.fetchPage(isNull(), anyInt())).thenReturn(lastPage("1", "2"));
        reader.open(new org.springframework.batch.item.ExecutionContext());
        while (reader.read() != null) {
            // 1회차 emit
        }
        reader.close();
        assertThat(reader.getSeenPkltIds()).containsExactlyInAnyOrder("1", "2");

        when(client.fetchPage(isNull(), anyInt())).thenReturn(lastPage("9"));
        reader.open(new org.springframework.batch.item.ExecutionContext());

        assertThat(reader.getSeenAddresses()).isEmpty();
        assertThat(reader.getSeenPkltIds())
                .as("open() 이 new HashSet<> 으로 재할당하므로 close() 가 비우지 않아도 stale 이 남지 않는다")
                .isEmpty();

        while (reader.read() != null) {
            // 2회차 emit
        }
        assertThat(reader.getSeenPkltIds()).containsExactly("9");
    }

    @Test
    void remembersAnAddressEvenWhenSourceIdsChangeAndFlagsMissingAddresses() throws Exception {
        ArrayNode source = MAPPER.createArrayNode();
        source.addObject().put("pklt_id", "1").put("pklt_addr", "같은 주소");
        source.addObject().put("pklt_id", "9").put("pklt_addr", "같은 주소");
        source.addObject().put("pklt_id", "10").putNull("pklt_addr");
        when(client.fetchPage(isNull(), anyInt())).thenReturn(new DhubKeysetPage(source, false, "10"));
        reader.open();
        while (reader.read() != null) { }
        reader.close();
        assertThat(reader.getSeenAddresses()).containsExactly("같은 주소");
        assertThat(reader.hasUnaddressedRows()).isTrue();
        reader.open();
        assertThat(reader.getSeenAddresses()).isEmpty();
        assertThat(reader.hasUnaddressedRows()).isFalse();
    }

    /** 한 페이지로 끝나는 응답 — hasMore=false. */
    private static DhubKeysetPage lastPage(String... pkltIds) {
        return new DhubKeysetPage(rows(pkltIds), false, pkltIds.length == 0 ? null : pkltIds[pkltIds.length - 1]);
    }

    private static ArrayNode rows(String... pkltIds) {
        ArrayNode array = MAPPER.createArrayNode();
        for (String id : pkltIds) {
            array.addObject().put("pklt_id", id).put("pklt_addr", "제주특별자치도 제주시 " + id);
        }
        return array;
    }
}
