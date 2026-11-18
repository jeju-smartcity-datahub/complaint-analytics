package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.InvestPrrtReportCreateRequest;
import jeju.datahub.analysis.service.InvestPrrtReportService;
import jeju.datahub.analysis.service.InvestPrrtReportVO;
import jeju.datahub.analysis.service.InvestPrrtResultVO;
import jeju.datahub.analysis.service.InvestPrrtService;
import jeju.datahub.report.service.ReportCreatorName;
import jeju.datahub.report.service.ReportListVO;
import jeju.datahub.report.service.impl.ReportMapper;
import jeju.datahub.report.service.impl.Rptp;
import jeju.datahub.report.service.impl.RptpRepository;
import lombok.RequiredArgsConstructor;

/**
 * 투자우선순위 보고서 서비스 구현체. 전자정부 {@link EgovAbstractServiceImpl} 상속.
 *
 * <p>처리 순서는 넷이다 — ①같은 조건으로 <b>재분석</b> → ②실행 이력 1행 → ③분석 결과 1행 → ④보고서 1행
 * ({@link HotspotReportServiceImpl#createReport} 와 같은 흐름). 산식·순위·등급은 {@link InvestPrrtService} 가
 * 이미 하므로 여기서 다시 구현하지 않는다.
 *
 * <h2>🔴 화면이 보낸 숫자를 저장하지 않는다</h2>
 * 요청 바디에는 <b>조회 조건과 파일형식만</b> 있다. 화면이 보낸 순위를 받아 굳히면 조작된 순위가 보고서로
 * 남을 수 있다 — 그래서 저장 직전에 서버가 한 번 더 분석한다.
 *
 * <h2>🔴 트랜잭션 하나로 세 표를 묶는다</h2>
 * 분석 실패(400/409)와 0곳 판정이 저장보다 <b>앞</b>에 오고, 뒤에서 예외가 나면 세 표가 함께 롤백된다 —
 * 실행 이력만 남고 보고서가 없는 반쪽 상태를 만들지 않는다. {@link InvestPrrtService#analyze} 는
 * {@code readOnly} 트랜잭션이지만 여기서 부르면 이 쓰기 트랜잭션에 참여한다(REQUIRED).
 *
 * <h2>🔴 스냅샷은 {@code rslt_json} 한 칸이 전부다</h2>
 * 가중치·등급 기준·계수·알림·요약·주차장 목록을 <b>모두</b> 결과 JSON 에 담고, 조회는 그 칸만 되살린다.
 * 가중치 마스터({@code TB_ANLS_VRBL_STNG})도 원천 데이터도 다시 읽지 않는다 — 설정을 고치거나 자료가 다시
 * 적재돼도 보고서는 저장 시점 그대로다. 등급({@code prrtLvlCd})도 주차장마다 저장한다 — 임계가 설정값이라
 * 조회 때 다시 판정하면 과거 보고서의 등급이 소급해 바뀐다(임계가 enum 상수인 집중구역과 다른 점).
 * 예외는 분석 계수 적용 내역({@code vrblAjmt}) 하나다 — {@code anls_param_json} 에 저장하고 거기서 되살린다.
 */
@Service
@RequiredArgsConstructor
public class InvestPrrtReportServiceImpl extends EgovAbstractServiceImpl implements InvestPrrtReportService {

    private static final Logger log = LoggerFactory.getLogger(InvestPrrtReportServiceImpl.class);

    /** 분석유형코드 ({@code ANLS_TYPE_CD}) — 이 값이 보고서 목록의 출처 칸을 "투자우선순위" 로 만든다 */
    private static final String ANLS_TYPE_INVEST = "INVEST";

    /** 분석실행상태코드 ({@code EXCN_STTS_CD}) — 저장이 곧 완료다(비동기 실행이 아니다) */
    private static final String EXCN_STTS_DONE = "DONE";

    /** 보고서상태코드 ({@code RPTP_STTS_CD}) — 파일을 서버에 굽지 않으므로 생성 즉시 완료다 */
    private static final String RPTP_STTS_DONE = "DONE";

