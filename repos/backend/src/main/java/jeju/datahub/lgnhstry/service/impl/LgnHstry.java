package jeju.datahub.lgnhstry.service.impl;

import java.time.LocalDateTime;

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
 * 로그인 이력 엔티티. (parking.TB_LGN_HSTRY)
 *
 * <p>영속성 규칙 기준 <b>쓰기(C·U)는 JPA</b> 다.
 * 이 표는 기록 전용이라 조회 화면(MyBatis)이 아직 없고, 접속 종료 보정도 단건을 찾아
 * 필드 하나를 바꾸는 전형적인 변경 감지(dirty checking) 경로다.
 *
 * <p>⚠️ 엔티티명에 {@code Entity} 접미사를 붙이지 않는다 — 이 레포 관례다
 * ({@code AuthrtMenu}·{@code NotiRcvr}·{@code RiskNotiHstry}).
 *
 * <p>🔴 {@code lgn_dt}·{@code lgn_yn} 은 NOT NULL 이라 <b>정적 팩토리에서 반드시 채운다</b>.
 * JPA 는 {@code @DynamicInsert} 가 없으면 매핑된 컬럼을 항상 INSERT 문에 실어 DB DEFAULT 가 무시된다.
 *
 * <p>🔴 {@code sesn_id} 는 세션 ID 원문이 아니라 {@link jeju.datahub.common.SesnIdHasher} 가 만든
 * <b>hex 소문자 16자</b> 다 — DB 가 {@code CHECK (sesn_id IS NULL OR length(sesn_id) = 16)} 으로 강제한다.
 */
@Entity
@Table(name = "tb_lgn_hstry", schema = "parking")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class LgnHstry {

    /** 로그인일련번호 (lgn_sn, BIGINT PK). 값을 넣지 않는다 — DB identity 가 매긴다. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // 🔴 AUTO 면 Hibernate 가 시퀀스를 찾다 죽는다
    @Column(name = "lgn_sn")
    private Long lgnSn;

    /** 사용자ID. 신원 확인 전 실패면 NULL (FK 없음) */
    @Column(name = "user_id", length = 20)
    private String userId;

    /** 로그인일시 (NOT NULL) */
    @Column(name = "lgn_dt", nullable = false)
    private LocalDateTime lgnDt;

    /** 로그인여부 (Y | N, NOT NULL) */
    @Column(name = "lgn_yn", length = 1, nullable = false)
    private String lgnYn;

    /** 로그인실패사유 — 🔴 원문이 아니라 마스킹된 사유 코드다 (최대 200자) */
    @Column(name = "lgn_fail_rsn", length = 200)
    private String lgnFailRsn;

    /** 로그인IP주소 (IPv6 최대 45자) */
    @Column(name = "lgn_ip_addr", length = 45)
    private String lgnIpAddr;

    /** 세션아이디 — SHA-256 앞 16자(hex). 원문을 저장하지 않는다 */
    @Column(name = "sesn_id", length = 64)
    private String sesnId;

    /** 접속종료일시 — best-effort. 브라우저 닫기·서버 재시작은 채워지지 않는다(정상) */
    @Column(name = "cntn_end_dt")
    private LocalDateTime cntnEndDt;

    /**
     * 로그인 성공 행.
     *
     * @param sesnIdHash {@link jeju.datahub.common.SesnIdHasher#shortHash(String)} 결과.
     *                   호출부가 정규화해 넘긴다 — 유효하지 않으면 null(= {@code sesn_id NULL})
     */
    public static LgnHstry success(String userId, String ipAddr, String sesnIdHash) {
        LgnHstry row = new LgnHstry();
        row.userId = userId;
        row.lgnDt = LocalDateTime.now();
        row.lgnYn = "Y";
        row.lgnIpAddr = ipAddr;
        row.sesnId = sesnIdHash;
        return row;
    }

    /**
     * 로그인 실패 행.
     *
     * <p>{@code sesn_id} 는 남기지 않는다 — 실패에는 확정된 세션이 없다.
     *
     * @param failRsn 🔴 <b>마스킹된 사유 코드</b>. 예외 메시지 원문을 넘기지 않는다
     */
    public static LgnHstry failure(String userId, String ipAddr, String failRsn) {
        LgnHstry row = new LgnHstry();
        row.userId = userId;
        row.lgnDt = LocalDateTime.now();
        row.lgnYn = "N";
        row.lgnFailRsn = failRsn;
        row.lgnIpAddr = ipAddr;
        return row;
    }
}
