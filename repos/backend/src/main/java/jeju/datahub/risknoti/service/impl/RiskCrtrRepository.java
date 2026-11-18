package jeju.datahub.risknoti.service.impl;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 위험단계 알림 기준 JPA 리포지터리. (쓰기 전용 — 3건 일괄 UPSERT)
 *
 * <p>PK 는 {@code String} 이다 — {@code thrsh_id} 가 {@code 'THRSH_' + risk_lvl_cd} 인
 * <b>할당형 문자열 PK</b> 이기 때문 (TSK-4028 D7·D11 예외).
 *
 * <p>🔴 할당형 PK 라 {@code save()} 는 {@code persist} 가 아니라 {@code em.merge()} 로 간다.
 * merge 는 flush 를 유발하지 않으므로, MyBatis 로 재조회하기 전에 <b>반드시 {@code flush()}</b> 를
 * 호출해야 PUT 응답이 갱신 전 값으로 나가지 않는다 (Step 6 규칙).
 *
 * <p>조회 메서드를 여기 늘리지 않는다 — 정렬이 {@code TB_COM_CD.sort_seq} 조인이라
 * 조회는 MyBatis({@link RiskNotiMapper#selectCriteria()}) 담당이다.
 */
public interface RiskCrtrRepository extends JpaRepository<RiskCrtr, String> {
}