    /** 생성구분 ({@code RPTP_CRT_TYPE_CD}) — 보고서 목록의 <b>직접 생성 탭</b>에 뜨게 한다 */
    private static final String CRT_TYPE_MANUAL = "MANUAL";

    private final InvestPrrtService investPrrtService;
    private final InvestPrrtReportMapper investPrrtReportMapper;
    /** 읍면동코드 → 시군구명·읍면동명 (집중구역 보고서가 쓰는 조회를 그대로 쓴다 — 같은 패키지) */
    private final HotspotReportMapper hotspotReportMapper;
    private final AnlsExcnRepository anlsExcnRepository;
    private final AnlsRsltRepository anlsRsltRepository;
    private final RptpRepository rptpRepository;
    /** 스냅샷 헤더(보고서명·지역명·기간·형식·생성일시) 조회용 — 지역 3단 CASE 를 복제하지 않는다 */
    private final ReportMapper reportMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public InvestPrrtReportVO createReport(InvestPrrtReportCreateRequest req, String userId, String userNm) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "요청 본문이 없습니다");
        }

        // ── 지역 조건 정리 — 저장할 값과 분석에 넘길 값을 같게 만든다 ─────────────────
        String sggNm = blankToNull(req.getSggNm());
        String emdCd = blankToNull(req.getEmdCd());
        String emdNm = null;
        if (emdCd != null) {
            AdmdstNmVO admdst = hotspotReportMapper.selectAdmdstNm(emdCd);
            if (admdst == null) {
                // tb_anls_excn.emd_cd 는 tb_admdst FK 다 — 여기서 끊지 않으면 저장에서 500 이 된다.
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "존재하지 않는 읍면동코드입니다: " + emdCd);
            }
            if (sggNm != null && !sggNm.equals(admdst.getSggNm())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "읍면동(" + emdCd + ")이 시군구(" + sggNm + ")에 속하지 않습니다");
            }
            emdNm = admdst.getEmdNm();
            // 🔴 emdCd 만 온 요청도 시군구명을 채워 저장한다. 비워 두면 보고서 조회 SQL 의
            //    `e.sgg_nm || ' ' || a.emd_nm` 이 NULL 연결이 되어 지역명이 통째로 사라진다.
            sggNm = admdst.getSggNm();
        }

        // ── ① 같은 조건으로 다시 분석한다 — 기간·설정 오류는 여기서 400/409 로 끊긴다 ──
        // 🔴 vrblVls 를 빠뜨리면 컴파일 오류 없이 보고서만 설정값으로 계산된다 — 화면과 순위가 갈린다
        InvestPrrtResultVO result = investPrrtService.analyzeWithWorkbook(
                req.getBgngYmd(), req.getEndYmd(), sggNm, emdCd, req.getVrblVls());
        if (result.getLots() == null || result.getLots().isEmpty()) {
            // 조회 API 는 0건도 200 이지만 보고서는 400 이다(집중구역과 같은 정책) — 열어도 볼 것이 없는
            // 보고서를 목록에 남기지 않는다. 아직 아무것도 저장하지 않았다.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "조건에 맞는 주차장이 없어 보고서를 만들 수 없습니다.");
        }
        String regionNm = InvestPrrtReportNaming.regionNm(sggNm, emdNm);

        // ── ② 실행 이력 1행 ─────────────────────────────────────────────────────
        LocalDateTime now = LocalDateTime.now();
        AnlsExcn excn = new AnlsExcn();
        excn.setAnlsTypeCd(ANLS_TYPE_INVEST);
        excn.setExcutrId(userId);                   // 로그인 사용자 — 해석·401 은 컨트롤러 책임
        excn.setSggNm(sggNm);
        excn.setEmdCd(emdCd);
        // 🔴 요청값이 아니라 실제 적용된 창이다 — 날짜를 생략한 요청도 그날의 기본값이 저장된다
        excn.setAnlsBgngYmd(result.getBgngYmd());
        excn.setAnlsEndYmd(result.getEndYmd());
        excn.setAnlsParamJson(buildParamJson(result, sggNm, emdCd));
        excn.setExcnSttsCd(EXCN_STTS_DONE);
        excn.setExcnDt(now);
        anlsExcnRepository.save(excn);
        // excn_id 를 결과 행·보고서 행이 참조하므로 여기서 확정한다 (identity 채번은 flush 시점).
        anlsExcnRepository.flush();

        // ── ③ 분석 결과 1행 (실행 1건 = 결과 1행) ─────────────────────────────────
        AnlsRslt rslt = new AnlsRslt();
        rslt.setExcnId(excn.getExcnId());
        rslt.setRsltJson(buildRsltJson(result));
        anlsRsltRepository.save(rslt);

        // ── ④ 보고서 1행 — MANUAL · file_path 는 NULL ──────────────────────────────
        Rptp rptp = new Rptp();
        rptp.setCrtType(CRT_TYPE_MANUAL);
        rptp.setExcnId(excn.getExcnId());
        rptp.setRptpNm(InvestPrrtReportNaming.rptpNm(regionNm));
        rptp.setAnlsPrdExpln(InvestPrrtReportNaming.anlsPrdExpln(result.getBgngYmd(), result.getEndYmd()));
        rptp.setSttsCd(RPTP_STTS_DONE);
        rptp.setFileFmt(req.getFileFmt());
        // filePath 는 채우지 않는다 — 파일을 서버에 굽지 않고 프론트가 응답으로 문서를 그린다.
        // stngId·trgtPrdCd·crltnDeptCd·crltnCyclCd 는 자동 생성 전용 칸이라 null 로 둔다.
        rptp.setCreatrId(userId);
        rptp.setCreatrNm(ReportCreatorName.cut(userNm));   // 🔴 creatr_nm 은 VARCHAR(50)
        rptp.setCrtYmd(now);
        rptpRepository.save(rptp);
        rptpRepository.flush();

        log.info("투자우선순위 보고서 생성: rptpId={} excnId={} 지역={} 기간={}~{} 주차장={}곳 형식={}",
                rptp.getRptpId(), excn.getExcnId(), regionNm,
                result.getBgngYmd(), result.getEndYmd(), result.getLots().size(), req.getFileFmt());

        InvestPrrtReportVO vo = new InvestPrrtReportVO();
        vo.setRptpId(rptp.getRptpId());
        vo.setExcnId(excn.getExcnId());
        vo.setRptpNm(rptp.getRptpNm());
        vo.setRegionNm(regionNm);
        vo.setAnlsPrdExpln(rptp.getAnlsPrdExpln());
        vo.setFileFmt(rptp.getFileFmt());
        vo.setCrtYmd(rptp.getCrtYmd());
        vo.setBgngYmd(result.getBgngYmd());
        vo.setEndYmd(result.getEndYmd());
        vo.setScoreFormula(result.getScoreFormula());
        vo.setWeights(result.getWeights());
        vo.setGradeCriteria(result.getGradeCriteria());
        vo.setCrtrVls(result.getCrtrVls());
        vo.setVrblAjmt(result.getVrblAjmt());
        vo.setWarnings(result.getWarnings());
        vo.setSmry(result.getSmry());
        vo.setLots(result.getLots());
        vo.setWbk(result.getWbk());   // 🔴 buildRsltJson 의 "wbk" 와 짝(하나만 하면 POST·GET 이 갈린다)
        return vo;
    }

    /**
     * {@inheritDoc}
     *
     * <p>순서는 <b>원문 1행 → 404 판정 3종 → 헤더 → 본문 복원</b> 이다.
     */
    @Override
    @Transactional(readOnly = true)
    public InvestPrrtReportVO selectReportSnapshot(Long rptpId) {
        InvestPrrtSnapshotVO snapshot = investPrrtReportMapper.selectInvestSnapshot(rptpId);
        if (snapshot == null) {
            // 404-①: 보고서 자체가 없다.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서를 찾을 수 없습니다: " + rptpId);
        }
        if (!ANLS_TYPE_INVEST.equals(snapshot.getAnlsTypeCd())) {
            // 404-②: 다른 유형(또는 실행 미연결)이다. 400 이 아니라 404 인 이유: 요청은 정상이고
            //        "그 경로에 그 자원이 없다".
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "투자우선순위 보고서가 아닙니다: " + rptpId + " (analysisType=" + snapshot.getAnlsTypeCd() + ")");
        }
        String rsltJson = snapshot.getRsltJson();
        if (rsltJson == null || rsltJson.isBlank()) {
            // 404-③: 유형은 맞지만 결과 행이 없다.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서에 저장된 분석 결과가 없습니다: " + rptpId);
        }

        // 헤더는 기존 단건 조회를 재사용한다. 위 조회가 행을 찾았으므로 null 은 동시 삭제뿐이다(방어).
        ReportListVO header = reportMapper.selectReportById(rptpId);
        if (header == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서를 찾을 수 없습니다: " + rptpId);
        }

        // 🔴 저장된 JSON 을 응답 VO 로 '바로' 읽는다. 키가 응답 필드명과 같아 변환이 없고, 트리(JsonNode)를
        //    거치지 않아 소수가 double 로 뭉개지지 않는다(BigDecimal 필드는 원문 숫자열 그대로 — 자릿수 유지).
        //    메타 7칸과 기간은 JSON 에 없어 아래에서 채운다.
        InvestPrrtReportVO vo;
        try {
            vo = objectMapper.readValue(rsltJson, InvestPrrtReportVO.class);
        } catch (JsonProcessingException e) {
            // 깨진 스냅샷은 조용히 넘길 상태가 아니다 — 빈 문서를 200 으로 내지 않고 500 으로 드러낸다.
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "저장된 분석 결과 역직렬화에 실패했습니다: rptpId=" + rptpId, e);
        }
        if (vo == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서에 저장된 분석 결과가 없습니다: " + rptpId);
        }
        vo.setRptpId(header.getRptpId());
        vo.setExcnId(snapshot.getExcnId());
        vo.setRptpNm(header.getRptpNm());
        vo.setRegionNm(header.getRegionNm());
        vo.setAnlsPrdExpln(header.getAnlsPrdExpln());
        vo.setFileFmt(header.getFileFmt());
        vo.setCrtYmd(header.getCrtYmd());
        vo.setBgngYmd(snapshot.getAnlsBgngYmd());
        vo.setEndYmd(snapshot.getAnlsEndYmd());
        vo.setVrblAjmt(parseVrblAjmt(snapshot.getVrblAjmtJson(), rptpId));
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
    // 저장 값 조립
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 분석 결과 JSON — 스냅샷 본문 전체. 키 8개를 <b>하나씩 명시해</b> 담는다:
     * {@code smry · weights · gradeCriteria · crtrVls · warnings · scoreFormula · lots · wbk}.
     *
     * <p>{@code wbk}(엑셀 재계산 데이터)는 <b>파일형식과 무관하게</b> 싣는다(PDF 로 만든 보고서도
     * 목록에서 엑셀로 다시 받는다). {@code null}({@code ENFORCE} 가중치 갈래)이면 키를 두지 않는다.
     *
     * <p>키 이름과 값의 모양이 조회 응답({@link InvestPrrtResultVO})과 같아 조회할 때 변환이 없다.
     * 값은 분석 결과를 <b>가공 없이</b> 옮긴다 — 여기서 반올림하거나 다시 계산하면 같은 보고서의 생성 응답과
     * 스냅샷 조회가 갈린다. {@code lots} 는 지역 필터 통과분이고 {@code rnk} 는 전 지역 순위 그대로다.
     *
     * <p>기간({@code bgngYmd}·{@code endYmd})은 여기 넣지 않는다 — 실행 행의 {@code anls_bgng_ymd}·
     * {@code anls_end_ymd} 가 정본이고, 두 곳에 적으면 갈린다.
     */
    private String buildRsltJson(InvestPrrtResultVO result) {
        Map<String, Object> rslt = new LinkedHashMap<>();
        rslt.put("smry", result.getSmry());
        rslt.put("weights", result.getWeights());
        rslt.put("gradeCriteria", result.getGradeCriteria());
        rslt.put("crtrVls", result.getCrtrVls());
        rslt.put("warnings", result.getWarnings());
        rslt.put("scoreFormula", result.getScoreFormula());
        rslt.put("lots", result.getLots());
        if (result.getWbk() != null) {
            rslt.put("wbk", result.getWbk());
        }
        return write(rslt, "분석 결과");
    }

    /**
     * 재현 메타 JSON — "무엇을 넣어 돌렸는가". 키를 하나씩 명시한다:
     * {@code bgngYmd · endYmd · sggNm · emdCd · weights[{vrblCd, vrblNm, stngVl}] · upGrdCrtrScr ·
     * mdGrdCrtrScr · altmntRngVl · indctPkltCnt · scoreFormula · vrblAjmt}.
     *
     * <p>스냅샷 조회는 이 칸에서 {@code vrblAjmt} 만 읽는다(그 밖의 본문은 {@code rslt_json} 이 전부다) — 이 JSON 은 나중에 사람이나
     * 다른 도구가 "그 보고서는 어떤 설정으로 만들었나" 를 SQL 로 볼 때 쓰는 기록이다. 그래서 가중치는
     * 설정 VO 전체가 아니라 재현에 필요한 3칸만 담는다.
     *
     * <p>등급 임계는 분석 응답의 {@code gradeCriteria} 에서 옮긴다(설정을 여기서 다시 읽지 않는다 —
     * 분석이 실제로 쓴 값과 갈릴 수 있다).
     */
    private String buildParamJson(InvestPrrtResultVO result, String sggNm, String emdCd) {
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("bgngYmd", result.getBgngYmd());
        param.put("endYmd", result.getEndYmd());
        param.put("sggNm", sggNm);
        param.put("emdCd", emdCd);

        List<Map<String, Object>> weights = new ArrayList<>();
        if (result.getWeights() != null) {
            for (AnalysisWeightVO w : result.getWeights()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("vrblCd", w.getVrblCd());
                row.put("vrblNm", w.getVrblNm());
                row.put("stngVl", w.getStngVl());
                weights.add(row);
            }
        }
        param.put("weights", weights);

        param.put("upGrdCrtrScr", minScr(result, InvestPrrtCalculator.LVL_HIGH));
        param.put("mdGrdCrtrScr", minScr(result, InvestPrrtCalculator.LVL_MID));
        InvestPrrtResultVO.CrtrVls crtrVls = result.getCrtrVls();
        param.put("altmntRngVl", crtrVls == null ? null : crtrVls.getAltmntRngVl());
        param.put("indctPkltCnt", crtrVls == null ? null : crtrVls.getIndctPkltCnt());
        param.put("scoreFormula", result.getScoreFormula());
        param.put("vrblAjmt", result.getVrblAjmt());
        return write(param, "분석 파라미터");
    }

    private static BigDecimal minScr(InvestPrrtResultVO result, String prrtLvlCd) {
        if (result.getGradeCriteria() != null) {
            for (InvestPrrtResultVO.GradeCriterion g : result.getGradeCriteria()) {
                if (prrtLvlCd.equals(g.getPrrtLvlCd())) {
                    return g.getMinScr();
                }
            }
        }
        return null;
    }

    /** HTTP 응답과 <b>같은 {@link ObjectMapper}</b> 로 쓴다 — 숫자·날짜 표기가 응답과 저장값에서 갈리지 않게. */
    private String write(Object value, String what) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, what + " 직렬화에 실패했습니다", e);
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
