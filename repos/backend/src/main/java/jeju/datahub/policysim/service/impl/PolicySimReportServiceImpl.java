package jeju.datahub.policysim.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.service.impl.AnlsExcn;
import jeju.datahub.analysis.service.impl.AnlsExcnRepository;
import jeju.datahub.analysis.service.impl.AnlsRslt;
import jeju.datahub.analysis.service.impl.AnlsRsltRepository;
import jeju.datahub.policysim.service.PolicySimAplcnParam;
import jeju.datahub.policysim.service.PolicySimReportCreateRequest;
import jeju.datahub.policysim.service.PolicySimReportScenarioRequest;
import jeju.datahub.policysim.service.PolicySimReportScenarioVO;
import jeju.datahub.policysim.service.PolicySimReportService;
import jeju.datahub.policysim.service.PolicySimReportVO;
import jeju.datahub.policysim.service.PolicySimRequest;
import jeju.datahub.policysim.service.PolicySimResultVO;
import jeju.datahub.policysim.service.PolicySimSavedResultVO;
import jeju.datahub.policysim.service.PolicySimService;
import jeju.datahub.report.service.ReportCreatorName;
import jeju.datahub.report.service.ReportListVO;
import jeju.datahub.report.service.impl.ReportMapper;
import jeju.datahub.report.service.impl.Rptp;
import jeju.datahub.report.service.impl.RptpRepository;
import lombok.RequiredArgsConstructor;

/**
 * 정책 시뮬레이션 시나리오 비교 보고서 서비스 구현체. 전자정부 {@link EgovAbstractServiceImpl} 상속. (TSK-4229 Step 3)
 *
 * <p>처리 순서 — <b>앞의 실패가 저장보다 먼저 온다</b>(트랜잭션 하나 — 뒤에서 예외가 나면 세 표가 함께 롤백):
 * ①{@code headIdx} 범위 → ②시나리오 값 확보(저장분 조회 · 미저장분 재계산) → ③추천 판정 →
 * ④실행 이력 1행 → ⑤분석 결과 1행 → ⑥보고서 1행.
 * ({@code ZoneRcmdtnReportServiceImpl} · {@code InvestPrrtReportServiceImpl} 와 같은 흐름)
 *
 * <h2>🔴 화면이 보낸 숫자를 받지 않는다 (D4)</h2>
 * 저장분은 {@code rsltId} 로 <b>저장값 그대로</b>(목록과 같은 {@code toSavedVO}) 읽고, 미저장분은 조건으로
 * <b>서버가 재계산</b>한다. 계산은 {@link PolicySimService} 인터페이스로만 부른다 — 구현 클래스를 주입하지 않는다.
 * 미저장분 {@code params} 는 같은 패키지의 {@link PolicySimServiceImpl#anlsParam} 을 static 으로 불러 만든다.
 *
 * <h2>🔴 보고서용 실행 행을 새로 만든다 (D2) · {@code TB_PLCY_SMLT_RSLT} 에 쓰지 않는다 (D3)</h2>
 * 스냅샷은 {@code TB_ANLS_RSLT.rslt_json} 한 칸이다. 결과 저장 행을 가리키면 시나리오 삭제가 FK 로 막히고,
 * 결과 표에 쓰면 내 저장 결과 목록에 보고서용 행이 섞인다.
 *
 * <h2>🔴 생성자는 인자다 — 상수 폴백이 없다 (D7)</h2>
 * {@code excutr_id}·{@code creatr_id} = 인자 {@code userId}, {@code creatr_nm} = {@link ReportCreatorName#cut}.
 * {@code userId} 가 null 이면 {@link IllegalArgumentException} — 컨트롤러가 401 을 냈어야 하는 상황이다.
 *
 * <h2>스냅샷 조회 (Step 4)</h2>
 * {@link #selectReportSnapshot} 은 {@code rslt_json} 을 응답 VO 로 되살린다. 🔴 소유 확인은 하지 않는다 —
 * 컨트롤러의 {@code ReportOwnershipGuard} 가 서비스 호출 직전에 한다(D8).
 */
