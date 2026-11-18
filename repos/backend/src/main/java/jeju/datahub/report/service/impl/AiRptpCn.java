package jeju.datahub.report.service.impl;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * AI 보고서 본문 엔티티. (parking.TB_AI_RPTP_CN — TSK-4293 Step 4·5)
 *
 * <p>{@code crt_type='AI'} 인 {@link Rptp} 1행에 본문 1행이 붙는다({@code rptp_id} 가 PK 이자 FK,
 * {@code ON DELETE CASCADE} — 보고서를 지우면 본문도 지워진다).
 *
 * <p>🔴 <b>{@code rptp_cn} 은 새니타이즈된 HTML 만 들어간다</b>(D3) — 저장 서비스가 INSERT 직전에
 * {@code AiReportHtmlSanitizer.sanitize} 를 명시적으로 부른다. 읽을 때도 한 번 더 부른다.
 *
 * <p>🔴 <b>멱등 키는 DB UNIQUE {@code (user_id, sesn_id, msg_id, rptp_seq)}</b>(D12) — 선조회만으로는
 * 동시 클릭 두 번이 모두 INSERT 된다. 위반은 저장 트랜잭션 바깥(컨트롤러)에서 잡는다.
 *
 * <p>⚠️ CLAUDE.md "대화를 우리 DB 에 저장하지 않는다" 의 <b>예외</b>다(D7) — 저장하는 것은 대화가 아니라
 * 사용자가 명시적으로 등록한 보고서 본문 1건이다.
 *
 * <p>JPA 는 {@code map-underscore-to-camel-case} 와 무관하므로 {@code @Column(name=...)} 을 필드마다 붙인다.
 */
@Entity
@Table(name = "tb_ai_rptp_cn", schema = "parking")
@Getter
@Setter
@ToString(exclude = "rptpCn")
@NoArgsConstructor
public class AiRptpCn {

    /** 보고서ID (rptp_id, BIGINT PK·FK→TB_RPTP). {@link Rptp} 저장 뒤 받은 값을 넣는다 — 채번하지 않는다 */
    @Id
    @Column(name = "rptp_id")
    private Long rptpId;

    /** 사용자아이디 (user_id, VARCHAR(20) NOT NULL) — 등록자 {@code TB_USER.user_id} · D12 멱등 키 */
    @Column(name = "user_id", nullable = false, length = 20)
    private String userId;

    /** 세션아이디 (sesn_id, VARCHAR(512) NOT NULL) — 업스트림 대화 세션 ID */
    @Column(name = "sesn_id", nullable = false, length = 512)
    private String sesnId;

    /** 메시지아이디 (msg_id, VARCHAR(64) NOT NULL) — 업스트림 assistant 메시지 {@code completion_id}(D11) */
    @Column(name = "msg_id", nullable = false, length = 64)
    private String msgId;

    /** 보고서순서 (rptp_seq, NUMERIC(10) NOT NULL) — 메시지 안 보고서 순번(요청 {@code rptIdx}) */
    @Column(name = "rptp_seq", nullable = false)
    private Integer rptpSeq;

    /** 보고서내용 (rptp_cn, TEXT NOT NULL) — 새니타이즈된 HTML */
    @Column(name = "rptp_cn", nullable = false, columnDefinition = "text")
    private String rptpCn;

    /** 등록일시 (reg_dt, NOT NULL DEFAULT now()) — 서버 실시각을 넣는다 */
    @Column(name = "reg_dt", nullable = false)
    private LocalDateTime regDt;
}
