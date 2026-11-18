package jeju.datahub.analysis.service;

/**
 * 구역추천 보고서 서비스. (분석 화면 "내보내기" = 보고서 저장 — TSK-4240)
 *
 * <p>조회 창구인 {@link ZoneRcmdtnService} 와 달리 <b>보고서를 남기는</b> 서비스다 — 한 번의 호출이
 * {@code TB_ANLS_EXCN}(실행 1행) · {@code TB_ANLS_RSLT}(결과 1행) · {@code TB_RPTP}(보고서 1행)
 * 세 표에 <b>정확히 한 벌</b>을 남긴다({@link InvestPrrtReportService} 와 같은 흐름 — D5).
 *
 * <p>🔴 <b>생성자는 인자로 받는다</b>(D6). 이 서비스는 인증 기술을 모르고 {@code userId}·{@code userNm} 문자열만
 * 받는다 — 로그인 사용자 해석과 401 판정은 컨트롤러({@code CurrentUserResolver})가 서비스보다 앞에서 끝낸다.
 * 폴백 상수를 두지 않는다(D7).
 *
 * <p>두 보기(요금제 적용 · 공유주차제 적용)는 분석유형코드가 같고({@code RECOMMEND}) 실행 행의
 * {@code anls_param_json.rcmdtnTypeCd}({@code FEE}/{@code SHARE})로 갈린다(D1).
 */
public interface ZoneRcmdtnReportService {

    /**
     * 요금제 적용 보고서 — 같은 조건으로 <b>다시 계산</b>해 그 결과를 보고서로 저장한다.
     * (POST /api/analysis/zone-recommendation/reports)
     *
     * <p>계산이 400/404 로 실패하면 세 표 모두 늘지 않는다. 후보 주차장이 0개소여도 저장하지 않고
     * <b>400</b> 이다(D9 — 열어도 볼 것이 없는 보고서를 남기지 않는다).
     * 🔴 <b>주차장이 있으면 만든다</b>(D10). 🔁 TSK-4340 — 읍면동 판정(FEE/SHARE·판정 불가)은 제거됐다.
     *
     * @param req    조회 조건(읍면동 · 민원 창 필수, 단속 창 · 유형 범위 선택) + 파일형식
     * @param userId 생성자·실행자 ID — {@code TB_USER.user_id}({@code cert_id} 가 아니다 — D8)
     * @param userNm 생성자명 — {@code TB_USER.user_nm}. 50자를 넘으면 잘라 저장한다(D8)
     * @return 저장된 스냅샷 그대로({@code rcmdtnTypeCd='FEE'} · {@code fee} 채움 · {@code share} 는 null)
     * @throws org.springframework.web.server.ResponseStatusException 400 — 기간·유형 범위 오류 · 후보 0개소 /
     *         404 — 실재하지 않는 읍면동코드
     */
    ZoneRcmdtnReportVO createFeeReport(ZoneRcmdtnReportCreateRequest req, String userId, String userNm);

    /**
     * 공유주차제 적용 보고서 — 같은 조건으로 <b>다시 계산</b>해 그 결과를 보고서로 저장한다.
     * (POST /api/analysis/zone-recommendation/share/reports — TSK-4240 Step 3)
     *
     * <p>계산이 400 으로 실패하면 세 표 모두 늘지 않는다. 검토 후보({@code rvwTrgtYn='Y'})가 0곳이어도 저장하지
     * 않고 <b>400</b> 이다(D9). 🔴 {@code zones} 가 비었는지가 아니다 — 후보가 없어도 {@code zones} 는 필터에 걸린
     * 읍면동 전부로 온다.
     *
     * <p>🔴 저장하는 {@code zones} 는 <b>후보만으로 자르지 않는다</b> — 자르면 {@code smry} 의 건수와 어긋난다.
     * 후보만 고르는 것은 문서 템플릿의 일이다.
     *
     * @param req    조회 조건(민원 창 필수, 시군구 필터 선택 — 읍면동 필터는 없다) + 파일형식
     * @param userId 생성자·실행자 ID — {@code TB_USER.user_id}(D8)
     * @param userNm 생성자명 — {@code TB_USER.user_nm}. 50자를 넘으면 잘라 저장한다(D8)
     * @return 저장된 스냅샷 그대로({@code rcmdtnTypeCd='SHARE'} · {@code share} 채움 · {@code fee} 는 null)
     * @throws org.springframework.web.server.ResponseStatusException 400 — 기간 오류 · 40자를 넘거나 실재하지
     *         않는 시군구명 · 검토 후보 0곳
     */
    ZoneRcmdtnReportVO createShareReport(ZoneRcmdtnShareReportCreateRequest req, String userId, String userNm);

    /**
     * 보고서 스냅샷 조회 — 저장된 본문을 저장 시점 그대로 돌려준다(다시 계산하지 않는다).
     * (GET /api/reports/{rptpId}/zone-recommendation — TSK-4240 Step 4)
     *
     * <p>응답 모양은 생성 API 와 같다 — 머리 9칸 + {@code rcmdtnTypeCd} + 본문 하나. 두 보기가 엔드포인트
     * <b>하나</b>를 같이 쓰고, 어느 본문을 채울지는 실행 행의 {@code anls_param_json.rcmdtnTypeCd} 가 정한다(D2).
     *
     * <p>🔴 <b>판별 키가 없거나 모르는 값이면 추측하지 않고 404</b> 다 — 다른 모양의 스냅샷을 엉뚱한 파서가
     * 읽으면 예외 없이 빈 표가 나온다.
     *
     * @param rptpId 보고서아이디
     * @return 저장된 스냅샷({@code FEE} 면 {@code fee}, {@code SHARE} 면 {@code share} 만 채운다)
     * @throws org.springframework.web.server.ResponseStatusException 404 — 보고서 없음 · 구역추천 보고서가 아님 ·
     *         보기를 알 수 없음 · 저장된 결과 없음 / 500 — 저장된 결과를 되읽지 못함
     */
    ZoneRcmdtnReportVO selectReportSnapshot(Long rptpId);
}
