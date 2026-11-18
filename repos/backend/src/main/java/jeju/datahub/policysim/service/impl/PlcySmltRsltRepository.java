package jeju.datahub.policysim.service.impl;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 정책시뮬레이션결과 JPA 리포지터리 (쓰기 전용 — {@code save} 만 쓴다).
 */
public interface PlcySmltRsltRepository extends JpaRepository<PlcySmltRslt, Long> {
}
