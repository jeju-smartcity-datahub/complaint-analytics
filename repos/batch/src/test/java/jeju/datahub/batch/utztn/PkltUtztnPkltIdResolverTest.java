package jeju.datahub.batch.utztn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import jeju.datahub.batch.utztn.PkltUtztnPkltIdResolver.Match;
import jeju.datahub.batch.utztn.api.PkltUtztnOriginRow;

/**
 * 주소 2단 매칭 회귀.
 *
 * <p>🔴 기대값은 <b>픽스처가 정한 id</b> 다 — 실 DB 의 {@code pklt_id} 리터럴을 박지 않는다
 * (부모 표는 전량 재적재로 IDENTITY 가 밀린다).
 *
 * <p>스프링 컨텍스트를 띄우지 않는다 — {@code JdbcTemplate} 만 모킹해 {@code beforeStep()} 에
 * 픽스처 행을 흘린다.
 */
class PkltUtztnPkltIdResolverTest {

    /** 픽스처 주차장 한 행. */
    private record Lot(long id, String nm, String addr, int cpcty) { }

    private PkltUtztnPkltIdResolver resolver;
    /** 착수 시 배정에 쓰이는 원천 주차장 목록 — 테스트가 {@code given()} 전에 채운다. */
    private final List<String[]> srcLots = new ArrayList<>();

    private void given(List<Lot> lots) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            for (Lot lot : lots) {
                ResultSet rs = mock(ResultSet.class);
                org.mockito.Mockito.when(rs.getLong("pklt_id")).thenReturn(lot.id());
                org.mockito.Mockito.when(rs.getString("pklt_nm")).thenReturn(lot.nm());
                org.mockito.Mockito.when(rs.getString("pklt_addr")).thenReturn(lot.addr());
                org.mockito.Mockito.when(rs.getInt("pklt_cpcty")).thenReturn(lot.cpcty());
                org.mockito.Mockito.when(rs.wasNull()).thenReturn(false);
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(), org.mockito.ArgumentMatchers.any(RowCallbackHandler.class));

        var api = mock(jeju.datahub.batch.utztn.api.DhubPkltUtztnApiClient.class);
        org.mockito.Mockito.when(api.fetchLots()).thenReturn(srcLots);

