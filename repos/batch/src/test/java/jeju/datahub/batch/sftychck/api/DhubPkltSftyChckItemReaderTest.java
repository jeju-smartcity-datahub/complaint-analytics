package jeju.datahub.batch.sftychck.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jeju.datahub.batch.cvlcpt.api.DhubApiException;
import jeju.datahub.batch.cvlcpt.api.DhubKeysetPage;
import jeju.datahub.batch.cvlcpt.api.DhubKeysetQuery;
import jeju.datahub.batch.cvlcpt.api.DhubProperties;
import jeju.datahub.batch.cvlcpt.api.DhubQueryClient;

/**
 * Reader → {@link DhubPkltSftyChckApiClient} → 공용 {@link DhubKeysetQuery} 를 실제로 태우고, 전송 계층
 * {@link DhubQueryClient} 만 가짜로 둔다(TSK-4254 Step 3). 가짜는 받은 SQL 의 {@code sfty_chck_id > N} ·
 * {@code LIMIT n} 을 해석해 메모리 원천에서 잘라 준다 — 커서 리터럴이 틀리면 행이 겹치거나 빠져 단언이 깨진다.
 *
 * <p>확인하는 것: keyset 다음 페이지 커서 · 미전진 예외 · 총건수 부족 예외 · 🔴 컬럼 감지({@code pklt_id} 있음/없음
 * 각각 SELECT 목록) · 필수 컬럼 누락 예외 · 원천 0행.
 */
class DhubPkltSftyChckItemReaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TABLE_ID = "dataset.sftychck-test";
    private static final String SOURCE_TABLE = "`test`.`tb_pklt_sfty_chck`";

    private static final Pattern CURSOR = Pattern.compile("sfty_chck_id > (\\S+)");
    private static final Pattern LIMIT = Pattern.compile("LIMIT (\\d+)");

    private DhubQueryClient queryClient;
    private DhubProperties properties;
    private DhubPkltSftyChckItemReader reader;

    /** 원천 키(오름차순). */
    private List<Long> sourceKeys = List.of();
    /** 원천에 pklt_id 컬럼이 있는지. */
    private boolean sourceHasPkltId = true;
    /** count(*) 응답 덮어쓰기 — 음수면 원천 크기. */
    private long countOverride = -1;
    /** true 면 커서를 무시하고 첫 페이지를 다시 준다(원천 정렬 이상 흉내). */
    private boolean ignoreCursor;

    private final List<String> pageQueries = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        queryClient = mock(DhubQueryClient.class);
        when(queryClient.query(eq(TABLE_ID), anyString(), anyInt()))
                .thenAnswer(inv -> answer(inv.getArgument(1), inv.getArgument(2)));
        properties = new DhubProperties();
        properties.setEmail("user@test.local");
        properties.setPassword("pw");
        properties.setSftychckTableId(TABLE_ID);
        properties.setSftychckSourceTable(SOURCE_TABLE);
        properties.setSftychckPageSize(2);
        reader = new DhubPkltSftyChckItemReader(new DhubPkltSftyChckApiClient(queryClient, properties), properties);
    }

    @Test
    @DisplayName("keyset — 페이지 크기에 닿으면 마지막 키로 다음 페이지를 받는다(숫자 리터럴 · 999→1000 경계)")
    void pagesWithNumericCursor() {
        sourceKeys = List.of(998L, 999L, 1000L, 1001L, 1002L);

        List<Long> read = readAll();

        assertThat(read).containsExactly(998L, 999L, 1000L, 1001L, 1002L);
        assertThat(pageQueries).hasSize(3);
        assertThat(pageQueries.get(0)).doesNotContain("WHERE");
        assertThat(pageQueries.get(1)).contains("WHERE sfty_chck_id > 999 ");
        assertThat(pageQueries.get(2)).contains("WHERE sfty_chck_id > 1001 ");
        assertThat(pageQueries).allSatisfy(q -> assertThat(q).contains("ORDER BY sfty_chck_id LIMIT 2"));
    }

    @Test
    @DisplayName("🔴 커서가 전진하지 않으면 예외 — 같은 페이지 무한 반복 방지")
    void failsWhenCursorDoesNotAdvance() {
        sourceKeys = List.of(1L, 2L, 3L, 4L);
        ignoreCursor = true;

        assertThat(reader.read()).isNotNull();
        assertThat(reader.read()).isNotNull();
        assertThatThrownBy(() -> reader.read())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("전진하지 않습니다");
    }

    @Test
    @DisplayName("총건수 > 수신이면 마지막 페이지 뒤 예외 — 잘린 수집이 COMPLETED 로 보이면 안 된다")
    void failsWhenReceivedFewerThanTotal() {
        sourceKeys = List.of(1L, 2L, 3L);
        countOverride = 5;

        assertThatThrownBy(this::readAll)
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("전량 수집 실패")
                .hasMessageContaining("원천 5행 중 3행");
    }

    @Test
    @DisplayName("🔴 컬럼 감지 — pklt_id 가 있으면 SELECT 목록에 넣고 행에 값을 싣는다")
    void selectsPkltIdWhenPresent() {
        sourceKeys = List.of(1L);
        sourceHasPkltId = true;

        PkltSftyChckOriginRow row = reader.read();

        assertThat(pageQueries.get(0)).startsWith("SELECT sfty_chck_id, pklt_id, chck_ymd, chck_prd_cd, "
                + "sfty_chck_type_cd, sfty_chck_rslt_cd, rmrk_cn, pklt_nm, pklt_addr FROM " + SOURCE_TABLE);
        assertThat(pageQueries.get(0)).doesNotContain("rgtr_id").doesNotContain("reg_dt");
        assertThat(row).isNotNull().extracting(PkltSftyChckOriginRow::pkltId).isEqualTo(101L);   // 5.1(TSK-4336)
    }

    @Test
    @DisplayName("🔴 컬럼 감지 — pklt_id 가 없으면 SELECT 하지 않는다(없는 컬럼 SELECT = 422)")
    void omitsPkltIdWhenAbsent() {
        sourceKeys = List.of(1L);
        sourceHasPkltId = false;

        PkltSftyChckOriginRow row = reader.read();

        assertThat(pageQueries.get(0)).startsWith("SELECT sfty_chck_id, chck_ymd, chck_prd_cd, "
                + "sfty_chck_type_cd, sfty_chck_rslt_cd, rmrk_cn, pklt_nm, pklt_addr FROM " + SOURCE_TABLE);
        assertThat(pageQueries.get(0)).doesNotContain("pklt_id,");
        assertThat(row).isNotNull().extracting(PkltSftyChckOriginRow::pkltId).isNull();   // 5.1(TSK-4336)
    }

    @Test
    @DisplayName("필수 컬럼이 원천에 없으면 Step 시작(감지) 단계에서 예외 — 수집 요청을 보내지 않는다")
    void failsOnMissingRequiredColumn() {
        ArrayNode detect = MAPPER.createArrayNode();
        ObjectNode first = row(1L, true);
        first.remove("sfty_chck_rslt_cd");
        detect.add(first);
        when(queryClient.query(eq(TABLE_ID), eq("SELECT * FROM " + SOURCE_TABLE + " LIMIT 1"), eq(1)))
                .thenReturn(detect);

        assertThatThrownBy(() -> reader.read())
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("누락 컬럼")
                .hasMessageContaining("sfty_chck_rslt_cd");
        assertThat(pageQueries).isEmpty();
    }

    @Test
    @DisplayName("원천 0행 — pklt_id 없음으로 진행하고 null 로 정상 종료")
    void emptySource() {
        sourceKeys = List.of();
        sourceHasPkltId = true;   // 0행이라 감지할 수 없다 — 없음으로 본다

        assertThat(reader.read()).isNull();
        assertThat(pageQueries).hasSize(1);
        assertThat(pageQueries.get(0)).doesNotContain("pklt_id,");
    }

    @Test
    @DisplayName("페이지 크기 0 이면 HTTP 호출 전에 예외")
    void failsOnZeroPageSizeBeforeAnyCall() {
        properties.setSftychckPageSize(0);

        assertThatThrownBy(() -> reader.read())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dhub.sftychck-page-size");
        verifyNoInteractions(queryClient);
    }

    @Test
    @DisplayName("같은 인스턴스로 close 후 다시 읽으면 감지·커서부터 다시 — 싱글턴 빈 재실행")
    void reopensAfterClose() {
        sourceKeys = List.of(1L, 2L, 3L);
        assertThat(readAll()).containsExactly(1L, 2L, 3L);
        reader.close();

        sourceHasPkltId = false;
        assertThat(readAll()).containsExactly(1L, 2L, 3L);
        assertThat(pageQueries.get(pageQueries.size() - 1)).doesNotContain("pklt_id,");
    }

    @Test
    @DisplayName("fetchPage 의 nextCursor 는 마지막 행 키다")
    void nextCursorIsLastKey() {
        sourceKeys = List.of(5L, 7L, 9L);
        DhubPkltSftyChckApiClient client = new DhubPkltSftyChckApiClient(queryClient, properties);
        DhubKeysetQuery query = client.keysetQuery(true);

        DhubKeysetPage page = client.fetchPage(query, null, 2);

        assertThat(page.nextCursor()).isEqualTo("7");
        assertThat(page.hasMore()).isTrue();
    }

    private List<Long> readAll() {
        List<Long> keys = new ArrayList<>();
        PkltSftyChckOriginRow row;
        while ((row = reader.read()) != null) {
            keys.add(row.sftyChckId());
        }
        return keys;
    }

    /** 가짜 analytics/query — 감지·총건수·페이지를 SQL 로 가른다. */
    private ArrayNode answer(String sql, int maxRows) {
        ArrayNode out = MAPPER.createArrayNode();
        if (sql.startsWith("SELECT * ")) {
            if (!sourceKeys.isEmpty()) {
                out.add(row(sourceKeys.get(0), sourceHasPkltId));
            }
            return out;
        }
        if (sql.contains("count(*)")) {
            out.addObject().put("cnt", countOverride >= 0 ? countOverride : sourceKeys.size());
            return out;
        }
        pageQueries.add(sql);
        Matcher limit = LIMIT.matcher(sql);
        int n = limit.find() ? Integer.parseInt(limit.group(1)) : maxRows;
        Matcher cursor = CURSOR.matcher(sql);
        long after = (!ignoreCursor && cursor.find()) ? Long.parseLong(cursor.group(1)) : Long.MIN_VALUE;
        boolean withPkltId = sql.contains("pklt_id,");
        sourceKeys.stream().filter(k -> k > after).limit(n).forEach(k -> out.add(row(k, withPkltId)));
        return out;
    }

    /** 원천 1행 — 값은 테스트용 가공값(실데이터 아님). rgtr_id 는 감지 응답에도 섞어 SELECT * 를 흉내낸다. */
    private static ObjectNode row(long key, boolean withPkltId) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("sfty_chck_id", key);
        if (withPkltId) {
            node.put("pklt_id", 100 + key);
        }
        node.put("chck_ymd", 20250301L);
        node.put("chck_prd_cd", "H1");
        node.put("sfty_chck_type_cd", "REGULAR");
        node.put("sfty_chck_rslt_cd", "A");
        node.putNull("rmrk_cn");
        node.put("pklt_nm", "테스트주차장" + key);
        node.put("pklt_addr", "테스트로 " + key);
        return node;
    }
}
