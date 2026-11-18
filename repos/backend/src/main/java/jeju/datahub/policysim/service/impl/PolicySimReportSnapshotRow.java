package jeju.datahub.policysim.service.impl;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 정책 시뮬레이션 보고서 스냅샷 조회의 원문 1행 ({@link PolicySimMapper#selectReportSnapshot}).
 *
 * <p>404 판정 3종(보고서 없음 · 유형 불일치 · 결과 없음)과 본문 복원을 한 쿼리로 끝내기 위한 내부 VO 다 —
 * 응답으로 나가지 않는다. 🔴 {@code creatrId} 를 두지 않는다 — 소유 판정은 컨트롤러의
 * {@code ReportOwnershipGuard} 한 곳이다.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class PolicySimReportSnapshotRow {

    /** 실행아이디. 보고서에 실행이 연결돼 있지 않으면 null */
    private Long excnId;

    /** 분석유형코드. 실행 미연결이면 null */
    private String anlsTypeCd;

    /** 저장된 결과 JSON 원문. 결과 행이 없으면 null */
    private String rsltJson;
}
