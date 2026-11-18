package jeju.datahub.batch.utztn;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jeju.datahub.batch.utztn.PkltUtztnPkltIdResolver.Match;
import jeju.datahub.batch.utztn.api.PkltUtztnOriginRow;

/**
 * 매칭 감사 기록 — {@code batch.tb_pklt_utztn_mtch} (TSK-4189 Step 7).
 *
 * <p>🔴 <b>검산 수단이 없어서 필요하다.</b> 원천이 주는 필드(이름·주소·면수)로는 매칭을 검산할 수 없으므로,
 * 어느 원천 주차장을 우리 어느 행에 붙였는지 <b>전량</b>을 남겨 사람이 SQL 로 훑게 한다.
 *
 * <p>🔴 <b>{@code REQUIRES_NEW} 다</b> — 청크가 롤백돼도 "무엇을 어떻게 판정했는지" 는 남아야 감사 기록이
 * 된다. 특히 미해석 행은 그 자체가 스킵 사유 기록이다.
 *
 * <p>주차장 단위(월 단위가 아니다)라 같은 주차장이 15개월 흘러와도 <b>한 행</b>이다 —
 * PK {@code (src_pklt_nm, src_pklt_addr)} 로 upsert 하므로 멱등하다.
 */
@Repository
public class PkltUtztnMatchAuditRepository {

    private static final String UPSERT = """
            INSERT INTO batch.tb_pklt_utztn_mtch
                (src_pklt_nm, src_pklt_addr, norm_addr, norm_nm,
                 pklt_id, mtch_stp, cand_cnt, ambg_yn, cpcty_dfrn_yn, fail_rsn, last_excn_dt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (src_pklt_nm, src_pklt_addr) DO UPDATE SET
                norm_addr     = EXCLUDED.norm_addr,
                norm_nm       = EXCLUDED.norm_nm,
                pklt_id       = EXCLUDED.pklt_id,
                mtch_stp      = EXCLUDED.mtch_stp,
                cand_cnt      = EXCLUDED.cand_cnt,
                ambg_yn       = EXCLUDED.ambg_yn,
                cpcty_dfrn_yn = EXCLUDED.cpcty_dfrn_yn,
                fail_rsn      = EXCLUDED.fail_rsn,
                last_excn_dt  = now()
            """;

    private final JdbcTemplate jdbcTemplate;

    public PkltUtztnMatchAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 매칭 결과 한 건을 남긴다.
     *
     * <p>⚠️ PK 두 컬럼이 {@code NOT NULL} 이라 원천 이름·주소가 null 이면 23502 로 Job 이 죽는다 —
     * {@code coalesce} 로 빈 문자열을 넣어 그 경로를 막고, 그 행은 {@code pklt_id IS NULL} +
     * {@code fail_rsn} 으로 남는다(Step 1 실측으로는 null 이 0건이다).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(PkltUtztnOriginRow row, Match match) {
        jdbcTemplate.update(UPSERT,
                nullToEmpty(row.pkltNm()),
                nullToEmpty(row.pkltAddr()),
                match.normAddr(),
                match.normNm(),
                match.pkltId(),
                match.mtchStp(),
                match.candCnt(),
                match.ambiguous() ? "Y" : "N",
                match.cpctyDiffers() ? "Y" : "N",
                match.failRsn());
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }
}
