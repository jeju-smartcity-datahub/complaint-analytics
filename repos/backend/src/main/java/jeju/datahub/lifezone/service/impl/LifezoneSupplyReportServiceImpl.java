package jeju.datahub.lifezone.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.service.impl.AnlsExcn;
import jeju.datahub.analysis.service.impl.AnlsExcnRepository;
import jeju.datahub.analysis.service.impl.AnlsRslt;
import jeju.datahub.analysis.service.impl.AnlsRsltRepository;
import jeju.datahub.lifezone.service.LifezoneGrade;
import jeju.datahub.lifezone.service.LifezoneQuadrantCriterionVO;
import jeju.datahub.lifezone.service.LifezoneSnapshotVO;
import jeju.datahub.lifezone.service.LifezoneSupply;
import jeju.datahub.lifezone.service.LifezoneSupplyCellVO;
import jeju.datahub.lifezone.service.LifezoneSupplyGradeCriterionVO;
import jeju.datahub.lifezone.service.LifezoneSupplyReportCreateRequest;
import jeju.datahub.lifezone.service.LifezoneSupplyReportService;
import jeju.datahub.lifezone.service.LifezoneSupplyReportVO;
import jeju.datahub.lifezone.service.LifezoneSupplyResultVO;
import jeju.datahub.lifezone.service.LifezoneSupplyService;
import jeju.datahub.lifezone.service.LifezoneSupplyWorkbookVO;
import jeju.datahub.region.service.EmdVO;
import jeju.datahub.region.service.RegionService;
import jeju.datahub.region.service.RegionVO;
import jeju.datahub.report.service.ReportCreatorName;
import jeju.datahub.report.service.ReportListVO;
import jeju.datahub.report.service.impl.ReportMapper;
import jeju.datahub.report.service.impl.Rptp;
import jeju.datahub.report.service.impl.RptpRepository;
import lombok.RequiredArgsConstructor;

/**
 * 생활권 <b>수급</b> 보고서 구현. (TSK-4089)
 *
 * <p>민원건수 보고서({@link LifezoneReportServiceImpl})와 <b>저장 절차는 같고 JSON 모양이 다르다.</b>
 * 실행 1행({@code TB_ANLS_EXCN}) · 결과 1행({@code TB_ANLS_RSLT}) · 보고서 1행({@code TB_RPTP})을
 * 한 트랜잭션으로 남기는 것까지 같다.
 *
 * <h2>🔴 {@code anls_type_cd} 가 {@code LIFEZONE_SPLY} 다 — {@code LIFEZONE} 이 아니다</h2>
 * 화면은 하나지만 <b>되읽는 파서가 다르다</b>. 보고서 목록은 {@code anls_type_cd} 로 어느 스냅샷
 * API 를 부를지 고르므로(프론트 {@code lib/report/reportDownload.ts}), 같은 코드로 저장하면 수급
 * 보고서를 민원건수 파서가 읽어 <b>조용히 빈 표</b>가 나온다 — 목록에서는 정상으로 보여 아무도
 * 알아채지 못한다. 공통코드 행은 마이그레이션 {@code 68-LIFEZONE-SPLY-ANLS-TYPE.sql} 이 넣는다.
 *
 * <h2>🔴 화면이 보낸 숫자를 저장하지 않는다</h2>
 * 요청은 <b>조회 조건만</b> 받고 서버가 {@link LifezoneSupplyService} 로 <b>다시 분석</b>한다 —
 * 화면 조작이 곧 보고서 위조가 되지 않게. 조회와 저장이 같은 서비스를 타므로 두 경로의 숫자가
 * 갈릴 수 없다.
 *
 * <h2>🔴 등급 범례를 {@code anls_param_json} 에 함께 굳힌다</h2>
 * 등급별 격자·민원·공급 0 격자 수는 <b>조회 결과에서 산출</b>된다. 굳혀 두지 않으면 나중에 스냅샷을
 * 열 때 범례를 재현할 수 없다. (민원건수 보고서가 {@code gradeScale}·{@code gradeCriteria} 를 굳히는 것과 같은 이유다.)
 *
 * <h2>🔁 신·구 스냅샷이 공존한다 (TSK-4258 D8·D14)</h2>
 * {@code anls_type_cd} 는 그대로 {@code LIFEZONE_SPLY} 이고, 신규 스냅샷은 {@code anls_param_json.anlsMthd =
 * "SHRT_RT"} 로 갈린다. 이 값이 없는 구 스냅샷(사분면)은 <b>원문 그대로</b> 되읽는다 — 재해석하지 않는다.
 * 프론트는 응답 {@code anlsMthd} 로 렌더러를 고른다.
 *
 * <h2>🔁 생성 시점 기준값을 {@code crtrVls} 로 굳힌다 (TSK-4258 D20)</h2>
 * 경계·최소 월평균 민원이 관리 화면에서 바뀌므로, {@code anls_param_json.crtrVls}
 * ({@code minMnthAvgCvlcpt}·{@code grdBndrys}[BNDRY1~4 오름차순])에 <b>그 보고서가 쓴 값</b>을 남긴다
 * (Hotspot·ZoneRcmdtn 선례). 원천은 설정 표를 다시 읽은 값이 아니라 <b>이번 분석 결과</b>다 — 다시 읽으면
 * 그 사이 저장된 값이 섞여 "결과에 적힌 기준" 과 갈린다. 🔴 문서를 그리는 데는 쓰지 않는다 — 범례는 이미
 * 굳힌 {@code gradeCriteria}(하한·문구)·{@code summary.minMnthAvgCvlcpt} 로 그리므로, 설정을 바꿔도 옛 보고서는
 * 자기 기준으로 그려진다. 그래서 스냅샷 응답 VO 에 따로 노출하지 않는다(같은 값을 두 키로 내리지 않는다).
 */
