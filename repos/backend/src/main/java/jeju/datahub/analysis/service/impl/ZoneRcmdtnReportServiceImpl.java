package jeju.datahub.analysis.service.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.service.ZoneRcmdtnReportCreateRequest;
import jeju.datahub.analysis.service.ZoneRcmdtnReportService;
import jeju.datahub.analysis.service.ZoneRcmdtnReportVO;
import jeju.datahub.analysis.service.ZoneRcmdtnResultVO;
import jeju.datahub.analysis.service.ZoneRcmdtnService;
import jeju.datahub.analysis.service.ZoneRcmdtnShareReportCreateRequest;
import jeju.datahub.analysis.service.ZoneRcmdtnShareResultVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareService;
import jeju.datahub.report.service.ReportCreatorName;
import jeju.datahub.report.service.ReportListVO;
import jeju.datahub.report.service.impl.ReportMapper;
import jeju.datahub.report.service.impl.Rptp;
import jeju.datahub.report.service.impl.RptpRepository;
import lombok.RequiredArgsConstructor;

/**
 * 구역추천 보고서 서비스 구현체. 전자정부 {@link EgovAbstractServiceImpl} 상속. (3)
 *
 * <p>처리 순서는 넷이다 — ①같은 조건으로 <b>재계산</b> → ②실행 이력 1행 → ③분석 결과 1행 → ④보고서 1행
 * ({@link InvestPrrtReportServiceImpl#createReport} 와 같은 흐름). 판정·순위·배정은 {@link ZoneRcmdtnService} 가
 * 이미 하므로 여기서 다시 구현하지 않는다.
 *
 * <h2>🔴 화면이 보낸 숫자를 저장하지 않는다</h2>
 * 요청 바디에는 <b>조회 조건과 파일형식만</b> 있다. 화면이 보낸 순위를 받아 굳히면 조작된 순위가 보고서로
 * 남을 수 있다 — 그래서 저장 직전에 서버가 한 번 더 계산한다.
 *
 * <h2>🔴 계산은 이력을 남기지 않는 창구로 정확히 1회다</h2>
 * {@link ZoneRcmdtnService#selectZoneRcmdtn} 을 부른다. 조회 화면이 쓰는
 * {@link ZoneRcmdtnService#analyzeAndRecord} 를 부르면 그쪽이 실행 행을 하나 남기고 여기서 또 하나를 남겨
 * <b>내보내기 1회에 실행 행이 2개</b>가 된다. 적재에 넣을 정규화된 기간·재현 메타는 같은 패키지의
 * {@link ZoneRcmdtnServiceImpl#param} · {@link ZoneRcmdtnServiceImpl#buildParamMap} 을 <b>static 으로</b> 불러
 * 얻는다(둘 다 순수 함수 — 다시 계산하지 않는다). 🔴 구현 클래스를 빈으로 주입하지 않는다.
 * 공유주차제 적용도 같다 — {@link ZoneRcmdtnShareService#selectShare} 1회 +
 * {@link ZoneRcmdtnShareServiceImpl#buildParamMap}(static).
 *
 * <h2>🔴 트랜잭션 하나로 세 표를 묶는다</h2>
 * 계산 실패(400/404)와 0개소 판정이 저장보다 <b>앞</b>에 오고, 뒤에서 예외가 나면 세 표가 함께 롤백된다 —
 * 실행 이력만 남고 보고서가 없는 반쪽 상태를 만들지 않는다.
 *
 * <h2>🔴 생성자는 인자다 — 상수 폴백이 없다</h2>
 * {@code excutr_id}·{@code creatr_id} = 인자 {@code userId}({@code TB_USER.user_id}),
 * {@code creatr_nm} = 인자 {@code userNm} 을 50자로 자른 값이다. 이 클래스는 인증 기술을 모른다 —
 * 로그인 사용자 해석과 401 은 컨트롤러가 서비스보다 앞에서 끝낸다.
 *
 * <h2>🔴 두 보기는 {@code anls_param_json.rcmdtnTypeCd} 로 갈린다</h2>
 * 분석유형코드는 둘 다 {@code RECOMMEND} 다. 조회 실행(요금제)은 그 키를 적지 않으므로, 보고서 실행은
 * <b>반드시 {@code FEE} 를 적는다</b> — 스냅샷 조회가 이 키로 본문 모양을 고르고, 키가 없으면 404 다.
 * 공유주차제 조회 실행은 이미 {@code SHARE} 를 적으므로 그쪽 보고서 실행은 조회 실행의 키를 <b>그대로</b> 쓴다.
 *
 * <h2>🔴 스냅샷은 {@code rslt_json} 한 칸이 전부다</h2>
 * 적용 계수({@code crtrVls})·경고·판정·배정·주차장 목록을 <b>모두</b> 결과 JSON 에 담는다. 설정을 고치거나
 * 자료가 다시 적재돼도 보고서는 저장 시점 그대로다. ⚠️ 조회 실행의 {@code rslt_json} 은 3키
 * ({@code zone·asgn·lots})뿐이라 그 조립을 돌려쓰지 않는다 — 보고서는 문서를 되살려야 해서 5키다.
 * 공유주차제 적용도 같은 이유로 조회 실행의 2키({@code smry·zones})가 아니라 6키다.
 * 예외는 분석 계수 적용 내역({@code vrblAjmt}) 하나다 — {@code anls_param_json} 에 저장하고 응답 머리로 되살린다.
 */
