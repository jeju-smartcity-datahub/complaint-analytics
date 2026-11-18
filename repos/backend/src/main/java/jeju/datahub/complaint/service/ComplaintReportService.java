package jeju.datahub.complaint.service;

/**
 * 민원현황 보고서 생성 서비스 인터페이스. (민원현황 화면 "내보내기" — TSK-4164)
 *
 * <p>조회 전용인 {@link ComplaintService} 와 달리 <b>쓰기</b> 서비스다 — 한 번의 호출이
 * {@code TB_ANLS_EXCN}(실행 1행) · {@code TB_ANLS_RSLT}(결과 1행 — 조회 응답 모양 그대로
 * {@code rslt_json} 에 담는다, D9) · {@code TB_RPTP}(보고서 1행) 세 표에
 * <b>정확히 한 벌</b>을 남긴다(D1 — 새 표를 만들지 않는다).
 *
 * <p>집계 자체는 다시 구현하지 않고 조회 서비스를 그대로 호출한다(D2) — 화면이 보낸 숫자를
 * 그대로 저장하면 보고서가 화면의 오류까지 그대로 굳힌다.
 */
public interface ComplaintReportService {

    /**
     * 같은 조건으로 <b>다시 집계</b>해 그 결과를 보고서로 저장한다.
     * (POST /api/complaints/reports)
     *
     * <p>🔴 결과가 0건이어도 <b>201</b> 로 정식 보고서를 남긴다(D5) — 집중구역
     * ({@code HotspotReportService#createReport} 는 0건이면 400)과 정책이 다르다. 0건을 막으면
     * 유형이 {@code CVLCPT} 인데 결과가 없는 행이 스냅샷 경로로 새어 404 가 되고 미리보기가
     * 통째로 실패한다.
     *
     * @param req 조회 조건({@link ComplaintRequest} 와 같은 필드) + 파일형식
     * @param userId 생성자·실행자 ID — {@code TB_USER.user_id}(인증 해석·401 은 컨트롤러 책임)
     * @param userNm 생성자명 — {@code TB_USER.user_nm}. 50자를 넘으면 잘라 저장한다
     * @return 저장된 스냅샷 그대로 — 프론트는 이 응답 하나로 문서를 그린다
     */
    ComplaintReportVO createReport(ComplaintReportCreateRequest req, String userId, String userNm);

    /**
     * 저장된 보고서를 <b>재분석 없이</b> 생성 당시 스냅샷 그대로 돌려준다.
     * (GET /api/reports/{rptpId}/complaint — TSK-4164 Step 4)
     *
     * <p>응답 모양은 {@link #createReport} 의 201 과 <b>같다</b> — 프론트가 두 경로를 한 렌더러로 그린다.
     *
     * <p>404 는 셋이다 — ① 보고서가 없다 ② 민원현황 보고서가 아니다({@code anls_type_cd != 'CVLCPT'},
     * 실행 미연결로 null 인 경우 포함) ③ 저장된 결과가 없다({@code rslt_json} 이 null / 역직렬화 실패).
     *
     * <p>🔴 <b>"0건" 은 404 사유가 아니다</b>(D5). {@code emdCompare=[]} 인 스냅샷은 정상이고
     * <b>200</b> 이다 — 집중구역의 {@code 0행 → 404} 규칙을 옮기면 0건 보고서가 영영 열리지 않는다.
     *
     * @param rptpId 보고서아이디
     * @return 저장된 스냅샷 (헤더 7칸 + kpi + 배열 2개)
     */
    ComplaintReportVO selectReportSnapshot(Long rptpId);
}
