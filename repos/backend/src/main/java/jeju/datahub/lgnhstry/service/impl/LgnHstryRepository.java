package jeju.datahub.lgnhstry.service.impl;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 로그인 이력 JPA 리포지터리 (TSK-4104). PK 는 {@code Long} — {@code lgn_sn} 이 BIGINT identity 다.
 *
 * <p>🔴 조회 메서드를 늘리지 않는다 — 이력 <b>조회</b>가 생기면 필터·페이징·집계라 MyBatis 담당이다.
 * 여기 있는 파생 쿼리 하나는 "접속 종료 보정 대상 1건"을 고르기 위한 <b>쓰기 경로의 일부</b>다.
 */
public interface LgnHstryRepository extends JpaRepository<LgnHstry, Long> {

    /**
     * 해당 세션 해시의 <b>가장 최근 성공 행 중 아직 닫히지 않은 1건</b>.
     *
     * <p>🔴 PostgreSQL 의 {@code UPDATE} 에는 {@code ORDER BY}/{@code LIMIT} 절이 없어
     * {@code UPDATE ... WHERE sesn_id = ?} 로 뭉뚱그리면 세션 ID 재사용 시 과거 행까지 닫힌다.
     * 대상 1건을 먼저 고르고 세터로 갱신한다(쓰기=JPA 규약 유지).
     */
    Optional<LgnHstry> findFirstBySesnIdAndLgnYnAndCntnEndDtIsNullOrderByLgnDtDesc(String sesnId, String lgnYn);
}
