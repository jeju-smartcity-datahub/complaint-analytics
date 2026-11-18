package jeju.datahub.batch.cvlcpt;

import javax.sql.DataSource;
import org.springframework.batch.item.database.ItemPreparedStatementSetter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.stereotype.Component;

/**
 * {@code parking.tb_cvlcpt} upsert Writer.
 *
 * <h2>🔁 2026-08-24 — 이제 이 Writer 가 파이프라인의 유일한 적재점이다</h2>
 * 원본(staging) 테이블이 사라지고 수집 Job 이 분류·지오코딩까지 마쳐 여기로 바로 쓴다
 * (47-CVLCPT-DIRECT.sql). 종전 {@code ComplaintOriginItemWriter} 는 삭제됐다.
 *
 * <h2>🔵 rule_persistence(JPA+MyBatis) 의 예외</h2>
 * 백엔드 규칙은 "쓰기(CUD)는 JPA"지만 <b>이 배치의 쓰기는 JPA 로 하지 않는다.</b>
 * <ul>
 *   <li><b>여기는 백엔드 애플리케이션이 아니다.</b> 배치는 별도 앱이고 JPA/MyBatis 의존성 자체를
 *       갖지 않는다({@code build.gradle} 은 {@code starter-jdbc} 만 쓴다). 엔티티를 들이면
 *       백엔드와 매핑을 이중 관리하게 된다.</li>
 *   <li><b>연산이 upsert 다.</b> 15만 행을 재실행 안전하게 넣으려면
 *       {@code ON CONFLICT (src_hash) DO UPDATE} 가 필요한데, JPA 로는 "조회 후 판단 → 저장"
 *       (행마다 SELECT)이 되어 목적과 비용이 모두 어긋난다.</li>
 *   <li><b>대용량 청크 쓰기다.</b> {@link JdbcBatchItemWriter} 는 청크를 JDBC batch 한 번으로
 *       내보낸다. 영속성 컨텍스트·변경 감지가 오히려 부담이고, 이 경로에 재사용할 도메인 로직도 없다.</li>
 * </ul>
 * 즉 규칙의 취지(도메인 쓰기 로직을 한 곳에 모은다)를 깨는 게 아니라, <b>적용 대상 밖</b>이다.
 *
 * <h2>🔴 {@code risk_lvl_cd} 는 리터럴 NULL 이다</h2>
 * 이 배치가 산출하는 값이 아니다. 파라미터로 두면 "언젠가 채워지는 값"으로 오독되므로 SQL 에
 * 못박는다 — {@link CvlcptRecord} 에 필드가 없는 것과 같은 이유다.
 *
 * <h2>🔴 {@code DO UPDATE} 가 같은 내용의 원천 load_dt와 가공값을 갱신한다</h2>
 * 원천 PK가 비유일하므로 정정 내용은 별도 스냅샷이 된다. 같은 내용만 갱신한다. "한 번 넣은 뒤 건드리지 않음"은
 * 원천과 어긋난 값을 영구히 남긴다. 원본 보존은 "수집 시점 스냅샷 동결"이 아니라
 * <b>"가공하지 않은 원문을 갖고 있다"</b>는 뜻이다.
 */
@Component
public class CvlcptItemWriter extends JdbcBatchItemWriter<CvlcptRecord> {