@Service
@RequiredArgsConstructor
public class ZoneRcmdtnReportServiceImpl extends EgovAbstractServiceImpl implements ZoneRcmdtnReportService {

    private static final Logger log = LoggerFactory.getLogger(ZoneRcmdtnReportServiceImpl.class);

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    /** 분석유형코드 ({@code ANLS_TYPE_CD}) — 두 보기 공통. 보고서 목록의 출처 칸을 "구역추천" 으로 만든다 */
    private static final String ANLS_TYPE_RECOMMEND = "RECOMMEND";

    /** {@code anls_param_json} · 응답의 판별 키 이름. 🔴 공유주차제 조회 실행이 이미 쓰는 키와 같은 이름이다 */
    private static final String RCMDTN_TYPE_KEY = "rcmdtnTypeCd";

    /** 판별 값 — 요금제 적용 */
    private static final String RCMDTN_TYPE_FEE = "FEE";

    /** 판별 값 — 공유주차제 적용. 실행 행에는 조회 실행의 조립부가 같은 값을 이미 적는다 */
    private static final String RCMDTN_TYPE_SHARE = "SHARE";

    /** 검토대상여부({@code rvwTrgtYn})의 '후보' 값 — 등급이 CORE·CAND 인 읍면동 */
    private static final String RVW_TRGT_Y = "Y";

    /** 분석실행상태코드 ({@code EXCN_STTS_CD}) — 저장이 곧 완료다(비동기 실행이 아니다) */
    private static final String EXCN_STTS_DONE = "DONE";

    /** 보고서상태코드 ({@code RPTP_STTS_CD}) — 파일을 서버에 굽지 않으므로 생성 즉시 완료다 */
    private static final String RPTP_STTS_DONE = "DONE";

    /** 생성구분 ({@code RPTP_CRT_TYPE_CD}) — 보고서 목록의 <b>직접 생성 탭</b>에 뜨게 한다 */
    private static final String CRT_TYPE_MANUAL = "MANUAL";

