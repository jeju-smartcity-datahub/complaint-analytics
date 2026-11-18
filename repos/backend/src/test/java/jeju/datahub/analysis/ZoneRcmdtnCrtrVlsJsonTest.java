package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.service.ZoneRcmdtnCrtrVls;
import jeju.datahub.analysis.service.ZoneRcmdtnReportService;
import jeju.datahub.analysis.service.ZoneRcmdtnReportVO;
import jeju.datahub.analysis.service.ZoneRcmdtnResultVO;

/**
 * {@link ZoneRcmdtnCrtrVls} JSON 왕복 확인 (TSK-4240 Step 4).
 *
 * <h2>왜 따로 확인하나</h2>
 * 구역추천 보고서 스냅샷 조회는 {@code rslt_json} 을 {@code ZoneRcmdtnResultVO} 로 되읽고, 그 안의
 * {@code crtrVls} 가 이 클래스다. 🔴 그런데 이 클래스는 <b>JSON 에서 되읽은 선례가 없다</b> — 필드 18개(🆕 TSK-4282 FEE 8칸 포함)가 전부
 * {@code final} 이고 기본 생성자·{@code @JsonCreator} 가 없으며({@code @AllArgsConstructor} 뿐) 2칸에
 * {@code @JsonIgnore} 가 걸려 있다. 되읽기가 되는지는 Jackson 의 암묵 생성자 판정에 달려 있다.
 *
 * <h2>🔴 스프링의 {@link ObjectMapper} 빈으로 확인한다</h2>
 * 서비스({@code ZoneRcmdtnReportServiceImpl})가 쓰는 바로 그 빈이다. {@code new ObjectMapper()} 는 모듈 구성이
 * 달라 판정이 갈릴 수 있다 — 그래서 {@code @SpringBootTest} 로 컨텍스트를 띄운다.
 */
