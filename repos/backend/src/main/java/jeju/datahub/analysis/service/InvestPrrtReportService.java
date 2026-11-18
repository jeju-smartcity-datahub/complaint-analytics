package jeju.datahub.analysis.service;

/**
 * 투자우선순위 보고서 서비스. (분석 화면 "내보내기" = 보고서 저장)
 *
 * <p>조회 전용인 {@link InvestPrrtService} 와 달리 <b>쓰기</b> 서비스다 — 한 번의 호출이
 * {@code TB_ANLS_EXCN}(실행 1행) · {@code TB_ANLS_RSLT}(결과 1행) · {@code TB_RPTP}(보고서 1행)
 * 세 표에 <b>정확히 한 벌</b>을 남긴다({@link HotspotReportService} 와 같은 흐름).
 */
public interface InvestPrrtReportService {

    /**
     * 같은 조건으로 <b>다시 분석</b>해 그 결과를 보고서로 저장한다.
     * (POST /api/analysis/investment-priority/reports)
     *
     * <p>분석이 400/409 로 실패하면 세 표 모두 늘지 않는다. 필터를 통과한 주차장이 0곳이어도
     * 저장하지 않고 <b>400</b> 이다(집중구역과 같은 정책 — 열어도 볼 것이 없는 보고서를 남기지 않는다).
     *
     * @param req 조회 조건(지역 필터·기간 — 모두 선택) + 파일형식
     * @param userId 생성자·실행자 ID — {@code TB_USER.user_id}(인증 해석·401 은 컨트롤러 책임)
     * @param userNm 생성자명 — {@code TB_USER.user_nm}. 50자를 넘으면 잘라 저장한다
     * @return 저장된 스냅샷 그대로 — 프론트는 이 응답 하나로 문서를 그린다
     * @throws org.springframework.web.server.ResponseStatusException 400 — 기간 오류 · 없는 읍면동코드 ·
     *         시군구와 읍면동 불일치 · 결과 0곳 / 409 — 분석변수 설정 오류
     */
    InvestPrrtReportVO createReport(InvestPrrtReportCreateRequest req, String userId, String userNm);

    /**
     * 저장된 보고서를 <b>저장 시점 스냅샷 그대로</b> 다시 읽는다.
     * (GET /api/reports/{rptpId}/investment-priority)
     *
     * <p>🔴 <b>다시 계산하지 않는다</b> — 그 사이 가중치 설정이나 원천 데이터가 바뀌어도 보고서는 만들어진
     * 날의 기록이다. 본문은 {@code TB_ANLS_RSLT.rslt_json} 한 칸에서만 읽는다(가중치·등급 기준도 거기 있다).
     * 적용 내역 {@code vrblAjmt} 만 {@code anls_param_json} 에서 읽으며, 이 값을 저장하기 전 보고서는 {@code null} 이다.
     *
     * <p>🔴 <b>404 는 세 가지다</b> — ①보고서가 없다 ②{@code INVEST} 실행이 아니다(실행 미연결 포함)
     * ③결과 행이 없다.
     *
     * @param rptpId 보고서아이디
     * @return 저장된 스냅샷({@link #createReport} 와 같은 모양)
     */
    InvestPrrtReportVO selectReportSnapshot(Long rptpId);
}
