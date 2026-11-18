package jeju.datahub.batch.risknoti;

import javax.sql.DataSource;
import org.springframework.batch.item.database.ItemPreparedStatementSetter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.stereotype.Component;

/**
 * {@code parking.tb_risk_stp_noti_hstry} upsert Writer.
 *
 * <h2>🔵 rule_persistence(JPA+MyBatis) 의 예외</h2>
 * 백엔드 규칙은 "쓰기(CUD)는 JPA"지만 <b>이 배치의 쓰기는 JPA 로 하지 않는다.</b>
 * <ul>
 *   <li><b>여기는 백엔드 애플리케이션이 아니다.</b> 배치는 별도 앱이고 JPA/MyBatis 의존성 자체를
 *       갖지 않는다({@code build.gradle} 은 {@code starter-jdbc} 만 쓴다). 엔티티를 들이면
 *       백엔드와 매핑을 이중 관리하게 된다.</li>
 *   <li><b>연산이 upsert 다.</b> 재판정을 재실행 안전하게 만들려면
 *       {@code ON CONFLICT (emd_cd, ocrn_dt) DO UPDATE} 가 필요한데, JPA 로는 "조회 후 판단 →
 *       저장"(행마다 SELECT)이 되어 목적과 비용이 모두 어긋난다. 더구나 아래처럼
 *       <b>일부 컬럼만 골라 갱신</b>하는 것이 이 SQL 의 핵심인데, 엔티티 저장은 그 통제를 주지 않는다.</li>
 *   <li><b>청크 쓰기다.</b> {@link JdbcBatchItemWriter} 는 청크를 JDBC batch 한 번으로 내보낸다.
 *       영속성 컨텍스트·변경 감지가 오히려 부담이고, 이 경로에 재사용할 도메인 로직도 없다.</li>
 * </ul>
 * 즉 규칙의 취지(도메인 쓰기 로직을 한 곳에 모은다)를 깨는 게 아니라, <b>적용 대상 밖</b>이다.
 *
 * <h2>🔴 열람·확인 4컬럼을 SET 에 넣지 않는다 (D7)</h2>
 * {@code prsl_yn}·{@code prsl_dt}(열람) · {@code idnty_yn}·{@code idnty_dt}(확인)는 사람이 화면에서
 * 바꾸는 값이다. 재판정이 <b>이미 열람한 알림을 미열람으로 되돌리면 안 된다.</b> INSERT 컬럼 목록에도
 * 넣지 않으므로 신규 행은 DDL 기본값({@code 'N'})으로 들어간다.
 * (49-STANDARDIZE-PARKING-NAMES 개명: {@code read_*}→{@code prsl_*} 열람 ·
 * {@code cnfm_*}→{@code idnty_*} 확인. 제약 {@code ck_risk_hstry_read_cnfm} 도 건드리지 않는다.)
 *
 * <h2>🔴 {@code prcs_stts_cd} 도 SET 에 넣지 않는다</h2>
 * INSERT 에서 {@code 'OPEN'} 리터럴로 한 번만 정한다. 이후 다른 기능이 처리상태를 다루기
 * 시작하면 재판정이 사람이 옮겨 놓은 상태를 되돌리게 되므로, 갱신 대상에서 미리 뺀다.
 */
@Component
public class RiskAlertItemWriter extends JdbcBatchItemWriter<RiskAlertRecord> {

    /** 파라미터 순서 = {@link RiskAlertRecord} 필드 순서 = {@link #SETTER} 의 인덱스 순서. 셋을 어긋나게 두지 말 것. */
    static final String UPSERT_SQL = """
            INSERT INTO parking.TB_RISK_STP_NOTI_HSTRY
                   (risk_lvl_cd, emd_cd, cvlcpt_cnt, incrs_rt, noti_ctt, incrs_rt_thrsh, ocrn_dt, prcs_stts_cd)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN')
            ON CONFLICT (emd_cd, ocrn_dt) DO UPDATE SET
              risk_lvl_cd    = EXCLUDED.risk_lvl_cd,
              cvlcpt_cnt     = EXCLUDED.cvlcpt_cnt,
              incrs_rt       = EXCLUDED.incrs_rt,
              noti_ctt       = EXCLUDED.noti_ctt,
              incrs_rt_thrsh = EXCLUDED.incrs_rt_thrsh
            """;

    /**
     * 위치 파라미터 바인딩.
     *
     * <p>{@link RiskAlertRecord} 는 record 라 {@code getXxx()} 게터가 없어
     * {@code BeanPropertyItemSqlParameterSourceProvider}(이름 기반)를 쓸 수 없다. SQL 이 위치
     * 파라미터({@code ?})이기도 해서 setter 를 직접 둔다(선례 {@code CvlcptItemWriter}).
     *
     * <p>{@code ocrn_dt} 는 {@code setObject} 로 넘긴다 — {@code Timestamp.valueOf(null)} 은 NPE 라
     * 원인 불명으로 죽지만, {@code setObject(null)} 이면 DB 의 {@code NOT NULL} 제약이
     * "어느 컬럼이 비었는지"를 알려 준다.
     */
    static final ItemPreparedStatementSetter<RiskAlertRecord> SETTER = (item, ps) -> {
        ps.setString(1, item.riskLvlCd());
        ps.setString(2, item.emdCd());
        // cvlcpt_cnt 는 INTEGER 다. 집계 결과가 long 이라 그대로 넘기며(암시적 대입 캐스팅),
        // 하루치 읍면동 민원 건수라 int 범위를 넘길 일이 없다.
        ps.setLong(3, item.cvlcptCnt());
        ps.setBigDecimal(4, item.incrsRt());
        ps.setString(5, item.notiCtt());
        ps.setBigDecimal(6, item.incrsRtThrsh());
        ps.setObject(7, item.ocrnDt());
    };

    public RiskAlertItemWriter(DataSource dataSource) {
        setDataSource(dataSource);
        setSql(UPSERT_SQL);
        setItemPreparedStatementSetter(SETTER);
        // upsert 는 INSERT 든 UPDATE 든 항상 1행을 바꾼다. 0행이면 SQL 이나 제약이 어긋났다는
        // 뜻이라 조용히 넘기지 않는다.
        setAssertUpdates(true);
    }
}
