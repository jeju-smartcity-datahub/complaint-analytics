package jeju.datahub.aisurvey.service.impl;

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
 * AI 어시스턴트 만족도 설문 응답 1건 — {@code parking.TB_AI_DGSTFN_SRVY}(마이그레이션 115).
 *
 * <p>쓰기는 JPA 다. 대화 원문은 담지 않고 응답 당시 세션 ID 만
 * 참고용으로 남긴다.
 */
@Entity
@Table(name = "tb_ai_dgstfn_srvy", schema = "parking")
@Getter
@Setter
@ToString(exclude = "opnnCn")
@NoArgsConstructor
public class AiDgstfnSrvy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "srvy_sn")
    private Long srvySn;

    @Column(name = "user_id", nullable = false, length = 20)
    private String userId;

    @Column(name = "sesn_id", length = 512)
    private String sesnId;

    /** 1~10. DB CHECK {@code ck_tb_ai_dgstfn_srvy_scr} 가 함께 막는다. */
    @Column(name = "dgstfn_scr", nullable = false)
    private Integer dgstfnScr;

    @Column(name = "opnn_cn", length = 4000)
    private String opnnCn;

    @Column(name = "reg_dt", nullable = false)
    private LocalDateTime regDt;
}
