package jeju.datahub.batch.sftychck;

/**
 * 적재 행 — {@code parking.tb_pklt_sfty_chck} 1행 (TSK-4254 Step 4).
 *
 * <p>Processor 가 주차장 해석·코드 매핑·일자 정규화를 <b>모두 마친 값</b>만 담는다 — Writer 는 변환하지 않는다.
 * {@code sfty_chck_id}(IDENTITY)·{@code rgtr_id}·{@code reg_dt} 는 담지 않는다(D6·D8 — Writer SQL 이 정한다).
 */
public record PkltSftyChckRow(
        long pkltId,            // pklt_id            (우리 tb_pblpklt.pklt_id — D2 로 해석한 값)
        String chckYmd,         // chck_ymd           CHAR(8) YYYYMMDD (실존 날짜로 검증됨)
        String chckPrdCd,       // chck_prd_cd        H1/H2 (원천 값 또는 점검월 유도)
        String sftyChckTypeCd,  // sfty_chck_type_cd  REGULAR/OCCASION/PRECISION
        String sftyChckRsltCd,  // sfty_chck_rslt_cd  A/B/C
        String rmrkCn, String srcJson) {        // rmrk_cn            nullable · 4000자 절단
    public PkltSftyChckRow(long id, String date, String period, String type, String result, String remarks) {
        this(id, date, period, type, result, remarks, null);
    }
}
