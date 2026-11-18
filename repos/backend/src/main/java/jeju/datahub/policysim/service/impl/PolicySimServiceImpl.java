package jeju.datahub.policysim.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

import jeju.datahub.analysis.service.AnlsVrblOvrd;
import jeju.datahub.analysis.service.AnlsVrblScope;
import jeju.datahub.analysis.service.impl.AnlsExcn;
import jeju.datahub.analysis.service.impl.AnlsExcnRepository;
import jeju.datahub.analysis.service.impl.AnlsVrblOvrdResolver;
import jeju.datahub.common.PageResponse;
import jeju.datahub.policysim.service.PolicySimAplcnParam;
import jeju.datahub.policysim.service.PolicySimRequest;
import jeju.datahub.policysim.service.PolicySimResultVO;
import jeju.datahub.policysim.service.PolicySimSavedResultVO;
import jeju.datahub.policysim.service.PolicySimService;
import lombok.RequiredArgsConstructor;

/**
 * 정책 시뮬레이션 서비스 구현 (TSK-4037 Step 5 주차장 확충 · TSK-4038 Step 4 이면도로 정비 · TSK-4039 Step 5 요금제 적용).
 *
 * <p>계산 순서: 입력 검증 → 기준선({@link PolicySimBaselineCalculator}) → 정책 유형별 변화식
 * (EXPAND {@link ExpandPolicyCalculator} · REPAIR {@link RepairPolicyCalculator} · FEE {@link FeePolicyCalculator})
 * → 출력 지표({@link PolicySimOutputCalculator}).
 * 산식은 각 계산기가 지고 여기서는 다시 구현하지 않는다.
 *
 * <h2>이면도로(REPAIR) — 인접 기준선은 전체 기준선을 거른다</h2>
 * 기준선은 {@link PolicySimBaselineCalculator#calculate()} 1회로 74곳 전부를 받는다(4037 확충과 같은 호출 — 범위는 그 뒤에
 * {@link ExpandPolicyCalculator#scope} 로 고른다). 인접 코드({@code selectAdjacentEmd})의 기준선은 <b>같은 결과에서 거른다</b> —
 * 같은 구간·같은 계수 인스턴스·자기 시 {@code g_sgg} 가 보장되고 집계 쿼리가 1회로 끝난다
 * ({@code calculate(emdCds)} 로 따로 받아도 값은 같지만 민원 구간 조회·계수 해석이 한 번 더 돈다).
 * 🔴 필수 계수는 REPAIR 만 17종({@link PolicySimParams#REPAIR_REQUIRED_VRBL_CDS})을 각각 확인한다 — EXPAND 는 13종 그대로.
 *
 * <h2>요금제(FEE) — 대상 = 조회 범위 ∩ 급지(emdCd 없을 때 · TSK-4291 B)</h2>
 * 대상 = {@link ExpandPolicyCalculator#scope}(4037 범위)를 급지(Z1 동 · Z2 읍·면)로 한 번 더 거른 것 — 🔴 TSK-4039 의
 * "급지 = V 출처만" 규칙은 폐기됐다(TSK-4291 Q1 = B). R 분모·인접 산출·민감도가 같은 대상을 쓴다 · 면수 = {@code selectFeeTargetSlot}(급지·유무료 — emdCd 지정 시 급지 무시) ·
 * 인접 = 이면도로와 같은 {@code selectAdjacentEmd} + 전체 기준선에서 거름 · 필수 계수 17종({@link PolicySimParams#FEE_REQUIRED_VRBL_CDS}).
 * 민감도({@link FeeSensitivity})는 같은 입력으로 계수 하나만 바꿔 다시 계산한다(범위 = 설정 행 min_vl·max_vl).
 *
 * <h2>🔴 저장은 서버 재계산이다</h2>
 * 집중구역 보고서 생성({@code HotspotReportServiceImpl})과 같은 패턴 — 요청에는 조건만 있고 숫자가 없다.
 * 저장 대상은 {@code TB_ANLS_EXCN} 1행 + {@code TB_PLCY_SMLT_RSLT} 1행이며 한 트랜잭션이다
 * ({@code TB_RPTP} 는 만들지 않는다). 계산이 실패하면(400·503) 아무 행도 남지 않는다 — 계산이 INSERT 보다 앞이다.
 *
 * <h2>잘못된 입력은 400</h2>
 * 이 레포에는 전역 예외 핸들러가 없다 — {@link ExpandPolicyCalculator#scope} 의 {@link IllegalArgumentException}
 * (없는 읍면동코드)을 그대로 두면 500 이 된다. 그래서 여기서 {@link ResponseStatusException} 400 으로 옮긴다.
 */
@Service
@RequiredArgsConstructor
public class PolicySimServiceImpl extends EgovAbstractServiceImpl implements PolicySimService {

    private static final Logger log = LoggerFactory.getLogger(PolicySimServiceImpl.class);

    /** 분석유형코드 (ANLS_TYPE_CD) */
    static final String ANLS_TYPE_POLICY_SIM = "POLICY_SIM";

