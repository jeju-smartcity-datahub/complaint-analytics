package jeju.datahub.risknoti.service.impl;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 수신자 × 지역 매핑 엔티티. (parking.TB_NOTI_RCVR_RGN — 쓰기 전용, TSK-4250 R1·R2)
 *
 * <p>🔴 <b>{@code sggNm}·{@code emdCd} 둘 다 NULL 인 행 = 전체 지역</b>이다(R3). NULL 은 "값 없음" 이 아니다 —
 * 행이 0개여야 "수신 지역 없음"(fail-closed)이다. 읍면동 행은 {@code sggNm} 이 반드시 채워진다
 * (CHECK {@code ck_noti_rcvr_rgn_sgg} — 서비스가 {@code TB_ADMDST} 에서 다시 채운다).
 *
 * <p>🔴 수신자당 같은 지역은 1행이다 — {@code UNIQUE NULLS NOT DISTINCT (rcvr_id, sgg_nm, emd_cd)}.
 * 서비스가 저장 전에 중복을 제거하므로 이 제약 위반은 정규화 버그다(409 로 가리지 않는다).
 *
 * <p><b>조회는 이 엔티티로 하지 않는다</b> — 목록·단건의 지역 배열은 MyBatis 가 접는다(Step 5).
 * 컬럼명은 DDL {@code database/migrations/sql/89-NOTI-RCVR-RGN.sql} 기준으로 명시한다.
 */
@Entity
@Table(name = "tb_noti_rcvr_rgn", schema = "parking")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class NotiRcvrRgn {

    /** 지역일련번호 (rgn_sn, BIGINT identity PK). 값을 넣지 않는다 — DB 가 매긴다 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rgn_sn")
    private Long rgnSn;

    /** 수신자ID (rcvr_id — TB_NOTI_RCVR 참조, ON DELETE CASCADE) */
    @Column(name = "rcvr_id", nullable = false)
    private Long rcvrId;

    /** 시군구명 (sgg_nm). {@code emdCd} 와 함께 NULL 이면 전체 지역(R3) */
    @Column(name = "sgg_nm", length = 40)
    private String sggNm;

    /** 읍면동코드 (emd_cd, FK→TB_ADMDST). NULL 이면 시군구 전체 */
    @Column(name = "emd_cd", length = 8)
    private String emdCd;

    /**
     * 신규 지역 행 생성용 생성자.
     *
     * @param rcvrId 수신자ID (부모 저장 후 DB identity 가 매긴 값)
     * @param sggNm  시군구명 (전체 지역이면 null)
     * @param emdCd  읍면동코드 (시군구 전체·전체 지역이면 null)
     */
    public NotiRcvrRgn(Long rcvrId, String sggNm, String emdCd) {
        this.rcvrId = rcvrId;
        this.sggNm = sggNm;
        this.emdCd = emdCd;
    }
}
