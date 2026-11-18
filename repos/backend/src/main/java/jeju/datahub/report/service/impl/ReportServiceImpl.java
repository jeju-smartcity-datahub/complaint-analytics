package jeju.datahub.report.service.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.common.PageResponse;
import jeju.datahub.dept.service.DeptService;
import jeju.datahub.report.service.ReportCreatorName;
import jeju.datahub.report.service.ReportListFilter;
import jeju.datahub.report.service.ReportListVO;
import jeju.datahub.report.service.ReportService;
import jeju.datahub.report.service.ReportSettingRequest;
import jeju.datahub.report.service.ReportSettingVO;
import lombok.RequiredArgsConstructor;

/**
 * 보고서 서비스 구현체. 전자정부 {@link EgovAbstractServiceImpl} 상속.
 *
 * <p>조회는 MyBatis({@link ReportMapper}) — 다중 JOIN + 페이징이라
 * {@code .claude/rules/rule_persistence-jpa-mybatis.md} 기준이 그렇다.
 * 설정 쓰기(등록·수정·소프트 삭제)는 같은 규칙에 따라 JPA({@link RptpAutoCrtStngRepository}) 다.
 */
@Service
@RequiredArgsConstructor
public class ReportServiceImpl extends EgovAbstractServiceImpl implements ReportService {

    /** 코드 그룹명 — 배포주기 */
    private static final String GROUP_CRLTN_CYCL_CD = "CRLTN_CYCL_CD";

    /** 코드 그룹명 — 분석실행유형 = 보고서 목록의 "출처" 필터 허용 집합 (TSK-4266 D7) */
    private static final String GROUP_ANLS_TYPE_CD = "ANLS_TYPE_CD";

    /** 보고서 목록 keyword 최대 길이 — trim 후 기준, 초과는 400 (TSK-4266 D5) */
    private static final int MAX_KEYWORD_LEN = 100;

    /** 생성구분 — 자동 생성 (삭제 불가) */
    private static final String CRT_TYPE_AUTO = "AUTO";

    /** 배포주기 코드 — 매월 (crltnMm 을 쓰지 않는 유일한 주기) */
    private static final String CYCL_MONTH = "MONTH";

    /**
     * 배포주기별 반복 간격(개월). 다음 배포 예정일 계산에 쓴다 (Step 6 주기 4종 표).
     *
     * <p>{@code CRLTN_CYCL_CD} 에는 {@code WEEK}(주간)도 있지만 <b>예정일 계산 규칙이 정의돼 있지
     * 않다</b> — 개월 단위 반복으로 표현할 수 없고 {@code crltn_mm}/{@code crltn_dd} 로 주(week)를
     * 특정할 수도 없다. 임의로 규칙을 지어내는 대신 여기 없는 주기는 400 으로 막는다.
     */
    private static final Map<String, Integer> CYCL_INTERVAL_MONTHS = Map.of(
            CYCL_MONTH, 1,
            "QUARTER", 3,
            "HALF", 6,
            "YEAR", 12);

    private final ReportMapper reportMapper;
    /** 🔴 배포부서 검증 창구 (TSK-4183) — 부서는 공통코드가 아니라 TB_DEPT 가 소유한다 */
    private final DeptService deptService;
    private final RptpAutoCrtStngRepository rptpAutoCrtStngRepository;
    private final RptpRepository rptpRepository;

    @Override
    public PageResponse<ReportListVO> selectReports(String crtType, String ownerId, ReportListFilter filter,
                                                    int page, int size) {
        // null 은 "필터 없음" 으로 본다 — 매퍼가 filter.* 를 읽으므로 여기서 상수로 바꿔 둔다
        ReportListFilter f = (filter == null) ? ReportListFilter.NONE : filter;
        validateListFilter(f);
        // 🔴 총건수와 목록이 같은 ownerId·filter 를 쓴다 — 조건이 갈리면 마지막 페이지가 빈다(TSK-4202 Step 4 · TSK-4266 D9)
        int totalElements = reportMapper.selectReportCount(crtType, ownerId, f);
        // 총건수가 0 이면 목록 쿼리를 보낼 이유가 없다 (JOIN 6개짜리 쿼리를 아낀다)
        if (totalElements == 0) {
            return PageResponse.of(Collections.<ReportListVO>emptyList(), page, size, totalElements);
        }
        // offset 은 long 으로 계산한다 — page 가 크면 page * size 가 int 범위를 넘어
        // 음수 OFFSET 으로 뒤집힐 수 있다 (SQL 오류 500)
        long offset = (long) page * size;
        List<ReportListVO> content = reportMapper.selectReportList(crtType, ownerId, f, size, offset);
        return PageResponse.of(content, page, size, totalElements);
    }

