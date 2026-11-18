package jeju.datahub.batch.acdnt;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import jeju.datahub.batch.acdnt.api.PkltAcdntOriginRow;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

class PkltAcdntProcessorTest {
    static PkltAcdntOriginRow source(String seq, String name, String address, String status) {
        return new PkltAcdntOriginRow(seq, name, address, LocalDateTime.of(2026, 5, 6, 0, 0),
                null, "입구 볼라드 파손", "보수 완료", status, "{}");
    }
    PkltAcdntPkltIdResolver resolver(Object[]... lots) throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            RowCallbackHandler callback = invocation.getArgument(1);
            for (Object[] lot : lots) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getLong("pklt_id")).thenReturn((Long) lot[0]);
                when(rs.getString("pklt_nm")).thenReturn((String) lot[1]);
                when(rs.getString("pklt_addr")).thenReturn((String) lot[2]);
                when(rs.getTimestamp("extsh_dt")).thenReturn(lot.length > 3 ? (java.sql.Timestamp) lot[3] : null);
                callback.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));
        var resolver = new PkltAcdntPkltIdResolver(jdbc);
        resolver.beforeStep(null);
        return resolver;
    }
    @Test void uniqueAddressResolvesAliasWithoutUsingSourceSequence() throws Exception {
        var resolver = resolver(new Object[] {9001L, "노형제5공영주차장", "노형동 3793"});
        assertThat(resolver.resolve(source("1", "노형 제5", "노형동 3793", "완료"))).isEqualTo(9001L);
    }
    @Test void sameAddressRequiresUniqueNameOtherwiseFails() throws Exception {
        var resolver = resolver(new Object[] {9001L, "A", "노형동 3793"}, new Object[] {9002L, "B", "노형동 3793"});
        assertThat(resolver.resolve(source(null, "B", "노형동 3793", "완료"))).isEqualTo(9002L);
        assertThatIllegalStateException().isThrownBy(() -> resolver.resolve(source("1", "X", "노형동 3793", "완료")))
                .withMessageContaining("후보 모호");
        assertThatIllegalStateException().isThrownBy(() -> resolver.resolve(source("1", null, null, null)));
    }
    @Test void statusAndSourceSequenceChangesKeepAccidentIdentity() throws Exception {
        var processor = new PkltAcdntProcessor(resolver(new Object[] {9001L, "노형 제5", "노형동 3793"}));
        var a = processor.process(source("1", "노형 제5", "노형동 3793", "처리 중"));
        var b = processor.process(source("99", "노형제5", "노형동 3793", "완료"));
        assertThat(a.srcHash()).isEqualTo(b.srcHash()).hasSize(64);
    }
    @Test void currentJeongjonRepresentativeWinsOverRetiredCopyWithShortAddress() throws Exception {
        var resolver = resolver(new Object[] {112L, "정존", "제주특별자치도 제주시 노형동  2612-2"},
                new Object[] {12313L, "정존", "노형동 2612-2", java.sql.Timestamp.valueOf("2026-11-16 02:57:38")});
        assertThat(resolver.resolve(source("1", "노형 정존", "노형동 2612-2", "완료"))).isEqualTo(112L);
    }
    @Test void twoActiveCopiesOrDifferentAddressesStayAmbiguous() throws Exception {
        var resolver = resolver(new Object[] {1L, "정존", "노형동 2612-2"}, new Object[] {2L, "정존", "노형동 2612-2"});
        assertThatIllegalStateException().isThrownBy(() -> resolver.resolve(source("1", "정존", "노형동 2612-2", "완료")));
        var other = resolver(new Object[] {1L, "A", "주소1"},
                new Object[] {2L, "A", "주소2", java.sql.Timestamp.valueOf("2026-11-16 00:00:00")});
        assertThatIllegalStateException().isThrownBy(() -> other.resolve(source("1", "A", null, "완료")));
    }
}
