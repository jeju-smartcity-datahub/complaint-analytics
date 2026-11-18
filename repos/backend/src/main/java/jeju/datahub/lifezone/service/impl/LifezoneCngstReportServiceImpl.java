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
import jeju.datahub.lifezone.service.LifezoneClsfMthd;
import jeju.datahub.lifezone.service.LifezoneCngstCellVO;
import jeju.datahub.lifezone.service.LifezoneCngstReportCreateRequest;
import jeju.datahub.lifezone.service.LifezoneCngstReportService;
import jeju.datahub.lifezone.service.LifezoneCngstReportVO;
import jeju.datahub.lifezone.service.LifezoneCngstResultVO;
import jeju.datahub.lifezone.service.LifezoneCngstService;
import jeju.datahub.lifezone.service.LifezoneCngstWorkbookVO;
import jeju.datahub.lifezone.service.LifezoneGrade;
import jeju.datahub.lifezone.service.LifezoneGradeCriterionVO;
import jeju.datahub.lifezone.service.LifezoneGradeScale;
import jeju.datahub.lifezone.service.LifezoneRankVO;
import jeju.datahub.lifezone.service.LifezoneSnapshotVO;
import jeju.datahub.lifezone.service.LifezoneTarget;
import jeju.datahub.lifezone.service.LifezoneTargetVO;
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
 * 생활권 <b>혼잡도</b> 보고서 구현.
 *
 * <p>수급 보고서({@link LifezoneSupplyReportServiceImpl})와 <b>저장 절차가 같다</b> — 실행·결과·보고서
 * 3표를 한 트랜잭션으로 남기고, 화면이 보낸 숫자 대신 서버가 다시 분석한 결과를 저장한다.
 *
 * <p>🔴 {@code anls_type_cd} 는 {@code LIFEZONE_CNGST} 다. {@code LifezoneCngstServiceImpl.ANLS_TYPE_CD}
 * ({@code LIFEZONE})는 <b>설정 표 키</b>라 보고서 코드로 쓰면 민원건수 파서가 이 보고서를 읽는다.
 *
 * <p>🔴 <b>등급·경계·안내값은 스냅샷 그대로다.</b> 경계({@code CNGST_GRD_BNDRY1~4})가 관리 화면에서 바뀌므로
 * 다시 판정하면 옛 보고서의 등급이 움직인다. {@code crtrVls} 는 설정 표를 다시 읽지 않고 <b>이번 분석 결과</b>에서
 * 꺼낸다. 문서는 {@code gradeCriteria}·{@code occUsedVl} 로 그리므로 {@code crtrVls} 는 감사용 기록이다.
 */
