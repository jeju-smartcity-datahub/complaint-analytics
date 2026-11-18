package jeju.datahub.report.service.impl;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * AI 보고서 본문 JPA 리포지터리 (TSK-4293 Step 5).
 *
 * <p>쓰기({@code saveAndFlush}) · PK 단건({@code findById} — 스냅샷) · 멱등 키 단건 조회만 둔다
 * ({@code rule_persistence-jpa-mybatis.md} — 쓰기·단순 단일 엔티티 조회 = JPA).
 */
public interface AiRptpCnRepository extends JpaRepository<AiRptpCn, Long> {

    /** D12 멱등 키(UNIQUE {@code uq_tb_ai_rptp_cn_src})로 이미 등록된 본문을 찾는다 */
    Optional<AiRptpCn> findByUserIdAndSesnIdAndMsgIdAndRptpSeq(
            String userId, String sesnId, String msgId, Integer rptpSeq);
}