@SpringBootTest
@DisplayName("TSK-4240 Step 4 — ZoneRcmdtnCrtrVls JSON 왕복")
class ZoneRcmdtnCrtrVlsJsonTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ZoneRcmdtnReportService reportService;

    @Test
    @DisplayName("쓴 JSON 을 되읽으면 13칸이 원본과 같다")
    void roundTripKeepsThirteenFields() throws Exception {
        ZoneRcmdtnCrtrVls origin = new ZoneRcmdtnCrtrVls(
                "NARROW",
                new BigDecimal("300.0000"),
                new BigDecimal("20.0000"),
                10,
                121,
                // 🆕 TSK-4282 — 요금제 검토지수 FEE 8칸
                new BigDecimal("70.0000"),
                new BigDecimal("30.0000"),
                new BigDecimal("22.0000"),
                new BigDecimal("10.0000"),
                new BigDecimal("72.0000"),
                new BigDecimal("28.0000"),
                new BigDecimal("0.5000"),
                new BigDecimal("100.0000"));

        String json = objectMapper.writeValueAsString(origin);

        // 🔁 TSK-4340 — 판정 계수 5칸은 VO 에서 지웠다(저장 JSON 에도 없다)
        JsonNode tree = objectMapper.readTree(json);
        for (String gone : List.of("pcshrCrtrVl", "paidDmndRt", "stsfRtThrsh", "cvlcptMmCrtrVl", "ilglCrtrVl")) {
            assertThat(tree.has(gone)).as(gone).isFalse();
        }

        ZoneRcmdtnCrtrVls back = objectMapper.readValue(json, ZoneRcmdtnCrtrVls.class);

        assertThat(back.getTrgtTypeSe()).isEqualTo(origin.getTrgtTypeSe());
        // BigDecimal 은 equals 로 본다 — 자릿수(scale)까지 원문 그대로 돌아와야 한다.
        assertThat(back.getAltmntRngVl()).isEqualTo(origin.getAltmntRngVl());
        assertThat(back.getSwtcTrgtMinCpcty()).isEqualTo(origin.getSwtcTrgtMinCpcty());
        assertThat(back.getMinTrendBase()).isEqualTo(origin.getMinTrendBase());
        assertThat(back.getTrendDayCnt()).isEqualTo(origin.getTrendDayCnt());
        // 🆕 TSK-4282 — FEE 8칸도 자릿수까지 그대로 돌아온다(보고서 스냅샷 재현)
        assertThat(back.getFeeCvlcptCntWgvl()).isEqualTo(origin.getFeeCvlcptCntWgvl());
        assertThat(back.getFeeCvlcptRtWgvl()).isEqualTo(origin.getFeeCvlcptRtWgvl());
        assertThat(back.getFeeCvlcptMmCrtrJeju()).isEqualTo(origin.getFeeCvlcptMmCrtrJeju());
        assertThat(back.getFeeCvlcptMmCrtrSeogwipo()).isEqualTo(origin.getFeeCvlcptMmCrtrSeogwipo());
        assertThat(back.getFeeCvlcptRtCrtrJeju()).isEqualTo(origin.getFeeCvlcptRtCrtrJeju());
        assertThat(back.getFeeCvlcptRtCrtrSeogwipo()).isEqualTo(origin.getFeeCvlcptRtCrtrSeogwipo());
        assertThat(back.getFeeCvlcptIncrsWgvl()).isEqualTo(origin.getFeeCvlcptIncrsWgvl());
        assertThat(back.getFeeCvlcptIncrsRtUplmtVl()).isEqualTo(origin.getFeeCvlcptIncrsRtUplmtVl());
    }

    /**
     * 🔴 TSK-4340 — 구 보고서 스냅샷({@code rslt_json})에는 지운 판정 키가 남아 있다. 되읽기가 unknown property 로
     * 깨지면 스냅샷 조회가 500 이다 — 옛 키를 담은 JSON 이 그대로 읽히는지 본다(crtrVls · zone · lots[]).
     */
    @Test
    @DisplayName("구 스냅샷 호환 — crtrVls.stsfRtThrsh·zone.stsfRt 등·lots[].reachNeed 가 있어도 되읽힌다")
    void legacySnapshotKeysAreIgnored() throws Exception {
        String crtr = "{\"trgtTypeSe\":\"NARROW\",\"altmntRngVl\":300,\"swtcTrgtMinCpcty\":20,"
                + "\"stsfRtThrsh\":50,\"cvlcptMmCrtrVl\":50,\"ilglCrtrVl\":1.0,\"minTrendBase\":10,\"trendDayCnt\":121}";
        ZoneRcmdtnCrtrVls c = objectMapper.readValue(crtr, ZoneRcmdtnCrtrVls.class);
        assertThat(c.getAltmntRngVl()).isEqualByComparingTo("300");
        assertThat(c.getTrendDayCnt()).isEqualTo(121);

        String rslt = "{\"crtrVls\":" + crtr + ",\"warnings\":[],"
                + "\"zone\":{\"emdCd\":\"50110104\",\"emdNm\":\"이도이동\",\"sggNm\":\"제주시\",\"cvlcptMmAvg\":388.8,"
                + "\"crtrIlglCnt\":14.9,\"needCpcty\":116.8,\"ableCpcty\":397,\"stsfRt\":100.0,"
                + "\"rcmdtnTypeCd\":\"FEE\",\"rsnCd\":null},"
                + "\"asgn\":{\"cvlcptTot\":3110,\"cvlcptAsgn\":933,\"asgnRt\":30.0},"
                + "\"lots\":[{\"pkltId\":1,\"rnk\":1,\"cumCpcty\":28,\"reachNeed\":false}]}";
        ZoneRcmdtnResultVO r = objectMapper.readValue(rslt, ZoneRcmdtnResultVO.class);
        assertThat(r.getZone().getEmdNm()).isEqualTo("이도이동");
        assertThat(r.getLots()).hasSize(1);
        assertThat(r.getLots().get(0).getRnk()).isEqualTo(1);
        assertThat(r.getAsgn().getCvlcptAsgn()).isEqualTo(933L);
    }

    /**
     * 🔴 TSK-4340 — DB 에 실제로 저장된 요금제 보고서 스냅샷(판정 키가 남은 구 스냅샷 포함)을 서비스 경로로 되읽는다.
     * 저장분이 없으면 0건이라 그대로 통과한다(데이터에 기대지 않는다 — 있는 것만 본다). 읽기 전용이다.
     */
    @Test
    @DisplayName("구 스냅샷 호환 — DB 의 요금제 보고서 스냅샷이 전부 500 없이 되읽힌다(selectReportSnapshot)")
    void storedFeeSnapshotsAreReadable() {
        List<Long> ids = jdbcTemplate.queryForList("""
                SELECT r.rptp_id FROM parking.tb_rptp r
                  JOIN parking.tb_anls_excn e ON e.excn_id = r.excn_id
                  JOIN parking.tb_anls_rslt x ON x.excn_id = r.excn_id
                 WHERE e.anls_type_cd = 'RECOMMEND' AND e.anls_param_json->>'rcmdtnTypeCd' = 'FEE'
                 ORDER BY r.rptp_id LIMIT 200
                """, Long.class);
        for (Long id : ids) {
            ZoneRcmdtnReportVO vo = reportService.selectReportSnapshot(id);
            assertThat(vo.getFee()).as("rptpId=%s", id).isNotNull();
            assertThat(vo.getFee().getLots()).as("rptpId=%s lots", id).isNotNull();
        }
    }
}