    /** 정책유형코드 (PLCY_TYPE_CD) — EXPAND(TSK-4037) · REPAIR(TSK-4038) · FEE(TSK-4039) */
    static final String PLCY_TYPE_EXPAND = "EXPAND";
    static final String PLCY_TYPE_REPAIR = "REPAIR";
    static final String PLCY_TYPE_FEE = "FEE";

    /** 요금제 적용 급지 — ALL(전체) · Z1(동) · Z2(읍·면). 생략 시 ALL (TSK-4039 Step 5-1 착수 시 확정) */
    static final String FEE_ZONE_ALL = "ALL";
    static final String FEE_ZONE_Z1 = "Z1";
    static final String FEE_ZONE_Z2 = "Z2";
    static final Set<String> FEE_ZONES = Set.of(FEE_ZONE_ALL, FEE_ZONE_Z1, FEE_ZONE_Z2);

    /**
     * 허용 요금 인상률(%) — 10·20·30. 🔴 목록 밖은 400 — 계산기는 d = −ε × p ÷ 100 &gt; 1 이면
     * {@link IllegalArgumentException}(500)이고, 민감도가 ε 하한까지 흔들므로 p 상한을 여기서 묶는다(Step 3 · 4).
     */
    static final Set<Integer> FEE_INCRS_RTS = Set.of(10, 20, 30);

    /** 정비 구간 연장(m) 범위 — 요청 DTO {@code @Min/@Max} 와 같다(서비스 직접 호출 방어) */
    static final int ROAD_MTNC_LEN_MIN = 1;
    static final int ROAD_MTNC_LEN_MAX = 10_000;

    /** 실행상태코드 — 동기 실행이라 저장이 곧 완료다 */
    private static final String EXCN_STTS_DONE = "DONE";

    /** 허용 면수 N */
    static final Set<Integer> ADD_PKLT_SLOTS = Set.of(50, 80, 100, 150, 200);

    /** 허용 예측기간(개월) */
    static final Set<Integer> PREDC_MMS = Set.of(3, 6, 12, 24);

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private final PolicySimBaselineCalculator baselineCalculator;
    private final ExpandPolicyCalculator expandPolicyCalculator;
    private final RepairPolicyCalculator repairPolicyCalculator;
    private final PolicySimOutputCalculator outputCalculator;
    private final AnlsExcnRepository anlsExcnRepository;
    private final PlcySmltRsltRepository plcySmltRsltRepository;
    private final PolicySimMapper policySimMapper;
    private final ObjectMapper objectMapper;
    /** 분석 계수 일시 조정 (TSK-4332) — 🔴 마지막 필드(생성자 직접 호출 테스트 3곳이 마지막 인자로 넘긴다) */
    private final AnlsVrblOvrdResolver anlsVrblOvrdResolver;

    @Override
    @Transactional(readOnly = true)
    public PolicySimResultVO simulate(PolicySimRequest request) {
        return compute(request).vo();
    }

