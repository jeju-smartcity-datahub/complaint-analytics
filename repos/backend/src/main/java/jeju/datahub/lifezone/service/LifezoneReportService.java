package jeju.datahub.lifezone.service;

/**
 * 생활권 시뮬레이션 보고서 서비스 인터페이스. (분석 화면 "내보내기")
 *
 * <p>조회 전용인 {@link LifezoneAnalysisService} 와 달리 <b>쓰기</b> 서비스다 — 한 번의 호출이
 * {@code TB_ANLS_EXCN}(실행 1행) · {@code TB_ANLS_RSLT}(결과 <b>1행</b> — 순위·셀·요약을
 * {@code rslt_json} 에 담는다) · {@code TB_RPTP}(보고서 1행) 세 표에 정확히 한 벌을 남긴다.
 *
 * <p>🔴 <b>생활권 전용 엔티티를 만들지 않는다</b> — 분석 결과 표 4종을
 * 공용 {@code TB_ANLS_RSLT} 하나로 합쳐, 쓰기에 필요한 엔티티({@code AnlsExcn}·{@code AnlsRslt})가
 * 이미 있다. 종전 {@code TB_LFZN_SMLT_RSLT} 는 마이그레이션 56 으로 폐기됐다.
 */
public interface LifezoneReportService {

    /**
     * 같은 조건으로 <b>다시 분석</b>해 그 결과를 보고서로 저장한다.
     * (POST /api/analysis/lifezone/reports)
     *
     * <p>요청에는 조회 조건과 파일형식만 있다 — 화면이 보낸 숫자를 그대로 굳히면 보고서가
     * 화면의 오류까지 영구 보존하고, 같은 조건을 다시 분석했을 때와 값이 달라져도 알 수 없다.
     *
     * <p>결과가 0건이면 저장하지 않고 <b>400</b> 이다 — 조회 API 의 "0건도 200"과 다르다.
     * 빈 보고서는 목록에 남아도 열 수 없어 사용자에게는 고장으로 보인다.
     *
     * @param req 분석 조건({@link LifezoneAnalysisRequest} 와 같은 필드) + 파일형식
     * @param userId 생성자·실행자 ID — {@code TB_USER.user_id}(인증 해석·401 은 컨트롤러 책임)
     * @param userNm 생성자명 — {@code TB_USER.user_nm}. 50자를 넘으면 잘라 저장한다
     * @return 저장된 스냅샷 그대로 — 프론트는 이 응답 하나로 문서를 그린다
     */
    LifezoneReportVO createReport(LifezoneReportCreateRequest req, String userId, String userNm);

    /**
     * 저장된 보고서를 <b>생성 당시 스냅샷 그대로</b> 다시 읽는다.
     * (GET /api/reports/&#123;rptpId&#125;/lifezone)
     *
     * <p>다시 분석하지 않는다 — {@link #createReport} 와 정반대다. 원천 데이터가 그 사이 바뀌어도
     * 보고서는 만들어진 날의 기록이어야 하므로 {@code rslt_json}(요약·순위·셀)과
     * {@code anls_param_json}(조건·문구·경계), 실행 행의 기간·지역만 읽어 같은 응답 모양을 만든다.
     *
     * <p>🔴 <b>404 는 세 가지다</b> — ①보고서가 없다 ②{@code LIFEZONE} 실행이 아니다
     * ③스냅샷이 비었다. ③을 빼면 시드로 만들어진 보고서가 <b>빈 셀 + NULL 경계</b>로 200 을 받아
     * 화면이 깨진 문서를 그린다.
     *
     * @param rptpId 보고서아이디
     * @return 저장된 스냅샷 (헤더 + 조건 echo + 등급 기준 + 요약 + 셀 + 순위)
     */
    LifezoneReportVO selectReportSnapshot(Long rptpId);
}
