package jeju.datahub.report.service;

import java.util.List;

import jeju.datahub.common.PageResponse;

/**
 * 보고서 서비스 인터페이스. (보고서 목록 · 보고서 관리 2화면)
 *
 * <p>조회 3종은 모두 다중 JOIN + 페이징이라
 * 영속성 규칙 기준 MyBatis 담당이고,
 * 설정 쓰기 3종(등록·수정·삭제)은 같은 규칙에 따라 JPA 담당이다.
 */
public interface ReportService {

    /**
     * 보고서 목록 조회 (페이징).
     *
     * <p>🔴 <b>소유자 필터</b> — {@code MANUAL}·{@code AI} 는 요청자가 만든 것만,
     * {@code AUTO} 는 배포 부서가 요청자 부서와 같거나 {@code ALL} 이거나 요청자가 보고서 관리 메뉴를
     * 가졌을 때만 보인다. 서비스는 인증 기술을 모른다 — {@code user_id} 문자열만 받고,
     * principal 해석과 401 판정은 컨트롤러({@code CurrentUserResolver})가 한다.
     *
     * <p>🔁 <b>목록 필터</b> — 보고서명·출처·생성일 조건을 목록·총건수에 <b>함께</b> 건다.
     * 필터는 소유자 조건 뒤에 {@code AND} 로만 붙는다 — 열람 범위를 넓히지 않는다.
     *
     * @param crtType 생성구분 (AUTO | MANUAL | AI). 화이트리스트 검증은 컨트롤러가 한다
     * @param ownerId 요청자의 {@code TB_USER.user_id}
     * @param filter  목록 필터 — 필터가 없으면 {@link ReportListFilter#NONE}({@code null} 도 미적용으로 본다)
     * @param page    0-base 페이지 번호
     * @param size    페이지 크기
     * @return 목록 + 페이징 메타
     * @throws org.springframework.web.server.ResponseStatusException 400 — keyword 100자 초과 ·
     *         사용중이 아닌 anlsTypeCd · fromYmd &gt; toYmd
     */
    PageResponse<ReportListVO> selectReports(String crtType, String ownerId, ReportListFilter filter,
                                             int page, int size);

    /**
     * 보고서 1건 조회 (미리보기 헤더용).
     *
     * @param rptpId 보고서ID
     * @return 보고서 1건
     * @throws org.springframework.web.server.ResponseStatusException 없는 보고서면 404
     */
    ReportListVO selectReport(Long rptpId);

    /**
     * 직접 생성 보고서 삭제 — <b>하드 삭제</b>다 ({@code TB_RPTP} 행만 지운다).
     *
     * <p>소유 판정(404)은 컨트롤러가 {@code ReportOwnershipGuard} 로 먼저 한다 — 여기는 생성구분만 본다.
     * {@code TB_RPTP} 를 참조하는 FK 가 없어 행을 지워도 다른 표가 깨지지 않는다.
     * 묶인 분석 실행·결과({@code TB_ANLS_EXCN}·{@code TB_ANLS_RSLT})는 남긴다 — 대시보드 분석 카드 등
     * 보고서와 무관하게 실행을 읽는 화면이 있다.
     *
     * @param rptpId 보고서ID
     * @throws org.springframework.web.server.ResponseStatusException
     *         없으면 404, 자동 생성 보고서면 400
     */
    void deleteReport(Long rptpId);

    /**
     * 자동 생성 설정 목록 조회 (전체, {@code crt_dt DESC}).
     * 소프트 삭제분({@code del_dt IS NOT NULL})은 제외한다.
     */
    List<ReportSettingVO> selectSettings();

    /**
     * 자동 생성 설정 등록.
     *
     * <p>{@code stngId} 는 DB identity 가 매기고, 다음 배포 예정일({@code crltnYmd})·활성여부·
     * 생성자·생성일시는 서버가 채운다. 지역은 {@code emdCd} 기준으로 서버가 정규화한다.
     *
     * @param userId 생성자 ID — {@code TB_USER.user_id}(해석·401 은 컨트롤러 책임)
     * @param userNm 생성자명 — {@code TB_USER.user_nm}. {@code creatr_nm} 은 50자라 넘으면 잘라 저장한다
     * @return 저장 직후 재조회한 설정 1건 (공통코드명·지역 표시명이 붙은 VO)
     * @throws org.springframework.web.server.ResponseStatusException
     *         없는 코드값·주기별 필수값 누락·없는 지역이면 400
     */
    ReportSettingVO createSetting(ReportSettingRequest request, String userId, String userNm);

    /**
     * 자동 생성 설정 수정. 등록과 같은 검증·정규화를 거치고 {@code crltnYmd} 도 다시 계산한다.
     *
     * <p>{@code creatrId}·{@code creatrNm}·{@code crtDt}·{@code actvtnYn}·{@code lastCrtDt} 는
     * 요청 바디에 없으므로 기존 값을 그대로 둔다.
     *
     * @return 저장 직후 재조회한 설정 1건
     * @throws org.springframework.web.server.ResponseStatusException
     *         없거나 이미 삭제된 설정이면 404, 입력이 잘못됐으면 400
     */
    ReportSettingVO updateSetting(Long stngId, ReportSettingRequest request);

    /**
     * 자동 생성 설정 삭제 — <b>소프트 삭제</b>다.
     *
     * <p>행을 지우지 않고 {@code del_dt} 을 채운다. 보고서가 참조 중이어도 성공하며(FK 를
     * 건드리지 않는다) 목록에서만 빠진다. 복원 경로는 만들지 않는다.
     *
     * @throws org.springframework.web.server.ResponseStatusException
     *         없거나 이미 삭제된 설정이면 404
     */
    void deleteSetting(Long stngId);
}