    @Override
    @Transactional
    public PolicySimResultVO save(PolicySimRequest request, String excutrId) {
        // ① 같은 조건으로 서버가 다시 계산한다 — 실패(400·503)면 아무것도 저장하지 않는다
        Computed c = compute(request);
        PolicySimResultVO vo = c.vo();
        PolicySimBaseline baseline = c.baseline();

        // ①-1 차단 상태(NO_ENF·NO_SPLY)는 저장하지 않는다 — 정책 후 값이 전부 null 인 행만 쌓인다 (TSK-4226 D1)
        if (PolicySimService.isBlocked(vo.getCalcSttsCd())) {
            log.info("정책 시뮬레이션 저장 생략(차단 상태): 유형={} 범위={} calcStts={}",
                    c.input().plcyType(), vo.getAnlsRngCn(), vo.getCalcSttsCd());
            return vo;
        }

        // ② 실행 이력 1행
        AnlsExcn excn = new AnlsExcn();
        excn.setAnlsTypeCd(ANLS_TYPE_POLICY_SIM);
        excn.setExcutrId(excutrId);        // 로그인 사용자 user_id — 해석·401 은 컨트롤러 책임
        excn.setSggNm(c.sggNm());           // emdCd 가 있으면 TB_ADMDST 값
        excn.setEmdCd(request.getEmdCd());
        // 기간 칸 = 민원 기준 구간 (단속 구간은 aplcn_param_json 이 진다). 민원 0건이면 null
        excn.setAnlsBgngYmd(baseline.cvlcptBgngYmd() != null ? baseline.cvlcptBgngYmd().format(YMD) : null);
        excn.setAnlsEndYmd(baseline.cvlcptEndYmd() != null ? baseline.cvlcptEndYmd().format(YMD) : null);
        excn.setAnlsParamJson(toJson(anlsParam(request, c.input(), vo)));
        excn.setExcnSttsCd(EXCN_STTS_DONE);
        excn.setExcnDt(LocalDateTime.now());
        anlsExcnRepository.save(excn);
        anlsExcnRepository.flush();         // identity 채번 확정 — 결과행이 참조한다

        // ③ 결과 1행
        PlcySmltRslt rslt = toEntity(excn.getExcnId(), request, vo, c.input());
        rslt.setAplcnParamJson(toJson(vo.getAplcnParam()));
        plcySmltRsltRepository.save(rslt);
        plcySmltRsltRepository.flush();

        log.info("정책 시뮬레이션 저장: excnId={} rsltId={} 유형={} 범위={} 방식={} N={} L={} T={} calcStts={} excs={}",
                excn.getExcnId(), rslt.getRsltId(), c.input().plcyType(), vo.getAnlsRngCn(),
                c.input().isRepair() ? rslt.getRoadMtncType()
                        : c.input().isFee() ? rslt.getFeeAplcnMthd() + "/" + rslt.getFeeAplcnZone() + "/" + rslt.getFeeIncrsRt()
                        : request.getExpandMethod(),
                rslt.getAddPkltSlot(), rslt.getRoadMtncLen(), request.getPredcMm(), vo.getCalcSttsCd(), vo.isExcsYn());
        return vo;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PolicySimSavedResultVO> selectSavedResults(String userId, int page, int size) {
        int totalElements = policySimMapper.selectSavedResultCount(userId);
        if (totalElements == 0) {
            return PageResponse.of(Collections.<PolicySimSavedResultVO>emptyList(), page, size, totalElements);
        }
        // offset 은 long — page * size 가 int 를 넘으면 음수 OFFSET 이 된다 (ReportServiceImpl 과 같은 이유)
        long offset = (long) page * size;
        List<PolicySimSavedResultVO> content = policySimMapper.selectSavedResultPage(userId, size, offset).stream()
                .map(this::toSavedVO)
                .toList();
        return PageResponse.of(content, page, size, totalElements);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PolicySimSavedResultVO> selectSavedResultsByIds(String userId, List<Long> rsltIds) {
        if (userId == null || rsltIds == null || rsltIds.isEmpty()) {
            return Collections.emptyList();
        }
        // 🔴 목록과 같은 toSavedVO — cstAmt 재구성 규칙을 복제하지 않는다(목록과 보고서의 비용이 갈린다)
        return policySimMapper.selectSavedResultsByIds(userId, rsltIds).stream()
                .map(this::toSavedVO)
                .toList();
    }

    @Override
    @Transactional
    public void deleteSavedResult(String userId, Long rsltId) {
        // 🔴 없음·남의 것·다른 유형을 모두 같은 404 로 — 남의 rsltId 가 존재하는지 드러내지 않는다
        PlcySmltRslt rslt = rsltId == null ? null : plcySmltRsltRepository.findById(rsltId).orElse(null);
        AnlsExcn excn = rslt == null ? null : anlsExcnRepository.findById(rslt.getExcnId()).orElse(null);
        if (excn == null
                || !ANLS_TYPE_POLICY_SIM.equals(excn.getAnlsTypeCd())
                || userId == null || !userId.equals(excn.getExcutrId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "정책 시뮬레이션 결과를 찾을 수 없습니다: " + rsltId);
        }
        // FK 방향 — 결과 → 실행 순. flush 로 DELETE 순서를 못박는다
        plcySmltRsltRepository.delete(rslt);
        plcySmltRsltRepository.flush();
        anlsExcnRepository.delete(excn);
        anlsExcnRepository.flush();
        log.info("정책 시뮬레이션 저장분 삭제: rsltId={} excnId={}", rsltId, excn.getExcnId());
    }

    /**
     * 매퍼 행 → 응답 VO. 저장 당시 값을 그대로 옮긴다 — 모델을 재계산하지 않는다.
     * {@code cstAmt} 만 DB 칸이 없어 {@code addPkltSlot × aplcnParam 의 방식별 단가}로 만든다
     * (계산 응답과 같은 {@link PolicySimOutputCalculator#plain} 표기).
     */
    PolicySimSavedResultVO toSavedVO(PolicySimSavedRow row) {
        PolicySimSavedResultVO vo = new PolicySimSavedResultVO();
        vo.setRsltId(row.getRsltId());
        vo.setExcnDt(row.getExcnDt());
        vo.setPlcyType(row.getPlcyType());
        vo.setExpandMethod(row.getExpandMethod());
        vo.setAddPkltSlot(row.getAddPkltSlot());
        vo.setPredcMm(row.getPredcMm());
        vo.setSggNm(row.getSggNm());
        vo.setEmdCd(row.getEmdCd());

        vo.setAnlsRngCn(row.getAnlsRngCn());
        vo.setCrtrCvlcptCnt(row.getCrtrCvlcptCnt());
        vo.setCrtrIlglCnt(row.getCrtrIlglCnt());
        vo.setCrtrCngstIdx(row.getCrtrCngstIdx());
        vo.setExsPkltCpcty(row.getExsPkltCpcty());
        vo.setVldAddPkltSlot(row.getVldAddPkltSlot());
        vo.setCvlcptRdctRt(row.getCvlcptRdctRt());
        vo.setCngstImpvRt(row.getCngstImpvRt());
        vo.setAftrCngstIdx(row.getAftrCngstIdx());
        vo.setAftrCvlcptCnt(row.getAftrCvlcptCnt());
        vo.setCalcSttsCd(row.getCalcSttsCd());
        vo.setExcsYn(row.isExcsYn());
        vo.setCstLvlCd(row.getCstLvlCd());
        // 이면도로 (TSK-4038) — 확충 행은 전부 null. adjcEmdCnt 는 저장하지 않으므로 null
        vo.setRoadMtncType(row.getRoadMtncType());
        vo.setRoadMtncLen(row.getRoadMtncLen());
        vo.setAdjcCvlcptCnt(row.getAdjcCvlcptCnt());
        // 요금제 (TSK-4039) — 확충·이면도로 행은 전부 null. 정밀도는 toVO 와 같다(DB NUMERIC(10,2) = scale 2).
        // 민감도(cvlcptRdctRtRng)·wrnngCn 은 응답 전용(저장 안 함)이라 저장 목록에서는 null 이다
        vo.setFeeAplcnMthd(row.getFeeAplcnMthd());
        vo.setFeeAplcnZone(row.getFeeAplcnZone());
        vo.setFeeIncrsRt(row.getFeeIncrsRt() != null ? row.getFeeIncrsRt().intValueExact() : null);
        vo.setAplcnTrgtSlot(row.getAplcnTrgtSlot() != null ? row.getAplcnTrgtSlot().longValue() : null);
        vo.setLvngVhclCnt(row.getLvngVhclCnt());
        vo.setAbsrpCnt(row.getAbsrpCnt());
        vo.setTrnsfCnt(row.getTrnsfCnt());

        PolicySimAplcnParam ap = fromJson(row.getAplcnParamJson(), row.getRsltId());
        vo.setAplcnParam(ap);
        vo.setCstAmt(savedCstAmt(row.getExpandMethod(), row.getAddPkltSlot(), ap));
        // TSK-4332 D6 — 저장 당시 적용 내역. 구 저장분(키 없음)·깨진 값은 null (초기값을 두지 않는다)
        vo.setVrblAjmt(ajmtFromJson(row.getVrblAjmtJson(), row.getRsltId()));
        return vo;
    }

    /**
     * {@code addPkltSlot × 단가} — 방식·면수·단가 중 하나라도 없으면 null (만들 수 없는 값을 지어내지 않는다).
     * 이면도로 행은 방식·면수가 없어 null 이다(계산 응답도 null — 단가 없음).
     */
    static BigDecimal savedCstAmt(String expandMethod, Integer addPkltSlot, PolicySimAplcnParam ap) {
        if (expandMethod == null || addPkltSlot == null || ap == null) {
            return null;
        }
        BigDecimal unit;
        try {
            unit = switch (ExpandMethod.valueOf(expandMethod)) {
                case VACLOT -> ap.cVacant();     // CST_VACLOT
                case MULTFLR -> ap.cMultflr();   // CST_MULTFLR
                case NEW -> ap.cPublic();        // CST_PBLPKLT
            };
        } catch (IllegalArgumentException e) {
            // 모르는 확충 방식이면 비용을 내지 않는다(null) — 방식 검증은 입력 검증 단계가 진다 (4.2 — TSK-4336)
            log.debug("[policy-sim] 확충 방식 해석 실패 — 비용 null: expandMethod={}", expandMethod);
            return null;
        }
        return unit == null ? null : PolicySimOutputCalculator.plain(unit.multiply(BigDecimal.valueOf(addPkltSlot)));
    }

    private PolicySimAplcnParam fromJson(String json, Long rsltId) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PolicySimAplcnParam.class);
        } catch (JsonProcessingException e) {
            // 한 행이 깨졌다고 목록 전체를 500 으로 죽이지 않는다 — 계수만 비우고 WARN
            log.warn("정책 시뮬레이션 aplcn_param_json 역직렬화 실패: rsltId={}", rsltId);
            return null;
        }
    }

    /** {@code anls_param_json.vrblAjmt} 원문 → Map. JSON {@code null}·NULL·실패는 null (실패는 fromJson 처럼 WARN) */
    private Map<String, Object> ajmtFromJson(String json, Long rsltId) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            log.warn("정책 시뮬레이션 vrblAjmt 역직렬화 실패: rsltId={}", rsltId);
            return null;
        }
    }

    /** 검증 → 기준선 → 유형별 변화식 → 출력. 저장 경로가 기준 구간을 쓰므로 기준선도 함께 돌려준다 */
    private Computed compute(PolicySimRequest req) {
        ValidInput in = validate(req);
        // 🔴 validate 뒤 — plcyType 형식 400 이 먼저다. 스코프는 정책 유형별(TSK-4332 D1), 세 갈래가 같은 ovrd 를 쓴다
        AnlsVrblScope scope = in.isFee() ? AnlsVrblScope.POLICY_FEE
                : in.isRepair() ? AnlsVrblScope.POLICY_REPAIR : AnlsVrblScope.POLICY_EXPAND;
        AnlsVrblOvrd ovrd = anlsVrblOvrdResolver.resolve(scope, req.getVrblVls());
        Computed c;
        if (in.isFee()) {
            c = computeFee(req, in, ovrd);
        } else {
            c = in.isRepair() ? computeRepair(req, in, ovrd) : computeExpand(req, in, ovrd);
        }
        c.vo().setVrblAjmt(ajmtMap(ovrd));
        return c;
    }

    /**
     * 적용 내역을 JSON 문자열로 한 번 왕복시켜 담는다 (TSK-4332 Step 2).
     * 🔴 {@code toAjmtJson()} 의 {@code BigDecimal} 을 그대로 두면 보고서 스냅샷(타입 없는 Map 역직렬화 → Double·Integer)과
     * 노드 타입이 갈려 생성 응답 ↔ 스냅샷 비교가 깨진다. {@code convertValue} 로는 정수가 IntNode 가 되지 않아 안 고쳐진다.
     */
    private Map<String, Object> ajmtMap(AnlsVrblOvrd ovrd) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(ovrd.toAjmtJson()),
                    new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "정책 시뮬레이션 계수 조정 내역 직렬화에 실패했습니다", e);
        }
    }

    /**
     * 계산기 입력 거절(IllegalArgumentException) → 400. 🔴 응답 사유는 고정 문구다 — 예외 메시지에는 요청값·내부 코드가
     * 섞여 있어(시큐어코딩 4.1 — TSK-4336) 상세는 서버 로그에만 남긴다.
     */
    private static ResponseStatusException badScope(IllegalArgumentException e) {
        log.info("[policy-sim] 입력 거절(400) — {}", e.getClass().getSimpleName());
        log.debug("[policy-sim] 입력 거절(400) 상세", e);   // 4.1 — 예외 원문은 DEBUG 로만 (TSK-4336)
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "정책 시뮬레이션 조건이 올바르지 않습니다.", e);
    }

    private Computed computeExpand(PolicySimRequest req, ValidInput in, AnlsVrblOvrd ovrd) {
        PolicySimBaseline baseline = baselineCalculator.calculate(null, PolicySimParams.REQUIRED_VRBL_CDS, ovrd);

        ExpandResult expand;
        try {
            expand = expandPolicyCalculator.calculate(baseline, in.expandMethod(),
                    req.getAddPkltSlot(), req.getPredcMm(), req.getSggNm(), req.getEmdCd());
        } catch (IllegalArgumentException e) {
            // 없는 읍면동코드 등 — 500 으로 새지 않게 400 으로 옮긴다
            throw badScope(e);
        }

        // emdCd 가 있으면 시군구명은 기준선(TB_ADMDST) 값이 정답이다 — 요청 sggNm 을 덮어쓴다
        String sggNm = req.getEmdCd() != null
                ? expand.emds().get(0).base().sggNm()
                : req.getSggNm();
        PolicySimResultVO vo = outputCalculator.calculate(baseline, expand, sggNm, req.getEmdCd());
        return new Computed(baseline, vo, sggNm, in);
    }

    /**
     * 이면도로 정비 (TSK-4038 Step 4-2) — 전체 기준선(17계수 확인) → 대상 범위 → 인접 코드 → 인접 기준선(전체에서 거름)
     * → {@link RepairPolicyCalculator} → 출력 지표.
     */
    private Computed computeRepair(PolicySimRequest req, ValidInput in, AnlsVrblOvrd ovrd) {
        PolicySimBaseline baseline = baselineCalculator.calculate(null, PolicySimParams.REPAIR_REQUIRED_VRBL_CDS, ovrd);
        RepairCoef coef = baseline.params().repairCoef();

        List<PolicySimEmdBaseline> target;
        try {
            target = ExpandPolicyCalculator.scope(baseline.emds(), req.getSggNm(), req.getEmdCd());
        } catch (IllegalArgumentException e) {
            throw badScope(e);
        }

        // 인접 = 대상 바깥의 이웃(ST_Intersects). 기준선은 같은 결과에서 거른다 — 같은 구간·계수·자기 시 g_sgg
        Set<String> adjCds = new HashSet<>(policySimMapper.selectAdjacentEmd(
                target.stream().map(PolicySimEmdBaseline::emdCd).toList()));
        List<PolicySimEmdBaseline> adjacent = baseline.emds().stream()
                .filter(e -> adjCds.contains(e.emdCd()))
                .toList();

        RepairResult repair = repairPolicyCalculator.calculate(target, adjacent, coef,
                in.roadMtncType(), req.getRoadMtncLen());

        String sggNm = req.getEmdCd() != null ? target.get(0).sggNm() : req.getSggNm();
        PolicySimResultVO vo = outputCalculator.calculate(baseline, repair, sggNm, req.getEmdCd());
        return new Computed(baseline, vo, sggNm, in);
    }

    /**
     * 요금제 적용 (TSK-4039 Step 5-2) — 전체 기준선(17계수 확인) → 대상 범위(4037 scope) → 적용 대상 면수(Step 2) →
     * 인접 코드 → 인접 기준선(전체에서 거름 — 이면도로와 같다) → {@link FeePolicyCalculator} → 민감도 → 출력 지표.
     *
     * <p>🔴 대상 = 조회 범위 ∩ 급지다(emdCd 없고 급지 Z1·Z2 일 때 — TSK-4291 Q1 = B · 종전 "급지는 V 의 출처만" 폐기).
     * 이 대상이 R 분모·인접 산출·민감도·출력 Σ 의 기준이다 — 같은 시의 다른 급지 읍면동도 인접이 될 수 있다.
     * 거른 결과가 비면 400({@code scope} 와 같은 규칙). 면수 행이 없는 대상은 면수 0.
     * 범위 전체가 NO_ENF 면 R·민감도가 null 이고 화면 팝업이 막는다(4037 규칙 — 계산은 예외 없이 끝난다).
     */
    private Computed computeFee(PolicySimRequest req, ValidInput in, AnlsVrblOvrd ovrd) {
        PolicySimBaseline baseline = baselineCalculator.calculate(null, PolicySimParams.FEE_REQUIRED_VRBL_CDS, ovrd);
        PolicySimParams params = baseline.params();
        FeeCoef coef = FeeCoef.from(params);

        List<PolicySimEmdBaseline> target;
        try {
            target = ExpandPolicyCalculator.scope(baseline.emds(), req.getSggNm(), req.getEmdCd());
        } catch (IllegalArgumentException e) {
            throw badScope(e);
        }
        // 🔴 급지가 대상 자체를 좁힌다(TSK-4291 B) — emdCd 지정 시에는 급지 무시(명세 6.3). scope 시그니처는 확충·이면도로 공용이라 여기서 거른다
        if (req.getEmdCd() == null && (FEE_ZONE_Z1.equals(in.feeAplcnZone()) || FEE_ZONE_Z2.equals(in.feeAplcnZone()))) {
            String z = in.feeAplcnZone();
            target = target.stream().filter(e -> zoneOf(e.emdNm()).equals(z)).toList();
            if (target.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "조회 범위에 급지 읍면동이 없습니다: sggNm=" + req.getSggNm() + ", feeAplcnZone=" + z);
            }
        }

        // emdCd 가 있으면 요청 급지를 무시하고 그 읍면동의 급지로 채운다(명세 6.3) — 매퍼도 emdCd 우선이라 zone 을 넘기지 않는다
        String zone = req.getEmdCd() != null ? zoneOf(target.get(0).emdNm()) : in.feeAplcnZone();
        String sggNm = req.getEmdCd() != null ? target.get(0).sggNm() : req.getSggNm();
        List<FeeTargetRow> feeTargets = policySimMapper.selectFeeTargetSlot(in.feeAplcnMthd().crgYn(),
                req.getEmdCd() != null ? null : zone, req.getEmdCd() != null ? null : req.getSggNm(), req.getEmdCd());

        Set<String> adjCds = new HashSet<>(policySimMapper.selectAdjacentEmd(
                target.stream().map(PolicySimEmdBaseline::emdCd).toList()));
        List<PolicySimEmdBaseline> adjacent = baseline.emds().stream()
                .filter(e -> adjCds.contains(e.emdCd()))
                .toList();

        BigDecimal p = in.feeIncrsRt() != null ? BigDecimal.valueOf(in.feeIncrsRt()) : null;
        FeeResult fee = FeePolicyCalculator.compute(target, feeTargets, adjacent, coef, in.feeAplcnMthd(), p);

        // 민감도 범위 — 방식별 수요 계수 + τ. 값이 없는 계수는 맵에서 빠지고 FeeSensitivity 가 그 키만 null 로 둔다
        Map<String, VrblRange> ranges = new HashMap<>();
        for (String cd : List.of(FeeCoef.PRC_ELST_VL, FeeCoef.PAID_DMND_RT, FeeCoef.TRNSF_RT)) {
            VrblRange r = params.range(cd);
            if (r != null) {
                ranges.put(cd, r);
            }
        }
        Map<String, FeeSensitivity.Sensitivity> sens = FeeSensitivity.compute(target, feeTargets, adjacent, fee, ranges);

        PolicySimResultVO vo = outputCalculator.calculate(baseline, fee, sens, sggNm, req.getEmdCd(), zone);
        return new Computed(baseline, vo, sggNm, in);
    }

    /** 읍면동의 급지 — 이름이 '읍'·'면' 으로 끝나면 Z2, 그 밖(동) Z1. Step 2 매퍼의 {@code emd_nm ~ '(읍|면)$'} 과 같은 판정 */
    static String zoneOf(String emdNm) {
        return emdNm != null && (emdNm.endsWith("읍") || emdNm.endsWith("면")) ? FEE_ZONE_Z2 : FEE_ZONE_Z1;
    }

    /** 허용 값 검증 — 형식은 {@code @Valid} 가 이미 봤다. 서비스 직접 호출에도 같은 규칙을 지키려 필수값도 다시 본다 */
    static ValidInput validate(PolicySimRequest req) {
        if (req == null) {
            throw badRequest("요청이 없습니다");
        }
        if (req.getPredcMm() == null || !PREDC_MMS.contains(req.getPredcMm())) {
            throw badRequest("predcMm 은 3 · 6 · 12 · 24 중 하나여야 합니다: " + req.getPredcMm());
        }
        if (PLCY_TYPE_EXPAND.equals(req.getPlcyType())) {
            ExpandMethod method;
            try {
                method = ExpandMethod.valueOf(String.valueOf(req.getExpandMethod()));
            } catch (IllegalArgumentException e) {
                throw badRequest("expandMethod 는 VACLOT · MULTFLR · NEW 중 하나여야 합니다: " + req.getExpandMethod());
            }
            if (req.getAddPkltSlot() == null || !ADD_PKLT_SLOTS.contains(req.getAddPkltSlot())) {
                throw badRequest("addPkltSlot 은 50 · 80 · 100 · 150 · 200 중 하나여야 합니다: " + req.getAddPkltSlot());
            }
            return new ValidInput(PLCY_TYPE_EXPAND, method, null, null, null, null);
        }
        if (PLCY_TYPE_REPAIR.equals(req.getPlcyType())) {
            RoadMtncType type;
            try {
                type = RoadMtncType.valueOf(String.valueOf(req.getRoadMtncType()));
            } catch (IllegalArgumentException e) {
                throw badRequest("roadMtncType 은 RSDNC · CMRC 중 하나여야 합니다: " + req.getRoadMtncType());
            }
            Integer len = req.getRoadMtncLen();
            if (len == null || len < ROAD_MTNC_LEN_MIN || len > ROAD_MTNC_LEN_MAX) {
                throw badRequest("roadMtncLen 은 " + ROAD_MTNC_LEN_MIN + " ~ " + ROAD_MTNC_LEN_MAX + " (m) 이어야 합니다: " + len);
            }
            return new ValidInput(PLCY_TYPE_REPAIR, null, type, null, null, null);
        }
        if (PLCY_TYPE_FEE.equals(req.getPlcyType())) {
            FeeAplcnMthd mthd;
            try {
                mthd = FeeAplcnMthd.valueOf(String.valueOf(req.getFeeAplcnMthd()));
            } catch (IllegalArgumentException e) {
                throw badRequest("feeAplcnMthd 는 RAISE · CONVERT 중 하나여야 합니다: " + req.getFeeAplcnMthd());
            }
            String zone = req.getFeeAplcnZone() != null ? req.getFeeAplcnZone() : FEE_ZONE_ALL;
            if (!FEE_ZONES.contains(zone)) {
                throw badRequest("feeAplcnZone 은 ALL · Z1 · Z2 중 하나여야 합니다: " + zone);
            }
            Integer rt = null;
            if (mthd == FeeAplcnMthd.RAISE) {
                rt = req.getFeeIncrsRt();
                if (rt == null || !FEE_INCRS_RTS.contains(rt)) {
                    throw badRequest("feeIncrsRt 는 10 · 20 · 30 중 하나여야 합니다: " + rt);
                }
            }
            // CONVERT 에 온 feeIncrsRt 는 무시한다(400 아님) — 계산·저장 모두 null
            return new ValidInput(PLCY_TYPE_FEE, null, null, mthd, zone, rt);
        }
        throw badRequest("지원하지 않는 정책유형입니다(EXPAND · REPAIR · FEE): " + req.getPlcyType());
    }

    /**
     * 결과 엔티티 — 값은 VO 에서 옮기되, Step 1-2 의 NULL 규칙을 저장 쪽에서 한 번 더 못박는다
     * (VO 가 이미 같은 규칙으로 비워 두지만, 칸 단위 계약을 엔티티 조립부에서 읽히게 한다).
     */
    static PlcySmltRslt toEntity(Long excnId, PolicySimRequest req, PolicySimResultVO vo, ValidInput in) {
        PlcySmltRslt r = new PlcySmltRslt();
        r.setExcnId(excnId);
        r.setPlcyTypeCd(in.plcyType());
        r.setPredcMm(req.getPredcMm());
        if (in.isFee()) {
            // 요금제 (TSK-4039 Step 5-4) — 면수 N·도로 칸은 없다 · 공급 불변이라 vld_add_pklt_slot 도 NULL(VO 가 이미 null)
            r.setAddPkltSlot(null);
            r.setFeeAplcnMthd(in.feeAplcnMthd().name());
            r.setFeeAplcnZone(vo.getFeeAplcnZone());          // emdCd 지정 시 서버가 채운 급지
            r.setFeeIncrsRt(in.feeIncrsRt() != null ? BigDecimal.valueOf(in.feeIncrsRt()) : null);   // RAISE 만
            r.setAplcnTrgtSlot(vo.getAplcnTrgtSlot() != null ? Math.toIntExact(vo.getAplcnTrgtSlot()) : null);
            r.setLvngVhclCnt(vo.getLvngVhclCnt());
            r.setAbsrpCnt(vo.getAbsrpCnt());
            r.setTrnsfCnt(vo.getTrnsfCnt());
            r.setAdjcCvlcptCnt(vo.getAdjcCvlcptCnt());       // 4038 칸 재사용(사용자 결정 2026-10-11)
        } else if (in.isRepair()) {
            // 이면도로 (TSK-4038 Step 4-4) — 면수 N 은 없다(ΔS 는 vld_add_pklt_slot). 요청에 확충 필드가 와도 저장하지 않는다
            r.setAddPkltSlot(null);
            r.setRoadMtncType(in.roadMtncType().name());
            r.setRoadMtncLen(req.getRoadMtncLen());
            r.setAdjcCvlcptCnt(vo.getAdjcCvlcptCnt());
        } else {
            r.setAddPkltSlot(req.getAddPkltSlot());
        }
        r.setCvlcptRdctRt(vo.getCvlcptRdctRt());
        r.setCngstImpvRt(vo.getCngstImpvRt());
        r.setCstLvlCd(vo.getCstLvlCd());
        r.setRcmdtnYn(false);               // 단건 저장 — 추천 판정은 시나리오 비교(Step 7-2)
        r.setAnlsRngCn(vo.getAnlsRngCn());
        r.setCrtrCvlcptCnt(vo.getCrtrCvlcptCnt());
        r.setCrtrIlglCnt(vo.getCrtrIlglCnt());
        r.setCrtrCngstIdx(vo.getCrtrCngstIdx());
        r.setAftrCngstIdx(vo.getAftrCngstIdx());
        r.setExsPkltCpcty(vo.getExsPkltCpcty() != null ? Math.toIntExact(vo.getExsPkltCpcty()) : null);
        r.setVldAddPkltSlot(in.isFee() ? null : vo.getVldAddPkltSlot());
        r.setAftrCvlcptCnt(vo.getAftrCvlcptCnt());
        r.setCalcSttsCd(vo.getCalcSttsCd());
        r.setExcsYn(vo.isExcsYn());

        if (PolicySimCalcStts.NO_ENF.name().equals(vo.getCalcSttsCd())) {
            // 정책 후 값(R·I·C1·H1)은 내지 않는다. 기준선 C0·H0 는 값으로 남긴다
            r.setCvlcptRdctRt(null);
            r.setCngstImpvRt(null);
            r.setAftrCvlcptCnt(null);
            r.setAftrCngstIdx(null);
        } else if (PolicySimCalcStts.NO_SPLY.name().equals(vo.getCalcSttsCd())) {
            r.setCngstImpvRt(null);
            r.setCrtrCngstIdx(null);
            r.setAftrCngstIdx(null);
        }
        return r;
    }

    /**
     * anls_param_json — 요청 조건 (적용 계수·구간은 aplcn_param_json). 키 모양은 유형과 무관하게 같고,
     * 그 유형이 쓰지 않는 필드는 null 이다 — 요청에 함께 왔더라도 계산에 쓰지 않은 값을 남기지 않는다.
     * 🔴 키 이름 {@code roadMtncType}·{@code roadMtncLen} 은 대시보드 실행 목록(DashboardPanelMapper)이 읽는다.
     * 요금제(TSK-4039 Step 5-4)는 {@code feeAplcnMthd}·{@code feeAplcnZone}(emdCd 지정 시 서버가 채운 급지)·{@code feeIncrsRt}(RAISE 만).
     * 패키지 가시성 — 보고서 서비스(TSK-4229 {@code PolicySimReportServiceImpl})가 미저장분 {@code params} 12키(TSK-4332 {@code vrblAjmt} 포함)를 같은 규칙으로 만든다.
     */
    static Map<String, Object> anlsParam(PolicySimRequest req, ValidInput in, PolicySimResultVO vo) {
        boolean repair = in.isRepair();
        boolean fee = in.isFee();
        boolean expand = !repair && !fee;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plcyType", in.plcyType());
        m.put("expandMethod", expand ? req.getExpandMethod() : null);
        m.put("addPkltSlot", expand ? req.getAddPkltSlot() : null);
        m.put("roadMtncType", repair ? in.roadMtncType().name() : null);
        m.put("roadMtncLen", repair ? req.getRoadMtncLen() : null);
        m.put("feeAplcnMthd", fee ? in.feeAplcnMthd().name() : null);
        m.put("feeAplcnZone", fee ? vo.getFeeAplcnZone() : null);
        m.put("feeIncrsRt", fee ? in.feeIncrsRt() : null);
        m.put("predcMm", req.getPredcMm());
        m.put("sggNm", req.getSggNm());
        m.put("emdCd", req.getEmdCd());
        // TSK-4332 D6 — 적용 내역(그 유형 스코프 허용 목록 전체). compute 가 채운 값을 읽기만 한다
        m.put("vrblAjmt", vo.getVrblAjmt());
        return m;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "정책 시뮬레이션 저장값 직렬화에 실패했습니다", e);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record Computed(PolicySimBaseline baseline, PolicySimResultVO vo, String sggNm, ValidInput input) {
    }

    /**
     * 검증을 통과한 정책 유형별 입력.
     *
     * @param plcyType     EXPAND · REPAIR · FEE
     * @param expandMethod EXPAND 일 때만
     * @param roadMtncType REPAIR 일 때만
     * @param feeAplcnMthd FEE 일 때만
     * @param feeAplcnZone FEE 일 때만 — 요청 급지(생략 시 ALL). emdCd 지정 시 응답·저장 값은 서버가 다시 채운다
     * @param feeIncrsRt   FEE·RAISE 일 때만(10·20·30) — CONVERT 는 null
     */
    record ValidInput(String plcyType, ExpandMethod expandMethod, RoadMtncType roadMtncType,
                      FeeAplcnMthd feeAplcnMthd, String feeAplcnZone, Integer feeIncrsRt) {
        boolean isRepair() {
            return PLCY_TYPE_REPAIR.equals(plcyType);
        }

        boolean isFee() {
            return PLCY_TYPE_FEE.equals(plcyType);
        }
    }
}
