package jeju.datahub.lifezone.service;

/**
 * 생활권 주차 수급 보고서 서비스. (TSK-4089)
 *
 * <p>민원건수 보고서({@link LifezoneReportService})와 합치지 않는다 — 저장하는 결과 JSON 의
 * 모양이 달라(5등급 ↔ 4사분면) 되읽는 파서도 갈린다.
 */
public interface LifezoneSupplyReportService {

    /**
     * 같은 조건으로 다시 분석해 실행·결과·보고서 3표에 저장한다. 결과 0건이면 400.
     *
     * @param req    조회 조건 + 파일형식
     * @param userId 생성자·실행자 ID — {@code TB_USER.user_id}(인증 해석·401 은 컨트롤러 책임)
     * @param userNm 생성자명 — {@code TB_USER.user_nm}. 50자를 넘으면 잘라 저장한다
     */
    LifezoneSupplyReportVO createReport(LifezoneSupplyReportCreateRequest req, String userId, String userNm);

    /** 저장된 스냅샷을 생성 당시 그대로 되읽는다(재분석하지 않는다). 없으면 404 */
    LifezoneSupplyReportVO selectReportSnapshot(Long rptpId);
}