@Service
@RequiredArgsConstructor
public class PolicySimReportServiceImpl extends EgovAbstractServiceImpl implements PolicySimReportService {

    private static final Logger log = LoggerFactory.getLogger(PolicySimReportServiceImpl.class);

    /** 분석실행상태코드 ({@code EXCN_STTS_CD}) — 저장이 곧 완료다 */
    private static final String EXCN_STTS_DONE = "DONE";

    /** 보고서상태코드 ({@code RPTP_STTS_CD}) — 파일을 서버에 굽지 않으므로 생성 즉시 완료다 */
    private static final String RPTP_STTS_DONE = "DONE";

    /** 생성구분 ({@code RPTP_CRT_TYPE_CD}) — 보고서 목록의 직접 생성 탭 */
    private static final String CRT_TYPE_MANUAL = "MANUAL";

    /** 🔴 인터페이스로 받는다 — 저장분 조회({@code selectSavedResultsByIds})·미저장분 재계산({@code simulate}) */
    private final PolicySimService policySimService;
    private final PolicySimMapper policySimMapper;
    private final AnlsExcnRepository anlsExcnRepository;
    private final AnlsRsltRepository anlsRsltRepository;
    private final RptpRepository rptpRepository;
    /** {@code emdCd} → 시군구명(TB_ADMDST) — 보고서 목록의 지역명({@code sgg_nm || ' ' || emd_nm})이 NULL 이 되지 않게 */
    private final ReportMapper reportMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public PolicySimReportVO createReport(PolicySimReportCreateRequest req, String userId, String userNm) {
        if (userId == null) {
            // 🔴 조용한 폴백을 만들지 않는다 — 컨트롤러가 401 을 냈어야 한다
            throw new IllegalArgumentException("보고서 생성자(userId)가 없습니다");
        }
        if (req == null || req.getScenarios() == null || req.getScenarios().isEmpty()) {
            throw badRequest("scenarios 는 1건 이상이어야 합니다");
        }
        List<PolicySimReportScenarioRequest> items = req.getScenarios();

        // ── ① headIdx 범위 — 0-base, size 미만 ──────────────────────────────────────
        Integer headIdx = req.getHeadIdx();
        if (headIdx == null || headIdx < 0 || headIdx >= items.size()) {
            throw badRequest("headIdx 가 범위를 벗어났습니다: " + headIdx + " (scenarios " + items.size() + "건)");
        }

        // ── ② 시나리오 값 확보 (D4) — 아직 아무것도 저장하지 않았다 ─────────────────────
        Map<Long, PolicySimSavedResultVO> savedById = loadSaved(items, userId);

        List<PolicySimResultVO> results = new ArrayList<>(items.size());
        List<Map<String, Object>> paramsList = new ArrayList<>(items.size());
        for (PolicySimReportScenarioRequest item : items) {
            if (item.getRsltId() != null) {
                PolicySimSavedResultVO saved = savedById.get(item.getRsltId());
                // 🔴 PolicySimResultVO 칸만 옮긴다 — 저장 VO 의 추가 8칸이 실리면 생성 응답과 스냅샷 조회가 갈린다.
                //    Spring BeanUtils(source, target) 다 — Apache commons 는 인자 순서가 반대다.
                PolicySimResultVO result = new PolicySimResultVO();
                BeanUtils.copyProperties(saved, result);
                results.add(result);
                paramsList.add(savedParams(saved));
            } else {
                PolicySimRequest cond = item.getCond();
                PolicySimResultVO result = policySimService.simulate(cond);   // REQUIRED — 이 트랜잭션에 참여
                String stts = result.getCalcSttsCd();
                if (PolicySimCalcStts.NO_ENF.name().equals(stts) || PolicySimCalcStts.NO_SPLY.name().equals(stts)) {
                    // 빈 칸투성이 보고서를 남기지 않는다
                    throw badRequest("계산할 수 없는 시나리오가 있어 보고서를 만들 수 없습니다 (calcSttsCd=" + stts + ")");
                }
                results.add(result);
                // 🔴 12키(vrblAjmt 포함)를 손으로 조립하지 않는다 — 유형별 null 규칙·서버 급지 채움이 anlsParam 에 있다
                paramsList.add(PolicySimServiceImpl.anlsParam(cond, PolicySimServiceImpl.validate(cond), result));
            }
        }

        // ── ③ 추천 판정 (D11) · seq/headSeq 는 1-base ────────────────────────────────
        int rcmdIdx = PolicySimReportRecommender.pick(results);
        int headSeq = headIdx + 1;
        List<PolicySimReportScenarioVO> scenarios = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            PolicySimReportScenarioVO s = new PolicySimReportScenarioVO();
            s.setSeq(i + 1);
            s.setRcmdYn(i == rcmdIdx);
            s.setRsltId(items.get(i).getRsltId());
            s.setParams(paramsList.get(i));
            s.setResult(results.get(i));
            scenarios.add(s);
        }

