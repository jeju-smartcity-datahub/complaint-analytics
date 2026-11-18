package jeju.datahub.analysis.service;

/**
 * 집중구역 분석 보고서 생성 서비스 인터페이스. (분석 화면 "내보내기" — TSK-4078)
 *
 * <p>조회 전용인 {@link HotspotAnalysisService} 와 달리 <b>쓰기</b> 서비스다 —
 * 한 번의 호출이 {@code TB_ANLS_EXCN}(실행 1행) · {@code TB_ANLS_RSLT}(결과 1행 — 순위 전체를
 * {@code rslt_json} 에 담는다, D15) · {@code TB_RPTP}(보고서 1행) 세 표에 <b>정확히 한 벌</b>을 남긴다.
 *
 * <p>분석 자체는 다시 구현하지 않고 {@link HotspotAnalysisService#analyze(HotspotAnalysisRequest)}
 * 를 호출한다(D3) — 화면이 보낸 숫자를 그대로 저장하면 보고서가 화면의 오류까지 그대로 굳힌다.
 */
public interface HotspotReportService {

    /**
     * 같은 조건으로 <b>다시 분석</b>해 그 결과를 보고서로 저장한다.
     * (POST /api/analysis/hotspot/reports)
     *
     * <p>결과가 0건이면 저장하지 않고 <b>400</b> 이다 — 조회 API 의 "0건도 200"(D11)과 다르다.
     * 빈 보고서는 목록에 남아도 열 수 없어 사용자에게는 고장으로 보인다.
     *
     * @param req 분석 조건({@link HotspotAnalysisRequest} 와 같은 필드) + 파일형식
     * @param userId 생성자·실행자 ID — {@code TB_USER.user_id}(인증 해석·401 은 컨트롤러 책임)
     * @param userNm 생성자명 — {@code TB_USER.user_nm}. 50자를 넘으면 잘라 저장한다
     * @return 저장된 스냅샷 그대로 — 프론트는 이 응답 하나로 문서를 그린다(D3)
     */
    HotspotReportVO createReport(HotspotReportCreateRequest req, String userId, String userNm);

    /**
     * 저장된 보고서를 <b>생성 당시 스냅샷 그대로</b> 다시 읽는다.
     * (GET /api/reports/{rptpId}/hotspot)
     *
     * <p>다시 분석하지 않는다 — {@link #createReport} 와 정반대다. 원천 데이터가 그 사이 바뀌어도
     * 보고서는 만들어진 날의 기록이어야 하므로 {@code TB_ANLS_RSLT.rslt_json} 의 저장값과
     * {@code TB_ANLS_EXCN.anls_param_json} 의 가중치만 읽어 같은 응답 모양으로 조립한다.
     *
     * <p>이 GET 이 {@code report} 가 아니라 {@code analysis} 서비스에 있는 이유: 404 3종 판정 ·
     * 파라미터 역직렬화 · {@code HotspotGrade} 판정 · {@link HotspotReportVO} 조립이 전부 집중구역
     * 도메인 지식이고, <b>POST 와 GET 이 같은 VO 를 만들므로</b> 한 클래스가 소유해야 조립이 갈리지 않는다.
     *
     * <p>🔴 <b>404 는 세 가지다</b> — ①보고서가 없다 ②{@code HOTSPOT} 실행이 아니다
     * ③스냅샷이 0행이다. ③을 빼면 시드 자동 생성 보고서처럼 유형만 {@code HOTSPOT} 이고 결과가
     * 없는 행이 <b>빈 구역 목록 + NULL 가중치</b>로 200 을 받아 화면이 깨진 문서를 그린다.
     *
     * <p>🔴 응답의 {@code zones} 는 POST 와 <b>비대칭</b>이다 — 결과 테이블에 컬럼이 없어
     * {@code emdNm}·{@code zoneNmSrc} 가 null 이고 좌표는 {@code NUMERIC} 반올림값이다.
     * 두 칸이 값을 갖는다고 가정하는 코드를 만들지 않는다.
     *
     * @param rptpId 보고서아이디
     * @return 저장된 스냅샷 (헤더 4칸 + 가중치 + 구역 목록)
     */
    HotspotReportVO selectReportSnapshot(Long rptpId);
}