    /** 파라미터 순서 = {@link CvlcptRecord} 필드 순서 = {@link #SETTER} 의 인덱스 순서. 셋을 어긋나게 두지 말 것. */
    static final String UPSERT_SQL = """
            INSERT INTO parking.TB_CVLCPT
              (origin_pk, cvlcpt_type_cd, emd_cd, lat, lot, ocrn_dt, risk_lvl_cd, src_cd,
               cvlcpt_cn, load_dt, aprs_cd,
               dclr_ymd, ttl, dclr_ocrn_rgn, prgrs_stts, ans_prcs_stts, ans_ymd, aplcnt_se,
               dclr_fld, rwrd_yn, prcs_inst_cnt, prcs_inst_nm, prcs_inst_pic_nm,
               dclr_grp_nm, mbr_grp_nm, dclr_type, prk_cvlcpt_type, src_path, src_hash)
            VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (src_hash) DO UPDATE SET
              origin_pk = COALESCE(EXCLUDED.origin_pk, parking.tb_cvlcpt.origin_pk),
              cvlcpt_type_cd = EXCLUDED.cvlcpt_type_cd, emd_cd = EXCLUDED.emd_cd,
              lat = EXCLUDED.lat, lot = EXCLUDED.lot, ocrn_dt = EXCLUDED.ocrn_dt,
              src_cd = EXCLUDED.src_cd, cvlcpt_cn = EXCLUDED.cvlcpt_cn,
              load_dt = GREATEST(parking.tb_cvlcpt.load_dt, EXCLUDED.load_dt), aprs_cd = EXCLUDED.aprs_cd,
              dclr_ymd = EXCLUDED.dclr_ymd, ttl = EXCLUDED.ttl,
              dclr_ocrn_rgn = EXCLUDED.dclr_ocrn_rgn, prgrs_stts = EXCLUDED.prgrs_stts,
              ans_prcs_stts = EXCLUDED.ans_prcs_stts, ans_ymd = EXCLUDED.ans_ymd,
              aplcnt_se = EXCLUDED.aplcnt_se, dclr_fld = EXCLUDED.dclr_fld,
              rwrd_yn = EXCLUDED.rwrd_yn, prcs_inst_cnt = EXCLUDED.prcs_inst_cnt,
              prcs_inst_nm = EXCLUDED.prcs_inst_nm, prcs_inst_pic_nm = EXCLUDED.prcs_inst_pic_nm,
              dclr_grp_nm = EXCLUDED.dclr_grp_nm, mbr_grp_nm = EXCLUDED.mbr_grp_nm,
              dclr_type = EXCLUDED.dclr_type, prk_cvlcpt_type = EXCLUDED.prk_cvlcpt_type,
              src_path = EXCLUDED.src_path
            """;

    /**
     * 위치 파라미터 바인딩.
     *
     * <p>{@link CvlcptRecord} 는 record 라 {@code getXxx()} 게터가 없어
     * {@code BeanPropertyItemSqlParameterSourceProvider}(이름 기반)를 쓸 수 없다. SQL 이
     * 위치 파라미터({@code ?})이기도 해서 setter 를 직접 둔다.
     *
     * <p>타임스탬프는 {@code setObject} 로 넘긴다 — {@code Timestamp.valueOf(null)} 은 NPE 라
     * 원인 불명으로 죽지만, {@code setObject(null)} 이면 DB 의 {@code NOT NULL} 제약이
     * "어느 컬럼이 비었는지"를 알려 준다. {@code prcsInstCnt} 도 같은 이유로 {@code setObject} 다.
     */
    static final ItemPreparedStatementSetter<CvlcptRecord> SETTER = (item, ps) -> {
        ps.setString(1, item.originPk());
        ps.setString(2, item.cvlcptTypeCd());
        ps.setString(3, item.emdCd());
        ps.setBigDecimal(4, item.lat());
        ps.setBigDecimal(5, item.lot());
        ps.setObject(6, item.ocrnDt());
        ps.setString(7, item.srcCd());
        ps.setString(8, item.cvlcptCn());
        ps.setObject(9, item.loadDt());
        ps.setString(10, item.aprsCd());
        ps.setString(11, item.dclrYmd());
        ps.setString(12, item.ttl());
        ps.setString(13, item.dclrOcrnRgn());
        ps.setString(14, item.prgrsStts());
        ps.setString(15, item.ansPrcsStts());
        ps.setString(16, item.ansYmd());
        ps.setString(17, item.aplcntSe());
        ps.setString(18, item.dclrFld());
        ps.setString(19, item.rwrdYn());
        ps.setObject(20, item.prcsInstCnt());
        ps.setString(21, item.prcsInstNm());
        ps.setString(22, item.prcsInstPicNm());
        ps.setString(23, item.dclrGrpNm());
        ps.setString(24, item.mbrGrpNm());
        ps.setString(25, item.dclrType());
        ps.setString(26, item.prkCvlcptType());
        ps.setString(27, item.srcPath());
        ps.setString(28, item.srcHash());
    };

    public CvlcptItemWriter(DataSource dataSource) {
        setDataSource(dataSource);
        setSql(UPSERT_SQL);
        setItemPreparedStatementSetter(SETTER);
        // upsert 는 INSERT 든 UPDATE 든 항상 1행을 바꾼다. 0행이면 SQL 이나 제약이 어긋났다는
        // 뜻이라 조용히 넘기지 않는다.
        setAssertUpdates(true);
    }
}