        // 대표 시나리오 — 실행 행의 지역·기간 칸과 anls_prd_expln 이 이 기준이다 (D5)
        Map<String, Object> headParams = paramsList.get(headIdx);
        PolicySimResultVO headResult = results.get(headIdx);
        String emdCd = (String) headParams.get("emdCd");
        // 🔴 emdCd 가 있으면 TB_ADMDST 값 — 요청 sggNm 을 믿지 않는다
        String sggNm = emdCd != null ? reportMapper.selectSggNmByEmdCd(emdCd) : (String) headParams.get("sggNm");
        String[] prd = splitPrd(headResult.getAplcnParam());
        Integer predcMm = (Integer) headParams.get("predcMm");

        // ── ④ ⑤ ⑥ 세 표 저장 ────────────────────────────────────────────────────
        LocalDateTime now = LocalDateTime.now();
        AnlsExcn excn = saveExcn(userId, sggNm, emdCd, prd[0], prd[1],
                write(anlsParamJson(req.getFileFmt(), headSeq, scenarios), "분석 파라미터"), now);

        Map<String, Object> rsltJson = new LinkedHashMap<>();
        rsltJson.put("headSeq", headSeq);
        rsltJson.put("scenarios", scenarios);
        saveRslt(excn.getExcnId(), write(rsltJson, "분석 결과"));

        // 🔴 이름의 날짜와 crt_ymd 를 같은 now 에서 뽑는다 · 순번 = 그날 내 이름의 최댓값 + 1 (개수 아님)
        String ymd = PolicySimReportNaming.ymd(now);
        int seq = policySimMapper.selectMaxRptpSeqOfDay(userId, ymd) + 1;
        Rptp rptp = saveRptp(excn.getExcnId(), PolicySimReportNaming.rptpNm(ymd, seq),
                PolicySimReportNaming.anlsPrdExpln(predcMm), req.getFileFmt(), userId, userNm, now);

        log.info("정책 시뮬레이션 보고서 생성: rptpId={} excnId={} 이름={} 시나리오={}건(저장분 {}) 대표={} 추천={} 형식={} 생성자={}",
                rptp.getRptpId(), excn.getExcnId(), rptp.getRptpNm(), items.size(), savedById.size(),
                headSeq, rcmdIdx >= 0 ? rcmdIdx + 1 : "-", req.getFileFmt(), userId);