    /** 🔴 인터페이스로 받는다 — 계산은 {@code selectZoneRcmdtn} 1회뿐이다 */
    private final ZoneRcmdtnService zoneRcmdtnService;
    /** 🔴 인터페이스로 받는다 — 계산은 {@code selectShare} 1회뿐이다 */
    private final ZoneRcmdtnShareService zoneRcmdtnShareService;
    private final AnlsExcnRepository anlsExcnRepository;
    private final AnlsRsltRepository anlsRsltRepository;
    private final RptpRepository rptpRepository;
    private final ZoneRcmdtnReportMapper zoneRcmdtnReportMapper;
    /** 스냅샷 헤더(보고서명·지역명·기간·형식·생성일시) 조회용 — 지역 3단 CASE 를 복제하지 않는다 */
    private final ReportMapper reportMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public ZoneRcmdtnReportVO createFeeReport(ZoneRcmdtnReportCreateRequest req, String userId, String userNm) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "요청 본문이 없습니다");
        }

        // ── ① 같은 조건으로 다시 계산한다 — 기간·유형 범위 오류(400)·없는 읍면동(404)은 여기서 끊긴다 ──
        //    🔴 이력을 남기지 않는 창구다. 조회 화면의 적재 창구를 부르면 실행 행이 2개 생긴다.
        //    7인자로 부른다(6인자 default 는 sggNm=null 이라 시군구 보고서가 400). emdCd·sggNm
        //    배타·정규화는 서비스의 resolveScope 가 한다 — 여기서 다시 보지 않는다(두 벌 금지).
        //    🔴 vrblVls 를 빠뜨리면 컴파일 오류 없이 보고서만 설정값으로 계산된다 — 화면과 순위가 갈린다
        //    형식과 무관하게 엑셀 재계산 데이터(wbk)를 붙이는 창구로 부른다(인자는 8인자와 같다).
        ZoneRcmdtnResultVO result = zoneRcmdtnService.selectZoneRcmdtnWithWorkbook(
                req.getEmdCd(), req.getSggNm(), req.getBgngYmd(), req.getEndYmd(),
                req.getCrdnBgngYmd(), req.getCrdnEndYmd(), req.getTrgtTypeSe(), req.getVrblVls());
        if (result.getLots() == null || result.getLots().isEmpty()) {
            // 조회 API 는 0개소도 200(결론)이지만 보고서는 400 이다 — 열어도 볼 것이 없는 보고서를
            // 목록에 남기지 않는다. 아직 아무것도 저장하지 않았다.
            // 읍면동 판정은 제거됐다. 주차장이 있으면 만든다.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "조건에 맞는 후보 주차장이 없어 보고서를 만들 수 없습니다.");
        }
        // 🔴 모드는 요청이 아니라 응답 scope 로, 시군구명은 응답 sggNm(서비스가 trim 한 값)으로 고른다.
        //    req.getEmdCd() != null 로 가르면 {"emdCd":"","sggNm":"제주시"} 가 서비스에선 시군구 모드인데 여기선
        //    읍면동 분기를 타 zone NPE(500)이다. 시군구 모드는 zone == null 이다.
        boolean sgg = ZoneRcmdtnServiceImpl.SCOPE_SGG.equals(result.getScope());
        ZoneRcmdtnResultVO.Zone zone = result.getZone();
        String excnSggNm = sgg ? result.getSggNm() : zone.getSggNm();
        String excnEmdCd = sgg ? null : zone.getEmdCd();

        // ── ①-2 정규화 — 🔴 다시 계산하지 않는다. param() 은 순수 함수다 ───────────────
        //    조회 실행의 적재부와 '같은 호출 · 같은 인자' 다 — emdCd 는 기준선이 읽어 온 값, 계수는 응답의
        //    crtrVls 다. 설정을 다시 읽으면 그 사이 바뀐 값이 섞여 '응답에 적힌 기준' 과 갈린다.
        ZoneRcmdtnQueryParam p = ZoneRcmdtnServiceImpl.param(excnEmdCd, sgg ? excnSggNm : null,   // 모드 분기
                req.getBgngYmd(), req.getEndYmd(), req.getCrdnBgngYmd(), req.getCrdnEndYmd(),
                result.getCrtrVls());
        LocalDate bgng = p.getCvFrom().toLocalDate();
        // 🔴 cvToExcl·crdnTo 는 반열림 상한이라 -1일 해야 요청이 받은 '포함' 종료일로 돌아온다
        LocalDate end = p.getCvToExcl().toLocalDate().minusDays(1);
        LocalDate crdnBgng = p.getCrdnFrom().toLocalDate();
        LocalDate crdnEnd = p.getCrdnTo().toLocalDate().minusDays(1);
        // 🔴 저장·응답의 기간은 요청 문자열이 아니라 p 에서 낸 값이다 — 조회 실행의 적재부와 같은 출처다.
        //    (요청 VO 의 @Pattern("\\d{8}") 이 8자리 숫자만 통과시키므로 지금은 요청값과 같은 문자열이 나온다.)
        String bgngYmd = bgng.format(YMD);
        String endYmd = end.format(YMD);

        // 조회 실행이 적는 키 '그대로' + 판별 키 한 칸
        //    scope·sggNm 은 buildParamMap 이 이미 싣는다(여기서 다시 넣지 않는다).
        Map<String, Object> paramMap = ZoneRcmdtnServiceImpl.buildParamMap(p, result, bgng, end, crdnBgng, crdnEnd);
        paramMap.put(RCMDTN_TYPE_KEY, RCMDTN_TYPE_FEE);

        // 🔴 스냅샷 머리(ReportMapper.selectReportById 의 CASE)와 같은 문자열이어야 한다 — 내려받은 파일과
        //    재다운로드 문서의 지역 표기가 갈리지 않게.
        //    시군구 모드는 regionNm(sggNm, null) = "{시군구} 전체"(스냅샷 CASE 의 2번째 분기와 같다).
        String regionNm = ZoneRcmdtnReportNaming.regionNm(excnSggNm, sgg ? null : zone.getEmdNm());

        // ── ② ③ ④ 세 표 저장 ────────────────────────────────────────────────────
        LocalDateTime now = LocalDateTime.now();
        // 🔴 sgg_nm 을 비우면 스냅샷 조회의 `sgg_nm || ' ' || emd_nm` 이 NULL 연결이 되어 지역명이 통째로
        //    사라진다 — 기준선이 TB_ADMDST 에서 읽어 온 값을 그대로 쓴다.
        //    시군구 모드는 sgg_nm = 정규화된 sggNm · emd_cd = NULL 이다.
        AnlsExcn excn = saveExcn(userId, excnSggNm, excnEmdCd, bgngYmd, endYmd,
                write(paramMap, "분석 파라미터"), now);
        saveRslt(excn.getExcnId(), buildFeeRsltJson(result));
        Rptp rptp = saveRptp(excn.getExcnId(), ZoneRcmdtnReportNaming.feeRptpNm(regionNm),
                ZoneRcmdtnReportNaming.anlsPrdExpln(bgngYmd, endYmd), req.getFileFmt(), userId, userNm, now);

        log.info("구역추천 요금제 적용 보고서 생성: rptpId={} excnId={} 지역={} 기간={}~{} 주차장={}개소 형식={} 생성자={}",
                rptp.getRptpId(), excn.getExcnId(), regionNm, bgngYmd, endYmd,
                result.getLots().size(), req.getFileFmt(), userId);

        ZoneRcmdtnReportVO vo = head(rptp, excn, regionNm);
        vo.setRcmdtnTypeCd(RCMDTN_TYPE_FEE);
        // 🔴 본문의 excnId 는 채우지 않는다(selectZoneRcmdtn 이 null 로 준 그대로) — 실행 ID 는 머리 한 곳이다.
        //    rslt_json 에 그 키가 없어 스냅샷에서는 항상 null 이므로, 여기서만 채우면 두 응답이 갈린다.
        // 🔴 exclLots 는 지도 참고용이라 rslt_json 에 없다 — 생성 응답도 비워 스냅샷 응답과 같게 한다.
        result.setExclLots(new ArrayList<>());
        // 🔴 vrblAjmt 도 rslt_json 에 없다 — 머리로 옮기고 본문은 비워 스냅샷 응답과 같게 한다
        vo.setVrblAjmt(result.getVrblAjmt());
        result.setVrblAjmt(null);
        vo.setFee(result);
        return vo;
    }

    @Override
    @Transactional
    public ZoneRcmdtnReportVO createShareReport(ZoneRcmdtnShareReportCreateRequest req, String userId, String userNm) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "요청 본문이 없습니다");
        }

        // ── ① 같은 조건으로 다시 계산한다 — 기간 오류·40자 초과/없는 시군구명(400)은 여기서 끊긴다 ──
        //    🔴 이력을 남기지 않는 창구다. 읍면동 필터는 받지 않는다(null) — 화면에서 그 칸을 걷었다.
        //    🔴 vrblVls 를 빠뜨리면 컴파일 오류 없이 보고서만 설정값으로 계산된다
        //    형식과 무관하게 엑셀 재계산 데이터(wbk)를 붙이는 창구로 부른다(인자는 selectShare 와 같다).
        ZoneRcmdtnShareResultVO result = zoneRcmdtnShareService.selectShareWithWorkbook(
                req.getBgngYmd(), req.getEndYmd(), req.getSggNm(), null, req.getVrblVls());
        // 🔴 zones 가 비었는지가 아니라 '후보' 가 있는지를 본다 — 후보가 없어도 zones 는 필터에 걸린
        //    읍면동 전부로 온다. 아직 아무것도 저장하지 않았다.
        long rvwTrgtCnt = result.getZones() == null ? 0
                : result.getZones().stream().filter(z -> RVW_TRGT_Y.equals(z.getRvwTrgtYn())).count();
        if (rvwTrgtCnt == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "조건에 맞는 공유주차 검토 후보가 없어 보고서를 만들 수 없습니다.");
        }

        // ── ①-2 정규화 — 조회 실행의 적재부(sggNmFilter)와 같은 규칙이다 ─────────────────
        //    🔴 빈 값·공백뿐이면 NULL 이다. '' 가 sgg_nm 에 들어가면 스냅샷 머리 지역명이 ' 전체' 가 된다.
        String sggNmFilter = trimToNull(req.getSggNm());
        // 실행 행의 기간은 요청값이다 — 요청 VO 의 @Pattern 이 8자리 숫자만 통과시키고 selectShare 가
        // 실재하는 날짜인지까지 확인했으므로, 조회 실행이 적는 '파싱을 통과한 값' 과 같은 문자열이다.
        String bgngYmd = req.getBgngYmd();
        String endYmd = req.getEndYmd();

        // 조회 실행이 적는 7키 '그대로' — 판별 키(SHARE)가 이미 들어 있다.
        // 계수는 설정을 다시 읽지 않고 응답의 crtrVls 를 쓴다.
        Map<String, Object> paramMap = ZoneRcmdtnShareServiceImpl.buildParamMap(
                result.getCrtrVls(), sggNmFilter, null, result.getVrblAjmt());

        // 🔴 스냅샷 머리(ReportMapper.selectReportById 의 CASE)와 같은 문자열이어야 한다 —
        //    '{시군구} 전체' 또는 '전체 지역'.
        String regionNm = ZoneRcmdtnReportNaming.regionNm(sggNmFilter, null);

        // ── ② ③ ④ 세 표 저장 — 읍면동 조건이 없는 보기라 emd_cd 는 NULL 이다 ──────────────
        LocalDateTime now = LocalDateTime.now();
        AnlsExcn excn = saveExcn(userId, sggNmFilter, null, bgngYmd, endYmd,
                write(paramMap, "분석 파라미터"), now);
        saveRslt(excn.getExcnId(), buildShareRsltJson(result));
        Rptp rptp = saveRptp(excn.getExcnId(), ZoneRcmdtnReportNaming.shareRptpNm(regionNm),
                ZoneRcmdtnReportNaming.anlsPrdExpln(bgngYmd, endYmd), req.getFileFmt(), userId, userNm, now);

        log.info("구역추천 공유주차제 적용 보고서 생성: rptpId={} excnId={} 지역={} 기간={}~{} 읍면동={}곳 후보={}곳 형식={} 생성자={}",
                rptp.getRptpId(), excn.getExcnId(), regionNm, bgngYmd, endYmd,
                result.getZones().size(), rvwTrgtCnt, req.getFileFmt(), userId);

        ZoneRcmdtnReportVO vo = head(rptp, excn, regionNm);
        vo.setRcmdtnTypeCd(RCMDTN_TYPE_SHARE);
        // 🔴 본문의 excnId 는 채우지 않는다(selectShare 가 null 로 준 그대로) — 실행 ID 는 머리 한 곳이다.
        // 🔴 vrblAjmt 도 rslt_json 에 없다 — 머리로 옮기고 본문은 비워 스냅샷 응답과 같게 한다
        vo.setVrblAjmt(result.getVrblAjmt());
        result.setVrblAjmt(null);
        vo.setShare(result);
        return vo;
    }

    /**
     * {@inheritDoc}
     *
     * <p>순서는 <b>원문 1행 → 404 판정 4종 → 헤더 → 본문 복원</b> 이다
     * ({@link InvestPrrtReportServiceImpl#selectReportSnapshot} 과 같은 흐름 + 판별 키 판정 1종).
     */
    @Override
    @Transactional(readOnly = true)
    public ZoneRcmdtnReportVO selectReportSnapshot(Long rptpId) {
        ZoneRcmdtnSnapshotVO snapshot = zoneRcmdtnReportMapper.selectZoneRcmdtnSnapshot(rptpId);
        if (snapshot == null) {
            // 404-①: 보고서 자체가 없다.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서를 찾을 수 없습니다: " + rptpId);
        }
        if (!ANLS_TYPE_RECOMMEND.equals(snapshot.getAnlsTypeCd())) {
            // 404-②: 다른 유형(또는 실행 미연결)이다. 400 이 아니라 404 인 이유: 요청은 정상이고
            //        "그 경로에 그 자원이 없다".
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "구역추천 보고서가 아닙니다: " + rptpId + " (analysisType=" + snapshot.getAnlsTypeCd() + ")");
        }
        String rcmdtnTypeCd = snapshot.getRcmdtnTypeCd();
        boolean fee = RCMDTN_TYPE_FEE.equals(rcmdtnTypeCd);
        if (!fee && !RCMDTN_TYPE_SHARE.equals(rcmdtnTypeCd)) {
            // 404-③: 🔴 보기를 알 수 없다(키 없음 포함) — 추측해서 한쪽 파서로 읽지 않는다.
            //        두 보기의 결과 JSON 은 모양이 달라, 엉뚱한 파서가 읽으면 예외 없이 빈 표가 된다.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "구역추천 보고서의 보기를 알 수 없습니다: " + rptpId + " (rcmdtnTypeCd=" + rcmdtnTypeCd + ")");
        }
        String rsltJson = snapshot.getRsltJson();
        if (rsltJson == null || rsltJson.isBlank()) {
            // 404-④: 유형·보기는 맞지만 결과 행이 없다.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서에 저장된 분석 결과가 없습니다: " + rptpId);
        }

        // 헤더는 기존 단건 조회를 재사용한다. 위 조회가 행을 찾았으므로 null 은 동시 삭제뿐이다(방어).
        ReportListVO header = reportMapper.selectReportById(rptpId);
        if (header == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서를 찾을 수 없습니다: " + rptpId);
        }

        // 🔴 응답 모양은 생성 API 와 같다 — 머리 9칸 + 판별 키 + 본문 하나.
        //    기간은 실행 행(anls_bgng_ymd·anls_end_ymd)이 정본이다(rslt_json 에 없다).
        ZoneRcmdtnReportVO vo = new ZoneRcmdtnReportVO();
        vo.setRptpId(header.getRptpId());
        vo.setExcnId(snapshot.getExcnId());
        vo.setRptpNm(header.getRptpNm());
        vo.setRegionNm(header.getRegionNm());
        vo.setAnlsPrdExpln(header.getAnlsPrdExpln());
        vo.setFileFmt(header.getFileFmt());
        vo.setCrtYmd(header.getCrtYmd());
        vo.setBgngYmd(snapshot.getAnlsBgngYmd());
        vo.setEndYmd(snapshot.getAnlsEndYmd());
        vo.setRcmdtnTypeCd(rcmdtnTypeCd);
        vo.setVrblAjmt(parseVrblAjmt(snapshot.getVrblAjmtJson(), rptpId));

        // 🔴 저장된 JSON 을 조회 응답 VO 로 '바로' 읽는다. 키가 응답 필드명과 같아 변환이 없고, 트리(JsonNode)를
        //    거치지 않아 소수가 double 로 뭉개지지 않는다. 본문의 excnId 는 JSON 에 없어 null 그대로다 —
        //    생성 응답도 그 칸을 채우지 않는다(실행 ID 는 머리 한 곳).
        if (fee) {
            vo.setFee(read(rsltJson, ZoneRcmdtnResultVO.class, rptpId));
        } else {
            vo.setShare(read(rsltJson, ZoneRcmdtnShareResultVO.class, rptpId));
        }
        return vo;
    }

    /** 키가 없으면(구 보고서) {@code null} 이다 — 빈 내역으로 채우면 "조정 안 함" 으로 읽힌다. */
    private Map<String, Object> parseVrblAjmt(String vrblAjmtJson, Long rptpId) {
        if (vrblAjmtJson == null || vrblAjmtJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(vrblAjmtJson, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "저장된 분석 계수 적용 내역 역직렬화에 실패했습니다: rptpId=" + rptpId, e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 세 표 저장 — 두 보기가 같이 쓴다(보기마다 다른 것은 인자로 받는다)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * ② 실행 이력 1행. 🔴 {@code excn_id} 를 결과 행·보고서 행이 참조하므로 여기서 {@code flush()} 로 채번을
     * 확정한다(identity 채번은 flush 시점).
     *
     * @param sggNm 시군구명 — 스냅샷 머리의 지역명 CASE 가 읽는 칸이다
     * @param emdCd 읍면동코드({@code tb_admdst} FK). 읍면동 조건이 없는 보기면 {@code null}
     */
    private AnlsExcn saveExcn(String userId, String sggNm, String emdCd,
                              String bgngYmd, String endYmd, String paramJson, LocalDateTime now) {
        AnlsExcn excn = new AnlsExcn();
        excn.setAnlsTypeCd(ANLS_TYPE_RECOMMEND);
        excn.setExcutrId(userId);
        excn.setSggNm(sggNm);
        excn.setEmdCd(emdCd);
        excn.setAnlsBgngYmd(bgngYmd);
        excn.setAnlsEndYmd(endYmd);
        excn.setAnlsParamJson(paramJson);
        excn.setExcnSttsCd(EXCN_STTS_DONE);
        excn.setExcnDt(now);
        anlsExcnRepository.save(excn);
        anlsExcnRepository.flush();
        return excn;
    }

    /** ③ 분석 결과 1행 (실행 1건 = 결과 1행 — {@code excn_id} UNIQUE) */
    private void saveRslt(Long excnId, String rsltJson) {
        AnlsRslt rslt = new AnlsRslt();
        rslt.setExcnId(excnId);
        rslt.setRsltJson(rsltJson);
        anlsRsltRepository.save(rslt);
    }

    /**
     * ④ 보고서 1행 — {@code MANUAL} · {@code file_path} 는 NULL.
     *
     * <p>{@code filePath} 는 채우지 않는다 — 파일을 서버에 굽지 않고 프론트가 응답으로 문서를 그린다.
     * {@code stngId}·{@code trgtPrdCd}·{@code crltnDeptCd}·{@code crltnCyclCd} 는 자동 생성 전용 칸이라 null 이다.
     */
    private Rptp saveRptp(Long excnId, String rptpNm, String anlsPrdExpln, String fileFmt,
                          String userId, String userNm, LocalDateTime now) {
        Rptp rptp = new Rptp();
        rptp.setCrtType(CRT_TYPE_MANUAL);
        rptp.setExcnId(excnId);
        rptp.setRptpNm(rptpNm);
        rptp.setAnlsPrdExpln(anlsPrdExpln);
        rptp.setSttsCd(RPTP_STTS_DONE);
        rptp.setFileFmt(fileFmt);
        rptp.setCreatrId(userId);
        rptp.setCreatrNm(ReportCreatorName.cut(userNm));   // 🔴 50자 절단 — 공용 유틸
        rptp.setCrtYmd(now);
        rptpRepository.save(rptp);
        rptpRepository.flush();
        return rptp;
    }

    /** 응답 머리 9칸. 판별 키와 본문은 호출부가 채운다 */
    private static ZoneRcmdtnReportVO head(Rptp rptp, AnlsExcn excn, String regionNm) {
        ZoneRcmdtnReportVO vo = new ZoneRcmdtnReportVO();
        vo.setRptpId(rptp.getRptpId());
        vo.setExcnId(excn.getExcnId());
        vo.setRptpNm(rptp.getRptpNm());
        vo.setRegionNm(regionNm);
        vo.setAnlsPrdExpln(rptp.getAnlsPrdExpln());
        vo.setFileFmt(rptp.getFileFmt());
        vo.setCrtYmd(rptp.getCrtYmd());
        vo.setBgngYmd(excn.getAnlsBgngYmd());
        vo.setEndYmd(excn.getAnlsEndYmd());
        return vo;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 저장 값 조립
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 요금제 적용 결과 JSON — 스냅샷 본문 전체. 키 9개를 <b>하나씩 명시해</b> 담는다:
     * {@code crtrVls · warnings · zone · asgn · lots · scope · sggNm · anlsMthd · wbk}.
     *
     * <p>{@code wbk}(엑셀 재계산 데이터)를 더했다(형식과 무관). 이 키가 없는 기존 스냅샷은
     * 역직렬화 시 null 이 되어 프론트가 엑셀 안내 문구로 떨어진다.
     *
     * <p>{@code anlsMthd}({@code RVW_SCR})를 더했다. 이 키가 없는 기존 스냅샷은 역직렬화 시
     * null 이 되어 프론트가 <b>구 방식(건수 순 · min-max 색)</b>으로 그린다. 점수 필드는 {@code lots} 가 VO째
     * 직렬화되므로 따로 담지 않는다.
     *
     * <p>{@code scope}·{@code sggNm} 을 더했다. 빠지면 스냅샷의 {@code scope} 가 null 이 되어
     * 시군구 보고서가 재다운로드 때 {@code EMD} 로 오해석된다. 시군구 모드의 {@code zone}·{@code asgn} 은 null 그대로
     * 담는다. ⚠️ 이 두 키가 없는 기존 스냅샷은 역직렬화 시 null 이 된다(프론트가 {@code EMD} 로 간주).
     *
     * <p>키 이름과 값의 모양이 조회 응답({@link ZoneRcmdtnResultVO})과 같아 되읽을 때 변환이 없다. 값은 계산
     * 결과를 <b>가공 없이</b> 옮긴다 — 여기서 반올림하거나 다시 계산하면 같은 보고서의 생성 응답과 스냅샷
     * 조회가 갈린다.
     *
     * <p>🔴 {@code excnId} 는 넣지 않는다 — 실행 ID 는 보고서 머리 한 곳이다. 기간도 넣지 않는다 — 실행 행의
     * {@code anls_bgng_ymd}·{@code anls_end_ymd} 가 정본이고, 두 곳에 적으면 갈린다.
     */
    private String buildFeeRsltJson(ZoneRcmdtnResultVO result) {
        Map<String, Object> rslt = new LinkedHashMap<>();
        rslt.put("crtrVls", result.getCrtrVls());
        rslt.put("warnings", result.getWarnings());
        rslt.put("zone", result.getZone());
        rslt.put("asgn", result.getAsgn());
        rslt.put("lots", result.getLots());
        rslt.put("scope", result.getScope());
        rslt.put("sggNm", result.getSggNm());
        rslt.put("anlsMthd", result.getAnlsMthd());
        rslt.put("wbk", result.getWbk());             // 형식과 무관하게 항상 싣는다
        return write(rslt, "분석 결과");
    }

    /**
     * 공유주차제 적용 결과 JSON — 스냅샷 본문 전체. 키 7개를 <b>하나씩 명시해</b> 담는다:
     * {@code crtrVls · dataBgngYmd · dataEndYmd · warnings · smry · zones · wbk}.
     *
     * <p>키 이름과 값의 모양이 조회 응답({@link ZoneRcmdtnShareResultVO})과 같아 되읽을 때 변환이 없다.
     *
     * <p>🔴 <b>{@code zones} 를 후보만으로 자르지 않는다</b> — 필터에 걸린 읍면동 전부를 저장한다. 자르면
     * {@code smry} 의 건수와 {@code zones} 가 어긋난다. 후보만 고르는 것은 문서 템플릿의 일이다.
     * 🔴 {@code excnId} 는 넣지 않는다 — 실행 ID 는 보고서 머리 한 곳이다.
     */
    private String buildShareRsltJson(ZoneRcmdtnShareResultVO result) {
        Map<String, Object> rslt = new LinkedHashMap<>();
        rslt.put("crtrVls", result.getCrtrVls());
        rslt.put("dataBgngYmd", result.getDataBgngYmd());
        rslt.put("dataEndYmd", result.getDataEndYmd());
        rslt.put("warnings", result.getWarnings());
        rslt.put("smry", result.getSmry());
        rslt.put("zones", result.getZones());
        rslt.put("wbk", result.getWbk());             // 형식과 무관하게 항상 싣는다
        return write(rslt, "분석 결과");
    }

    /** 빈 값·공백뿐이면 {@code null}, 아니면 앞뒤 공백을 걷은 값 ({@code ZoneRcmdtnShareServiceImpl} 과 같은 규칙) */
    private static String trimToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /** HTTP 응답과 <b>같은 {@link ObjectMapper}</b> 로 쓴다 — 숫자·날짜 표기가 응답과 저장값에서 갈리지 않게. */
    private String write(Object value, String what) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 🔴 삼키지 않는다 — 빈 JSON 을 적재하면 열 수 없는 보고서가 조용히 굳는다.
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, what + " 직렬화에 실패했습니다", e);
        }
    }

    /**
     * 저장된 결과 JSON 을 본문 VO 로 되살린다 — {@link #write} 와 <b>같은 {@link ObjectMapper}</b> 다.
     *
     * <p>🔴 깨진 스냅샷은 조용히 넘길 상태가 아니다 — 빈 문서를 200 으로 내지 않고 <b>500</b> 으로 드러낸다.
     * JSON 리터럴 {@code null} 이 저장된 경우는 결과가 없는 것이라 404 다.
     */
    private <T> T read(String rsltJson, Class<T> type, Long rptpId) {
        T body;
        try {
            body = objectMapper.readValue(rsltJson, type);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "저장된 분석 결과 역직렬화에 실패했습니다: rptpId=" + rptpId, e);
        }
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서에 저장된 분석 결과가 없습니다: " + rptpId);
        }
        return body;
    }
}