        resolver = new PkltUtztnPkltIdResolver(jdbc, api);
        resolver.beforeStep(null);
    }

    private static PkltUtztnOriginRow src(String nm, String addr) {
        return src(nm, addr, "2025-06", 42);
    }

    private static PkltUtztnOriginRow src(String nm, String addr, String ym, int cpcty) {
        return new PkltUtztnOriginRow(nm, addr, ym, 3615, cpcty, new BigDecimal("2.869047"));
    }

    /** 원천 주차장을 배정 목록에 등록한다 — {@code given()} 보다 먼저 부른다. */
    private void srcLot(String nm, String addr) {
        srcLots.add(new String[] { nm, addr });
    }

    @BeforeEach
    void setUp() {
        srcLot("남원 비안동 공영주차장", "남원읍 남원리 180");
        srcLot("천지공영 주차빌딩", "서귀동 305-1, 300-19");
        srcLot("대정하모 공영노외3주차장", "없는 주소 998");
        srcLot("남광북1길", "없는 주소 997");
        srcLot("동홍1 공영주차빌딩", "동홍동 395-2");
        srcLot("없는 주차장", "없는 주소 996");
        srcLot("남원 비안동 제2주차장", "남원리 180");
        given(List.of(
                new Lot(101, "남원 비안동 공영주차장", "남원리 180", 42),
                new Lot(102, "대정하모 공영노외3 주차장", "하모리 1512-1, 1513", 14),
                new Lot(103, "천지공영주차빌딩(공작물)", "서귀동 305-1, 서귀동 300-19", 218),
                // 같은 정규화 주소에 둘 — MIN(pklt_id) 가 이긴다
                new Lot(104, "동홍1 공영주차빌딩", "동홍동 395-2", 144),
                new Lot(105, "동홍1 공영주차빌딩", "동홍동 395-2", 144),
                // 같은 도로명을 쓰는 노상 구역 둘 — 이름 폴백이 모호해진다
                new Lot(201, "남광북1길", "이도이동 1063-12, 1063-13", 10),
                new Lot(202, "남광북1길", "1065-6 ~ 1067", 8)));
    }

    @Test
    @DisplayName("1단: 읍면 접두를 제거해 붙는다 — '남원읍 남원리 180' → '남원리 180'")
    void addrStripsEupMyeonPrefix() {
        Match m = resolver.resolve(src("남원 비안동 공영주차장", "남원읍 남원리 180"));
        assertThat(m.resolved()).isTrue();
        assertThat(m.pkltId()).isEqualTo(101L);
        assertThat(m.mtchStp()).isEqualTo("ADDR");
    }

    @Test
    @DisplayName("1단: 다필지 주소를 첫 지번으로 자르지 않는다 — 원문 그대로 비교해 미해석이면 2단으로 간다")
    void addrDoesNotTruncateMultiParcel() {
        // 원천 '서귀동 305-1, 300-19' ↔ 우리 '서귀동 305-1, 서귀동 300-19' → 주소로는 안 붙는다.
        Match m = resolver.resolve(src("천지공영 주차빌딩", "서귀동 305-1, 300-19", "2025-06", 218));
        assertThat(m.mtchStp())
                .as("첫 지번으로 잘랐다면 ADDR 로 붙었을 것이다 — 자르지 않아야 한다")
                .isEqualTo("NM");
        assertThat(m.pkltId()).isEqualTo(103L);
    }

    @Test
    @DisplayName("2단: 괄호를 제거해 붙는다 — '천지공영 주차빌딩' → '천지공영주차빌딩(공작물)'")
    void nameStripsParentheses() {
        Match m = resolver.resolve(src("천지공영 주차빌딩", "서귀동 305-1, 300-19", "2025-06", 218));
        assertThat(m.resolved()).isTrue();
        assertThat(m.pkltId()).isEqualTo(103L);
        assertThat(m.mtchStp()).isEqualTo("NM");
    }

    @Test
    @DisplayName("2단: 공백을 제거해 붙는다 — '공영노외3주차장' → '공영노외3 주차장'")
    void nameStripsSpaces() {
        Match m = resolver.resolve(src("대정하모 공영노외3주차장", "없는 주소 998", "2025-06", 14));
        assertThat(m.resolved()).isTrue();
        assertThat(m.pkltId()).isEqualTo(102L);
        assertThat(m.mtchStp()).isEqualTo("NM");
    }

    @Test
    @DisplayName("🔴 2단 후보가 2개 이상이면 붙이지 않고 NM_AMBIGUOUS 로 스킵한다 — 노상 구역이 엉키는 것을 막는다")
    void nameAmbiguousIsSkipped() {
        Match m = resolver.resolve(src("남광북1길", "없는 주소 997", "2025-06", 10));
        assertThat(m.resolved()).isFalse();
        assertThat(m.failRsn()).isEqualTo("NM_AMBIGUOUS");
        assertThat(m.candCnt()).isEqualTo(2);
    }

    @Test
    @DisplayName("🔴 순서 회귀: 1단이 성공하면 2단을 적용하지 않는다 — 후보 다수여도 MIN(pklt_id)")
    void addrWinsOverName() {
        Match m = resolver.resolve(src("동홍1 공영주차빌딩", "동홍동 395-2", "2025-06", 144));
        assertThat(m.mtchStp()).isEqualTo("ADDR");
        assertThat(m.pkltId()).as("MIN(pklt_id) 가 이긴다").isEqualTo(104L);
        assertThat(m.ambiguous()).as("후보 2개였음을 기록한다").isTrue();
    }

    @Test
    @DisplayName("🔴 월 다중 회귀: 같은 주차장의 15개월이 모두 해석된다 — DUP_TARGET 으로 2번째 달부터 스킵되지 않는다")
    void sameLotAcrossManyMonthsAllResolve() {
        List<Match> matches = new ArrayList<>();
        for (int i = 6; i <= 17; i++) { // 2025-06 ~ 2026-05 = 12개월
            String ym = i <= 12 ? "2025-%02d".formatted(i) : "2026-%02d".formatted(i - 12);
            matches.add(resolver.resolve(src("남원 비안동 공영주차장", "남원읍 남원리 180", ym, 42)));
        }
        assertThat(matches).hasSize(12);
        assertThat(matches).allSatisfy(m -> {
            assertThat(m.resolved())
                    .as("같은 주차장의 다른 달은 정상 경로다 — DUP_TARGET 판정을 행 단위로 구현하면 1건만 남는다")
                    .isTrue();
            assertThat(m.pkltId()).isEqualTo(101L);
        });
    }

    @Test
    @DisplayName("🔴 다른 원천 주차장이 같은 pklt_id 를 노리면 두 번째는 DUP_TARGET 으로 스킵한다")
    void differentSourceLotHittingSameTargetIsSkipped() {
        // 같은 정규화 주소를 노리는 원천 2건 — 후보는 101 하나뿐이라 한쪽은 DUP_TARGET 이다.
        Match a = resolver.resolve(src("남원 비안동 공영주차장", "남원읍 남원리 180"));
        Match b = resolver.resolve(src("남원 비안동 제2주차장", "남원리 180"));

        assertThat(List.of(a.resolved(), b.resolved()))
                .as("정확히 한쪽만 붙는다")
                .containsExactlyInAnyOrder(true, false);
        Match loser = a.resolved() ? b : a;
        assertThat(loser.failRsn()).isEqualTo("DUP_TARGET");
    }

    @Test
    @DisplayName("🔴 순서 독립 회귀: 주소 후보가 여럿이면 **이름이 맞는 쪽**이 자리를 가져간다 — MIN 이 밀어내지 못한다")
    void nameMatchWinsOverMinWithinAddrCandidates() {
        // 우리 표: 301 '제일주차빌딩' · 302 '일도일동 1476-33' (둘 다 같은 주소)
        // 원천:   '제일주차빌딩'(이름 일치) · '일도일동 공영주차장1'(이름 불일치)
        // 🔴 MIN(pklt_id)=301 이 먼저 흘러온 쪽에 가면 제일주차빌딩이 밀려난다(2026-09-30 E2E 실측 사고).
        srcLots.clear();
        srcLot("일도일동 공영주차장1", "일도일동 1476-33");
        srcLot("제일주차빌딩", "일도일동 1476-33");
        given(List.of(
                new Lot(301, "제일주차빌딩", "일도일동 1476-33", 13),
                new Lot(302, "일도일동 1476-33", "일도일동 1476-33", 13)));

        Match named = resolver.resolve(src("제일주차빌딩", "일도일동 1476-33", "2025-06", 13));
        Match generic = resolver.resolve(src("일도일동 공영주차장1", "일도일동 1476-33", "2025-06", 13));

        assertThat(named.pkltId()).as("이름이 맞는 쪽이 301 을 가져간다").isEqualTo(301L);
        assertThat(generic.pkltId()).as("나머지는 남은 후보 302 로 간다").isEqualTo(302L);
        assertThat(generic.failRsn()).as("DUP_TARGET 으로 버려지지 않는다").isNull();
    }

    @Test
    @DisplayName("1·2단 모두 실패하면 ADDR_NM_BOTH_MISS 다")
    void bothStepsMiss() {
        Match m = resolver.resolve(src("없는 주차장", "없는 주소 996"));
        assertThat(m.resolved()).isFalse();
        assertThat(m.failRsn()).isEqualTo("ADDR_NM_BOTH_MISS");
    }

    @Test
    @DisplayName("msmt_ym → tot_ym 변환: '2025-06' → '202506'")
    void totYmStripsHyphen() {
        assertThat(src("A", "B", "2025-06", 1).totYm()).isEqualTo("202506");
    }

    @Test
    @DisplayName("🔴 msmt_ym 이 6자리 숫자로 떨어지지 않으면 FAILED 로 끊는다 — CHAR(6) 은 잘리지 않고 22001 이다")
    void totYmRejectsBadFormat() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> src("A", "B", "2025-6-1", 1).totYm())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("yyyy-MM");
    }
}
