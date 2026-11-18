package jeju.datahub.analysis.service.impl;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 구역추천 보고서 스냅샷 조회의 원문 1행. ({@link ZoneRcmdtnReportMapper#selectZoneRcmdtnSnapshot})
 *
 * <p>404 판정 4종(보고서 없음 · 유형 불일치 · 보기를 알 수 없음 · 결과 없음)과 본문 복원을 한 쿼리로 끝내기 위한
 * 내부 VO 다 — 응답으로 나가지 않는다.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ZoneRcmdtnSnapshotVO {

    /** 실행아이디. 보고서에 실행이 연결돼 있지 않으면 null */
    private Long excnId;

    /** 분석유형코드. 실행 미연결이면 null */
    private String anlsTypeCd;

    /** 실제 적용된 분석 시작일 (YYYYMMDD) */
    private String anlsBgngYmd;

    /** 실제 적용된 분석 종료일 (YYYYMMDD) */
    private String anlsEndYmd;

    /**
     * 보기 판별 키 — 실행 행의 {@code anls_param_json.rcmdtnTypeCd} ({@code FEE} | {@code SHARE}).
     * 🔴 키를 적지 않은 실행이면 null 이다 — 그때 한쪽 파서로 추측해 읽지 않는다(D2).
     */
    private String rcmdtnTypeCd;

    /** 저장된 결과 JSON 원문. 결과 행이 없으면 null */
    private String rsltJson;

    /** 저장된 분석 계수 적용 내역 JSON 원문 (TSK-4325 D10). 이 값을 저장하기 전 보고서는 null */
    private String vrblAjmtJson;
}
