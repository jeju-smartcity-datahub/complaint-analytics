package jeju.datahub.aisurvey.service.impl;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** 설문 응답 쓰기 + 내 최근 응답 1건 조회(단순 단일 엔티티 조회라 JPA). 인덱스 {@code ix_tb_ai_dgstfn_srvy_user_reg}. */
public interface AiDgstfnSrvyRepository extends JpaRepository<AiDgstfnSrvy, Long> {

    Optional<AiDgstfnSrvy> findFirstByUserIdOrderByRegDtDesc(String userId);
}
