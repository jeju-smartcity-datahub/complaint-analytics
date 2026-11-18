package jeju.datahub.report.service;

/**
 * 보고서 <b>자동 생성</b> 서비스. (POST /api/internal/reports/auto-crt)
 *
 * <p>배치({@code reportAutoCrtJob})가 "주기가 도래했다" 고 판정한 설정 1건을 받아 보고서 1건을
 * 만든다. 🔴 <b>집계를 새로 구현하지 않는다</b> — 유형에 따라 기존 생성 서비스
 * ({@code ComplaintReportService} · {@code HotspotReportService})를 <b>그대로 호출</b>하고,
 * 그 결과 행에 자동 생성 표식({@code crt_type='AUTO'} + 설정 4칸)만 덮어쓴다.
 * 집계 규칙이 두 곳으로 갈리면 수동 생성과 자동 생성의 숫자가 조용히 달라진다.
 *
 * <h2>🔴 상태코드가 배치의 분기 기준이다</h2>
 *
 * <table border="1">
 *   <caption>호출 결과</caption>
 *   <tr><th>상황</th><th>이 서비스</th><th>컨트롤러</th><th>배치</th></tr>
 *   <tr><td>새로 만들었다</td><td>{@code created=true}</td><td>201</td>
 *       <td>{@code last_crt_dt} 갱신</td></tr>
 *   <tr><td>이미 있다</td><td>{@code created=false}</td><td>200</td>
 *       <td>정상 통과 — 스킵이 아니다</td></tr>
 *   <tr><td>설정이 없다·삭제됐다·요청이 설정과 어긋난다</td><td>400</td><td>400</td>
 *       <td>스킵 + 사유 기록 (①)</td></tr>
 *   <tr><td>{@code HOTSPOT} 인데 분석 결과 0건</td><td>400 (기존 서비스가 던진다)</td><td>400</td>
 *       <td>스킵 + 사유 기록</td></tr>
 * </table>
 *
 * <p>🔴 <b>0건 정책이 유형마다 반대다</b> — {@code CVLCPT} 는 0건도 정식 보고서를 남기고
 * ({@code }), {@code HOTSPOT} 은 0건이면 400 이다. 이 서비스는 그 차이를 <b>중화하지
 * 않는다</b> — {@code HotspotReportService} 가 던지는 400 을 삼키면 배치가 "성공" 으로 세어
 * 스킵 사유가 사라진다.
 */
public interface ReportAutoCrtService {

    /**
     * 설정 1건 · 대상기간 1개로 보고서를 만든다. <b>멱등</b>하다 — 같은
     * {@code (stngId, trgtPrdCd)} 로 몇 번을 불러도 {@code TB_RPTP} 에는 한 행만 남는다.
     *
     * <p>처리 순서는 {@code ReportAutoCrtServiceImpl} 의 javadoc 참고
     * (①설정 검증 ②멱등 확인 ③지역 변환 ④유형 분기·생성 ⑤AUTO 표식 덮기).
     *
     * <p>🔴 <b>{@code last_crt_dt} 는 갱신하지 않는다</b> — 배치가 갱신한다. 여기서 건드리면
     * 배치가 호출에 실패해 스킵한 경우에도 "최근 생성" 이 갱신돼 화면이 거짓말을 한다.
     *
     * @param req 배치가 보낸 설정 원문 + 산출된 대상기간
     * @return 만든(또는 이미 있던) 보고서의 식별자와 {@code created} 플래그
     * @throws org.springframework.web.server.ResponseStatusException 400 — 설정 불일치·삭제된 설정·
     *         지역 미존재, 그리고 {@code HOTSPOT} 0건(기존 서비스가 던지는 것을 <b>그대로 전파</b>한다)
     */
    ReportAutoCrtVO createReport(ReportAutoCrtRequest req);
}