@Service
@RequiredArgsConstructor
public class LifezoneSupplyReportServiceImpl extends EgovAbstractServiceImpl
        implements LifezoneSupplyReportService {

    private static final Logger log = LoggerFactory.getLogger(LifezoneSupplyReportServiceImpl.class);

    /** 🔴 {@code LIFEZONE} 과 갈린다 — 위 클래스 주석 참고 */
    private static final String ANLS_TYPE_LIFEZONE_SPLY = "LIFEZONE_SPLY";

    private static final String EXCN_STTS_DONE = "DONE";
    private static final String RPTP_STTS_DONE = "DONE";
    private static final String CRT_TYPE_MANUAL = "MANUAL";
    private final LifezoneSupplyService lifezoneSupplyService;
    private final RegionService regionService;
    private final AnlsExcnRepository anlsExcnRepository;
    private final AnlsRsltRepository anlsRsltRepository;
    private final RptpRepository rptpRepository;
    private final ReportMapper reportMapper;
    private final ObjectMapper objectMapper;

    /* ── 1. 생성 ──────────────────────────────────────── */

    @Override
    @Transactional
    public LifezoneSupplyReportVO createReport(LifezoneSupplyReportCreateRequest req, String userId, String userNm) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "요청 본문이 없습니다");
        }
        String grade = resolveGrade(req.getGrade());   // 🔴 분석 전에 끊는다 — 잘못된 값으로 집계를 돌리지 않게
        // 🆕 TSK-4340 D7 — 엑셀 재계산 데이터(wbk)를 같은 조건으로 함께 구한다(형식 무관 — PDF 로 저장해도 싣는다)
        LifezoneSupplyService.LifezoneSupplyWithWorkbook analyzed =
                lifezoneSupplyService.analyzeWithWorkbook(req.toAnalysisRequest());
        LifezoneSupplyResultVO result = analyzed.result();
        LifezoneSupplyWorkbookVO wbk = analyzed.wbk();
        List<LifezoneSupplyCellVO> cells = result.getCells();
        if (cells == null || cells.isEmpty()) {
            // 🔴 0건은 조회에서는 정상(200)이지만 보고서로는 만들지 않는다 — 빈 문서가 남으면
            //    목록에서 "분석했는데 결과가 있다" 로 읽힌다.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "분석 결과가 없어 보고서를 만들 수 없습니다.");
        }

        String emdCd = result.getEmdCd();
        String sggNm = result.getSggNm();
        String emdNm = null;
        if (emdCd != null) {
            AdmdstNm admdst = resolveAdmdst(emdCd);
            emdNm = admdst.emdNm();
            sggNm = admdst.sggNm();
        }
        String regionNm = LifezoneReportNaming.regionNm(sggNm, emdNm);
        LocalDateTime now = LocalDateTime.now();

        AnlsExcn excn = new AnlsExcn();
        excn.setAnlsTypeCd(ANLS_TYPE_LIFEZONE_SPLY);
        excn.setExcutrId(userId);                   // 로그인 사용자 — 해석·401 은 컨트롤러 책임
        excn.setSggNm(sggNm);                       // 지역 조건이 없으면 null
        excn.setEmdCd(emdCd);
        excn.setAnlsBgngYmd(result.getBgngYmd());   // 요청 문자열이 아니라 정규화된 YYYYMMDD
        excn.setAnlsEndYmd(result.getEndYmd());
        excn.setAnlsParamJson(buildParamJson(result, grade));
        excn.setExcnSttsCd(EXCN_STTS_DONE);
        excn.setExcnDt(now);
        anlsExcnRepository.save(excn);
        anlsExcnRepository.flush();

        AnlsRslt rslt = new AnlsRslt();
        rslt.setExcnId(excn.getExcnId());
        rslt.setRsltJson(buildRsltJson(result, wbk));
        anlsRsltRepository.save(rslt);

        Rptp rptp = new Rptp();
        rptp.setCrtType(CRT_TYPE_MANUAL);
        rptp.setExcnId(excn.getExcnId());
        rptp.setRptpNm(LifezoneReportNaming.rptpNm(
                result.getAnlsTrgtNm(), regionNm, result.getGridSzNm()));
        rptp.setAnlsPrdExpln(LifezoneReportNaming.anlsPrdExpln(result.getBgngYmd(), result.getEndYmd()));
        rptp.setSttsCd(RPTP_STTS_DONE);
        rptp.setFileFmt(req.getFileFmt());
        rptp.setCreatrId(userId);
        rptp.setCreatrNm(ReportCreatorName.cut(userNm));   // 🔴 creatr_nm 은 VARCHAR(50)
        rptp.setCrtYmd(now);
        rptpRepository.save(rptp);
        rptpRepository.flush();

        log.info("생활권 주차 수급 보고서 생성: rptpId={} excnId={} 지역={} 기간={}~{} 격자={} 셀={}건 "
                        + "등급(심각={}, 경고={}, 주의={}, 보통={}, 낮음={}) 공급0={} 표등급={} 형식={}",
                rptp.getRptpId(), excn.getExcnId(), regionNm,
                result.getBgngYmd(), result.getEndYmd(), result.getGridSzNm(), cells.size(),
                result.getSevereCnt(), result.getWarnCnt(), result.getCautionCnt(),
                result.getNormalCnt(), result.getLowCnt(), result.getZeroSplyGridCnt(),
                grade != null ? grade : "전체", req.getFileFmt());

        LifezoneSupplyReportVO vo = new LifezoneSupplyReportVO();
        copyBody(result, vo);
        vo.setRptpId(rptp.getRptpId());
        vo.setExcnId(excn.getExcnId());
        vo.setRptpNm(rptp.getRptpNm());
        vo.setRegionNm(regionNm);
        vo.setAnlsPrdExpln(rptp.getAnlsPrdExpln());
        vo.setFileFmt(rptp.getFileFmt());
        vo.setCrtYmd(rptp.getCrtYmd());
        vo.setGrade(grade);
        vo.setWbk(wbk);
        return vo;
    }

    /* ── 2. 스냅샷 ────────────────────────────────────── */

    @Override
    @Transactional(readOnly = true)
    public LifezoneSupplyReportVO selectReportSnapshot(Long rptpId) {
        LifezoneSnapshotVO snap = reportMapper.selectLifezoneSnapshot(rptpId);
        if (snap == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서를 찾을 수 없습니다: " + rptpId);
        }
        if (!ANLS_TYPE_LIFEZONE_SPLY.equals(snap.getAnlsTypeCd())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "생활권 주차 수급 보고서가 아닙니다: " + rptpId
                            + " (analysisType=" + snap.getAnlsTypeCd() + ")");
        }
        if (snap.getRsltJson() == null || snap.getRsltJson().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "보고서에 저장된 분석 결과가 없습니다: " + rptpId);
        }
        ReportListVO header = reportMapper.selectReportById(rptpId);
        if (header == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서를 찾을 수 없습니다: " + rptpId);
        }

        JsonNode param = readTree(snap.getAnlsParamJson(), "anls_param_json", rptpId);
        JsonNode rsltNode = readTree(snap.getRsltJson(), "rslt_json", rptpId);

        LifezoneSupplyReportVO vo = new LifezoneSupplyReportVO();
        vo.setRptpId(header.getRptpId());
        vo.setExcnId(snap.getExcnId());
        vo.setRptpNm(header.getRptpNm());
        vo.setRegionNm(header.getRegionNm());
        vo.setAnlsPrdExpln(header.getAnlsPrdExpln());
        vo.setFileFmt(header.getFileFmt());
        vo.setCrtYmd(header.getCrtYmd());

        vo.setBgngYmd(snap.getAnlsBgngYmd());
        vo.setEndYmd(snap.getAnlsEndYmd());
        vo.setSggNm(snap.getSggNm());
        vo.setEmdCd(snap.getEmdCd());
        vo.setAnlsTrgtCd(text(param, "anlsTrgtCd"));
        vo.setAnlsTrgtNm(text(param, "anlsTrgtNm"));
        vo.setGridSz(param.path("gridSz").asInt());
        vo.setGridSzNm(text(param, "gridSzNm"));
        vo.setCrs(text(param, "crs"));
        vo.setSectionTitle(text(param, "sectionTitle"));
        vo.setSectionSub(text(param, "sectionSub"));
        // 🔴 안내 문구는 **저장값이 있으면 그것을 쓴다.** 문구를 나중에 고쳐도 과거 보고서는
        //    그때의 문구로 남아야 한다(그 문서가 무엇을 근거로 쓰였는지가 바뀌면 안 된다).
        String disclaimer = text(param, "disclaimer");
        vo.setDisclaimer(disclaimer != null ? disclaimer : LifezoneSupply.DISCLAIMER);
        // 🔁 분석 방법(TSK-4258 D8) — 프론트가 이 값으로 렌더러를 고른다. 구 스냅샷은 null(키 생략).
        String anlsMthd = text(param, "anlsMthd");
        vo.setAnlsMthd(anlsMthd);
        JsonNode summary = rsltNode.path("summary");
        if (LifezoneSupply.ANLS_MTHD_SHRT_RT.equals(anlsMthd)) {
            // 신규 스냅샷 — 부족률 등급 5단계. 🔴 구 키를 읽지 않는다(asDouble()/asInt() 가 0 을 채워
            //    NON_NULL 생략이 깨지고, 범례가 없다는 WARN 이 매번 찍힌다).
            vo.setGradeCriteria(parseGradeCriteria(param, rptpId));
            // 🔴 표 등급 — 저장값을 다시 검증하지 않는다(굳힌 값 그대로 — 스냅샷 계약). null = 전체.
            vo.setGrade(text(param, "grade"));
            vo.setGridNotice(text(summary, "gridNotice"));
            applyGradeSummary(summary, vo);
        } else {
            // 🔴 구 스냅샷(사분면 — anlsMthd 부재) — 원문 그대로 되읽는다(재해석하지 않는다 · D14).
            vo.setCvlcptHighThld(param.path("cvlcptHighThld").asDouble());
            vo.setSplyLowThld(param.path("splyLowThld").asDouble());
            vo.setQdrnCriteria(parseCriteria(param, rptpId));
            // 🔴 표 사분면(D17) — 키가 없는 과거 스냅샷은 null(= 전체)로 종전처럼 그린다.
            //    저장값을 다시 검증하지 않는다(굳힌 값 그대로 — 스냅샷 계약).
            vo.setQdrn(text(param, "qdrn"));
            applySummary(summary, vo);
        }
        // 🆕 TSK-4325 D10 — 키가 없으면(구 스냅샷) null 이다. 빈 내역으로 채우면 "조정 안 함" 으로 읽힌다
        vo.setVrblAjmt(parseVrblAjmt(param));
        vo.setCells(parseCells(rsltNode, rptpId));
        vo.setWbk(parseWbk(rsltNode, rptpId));   // 🆕 TSK-4340 D7-1 — 구 스냅샷은 null
        return vo;
    }

    /* ── 3. 직렬화 ────────────────────────────────────── */

    /**
     * 실행 파라미터 JSON — <b>조건과 화면 문구, 분석 방법, 그리고 등급 범례</b>를 굳힌다.
     *
     * <p>🔴 {@code anlsMthd = "SHRT_RT"} 가 신·구 스냅샷을 가르는 유일한 표지다(D8). 범례 5행의
     * 격자·민원·공급 0 격자 수는 조회 결과에서 산출되므로 함께 굳힌다.
     * 🔴 구 키({@code cvlcptHighThld}·{@code splyLowThld}·{@code qdrnCriteria}·{@code qdrn})는 쓰지 않는다.
     */
    private String buildParamJson(LifezoneSupplyResultVO result, String grade) {
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("gridSz", result.getGridSz());
        param.put("gridSzNm", result.getGridSzNm());
        param.put("anlsTrgtCd", result.getAnlsTrgtCd());
        param.put("anlsTrgtNm", result.getAnlsTrgtNm());
        param.put("crs", result.getCrs());
        param.put("sectionTitle", result.getSectionTitle());
        param.put("sectionSub", result.getSectionSub());
        param.put("disclaimer", result.getDisclaimer());
        param.put("anlsMthd", LifezoneSupply.ANLS_MTHD_SHRT_RT);
        param.put("gradeCriteria", result.getGradeCriteria());
        // 🔁 D20 — 생성 시점 기준값(감사용 기록). 읽는 곳은 없다 — 위 클래스 주석 참고
        param.put("crtrVls", crtrVlsOf(result));
        // 🆕 TSK-4325 D10 — 분석 계수 적용 내역(문서에는 그리지 않는다). crtrVls 도 이미 적용값이다
        param.put("vrblAjmt", result.getVrblAjmt());
        // 🔴 표 등급(TSK-4258 — 종전 qdrn) — null(전체)이어도 키를 남긴다. 읽기는 키 없음·null 을 같게 본다.
        param.put("grade", grade);
        return writeJson(param, "분석 파라미터");
    }

    /**
     * 생성 시점 기준값 {@code {minMnthAvgCvlcpt, grdBndrys: [BNDRY1, BNDRY2, BNDRY3, BNDRY4]}} (D20).
     *
     * <p>경계는 범례 {@code gradeCriteria} 의 하한({@code minShrtRt})에서 꺼낸다 — 판정·범례와 같은 값이 보장된다.
     * 순서는 설정 표와 같은 오름차순(보통 → 심각)이다.
     */
    private static Map<String, Object> crtrVlsOf(LifezoneSupplyResultVO result) {
        Map<LifezoneGrade, Integer> minByGrade = new EnumMap<>(LifezoneGrade.class);
        for (LifezoneSupplyGradeCriterionVO c : result.getGradeCriteria()) {
            minByGrade.put(LifezoneGrade.valueOf(c.getGrade()), c.getMinShrtRt());
        }
        Map<String, Object> crtrVls = new LinkedHashMap<>();
        crtrVls.put("minMnthAvgCvlcpt", result.getMinMnthAvgCvlcpt());
        crtrVls.put("grdBndrys", List.of(
                minByGrade.get(LifezoneGrade.NORMAL), minByGrade.get(LifezoneGrade.CAUTION),
                minByGrade.get(LifezoneGrade.WARN), minByGrade.get(LifezoneGrade.SEVERE)));
        return crtrVls;
    }

    /**
     * 결과 JSON — 요약 + 셀 전량.
     *
     * <p>🔴 셀의 {@code ring}(꼭짓점 4점)도 함께 굳힌다. 격자는 좌표에서 <b>계산</b>되므로
     * 격자ID 로 되살릴 수는 있지만, 그러려면 스냅샷 읽기가 좌표 변환까지 다시 해야 한다 —
     * "재분석하지 않는다" 는 스냅샷의 계약을 깨는 일이다.
     */
    private String buildRsltJson(LifezoneSupplyResultVO result, LifezoneSupplyWorkbookVO wbk) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totGridCnt", result.getTotGridCnt());
        summary.put("totCvlcptCnt", result.getTotCvlcptCnt());
        summary.put("totPkltCpctySum", result.getTotPkltCpctySum());
        summary.put("severeCnt", result.getSevereCnt());
        summary.put("warnCnt", result.getWarnCnt());
        summary.put("cautionCnt", result.getCautionCnt());
        summary.put("normalCnt", result.getNormalCnt());
        summary.put("lowCnt", result.getLowCnt());
        summary.put("zeroSplyGridCnt", result.getZeroSplyGridCnt());
        summary.put("minMnthAvgCvlcpt", result.getMinMnthAvgCvlcpt());
        // 100m 외에는 null — 읽기는 키 없음·null 을 같게 본다(text())
        summary.put("gridNotice", result.getGridNotice());

        Map<String, Object> rslt = new LinkedHashMap<>();
        rslt.put("summary", summary);
        rslt.put("cells", result.getCells());
        // 🆕 TSK-4340 D7 — 엑셀 재계산 데이터. 형식과 무관하게 싣는다(PDF 로 만든 보고서도 엑셀로 다시 받는다)
        rslt.put("wbk", wbk);
        return writeJson(rslt, "분석 결과");
    }

    private String writeJson(Object value, String label) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    label + " 직렬화에 실패했습니다", e);
        }
    }

    /* ── 4. 역직렬화 ──────────────────────────────────── */

    private JsonNode readTree(String json, String field, Long rptpId) {
        if (json == null || json.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "보고서에 " + field + " 가 없습니다: " + rptpId);
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "보고서의 " + field + " 를 읽을 수 없습니다: " + rptpId, e);
        }
    }

    /**
     * 등급 범례 5행을 되살린다 (신규 스냅샷 — TSK-4258).
     *
     * <p>저장분이 비었거나 깨졌으면 <b>빈 목록</b>을 돌려준다 — 범례가 없어도 셀과 요약은 유효해
     * 문서를 만들 수 있다({@link #parseCriteria} 와 같은 방침).
     */
    private List<LifezoneSupplyGradeCriterionVO> parseGradeCriteria(JsonNode param, Long rptpId) {
        JsonNode node = param.path("gradeCriteria");
        if (!node.isArray() || node.isEmpty()) {
            log.warn("보고서에 등급 범례가 없습니다 — 범례 없이 내려보냅니다. rptpId={}", rptpId);
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(List.of(
                    objectMapper.treeToValue(node, LifezoneSupplyGradeCriterionVO[].class)));
        } catch (JsonProcessingException e) {
            log.warn("보고서의 등급 범례를 읽지 못했습니다 — 범례 없이 내려보냅니다. rptpId={} 사유={}",
                    rptpId, e.getClass().getSimpleName());
            log.debug("보고서의 등급 범례를 읽지 못했습니다 상세", e);   // 4.1 — 예외 원문(toString)은 DEBUG 로만 (TSK-4336)
            return new ArrayList<>();
        }
    }

    /**
     * 사분면 범례를 되살린다 (구 스냅샷 전용 — TSK-4258 D14).
     *
     * <p>저장분이 비었거나 깨졌으면 <b>빈 목록</b>을 돌려준다 — 범례가 없어도 셀과 요약은 유효해
     * 문서를 만들 수 있다. 여기서 예외를 던지면 오래된 보고서가 통째로 열리지 않는다.
     */
    private List<LifezoneQuadrantCriterionVO> parseCriteria(JsonNode param, Long rptpId) {
        JsonNode node = param.path("qdrnCriteria");
        if (!node.isArray() || node.isEmpty()) {
            log.warn("보고서에 사분면 범례가 없습니다 — 범례 없이 내려보냅니다. rptpId={}", rptpId);
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(List.of(
                    objectMapper.treeToValue(node, LifezoneQuadrantCriterionVO[].class)));
        } catch (JsonProcessingException e) {
            log.warn("보고서의 사분면 범례를 읽지 못했습니다 — 범례 없이 내려보냅니다. rptpId={} 사유={}",
                    rptpId, e.getClass().getSimpleName());
            log.debug("보고서의 사분면 범례를 읽지 못했습니다 상세", e);   // 4.1 — 예외 원문(toString)은 DEBUG 로만 (TSK-4336)
            return new ArrayList<>();
        }
    }

    /** 신규 스냅샷 요약 (TSK-4258). 🔴 등급 수는 키가 없으면 {@code null} 로 둔다 — {@code 0} 으로 채우지 않는다. */
    private static void applyGradeSummary(JsonNode summary, LifezoneSupplyReportVO vo) {
        vo.setTotGridCnt(summary.path("totGridCnt").asInt());
        vo.setTotCvlcptCnt(summary.path("totCvlcptCnt").asInt());
        vo.setTotPkltCpctySum(summary.path("totPkltCpctySum").asInt());
        vo.setSevereCnt(intOrNull(summary, "severeCnt"));
        vo.setWarnCnt(intOrNull(summary, "warnCnt"));
        vo.setCautionCnt(intOrNull(summary, "cautionCnt"));
        vo.setNormalCnt(intOrNull(summary, "normalCnt"));
        vo.setLowCnt(intOrNull(summary, "lowCnt"));
        vo.setZeroSplyGridCnt(intOrNull(summary, "zeroSplyGridCnt"));
        vo.setMinMnthAvgCvlcpt(intOrNull(summary, "minMnthAvgCvlcpt"));
    }

    /** 구 스냅샷 요약 (사분면 — 원문 그대로) */
    private static void applySummary(JsonNode summary, LifezoneSupplyReportVO vo) {
        vo.setTotGridCnt(summary.path("totGridCnt").asInt());
        vo.setTotCvlcptCnt(summary.path("totCvlcptCnt").asInt());
        vo.setTotPkltCpctySum(summary.path("totPkltCpctySum").asInt());
        vo.setQ1Cnt(summary.path("q1Cnt").asInt());
        vo.setQ2Cnt(summary.path("q2Cnt").asInt());
        vo.setQ3Cnt(summary.path("q3Cnt").asInt());
        vo.setQ4Cnt(summary.path("q4Cnt").asInt());
    }

    /**
     * 셀을 되살린다.
     *
     * <p>🔴 <b>등급(구 스냅샷은 사분면)을 다시 판정하지 않는다</b> — 저장된 {@code grade}·{@code rnk}
     * (구 스냅샷은 {@code qdrn})을 그대로 쓴다. 스냅샷의 계약은 "생성 당시 그대로" 다.
     * 신·구 공통이다 — 구 셀에는 {@code grade} 등 신규 키가 없어 {@code null} 로 남는다(키 생략).
     */
    private List<LifezoneSupplyCellVO> parseCells(JsonNode rslt, Long rptpId) {
        JsonNode node = rslt.path("cells");
        if (!node.isArray() || node.isEmpty()) {
            log.warn("보고서에 격자 셀이 없습니다. rptpId={}", rptpId);
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(List.of(
                    objectMapper.treeToValue(node, LifezoneSupplyCellVO[].class)));
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "보고서의 격자 셀을 읽을 수 없습니다: " + rptpId, e);
        }
    }

    /**
     * 저장된 엑셀 재계산 데이터를 되살린다 (TSK-4340 D7-1 — TSK-4338 {@code HotspotReportServiceImpl.parseWbk} 와 같은 방침).
     *
     * <p>키가 없으면(구 스냅샷) {@code null} 이다 — 백필하지 않는다. 파싱 실패도 500 이 아니라 {@code null} + WARN 이다 —
     * 격자 본문은 멀쩡한데 부가 데이터 때문에 PDF·Word 까지 못 열게 만들 이유가 없다.
     */
    private LifezoneSupplyWorkbookVO parseWbk(JsonNode rslt, Long rptpId) {
        JsonNode node = rslt.path("wbk");
        if (!node.isObject()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(node, LifezoneSupplyWorkbookVO.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("저장된 엑셀 데이터 역직렬화 실패 (wbk 없이 응답): rptpId={} 사유={}", rptpId, e.getClass().getSimpleName());
            log.debug("저장된 엑셀 데이터 역직렬화 실패 (wbk 없이 응답) 상세", e);   // 4.1 — 예외 원문은 DEBUG 로만 (TSK-4336)
            return null;
        }
    }

    /* ── 5. 공통 ──────────────────────────────────────── */

    /**
     * 표 등급 해석 (🔁 TSK-4258 — 종전 {@code resolveQdrn}). 생략·공백은 {@code null}(= 전체),
     * 리스트 대상 3종({@link LifezoneGrade#isListed()} — {@code SEVERE}·{@code WARN}·{@code CAUTION}) 밖은 <b>400</b>.
     *
     * <p>🔴 {@code NORMAL}·{@code LOW} 도 400 이다 — 실재하는 등급이지만 표가 0행이 된다.
     * 🔴 {@link ResponseStatusException} 으로 던진다 — {@code valueOf} 의 {@link IllegalArgumentException}
     * 은 전역 핸들러가 없어 500 으로 나간다({@code LifezoneClsfMthdResolver} 와 같은 관례).
     */
    private static String resolveGrade(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        LifezoneGrade g = LifezoneGrade.ofCode(code);
        if (g == null || !g.isListed()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "지원하지 않는 등급입니다: " + code + " (사용 가능: SEVERE, WARN, CAUTION · 생략 = 전체)");
        }
        return g.name();
    }

    private static void copyBody(LifezoneSupplyResultVO src, LifezoneSupplyReportVO dst) {
        dst.setBgngYmd(src.getBgngYmd());
        dst.setEndYmd(src.getEndYmd());
        dst.setSggNm(src.getSggNm());
        dst.setEmdCd(src.getEmdCd());
        dst.setAnlsTrgtCd(src.getAnlsTrgtCd());
        dst.setAnlsTrgtNm(src.getAnlsTrgtNm());
        dst.setGridSz(src.getGridSz());
        dst.setGridSzNm(src.getGridSzNm());
        dst.setCrs(src.getCrs());
        dst.setSectionTitle(src.getSectionTitle());
        dst.setSectionSub(src.getSectionSub());
        dst.setDisclaimer(src.getDisclaimer());
        // 🔴 TSK-4258 신규 필드는 전부 옮긴다 — 빠뜨리면 생성 응답에서 그 키가 NON_NULL 로 조용히 사라진다.
        dst.setGridNotice(src.getGridNotice());
        dst.setAnlsMthd(src.getAnlsMthd());
        dst.setSevereCnt(src.getSevereCnt());
        dst.setWarnCnt(src.getWarnCnt());
        dst.setCautionCnt(src.getCautionCnt());
        dst.setNormalCnt(src.getNormalCnt());
        dst.setLowCnt(src.getLowCnt());
        dst.setZeroSplyGridCnt(src.getZeroSplyGridCnt());
        dst.setMinMnthAvgCvlcpt(src.getMinMnthAvgCvlcpt());
        dst.setVrblAjmt(src.getVrblAjmt());   // 🔴 빠뜨리면 POST 응답만 vrblAjmt 가 빈다 (TSK-4325)
        dst.setGradeCriteria(src.getGradeCriteria());
        dst.setTotGridCnt(src.getTotGridCnt());
        dst.setTotCvlcptCnt(src.getTotCvlcptCnt());
        dst.setTotPkltCpctySum(src.getTotPkltCpctySum());
        dst.setCells(src.getCells());
        /*
         * 🔴 dataBgngYmd·dataEndYmd 는 **의도적으로 비운다.** 원천 보유 구간은 "지금" 의 값이라
         *    스냅샷이 아니다 — 저장 시점의 값을 넣으면 오래된 보고서가 현재와 다른 구간을
         *    사실처럼 안내한다. (민원건수 보고서와 같은 방침.)
         */
    }

    private record AdmdstNm(String emdNm, String sggNm) {
    }

    private AdmdstNm resolveAdmdst(String emdCd) {
        for (RegionVO region : regionService.selectRegions()) {
            for (EmdVO emd : region.getEmds()) {
                if (emdCd.equals(emd.getEmdCd())) {
                    return new AdmdstNm(emd.getEmdNm(), region.getSggNm());
                }
            }
        }
        // analyze() 가 이미 실재를 확인했으므로 정상 흐름에서는 오지 않는다(방어).
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "존재하지 않는 읍면동코드입니다: " + emdCd);
    }

    /** JSON 정수 칸 — 없거나 null 이면 {@code null}({@code asInt()} 의 {@code 0} 으로 채우지 않는다). */
    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asInt();
    }

    /** JSON 문자열 칸 — 없으면 {@code null}(빈 문자열로 바꾸지 않는다). */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /** 생성 당시 분석 계수 적용 내역 (TSK-4325 D10). 객체가 아니면(키 없음 — 구 스냅샷) {@code null}. */
    private Map<String, Object> parseVrblAjmt(JsonNode param) {
        JsonNode node = param.path("vrblAjmt");
        if (!node.isObject()) {
            return null;
        }
        try {
            return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() { });
        } catch (IllegalArgumentException e) {
            log.warn("보고서의 분석 계수 적용 내역을 읽지 못했습니다 — null 로 내려보냅니다. 사유={}", e.getClass().getSimpleName());
            log.debug("보고서의 분석 계수 적용 내역을 읽지 못했습니다 상세", e);   // 4.1 — 예외 원문(toString)은 DEBUG 로만 (TSK-4336)
            return null;
        }
    }
}
