package jeju.datahub.batch.crackdown.geocode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** {@link CrackdownCoordWriter} — SQL 과 파라미터 순서. */
class CrackdownCoordWriterTest {

    @Test
    @DisplayName("파라미터 순서는 lat, lot, emd_cd, crdn_addr")
    void setterOrder() throws Exception {
        BigDecimal lat = new BigDecimal("33.4996000000");
        BigDecimal lot = new BigDecimal("126.5312000000");
        CrackdownCoord coord = new CrackdownCoord("노형동 1234-5", lat, lot, "50110107");
        PreparedStatement ps = mock(PreparedStatement.class);

        CrackdownCoordWriter.SETTER.setValues(coord, ps);

        InOrder order = inOrder(ps);
        order.verify(ps).setBigDecimal(1, lat);
        order.verify(ps).setBigDecimal(2, lot);
        order.verify(ps).setString(3, "50110107");
        order.verify(ps).setString(4, "노형동 1234-5");
        verify(ps, never()).setObject(anyInt(), anyString());
    }

    @Test
    @DisplayName("좌표 없는 행만 갱신하고 geom(생성 컬럼)은 쓰지 않는다")
    void sql() {
        assertThat(CrackdownCoordWriter.UPDATE_SQL.strip().replaceAll("\\s+", " "))
                .isEqualTo("UPDATE parking.tb_crdn_dsctn SET lat = ?, lot = ?, emd_cd = ? "
                        + "WHERE crdn_addr = ? AND lat IS NULL");
        assertThat(CrackdownCoordWriter.UPDATE_SQL).doesNotContain("geom");
    }
}