        PolicySimReportVO vo = new PolicySimReportVO();
        vo.setRptpId(rptp.getRptpId());
        vo.setExcnId(excn.getExcnId());
        vo.setRptpNm(rptp.getRptpNm());
        vo.setAnlsPrdExpln(rptp.getAnlsPrdExpln());
        vo.setFileFmt(rptp.getFileFmt());
        vo.setCrtYmd(rptp.getCrtYmd());
        vo.setHeadSeq(headSeq);
        vo.setScenarios(scenarios);
        return vo;
    }

    /**
     * {@inheritDoc}
     *
     * <p>순서는 <b>원문 1행 → 404 판정 3종 → 헤더 → 본문 복원</b> 이다
     * ({@code InvestPrrtReportServiceImpl#selectReportSnapshot} 과 같은 흐름).
     * 🔴 소유 확인(남의 것 404)은 여기서 하지 않는다 — 컨트롤러의 {@code ReportOwnershipGuard} 가 이 호출 전에 끝낸다(D8).
     */
    @Override
    @Transactional(readOnly = true)
    public PolicySimReportVO selectReportSnapshot(Long rptpId) {
        PolicySimReportSnapshotRow snapshot = policySimMapper.selectReportSnapshot(rptpId);
        if (snapshot == null) {
            // 404-①: 보고서 자체가 없다.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서를 찾을 수 없습니다: " + rptpId);
        }
        if (!PolicySimServiceImpl.ANLS_TYPE_POLICY_SIM.equals(snapshot.getAnlsTypeCd())) {
            // 404-②: 다른 유형(또는 실행 미연결)이다. 400 이 아니라 404 인 이유: 요청은 정상이고
            //        "그 경로에 그 자원이 없다".
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "정책 시뮬레이션 보고서가 아닙니다: " + rptpId + " (analysisType=" + snapshot.getAnlsTypeCd() + ")");
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

        // 🔴 저장된 JSON({headSeq, scenarios})을 응답 VO 로 '바로' 읽는다 — 키가 응답 필드명과 같아 변환이 없고,
        //    트리(JsonNode)를 거치지 않아 소수가 double 로 뭉개지지 않는다. 다시 계산하지 않는다(POST 응답과 같은 값).
        //    메타 6칸은 JSON 에 없어 아래에서 채운다.
        PolicySimReportVO vo;
        try {
            vo = objectMapper.readValue(rsltJson, PolicySimReportVO.class);
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
        vo.setAnlsPrdExpln(header.getAnlsPrdExpln());
        vo.setFileFmt(header.getFileFmt());
        vo.setCrtYmd(header.getCrtYmd());
        return vo;
    }

    /**
     * 저장분을 <b>한 번의 쿼리</b>로 읽는다. 🔴 같은 {@code rsltId} 중복은 쿼리 전에 400 — {@code IN} 이 한 행으로 돌려줘
     * 내 것인데도 404 로 오판한다. 요청 수와 조회 수가 다르면 404(없음·남의 것 — 존재 여부를 드러내지 않는다).
     */
    private Map<Long, PolicySimSavedResultVO> loadSaved(List<PolicySimReportScenarioRequest> items, String userId) {
        List<Long> rsltIds = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (PolicySimReportScenarioRequest item : items) {
            if (item == null || (item.getRsltId() == null) == (item.getCond() == null)) {
                throw badRequest("시나리오는 rsltId 또는 cond 중 정확히 하나만 가져야 합니다");
            }
            if (item.getRsltId() != null) {
                if (!seen.add(item.getRsltId())) {
                    throw badRequest("같은 rsltId 가 두 번 왔습니다: " + item.getRsltId());
                }
                rsltIds.add(item.getRsltId());
            }
        }
        Map<Long, PolicySimSavedResultVO> byId = new HashMap<>();
        if (rsltIds.isEmpty()) {
            return byId;
        }
        // IN 은 순서를 지키지 않는다 — rsltId 로 찾아 요청 순서에 맞춘다(호출부)
        for (PolicySimSavedResultVO saved : policySimService.selectSavedResultsByIds(userId, rsltIds)) {
            byId.put(saved.getRsltId(), saved);
        }
        if (byId.size() != rsltIds.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "정책 시뮬레이션 결과를 찾을 수 없습니다");
        }
        return byId;
    }

    /**
     * 저장분 {@code params} 12키 — 저장 VO 의 <b>같은 이름 칸</b>에서 옮긴다(키 이름은 {@code anlsParam} 과 같다).
     * {@code vrblAjmt}(TSK-4332 D10)도 저장 VO 에서 옮기고 재계산하지 않는다 — 구 저장분은 null.
     */
    private static Map<String, Object> savedParams(PolicySimSavedResultVO s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plcyType", s.getPlcyType());
        m.put("expandMethod", s.getExpandMethod());
        m.put("addPkltSlot", s.getAddPkltSlot());
        m.put("roadMtncType", s.getRoadMtncType());
        m.put("roadMtncLen", s.getRoadMtncLen());
        m.put("feeAplcnMthd", s.getFeeAplcnMthd());
        m.put("feeAplcnZone", s.getFeeAplcnZone());
        m.put("feeIncrsRt", s.getFeeIncrsRt());
        m.put("predcMm", s.getPredcMm());
        m.put("sggNm", s.getSggNm());
        m.put("emdCd", s.getEmdCd());
        m.put("vrblAjmt", s.getVrblAjmt());
        return m;
    }

    /** 대표 결과의 민원 기준 구간 {@code YYYYMMDD~YYYYMMDD} → [시작, 끝]. 없으면 둘 다 null(저장 경로의 "민원 0건" 과 같다) */
    private static String[] splitPrd(PolicySimAplcnParam ap) {
        String prd = ap == null ? null : ap.cvlcptPrd();
        if (prd == null) {
            return new String[] {null, null};
        }
        String[] parts = prd.split("~", -1);
        if (parts.length != 2) {
            return new String[] {null, null};
        }
        return new String[] {parts[0].trim(), parts[1].trim()};
    }

    /** {@code anls_param_json} — 재현 메타(스냅샷 조회는 이 칸을 읽지 않는다) */
    private static Map<String, Object> anlsParamJson(String fileFmt, int headSeq, List<PolicySimReportScenarioVO> scenarios) {
        List<Map<String, Object>> list = new ArrayList<>(scenarios.size());
        for (PolicySimReportScenarioVO s : scenarios) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", s.getSeq());
            m.put("rsltId", s.getRsltId());
            m.put("params", s.getParams());
            list.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("rptpYn", true);
        root.put("fileFmt", fileFmt);
        root.put("headSeq", headSeq);
        root.put("scenarios", list);
        return root;
    }

    /** ④ 실행 이력 1행. 🔴 {@code excn_id} 를 결과·보고서 행이 참조하므로 {@code flush()} 로 채번을 확정한다 */
    private AnlsExcn saveExcn(String userId, String sggNm, String emdCd,
                              String bgngYmd, String endYmd, String paramJson, LocalDateTime now) {
        AnlsExcn excn = new AnlsExcn();
        excn.setAnlsTypeCd(PolicySimServiceImpl.ANLS_TYPE_POLICY_SIM);
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

    /** ⑤ 분석 결과 1행 (실행 1건 = 결과 1행) */
    private void saveRslt(Long excnId, String rsltJson) {
        AnlsRslt rslt = new AnlsRslt();
        rslt.setExcnId(excnId);
        rslt.setRsltJson(rsltJson);
        anlsRsltRepository.save(rslt);
    }

    /**
     * ⑥ 보고서 1행 — {@code MANUAL} · {@code file_path} NULL. {@code stngId}·{@code trgtPrdCd}·{@code crltnDeptCd}·
     * {@code crltnCyclCd} 는 자동 생성 전용 칸이라 null 이다.
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
        rptp.setCreatrNm(ReportCreatorName.cut(userNm));   // 🔴 50자 절단 — 공용 유틸(TSK-4202)
        rptp.setCrtYmd(now);
        rptpRepository.save(rptp);
        rptpRepository.flush();
        return rptp;
    }

    /** HTTP 응답과 <b>같은 {@link ObjectMapper}</b> 로 쓴다 — 숫자 표기가 응답과 저장값에서 갈리지 않게 */
    private String write(Object value, String what) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 🔴 삼키지 않는다 — 빈 JSON 을 적재하면 열 수 없는 보고서가 조용히 굳는다
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, what + " 직렬화에 실패했습니다", e);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
