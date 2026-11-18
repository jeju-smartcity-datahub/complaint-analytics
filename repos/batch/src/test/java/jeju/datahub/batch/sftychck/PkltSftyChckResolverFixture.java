package jeju.datahub.batch.sftychck;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import jeju.datahub.batch.sftychck.api.PkltSftyChckOriginRow;

/**
 * DB 없이 {@link PkltSftyChckPkltIdResolver} 를 띄우는 픽스처 — {@code JdbcTemplate} 만 모킹해 {@code beforeStep()} 에
 * 가짜 주차장 행을 흘린다(선례 {@code PkltUtztnPkltIdResolverTest}).
 *
 * <p>🔴 기대값은 픽스처가 정한 id 다 — 실 DB 의 {@code pklt_id} 리터럴·실데이터 이름·주소를 박지 않는다.
 */
final class PkltSftyChckResolverFixture {

    /** 픽스처 주차장 한 행 — {@code parking.tb_pblpklt(pklt_id, origin_pk, pklt_nm, pklt_addr)}. */
    record Lot(long id, String originPk, String nm, String addr) { }

    /** {@code beforeStep()} 이 읽을 행 — 테스트가 바꿔 끼우면 다음 {@code beforeStep()} 에 반영된다. */
    final List<Lot> lots = new ArrayList<>();
    final PkltSftyChckPkltIdResolver resolver;

    PkltSftyChckResolverFixture(List<Lot> initial) {
        lots.addAll(initial);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            for (Lot lot : List.copyOf(lots)) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getLong("pklt_id")).thenReturn(lot.id());
                when(rs.getString("origin_pk")).thenReturn(lot.originPk());
                when(rs.getString("pklt_nm")).thenReturn(lot.nm());
                when(rs.getString("pklt_addr")).thenReturn(lot.addr());
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));
        resolver = new PkltSftyChckPkltIdResolver(jdbc);
        resolver.beforeStep(null);
    }

    /** 원천 행 — 코드값은 정상 기본값. */
    static PkltSftyChckOriginRow src(long sftyChckId, Long pkltId, String nm, String addr) {
        return new PkltSftyChckOriginRow(sftyChckId, pkltId, "20260301", "H1", "REGULAR", "A", null, nm, addr);
    }

    static PkltSftyChckOriginRow src(long sftyChckId, Long pkltId, String nm, String addr, String ymd,
                                     String prd, String type, String rslt, String rmrk) {
        return new PkltSftyChckOriginRow(sftyChckId, pkltId, ymd, prd, type, rslt, rmrk, nm, addr);
    }
}
