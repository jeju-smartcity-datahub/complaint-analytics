package jeju.datahub.batch.utztn.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import jeju.datahub.batch.cvlcpt.api.DhubProperties;
import jeju.datahub.batch.cvlcpt.api.DhubQueryClient;

/**
 * 시큐어코딩 3.2 — 이용률 원천 조회의 페이지 루프는 상한을 반복 조건에 두고,
 * 상한에 닿아 끝나면 덜 받은 채 성공하지 않고 예외로 끊는다.
 */
class DhubPkltUtztnApiClientPageLimitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("주차장 목록 — 원천이 끝없이 가득 찬 페이지를 주면 MAX_PAGES 번만 조회하고 페이지 상한 예외")
    void lotListStopsAtPageLimit() {
        DhubQueryClient queryClient = mock(DhubQueryClient.class);
        AtomicLong mi = new AtomicLong();
        // 페이지 크기 1 · 매번 1행(= 가득 참) · 커서(mi)는 전진 → 커서 미전진 가드로는 끝나지 않는 상황
        when(queryClient.query(anyString(), anyString(), anyInt())).thenAnswer(inv -> {
            ArrayNode rows = MAPPER.createArrayNode();
            rows.addObject().put("pklt_nm", "주차장").put("pklt_addr", "제주시").put("mi", mi.incrementAndGet());
            return rows;
        });
        DhubProperties properties = new DhubProperties();
        properties.setUtztnMaxRows(1);
        properties.setUtztnSourceTable("`jeju_mini`.`tb_pklt_utztn`");
        properties.setUtztnTableId("dataset.test");

        DhubPkltUtztnApiClient client = new DhubPkltUtztnApiClient(queryClient, properties);

        assertThatThrownBy(client::fetchLots)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("페이지 상한");
        verify(queryClient, times(DhubPkltUtztnApiClient.MAX_PAGES)).query(anyString(), anyString(), anyInt());
    }
}