    /**
     * 목록 필터 검증 (TSK-4266 D5·D6·D7) — 위반은 400.
     *
     * <p>🔴 검증은 <b>여기 한 곳</b>이다. 출처 코드 판정에 DB(공통코드)가 필요해 컨트롤러가 아니라 서비스에 둔다 —
     * 설정 저장의 {@link #validateCodes} 와 같은 자리다. 값은 이미 {@link ReportListFilter#of} 가 정규화했다
     * (keyword trim · 빈 값 → null · 종료일 +1일).
     *
     * <p>{@code anlsTypeCd} 허용 집합은 {@code ANLS_TYPE_CD} <b>사용중 코드 전체</b>다 —
     * ⚠️ {@code addl_atrb1}(RPTP/BOTH)로 좁히지 않는다(자동 생성 설정용 구분이라 {@code POLICY_SIM} 등이 빠진다 — D4).
     */
    private void validateListFilter(ReportListFilter f) {
        if (f.getKeyword() != null && f.getKeyword().length() > MAX_KEYWORD_LEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "keyword 는 " + MAX_KEYWORD_LEN + "자 이하여야 합니다: " + f.getKeyword().length() + "자");
        }
        if (f.getAnlsTypeCd() != null
                && !reportMapper.selectCodeValues(GROUP_ANLS_TYPE_CD).contains(f.getAnlsTypeCd())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "사용할 수 없는 출처 코드입니다: anlsTypeCd=" + f.getAnlsTypeCd());
        }
        // fromYmd > toYmd ⇔ fromDt >= toYmd+1일 00:00 (같은 날이면 fromDt < toDtExclusive 라 통과)
        if (f.getFromDt() != null && f.getToDtExclusive() != null && !f.getFromDt().isBefore(f.getToDtExclusive())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "fromYmd 가 toYmd 보다 늦을 수 없습니다: fromYmd=" + f.getFromDt().toLocalDate()
                            + ", toYmd=" + f.getToDtExclusive().toLocalDate().minusDays(1));
        }
    }

    @Override
    public ReportListVO selectReport(Long rptpId) {
        ReportListVO report = reportMapper.selectReportById(rptpId);
        if (report == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서를 찾을 수 없습니다: " + rptpId);
        }
        return report;
    }

    @Override
    @Transactional
    public void deleteReport(Long rptpId) {
        Rptp rptp = rptpRepository.findById(rptpId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "보고서를 찾을 수 없습니다: " + rptpId));
        // 자동 생성분은 배포 부서 전체가 보는 공유물이고 (stng_id, trgt_prd_cd) 멱등키를 진다 —
        // 지우면 다음 배치가 같은 주기를 다시 만든다. 화면도 직접 생성 탭에만 삭제 버튼을 둔다.
        if (CRT_TYPE_AUTO.equals(rptp.getCrtType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "자동 생성 보고서는 삭제할 수 없습니다");
        }
        rptpRepository.delete(rptp);
    }

    @Override
    public List<ReportSettingVO> selectSettings() {
        return reportMapper.selectSettingList().stream()
                .map(this::fillNextCrltnYmd)
                .toList();
    }

    // === 설정 CRUD (Step 6) =================================================================

    @Override
    @Transactional
    public ReportSettingVO createSetting(ReportSettingRequest req, String userId, String userNm) {
        RptpAutoCrtStng entity = new RptpAutoCrtStng();
        // 1) 코드 유효성 2) 주기별 crltnMm 규칙 2-1) 지역 정규화 — 수정과 공통
        applyRequest(entity, req);

        // 3) stngId 는 넣지 않는다 — DB identity 가 매긴다 (D6). save() 후 엔티티에서 꺼낸다.
        //    DB NOT NULL 3개는 요청 바디에 없으므로 여기서 채운다
        //    (crltn_ymd 는 2026-08-24 컬럼이 사라져 더 이상 저장하지 않는다).
        entity.setActvtnYn(Boolean.TRUE);          // actvtn_yn  NOT NULL — 등록은 항상 활성 (D11: 삭제와 다른 뜻)
        entity.setCreatrId(userId);                // creatr_id  NOT NULL — 로그인 사용자(해석·401 은 컨트롤러)
        entity.setCreatrNm(ReportCreatorName.cut(userNm));   // creatr_nm 은 VARCHAR(50) — 자르지 않으면 500
        entity.setCrtDt(LocalDateTime.now());      // crt_dt     NOT NULL

        // 5) save() → flush()  ★ MyBatis 재조회 전 필수
        rptpAutoCrtStngRepository.save(entity);
        rptpAutoCrtStngRepository.flush();

        // 6) 공통코드명·지역 표시명이 붙은 VO 로 되돌려준다 (배포 예정일은 여기서 계산해 채운다)
        return fillNextCrltnYmd(reportMapper.selectSettingById(entity.getStngId()));
    }

    @Override
    @Transactional
    public ReportSettingVO updateSetting(Long stngId, ReportSettingRequest req) {
        RptpAutoCrtStng entity = loadAlive(stngId);
        // 요청에 있는 값만 덮어쓴다 — creatr_id·creatr_nm·crt_dt·actvtn_yn·last_crt_dt 는 그대로 둔다
        applyRequest(entity, req);

        // 변경 감지로 UPDATE 가 나가지만, MyBatis 재조회 전에 반드시 DB 로 밀어야 한다
        rptpAutoCrtStngRepository.flush();

        // ★ 같은 트랜잭션에서 selectSettingById 를 두 번째 부른다(존재 확인 → 재조회).
        //   XML 의 flushCache="true" 가 MyBatis 1차 캐시를 비워 직전 값이 반환되는 것을 막는다.
        return fillNextCrltnYmd(reportMapper.selectSettingById(stngId));
    }

    @Override
    @Transactional
    public void deleteSetting(Long stngId) {
        RptpAutoCrtStng entity = loadAlive(stngId);
        // 소프트 삭제 (D11) — repository.delete() 를 부르지 않는다.
        // 행을 지우면 TB_RPTP FK 위반이 나고, 과거 보고서의 배포 주기·부서·지역 표시가 함께 사라진다.
        entity.setDelDt(LocalDateTime.now());   // JPA 변경 감지로 UPDATE
    }

    // --- 내부 헬퍼 ------------------------------------------------------------------------

    /**
     * 수정·삭제 대상 설정을 영속 상태로 잡아온다. 없거나 이미 삭제됐으면 404.
     *
     * <p>존재 판정을 {@code selectSettingById}(MyBatis)로 하는 이유는 그 쿼리만
     * {@code del_dt} 조건 없이 읽어 <b>"없음"과 "이미 삭제됨"을 구분</b>할 수 있기 때문이다.
     * 둘 다 응답은 404 지만(D11), 삭제분을 되살리는 경로를 만들지 않으려면 구분해서 판정해야 한다.
     */
    private RptpAutoCrtStng loadAlive(Long stngId) {
        ReportSettingVO existing = reportMapper.selectSettingById(stngId);
        if (existing == null || existing.getDelDt() != null) {
            // 이미 삭제된 건도 없는 것과 같이 취급한다 (D11) — 복원 기능은 만들지 않는다
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "설정을 찾을 수 없습니다: " + stngId);
        }
        return rptpAutoCrtStngRepository.findById(stngId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "설정을 찾을 수 없습니다: " + stngId));
    }

    /** 등록·수정이 공통으로 쓰는 검증 + 정규화 + 매핑. 실패는 전부 400 이다. */
    private void applyRequest(RptpAutoCrtStng entity, ReportSettingRequest req) {
        // 1) 코드 유효성 — 없는 코드는 어떤 코드가 잘못됐는지 메시지에 담아 400
        validateCodes(req);

        entity.setRptpTypeCd(req.getRptpTypeCd());
        entity.setCrltnDeptCd(req.getCrltnDeptCd());
        entity.setCrltnCyclCd(req.getCrltnCyclCd());
        entity.setCrltnDd(req.getCrltnDd());

        // 2) 주기별 crltnMm 규칙 (MONTH 면 무시하고 null, 그 외는 필수)
        entity.setCrltnMm(resolveCrltnMm(req));

        // 2-1) 지역 정규화 (D12)
        applyRegion(entity, req);

        // 🔁 2026-08-24 — 다음 배포 예정일(crltn_ymd)은 더 이상 저장하지 않는다.
        //    컬럼이 사라졌고(45-DROP-RPTP-STNG-COLUMNS.sql), 값은 응답을 만들 때
        //    fillNextCrltnYmd() 가 계산해 채운다. 저장하지 않는 편이 오히려 정확하다 —
        //    저장 방식은 다음 수정 전까지 지난 날짜를 그대로 들고 있었다.
    }

    /**
     * 조회 결과에 다음 배포 예정일을 채운다.
     *
     * <p>DB 에 컬럼이 없으므로 <b>여기가 이 값의 유일한 출처</b>다. 목록·단건 응답이 모두
     * 이 메서드를 거쳐야 화면의 "다음 배포일" 이 비지 않는다.
     *
     * <p>주기 코드가 알 수 없는 값이면(코드표가 늘었는데 여기가 안 따라온 경우) 계산을 건너뛰고
     * null 로 둔다 — 응답 하나 때문에 조회 전체를 500 으로 떨어뜨리지 않는다.
     */
    private <T extends ReportSettingVO> T fillNextCrltnYmd(T vo) {
        if (vo == null || vo.getCrltnCyclCd() == null || vo.getCrltnDd() == null) {
            return vo;
        }
        if (!CYCL_INTERVAL_MONTHS.containsKey(vo.getCrltnCyclCd())) {
            return vo;
        }
        vo.setCrltnYmd(calculateNextCrltnYmd(
                vo.getCrltnCyclCd(), vo.getCrltnMm(), vo.getCrltnDd(), LocalDate.now()));
        return vo;
    }

    /**
     * 코드 3종 유효성. 프론트 드롭다운이 이미 거른 값이지만 요청은 프론트를 거치지 않고도 들어온다.
     *
     * <p>{@code rptpTypeCd} 는 {@code ANLS_TYPE_CD} 전체가 아니라
     * {@code addl_atrb1 IN ('RPTP','BOTH')} 인 3종만 허용한다 (D13) —
     * {@code POLICY_SIM} 처럼 분석 화면 전용 코드를 보내면 400 이다.
     */
    private void validateCodes(ReportSettingRequest req) {
        List<String> invalid = new ArrayList<>();
        if (!reportMapper.selectReportTypeCds().contains(req.getRptpTypeCd())) {
            invalid.add("rptpTypeCd=" + req.getRptpTypeCd());
        }
        // 🔴 배포부서 근거는 공통코드가 아니라 TB_DEPT 다 (TSK-4183 — 부서 코드그룹은 삭제됐다).
        //    창구는 DeptService.activeDeptCds() 하나이며 같은 쿼리를 이 매퍼에 복제하지 않는다.
        if (!deptService.activeDeptCds().contains(req.getCrltnDeptCd())) {
            invalid.add("crltnDeptCd=" + req.getCrltnDeptCd());
        }
        if (!reportMapper.selectCodeValues(GROUP_CRLTN_CYCL_CD).contains(req.getCrltnCyclCd())) {
            invalid.add("crltnCyclCd=" + req.getCrltnCyclCd());
        }
        if (!invalid.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "사용할 수 없는 코드값입니다: " + String.join(", ", invalid));
        }
        // 코드 자체는 존재해도 배포 예정일 계산 규칙이 없는 주기(WEEK)는 저장할 수 없다
        if (!CYCL_INTERVAL_MONTHS.containsKey(req.getCrltnCyclCd())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "지원하지 않는 배포주기입니다: " + req.getCrltnCyclCd() + " (MONTH | QUARTER | HALF | YEAR)");
        }
    }

    /**
     * 저장할 {@code crltnMm} 을 정한다.
     *
     * <p>{@code MONTH} 는 매월이라 기준월이라는 개념이 없다 — 보내도 <b>무시하고 null 로 저장</b>한다.
     * 나머지 주기는 기준월이 없으면 다음 배포 예정일을 계산할 수 없어 400 이다.
     */
    private Integer resolveCrltnMm(ReportSettingRequest req) {
        if (CYCL_MONTH.equals(req.getCrltnCyclCd())) {
            return null;
        }
        if (req.getCrltnMm() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "배포주기가 " + req.getCrltnCyclCd() + " 이면 crltnMm 은 필수입니다");
        }
        return req.getCrltnMm();
    }

    /**
     * 지역 3형태 정규화 (D12 — 알림 수신자와 같은 규칙).
     *
     * <ul>
     *   <li>{@code emdCd} 있음 → {@code sggNm} 은 <b>서버가 TB_ADMDST 에서 다시 채운다.</b>
     *       클라이언트가 보낸 값은 버린다 — '제주시 + 성산읍' 같은 불일치 조합을 원천 차단한다.
     *       없는 {@code emdCd} 는 400 (FK 위반을 500 으로 흘리지 않는다).</li>
     *   <li>{@code sggNm} 만 → 존재 확인 후 시군구 전체 ({@code emdCd} 는 null)</li>
     *   <li>둘 다 없음 → 전체 지역 (둘 다 null)</li>
     * </ul>
     *
     * <p>빈 문자열은 "지정 안 함"으로 본다 — 폼이 비운 select 를 {@code ""} 로 보내는 경우가 있고,
     * 그대로 저장하면 {@code emd_cd=''} 가 FK 위반으로 500 이 된다.
     */
    private void applyRegion(RptpAutoCrtStng entity, ReportSettingRequest req) {
        String emdCd = trimToNull(req.getEmdCd());
        String sggNm = trimToNull(req.getSggNm());

        if (emdCd != null) {
            String resolvedSggNm = reportMapper.selectSggNmByEmdCd(emdCd);
            if (resolvedSggNm == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "존재하지 않는 읍면동코드입니다: " + emdCd);
            }
            entity.setSggNm(resolvedSggNm);
            entity.setEmdCd(emdCd);
            return;
        }

        if (sggNm != null) {
            if (reportMapper.countSggNm(sggNm) == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "존재하지 않는 시군구명입니다: " + sggNm);
            }
            entity.setSggNm(sggNm);
            entity.setEmdCd(null);
            return;
        }

        entity.setSggNm(null);
        entity.setEmdCd(null);
    }

    /**
     * 다음 배포 예정일 계산 (Step 6 주기 4종 표).
     *
     * <table border="1">
     *   <caption>주기별 규칙</caption>
     *   <tr><td>MONTH</td>  <td>오늘 이후 첫 {@code crltnDd} 일 (이번 달이 지났으면 다음 달)</td></tr>
     *   <tr><td>QUARTER</td><td>{@code crltnMm} 을 시작으로 3개월 간격, 오늘 이후 첫 날짜</td></tr>
     *   <tr><td>HALF</td>   <td>{@code crltnMm} 을 시작으로 6개월 간격</td></tr>
     *   <tr><td>YEAR</td>   <td>오늘 이후 첫 {@code crltnMm} 월 {@code crltnDd} 일</td></tr>
     * </table>
     *
     * <p>구현은 <b>한 해 전 기준월에서 출발해 주기만큼 전진</b>한다. 올해 기준월에서 출발하면
     * 예를 들어 {@code QUARTER + crltnMm=12} 를 1월에 등록할 때 12월이 이미 오늘 이후라
     * 3월(같은 주기의 더 이른 날짜)을 건너뛴다. 간격 1·3·6·12 는 모두 12 의 약수라
     * 한 해 전 같은 달에서 출발해도 같은 잔여류에 머문다.
     *
     * <p>{@code crltnDd} 는 1~28 로 제한돼 있어 존재하지 않는 날짜가 나오지 않는다.
     *
     * <p>🔁 2026-08-24 — 저장하지 않고 <b>조회할 때마다</b> 부른다(fillNextCrltnYmd). 그래서
     * "시간이 지나면 과거 날짜로 굳는다" 는 종전의 제약이 사라졌다 — 언제 읽어도 오늘 이후의
     * 가장 이른 배포일이 나온다.
     */
    private LocalDate calculateNextCrltnYmd(String crltnCyclCd, Integer crltnMm, int crltnDd, LocalDate today) {
        int intervalMonths = CYCL_INTERVAL_MONTHS.get(crltnCyclCd);
        // MONTH 는 기준월이 없다(null) — 이번 달을 기준월로 삼고 1개월 간격으로 전진한다
        int anchorMonth = (crltnMm != null) ? crltnMm : today.getMonthValue();

        LocalDate candidate = LocalDate.of(today.getYear() - 1, anchorMonth, crltnDd);
        while (candidate.isBefore(today)) {
            candidate = candidate.plusMonths(intervalMonths);
        }
        return candidate;
    }

    /** 공백만 있는 문자열·빈 문자열을 null 로 접는다 (선택 입력의 "지정 안 함" 판정용) */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