@Service
@RequiredArgsConstructor
public class LifezoneCngstReportServiceImpl extends EgovAbstractServiceImpl
        implements LifezoneCngstReportService {

    private static final Logger log = LoggerFactory.getLogger(LifezoneCngstReportServiceImpl.class);

    private static final String ANLS_TYPE_LIFEZONE_CNGST = "LIFEZONE_CNGST";
    private static final String EXCN_STTS_DONE = "DONE";
    private static final String RPTP_STTS_DONE = "DONE";
    private static final String CRT_TYPE_MANUAL = "MANUAL";

    private final LifezoneCngstService lifezoneCngstService;
    private final RegionService regionService;
    private final AnlsExcnRepository anlsExcnRepository;
    private final AnlsRsltRepository anlsRsltRepository;
    private final RptpRepository rptpRepository;
    private final ReportMapper reportMapper;
    private final ObjectMapper objectMapper;

    /* ── 1. 생성 ──────────────────────────────────────── */

    @Override
    @Transactional
    public LifezoneCngstReportVO createReport(LifezoneCngstReportCreateRequest req, String userId, String userNm) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "요청 본문이 없습니다");
        }
        // 엑셀 원자료(wbk)를 같은 조건으로 함께 구한다(형식 무관 — PDF 로 저장해도 싣는다)
        LifezoneCngstService.LifezoneCngstWithWorkbook analyzed =
                lifezoneCngstService.analyzeWithWorkbook(req.toAnalysisRequest());
        LifezoneCngstResultVO result = analyzed.result();
        LifezoneCngstWorkbookVO wbk = analyzed.wbk();
        List<LifezoneCngstCellVO> cells = result.getCells();
        if (cells == null || cells.isEmpty()) {
            // 🔴 순위 0행은 막지 않는다 — 셀이 있고 심각·경고·주의가 없는 것은 정상이다.
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
        excn.setAnlsTypeCd(ANLS_TYPE_LIFEZONE_CNGST);
        excn.setExcutrId(userId);
        excn.setSggNm(sggNm);
        excn.setEmdCd(emdCd);
        excn.setAnlsBgngYmd(result.getBgngYmd());
        excn.setAnlsEndYmd(result.getEndYmd());
        excn.setAnlsParamJson(buildParamJson(result));
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

        log.info("생활권 혼잡도 보고서 생성: rptpId={} excnId={} 지역={} 기간={}~{} 격자={} 셀={}건 순위={}행 "
                        + "등급(심각={}, 경고={}, 주의={}, 보통={}, 낮음={}) 형식={}",
                rptp.getRptpId(), excn.getExcnId(), regionNm,
                result.getBgngYmd(), result.getEndYmd(), result.getGridSzNm(), cells.size(),
                result.getRanking().size(), result.getSevereCnt(), result.getWarnCnt(), result.getCautionCnt(),
                result.getNormalCnt(), result.getLowCnt(), req.getFileFmt());

        LifezoneCngstReportVO vo = new LifezoneCngstReportVO();
        copyBody(result, vo);
        vo.setRptpId(rptp.getRptpId());
        vo.setExcnId(excn.getExcnId());
        vo.setRptpNm(rptp.getRptpNm());
        vo.setRegionNm(regionNm);
        vo.setAnlsPrdExpln(rptp.getAnlsPrdExpln());
        vo.setFileFmt(rptp.getFileFmt());
        vo.setCrtYmd(rptp.getCrtYmd());
        vo.setWbk(wbk);
        return vo;
    }

    /* ── 2. 스냅샷 ────────────────────────────────────── */

    @Override
    @Transactional(readOnly = true)
    public LifezoneCngstReportVO selectReportSnapshot(Long rptpId) {
        LifezoneSnapshotVO snap = reportMapper.selectLifezoneSnapshot(rptpId);
        if (snap == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서를 찾을 수 없습니다: " + rptpId);
        }
        if (!ANLS_TYPE_LIFEZONE_CNGST.equals(snap.getAnlsTypeCd())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "생활권 혼잡도 보고서가 아닙니다: " + rptpId + " (analysisType=" + snap.getAnlsTypeCd() + ")");
        }
        if (snap.getRsltJson() == null || snap.getRsltJson().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서에 저장된 분석 결과가 없습니다: " + rptpId);
        }
        ReportListVO header = reportMapper.selectReportById(rptpId);
        if (header == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서를 찾을 수 없습니다: " + rptpId);
        }

        JsonNode param = readTree(snap.getAnlsParamJson(), "anls_param_json", rptpId);
        JsonNode rsltNode = readTree(snap.getRsltJson(), "rslt_json", rptpId);
        JsonNode summary = rsltNode.path("summary");

        LifezoneCngstReportVO vo = new LifezoneCngstReportVO();
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
        vo.setClsfMthd(text(param, "clsfMthd"));
        vo.setClsfMthdNm(text(param, "clsfMthdNm"));
        vo.setAvailableClsfMthds(LifezoneTarget.CNGST.getClsfMthds().stream().map(Enum::name).toList());
        vo.setUnit(text(param, "unit"));
        vo.setSectionTitle(text(param, "sectionTitle"));
        vo.setSectionSub(text(param, "sectionSub"));
        vo.setAvailableTargets(LifezoneTargetVO.allOf());
        vo.setWeights(List.of());
        List<LifezoneGradeCriterionVO> criteria = convert(param.path("gradeCriteria"),
                new TypeReference<>() { }, "gradeCriteria", rptpId);
        vo.setGradeCriteria(criteria);
        vo.setGradeScale(rebuildScale(param, criteria, rptpId));
        if (param.hasNonNull("occUsedVl")) {
            vo.setOccUsedVl(new BigDecimal(param.path("occUsedVl").asText()));
        }
        // 키가 없으면(구 스냅샷) null 이다. 빈 내역으로 채우면 "조정 안 함" 으로 읽힌다
        vo.setVrblAjmt(parseVrblAjmt(param));

        vo.setTotGridCnt(summary.path("totGridCnt").asInt());
        vo.setGrdTrgtGridCnt(summary.path("grdTrgtGridCnt").asInt());
        vo.setNoEnfGridCnt(summary.path("noEnfGridCnt").asInt());
        vo.setRefGridCnt(summary.path("refGridCnt").asInt());
        vo.setOver1GridCnt(summary.path("over1GridCnt").asInt());
        vo.setSevereCnt(summary.path("severeCnt").asInt());
        vo.setWarnCnt(summary.path("warnCnt").asInt());
        vo.setCautionCnt(summary.path("cautionCnt").asInt());
        vo.setNormalCnt(summary.path("normalCnt").asInt());
        vo.setLowCnt(summary.path("lowCnt").asInt());
        // 🔴 dataBgngYmd·dataEndYmd 는 비운다 — 원천 보유 구간은 "지금" 의 값이라 스냅샷이 아니다.

        vo.setRanking(convert(rsltNode.path("ranking"), new TypeReference<List<LifezoneRankVO>>() { },
                "ranking", rptpId));
        vo.setCells(convert(rsltNode.path("cells"), new TypeReference<List<LifezoneCngstCellVO>>() { },
                "cells", rptpId));
        vo.setWbk(parseWbk(rsltNode, rptpId));   // 구 스냅샷은 null
        return vo;
    }

    /* ── 3. 직렬화 ────────────────────────────────────── */

    /** 실행 파라미터 JSON — 조건·문구·등급 범례·생성 시점 기준값을 굳힌다. */
    private String buildParamJson(LifezoneCngstResultVO result) {
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("gridSz", result.getGridSz());
        param.put("gridSzNm", result.getGridSzNm());
        param.put("anlsTrgtCd", result.getAnlsTrgtCd());
        param.put("anlsTrgtNm", result.getAnlsTrgtNm());
        param.put("clsfMthd", result.getClsfMthd());
        param.put("clsfMthdNm", result.getClsfMthdNm());
        param.put("unit", result.getUnit());
        param.put("sectionTitle", result.getSectionTitle());
        param.put("sectionSub", result.getSectionSub());
        param.put("gradeScale", result.getGradeScale());
        param.put("gradeCriteria", result.getGradeCriteria());
        param.put("occUsedVl", result.getOccUsedVl());
        param.put("crtrVls", crtrVlsOf(result));
        // 분석 계수 적용 내역(문서에는 그리지 않는다). crtrVls 도 이미 적용값이다
        param.put("vrblAjmt", result.getVrblAjmt());
        return writeJson(param, "분석 파라미터");
    }

    /**
     * 생성 시점 기준값 {@code {occUsedVl, grdBndrys: [BNDRY1, BNDRY2, BNDRY3, BNDRY4]}}.
     *
     * <p>경계는 범례 하한에서 꺼낸다 — 판정·범례와 같은 값이 보장된다. 🔴 단위는 {@code anlsVl} 과 같은 점수 × 100 이다.
     */
    private static Map<String, Object> crtrVlsOf(LifezoneCngstResultVO result) {
        Map<LifezoneGrade, Integer> minByGrade = new EnumMap<>(LifezoneGrade.class);
        for (LifezoneGradeCriterionVO c : result.getGradeCriteria()) {
            minByGrade.put(LifezoneGrade.valueOf(c.getGrade()), c.getMinVl());
        }
        Map<String, Object> crtrVls = new LinkedHashMap<>();
        crtrVls.put("occUsedVl", result.getOccUsedVl());
        crtrVls.put("grdBndrys", List.of(
                minByGrade.get(LifezoneGrade.NORMAL), minByGrade.get(LifezoneGrade.CAUTION),
                minByGrade.get(LifezoneGrade.WARN), minByGrade.get(LifezoneGrade.SEVERE)));
        return crtrVls;
    }

    /** 결과 JSON — 요약 + 순위 + 셀 전량. 🔴 셀은 등급·{@code ring} 까지 굳힌다(읽을 때 다시 계산하지 않는다). */
    private String buildRsltJson(LifezoneCngstResultVO result, LifezoneCngstWorkbookVO wbk) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totGridCnt", result.getTotGridCnt());
        summary.put("grdTrgtGridCnt", result.getGrdTrgtGridCnt());
        summary.put("noEnfGridCnt", result.getNoEnfGridCnt());
        summary.put("refGridCnt", result.getRefGridCnt());
        summary.put("over1GridCnt", result.getOver1GridCnt());
        summary.put("severeCnt", result.getSevereCnt());
        summary.put("warnCnt", result.getWarnCnt());
        summary.put("cautionCnt", result.getCautionCnt());
        summary.put("normalCnt", result.getNormalCnt());
        summary.put("lowCnt", result.getLowCnt());

        Map<String, Object> rslt = new LinkedHashMap<>();
        rslt.put("summary", summary);
        rslt.put("ranking", result.getRanking());
        rslt.put("cells", result.getCells());
        // 엑셀 원자료. 형식과 무관하게 싣는다(PDF 로 만든 보고서도 엑셀로 다시 받는다)
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
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서에 " + field + " 가 없습니다: " + rptpId);
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "보고서의 " + field + " 를 읽을 수 없습니다: " + rptpId, e);
        }
    }

    private <T> T convert(JsonNode node, TypeReference<T> type, String field, Long rptpId) {
        if (!node.isArray()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "저장된 분석 결과에 " + field + " 배열이 없습니다: rptpId=" + rptpId);
        }
        return objectMapper.convertValue(node, type);
    }

    /**
     * 저장된 경계로 {@link LifezoneGradeScale} 을 되살린다.
     *
     * <p>🔴 Jackson 역직렬화를 쓰지 않는다 — {@code @JsonIgnore int[] minVls} 를 가진 record 라 생성자가 터진다.
     * 경계는 {@code gradeCriteria[].minVl} 에 있으므로 등급 이름으로 자리를 찾아 정규 생성자에 넣는다.
     */
    private LifezoneGradeScale rebuildScale(JsonNode param, List<LifezoneGradeCriterionVO> criteria, Long rptpId) {
        JsonNode scaleNode = param.path("gradeScale");
        LifezoneClsfMthd mthd = LifezoneClsfMthd.ofCode(scaleNode.path("method").asText(null));
        int[] minVls = new int[LifezoneGrade.values().length];
        List<LifezoneGrade> filled = new ArrayList<>();
        for (LifezoneGradeCriterionVO row : criteria) {
            LifezoneGrade grade = LifezoneGrade.ofCode(row.getGrade());
            if (grade != null) {
                minVls[grade.ordinal()] = row.getMinVl();
                filled.add(grade);
            }
        }
        if (mthd == null || !filled.containsAll(List.of(LifezoneGrade.values()))) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "보고서 스냅샷의 등급 기준을 읽을 수 없습니다: rptpId=" + rptpId);
        }
        return new LifezoneGradeScale(scaleNode.path("cap").asInt(), scaleNode.path("width").asInt(), mthd, minVls);
    }

    /**
     * 저장된 엑셀 원자료를 되살린다 ({@code HotspotReportServiceImpl.parseWbk} 와 같은 방침).
     *
     * <p>키가 없으면(구 스냅샷) {@code null} 이다 — 백필하지 않는다. 파싱 실패도 500 이 아니라 {@code null} + WARN 이다.
     */
    private LifezoneCngstWorkbookVO parseWbk(JsonNode rslt, Long rptpId) {
        JsonNode node = rslt.path("wbk");
        if (!node.isObject()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(node, LifezoneCngstWorkbookVO.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("저장된 엑셀 데이터 역직렬화 실패 (wbk 없이 응답): rptpId={} 사유={}", rptpId, e.getClass().getSimpleName());
            log.debug("저장된 엑셀 데이터 역직렬화 실패 (wbk 없이 응답) 상세", e);   // 4.1 — 예외 원문은 DEBUG 로만
            return null;
        }
    }

    /* ── 5. 공통 ──────────────────────────────────────── */

    private static void copyBody(LifezoneCngstResultVO src, LifezoneCngstReportVO dst) {
        dst.setBgngYmd(src.getBgngYmd());
        dst.setEndYmd(src.getEndYmd());
        dst.setSggNm(src.getSggNm());
        dst.setEmdCd(src.getEmdCd());
        dst.setAnlsTrgtCd(src.getAnlsTrgtCd());
        dst.setAnlsTrgtNm(src.getAnlsTrgtNm());
        dst.setGridSz(src.getGridSz());
        dst.setGridSzNm(src.getGridSzNm());
        dst.setClsfMthd(src.getClsfMthd());
        dst.setClsfMthdNm(src.getClsfMthdNm());
        dst.setAvailableClsfMthds(src.getAvailableClsfMthds());
        dst.setUnit(src.getUnit());
        dst.setSectionTitle(src.getSectionTitle());
        dst.setSectionSub(src.getSectionSub());
        dst.setAvailableTargets(src.getAvailableTargets());
        dst.setGradeScale(src.getGradeScale());
        dst.setGradeCriteria(src.getGradeCriteria());
        dst.setWeights(src.getWeights());
        dst.setTotGridCnt(src.getTotGridCnt());
        dst.setGrdTrgtGridCnt(src.getGrdTrgtGridCnt());
        dst.setNoEnfGridCnt(src.getNoEnfGridCnt());
        dst.setRefGridCnt(src.getRefGridCnt());
        dst.setOver1GridCnt(src.getOver1GridCnt());
        dst.setOccUsedVl(src.getOccUsedVl());
        dst.setVrblAjmt(src.getVrblAjmt());   // 🔴 빠뜨리면 POST 응답만 vrblAjmt 가 빈다
        dst.setSevereCnt(src.getSevereCnt());
        dst.setWarnCnt(src.getWarnCnt());
        dst.setCautionCnt(src.getCautionCnt());
        dst.setNormalCnt(src.getNormalCnt());
        dst.setLowCnt(src.getLowCnt());
        dst.setCells(src.getCells());
        dst.setRanking(src.getRanking());
        // 🔴 dataBgngYmd·dataEndYmd 는 의도적으로 비운다 — 저장 응답과 스냅샷의 본문을 같게 둔다.
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
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "존재하지 않는 읍면동코드입니다: " + emdCd);
    }

    /** JSON 문자열 칸 — 없으면 {@code null}. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /** 생성 당시 분석 계수 적용 내역. 객체가 아니면(키 없음 — 구 스냅샷) {@code null}. */
    private Map<String, Object> parseVrblAjmt(JsonNode param) {
        JsonNode node = param.path("vrblAjmt");
        if (!node.isObject()) {
            return null;
        }
        try {
            return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() { });
        } catch (IllegalArgumentException e) {
            log.warn("보고서의 분석 계수 적용 내역을 읽지 못했습니다 — null 로 내려보냅니다. 사유={}", e.getClass().getSimpleName());
            log.debug("보고서의 분석 계수 적용 내역을 읽지 못했습니다 상세", e);   // 4.1 — 예외 원문(toString)은 DEBUG 로만
            return null;
        }
    }
}
