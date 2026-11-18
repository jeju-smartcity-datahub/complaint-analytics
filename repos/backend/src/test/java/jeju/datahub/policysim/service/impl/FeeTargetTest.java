package jeju.datahub.policysim.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 요금제 적용 대상 집계 통합테스트 ({@link PolicySimMapper#selectFeeTargetSlot}).
 *
 * <p>🔴 면수는 고정값이 아니라 <b>독립 SQL 대조</b>로 단언한다. {@code tb_pblpklt} 는 공영주차장 수집 배치가
 * 분기마다 다시 적재해 면수가 움직인다 — 공간 데이터만 읽는 {@code AdjacentEmdTest} 와 달리 고정 단언은
 * 재적재 한 번에 깨진다(메모리 "통합 테스트에 데이터 날짜를 박지 마라" 와 같은 사정).
 * 검증값(무료 Z1 24,735 · Z2 11,425 · 전체 36,160 / 유료 10,723 — 2026-10-11 로컬 실측)은 이 대조식과 같다.
 *
 * <p>독립 SQL 은 매퍼와 다르게 짠다 — 읍면동 GROUP BY·LEFT JOIN 없이 주차장 표를 직접 합산한다.
 * 조회 전용이라 정리할 행이 없다.
 */
@SpringBootTest
class FeeTargetTest {

    private static final String CONVERT = "N";
    private static final String RAISE = "Y";
    private static final String EMD_YEONDONG = "50110137";   // 동 — 1급지
    private static final String EMD_UDO = "50110330";        // 면 — 2급지

    @Autowired
    private PolicySimMapper policySimMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static long sum(List<FeeTargetRow> rows) {
        return rows.stream().mapToLong(FeeTargetRow::getAplcnTrgtSlot).sum();
    }

    /** 독립 대조 — 주차장 표 직접 합산. zone: Z1/Z2/null(전체) */
    private long expected(String crgYn, String zone, String sggNm, String emdCd) {
        StringBuilder sql = new StringBuilder("""
                SELECT COALESCE(SUM(p.pklt_cpcty), 0)
                  FROM parking.tb_pblpklt p
                  JOIN parking.tb_admdst a ON a.emd_cd = p.emd_cd
                 WHERE (p.ownshp_type_nm IS NULL OR p.ownshp_type_nm <> '민영')
                   AND p.extsh_dt IS NULL
                   AND CASE WHEN p.prk_crg_yn = 'Y' THEN 'Y' WHEN p.prk_crg_yn IS NULL OR p.prk_crg_yn = 'N' THEN 'N' ELSE p.prk_crg_yn END = ?
                """);
        List<Object> args = new java.util.ArrayList<>(List.of(crgYn));
        if (emdCd != null) {
            sql.append(" AND a.emd_cd = ?");
            args.add(emdCd);
        } else {
            if ("Z1".equals(zone)) sql.append(" AND right(a.emd_nm, 1) NOT IN ('읍','면')");
            if ("Z2".equals(zone)) sql.append(" AND right(a.emd_nm, 1) IN ('읍','면')");
            if (sggNm != null) {
                sql.append(" AND a.sgg_nm = ?");
                args.add(sggNm);
            }
        }
        return jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
    }

    @Test
    @DisplayName("CONVERT(무료) — Z1·Z2·ALL 이 독립 SQL 과 같고 Z1 + Z2 = ALL")
    void convertByZone() {
        long z1 = sum(policySimMapper.selectFeeTargetSlot(CONVERT, "Z1", null, null));
        long z2 = sum(policySimMapper.selectFeeTargetSlot(CONVERT, "Z2", null, null));
        long all = sum(policySimMapper.selectFeeTargetSlot(CONVERT, "ALL", null, null));

        assertThat(z1).isPositive().isEqualTo(expected(CONVERT, "Z1", null, null));
        assertThat(z2).isPositive().isEqualTo(expected(CONVERT, "Z2", null, null));
        assertThat(all).isEqualTo(expected(CONVERT, null, null, null)).isEqualTo(z1 + z2);
    }

    @Test
    @DisplayName("RAISE(유료) — Z1·Z2·ALL 이 독립 SQL 과 같고 Z1 + Z2 = ALL")
    void raiseByZone() {
        long z1 = sum(policySimMapper.selectFeeTargetSlot(RAISE, "Z1", null, null));
        long z2 = sum(policySimMapper.selectFeeTargetSlot(RAISE, "Z2", null, null));
        long all = sum(policySimMapper.selectFeeTargetSlot(RAISE, "ALL", null, null));

        assertThat(all).isPositive().isEqualTo(expected(RAISE, null, null, null)).isEqualTo(z1 + z2);
        assertThat(z1).isEqualTo(expected(RAISE, "Z1", null, null));
        assertThat(z2).isEqualTo(expected(RAISE, "Z2", null, null));
    }

    @Test
    @DisplayName("급지 판정 — Z1 은 동만 · Z2 는 읍·면만, ALL 은 74 읍면동 전부(면수 0 포함)")
    void zoneRowsFollowSuffix() {
        assertThat(policySimMapper.selectFeeTargetSlot(CONVERT, "Z1", null, null))
                .isNotEmpty().allMatch(r -> !r.getEmdNm().endsWith("읍") && !r.getEmdNm().endsWith("면"));
        assertThat(policySimMapper.selectFeeTargetSlot(CONVERT, "Z2", null, null))
                .isNotEmpty().allMatch(r -> r.getEmdNm().endsWith("읍") || r.getEmdNm().endsWith("면"));

        Integer admdstCnt = jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_admdst", Integer.class);
        assertThat(policySimMapper.selectFeeTargetSlot(RAISE, "ALL", null, null)).hasSize(admdstCnt);
    }

    @Test
    @DisplayName("sggNm — 제주시 + 서귀포시 = 제주 전체")
    void sggSplit() {
        long jeju = sum(policySimMapper.selectFeeTargetSlot(CONVERT, "ALL", PolicySimParams.SGG_JEJU, null));
        long seogwipo = sum(policySimMapper.selectFeeTargetSlot(CONVERT, "ALL", PolicySimParams.SGG_SEOGWIPO, null));

        assertThat(jeju).isEqualTo(expected(CONVERT, null, PolicySimParams.SGG_JEJU, null));
        assertThat(jeju + seogwipo).isEqualTo(sum(policySimMapper.selectFeeTargetSlot(CONVERT, "ALL", null, null)));
    }

    @Test
    @DisplayName("emdCd 지정 시 zone·sggNm 무시 — 연동(동)에 Z2·서귀포시를 줘도 연동 1행")
    void emdCdOverridesZone() {
        List<FeeTargetRow> rows = policySimMapper.selectFeeTargetSlot(CONVERT, "Z2", PolicySimParams.SGG_SEOGWIPO, EMD_YEONDONG);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEmdCd()).isEqualTo(EMD_YEONDONG);
        assertThat(rows.get(0).getEmdNm()).isEqualTo("연동");
        assertThat(rows.get(0).getAplcnTrgtSlot())
                .isPositive()
                .isEqualTo(expected(CONVERT, null, null, EMD_YEONDONG))
                .isEqualTo(sum(policySimMapper.selectFeeTargetSlot(CONVERT, "Z1", null, EMD_YEONDONG)));
    }

    @Test
    @DisplayName("emdCd 지정 + 해당 주차장 없음 — 0 면 1행 (우도면 RAISE)")
    void emdCdWithoutLotsIsZero() {
        List<FeeTargetRow> rows = policySimMapper.selectFeeTargetSlot(RAISE, "Z1", null, EMD_UDO);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAplcnTrgtSlot()).isEqualTo(expected(RAISE, null, null, EMD_UDO));
    }
}
