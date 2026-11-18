package jeju.datahub.lifezone.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.service.impl.AnlsExcn;
import jeju.datahub.analysis.service.impl.AnlsExcnRepository;
import jeju.datahub.analysis.service.impl.AnlsRslt;
import jeju.datahub.analysis.service.impl.AnlsRsltRepository;
import jeju.datahub.lifezone.service.LifezoneAnalysisResultVO;
import jeju.datahub.lifezone.service.LifezoneAnalysisService;
import jeju.datahub.lifezone.service.LifezoneCellVO;
import jeju.datahub.lifezone.service.LifezoneClsfMthd;
import jeju.datahub.lifezone.service.LifezoneGrade;
import jeju.datahub.lifezone.service.LifezoneGradeCriterionVO;
import jeju.datahub.lifezone.service.LifezoneGradeScale;
import jeju.datahub.lifezone.service.LifezoneRankVO;
import jeju.datahub.lifezone.service.LifezoneReportCreateRequest;
import jeju.datahub.lifezone.service.LifezoneReportService;
import jeju.datahub.lifezone.service.LifezoneReportVO;
import jeju.datahub.lifezone.service.LifezoneSnapshotVO;
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
 * 생활권 시뮬레이션 보고서 서비스 구현체. 전자정부 {@link EgovAbstractServiceImpl} 상속.
 *
 * <p>처리 순서는 넷이다 — ①같은 조건으로 <b>재분석</b> → ②실행 이력 1행 → ③분석 결과 <b>1행</b>
 * ({@code TB_ANLS_RSLT.rslt_json} 에 요약·순위·셀 전량을 담는다) → ④보고서 1행.
 * 집계·등급 판정·구역명 POI 는 {@link LifezoneAnalysisService} 가 이미 하므로 다시 구현하지 않는다.
 *
 * <h2>🔴 생활권 전용 엔티티를 만들지 않는다</h2>
 * (마이그레이션 56)가 분석 결과 표 4종을 공용 {@code TB_ANLS_RSLT} 하나로 합치면서
 * {@code TB_LFZN_SMLT_RSLT} 를 폐기했다. 그래서 {@code AnlsExcn}·{@code AnlsRslt}·{@code Rptp} 와
 * 그 리포지터리를 <b>그대로 재사용</b>한다 — 저장 경로가 집중구역과 완전히 같아진다.
 *
 * <h2>🔴 화면이 보낸 숫자를 저장하지 않는다</h2>
 * 요청 바디에는 조회 조건과 파일형식만 있고 건수·등급이 없다. 화면이 계산한 값을 그대로 굳히면
 * 보고서가 화면의 오류까지 영구 보존하고, 같은 조건을 다시 분석했을 때와 값이 달라져도 알 수 없다.
 *
 * <h2>🔴 트랜잭션 하나로 세 표를 묶는다</h2>
 * 실행 이력만 남고 보고서가 없는 <b>반쪽 상태</b>를 만들지 않는다. 0건 판정이 이력 저장보다
 * <b>앞</b>에 오는 것도 같은 이유이며, 뒤에서 예외가 나면 세 표가 함께 롤백된다.
 *
 * <p>⚠️ {@link LifezoneAnalysisService#analyze} 는 {@code @Transactional(readOnly = true)} 지만
 * 여기서 부르면 이 쓰기 트랜잭션에 <b>참여</b>한다(REQUIRED) — readOnly 는 새 트랜잭션을 열 때만
 * 적용되므로 이후 INSERT 가 막히지 않는다. 그 안의 구역명 캐시 쓰기는 {@code REQUIRES_NEW} 로
 * 분리돼 있어 이 트랜잭션이 롤백돼도 캐시는 남는다(외부 API 재호출을 아끼는 쪽이 맞다).
 *
 * <h2>🔴 두 JSON 칸의 분담</h2>
 * <table border="1">
 *   <caption>무엇이 어디에 들어가나</caption>
 *   <tr><th>칸</th><th>내용</th></tr>
 *   <tr><td>{@code TB_ANLS_RSLT.rslt_json}</td>
 *       <td><b>결과만</b> — {@code summary}(6수치) · {@code ranking}(심각·경고·주의 전부) · {@code cells}(값&gt;0 전량)</td></tr>
 *   <tr><td>{@code TB_ANLS_EXCN.anls_param_json}</td>
 *       <td><b>재현 메타</b> — 격자·분석유형·분류방법·문구·{@code gradeScale}·{@code gradeCriteria}</td></tr>
 * </table>
 * {@code gridSz}·{@code anlsTrgtCd} 를 결과 쪽에 <b>중복해 넣지 않는다</b> — 두 곳이 갈리면
 * 어느 쪽이 정본인지 알 수 없다.
 */
@Service
@RequiredArgsConstructor
public class LifezoneReportServiceImpl extends EgovAbstractServiceImpl implements LifezoneReportService {

    private static final Logger log = LoggerFactory.getLogger(LifezoneReportServiceImpl.class);

    /** 분석유형코드 ({@code ANLS_TYPE_CD}) — 이 값이 목록의 출처 칸을 "생활권시뮬레이션" 으로 만든다 */
    private static final String ANLS_TYPE_LIFEZONE = "LIFEZONE";

    /** 분석실행상태코드 ({@code EXCN_STTS_CD}) — 저장이 곧 완료다(비동기 실행이 아니다) */
    private static final String EXCN_STTS_DONE = "DONE";

    /** 보고서상태코드 ({@code RPTP_STTS_CD}) — 파일을 굽지 않으므로 생성 즉시 완료다 */
    private static final String RPTP_STTS_DONE = "DONE";

    /**
     * 생성구분 ({@code RPTP_CRT_TYPE_CD}). 🔴 이 값이 보고서 목록의 <b>직접 생성 탭</b>을 정한다 —
     * {@code AUTO} 로 저장하면 자동 생성 탭에 섞여 배포부서·주기 칸이 빈 행으로 나타난다.
     */
    private static final String CRT_TYPE_MANUAL = "MANUAL";

    /** 등급 수. {@link LifezoneGrade} 상수 개수와 항상 같다 — 저장된 경계 5행을 되살릴 때 쓴다 */
    private static final int GRADE_CNT = LifezoneGrade.values().length;

    private final LifezoneAnalysisService lifezoneAnalysisService;
    /** {@code emdCd → (emdNm, sggNm)} 조회용. 지역명 CASE 와 같은 원천({@code TB_ADMDST})을 본다 */
    private final RegionService regionService;
    private final AnlsExcnRepository anlsExcnRepository;
    private final AnlsRsltRepository anlsRsltRepository;
    private final RptpRepository rptpRepository;
    /**
     * 스냅샷 조회용. 🔴 {@code report} 도메인의 매퍼를 {@code lifezone} 서비스가 주입한다 —
     * 층이 달라(표현/서비스 ↔ 영속성) 순환 참조가 생기지 않는다. 반대로 {@code report} 서비스가
     * 이 조립을 갖게 하면 보고서 도메인이 생활권 등급 경계 재현까지 알게 된다.
     */
    private final ReportMapper reportMapper;
    private final ObjectMapper objectMapper;
    /** 보고서 셀 복원 시 구역명 캐시 일괄 조회용 */
    private final LifezoneAnalysisMapper lifezoneAnalysisMapper;

    // ─────────────────────────────────────────────────────────────────────────────
    // 저장 (POST)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>생성 일시({@code excn_dt}·{@code crt_ymd})는 서버 실시각({@link LocalDateTime#now()})이다 —
     * 목록 최신순({@code crt_ymd DESC})의 정렬 기준이다.
     */
    @Override
    @Transactional
    public LifezoneReportVO createReport(LifezoneReportCreateRequest req, String userId, String userNm) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "요청 본문이 없습니다");
        }

        // ── ① 같은 조건으로 다시 분석한다 ───────────────────────────────────
        //    조건 검증(격자 4종·미구현 유형·빈 지역·기간 역전)도 analyze() 가 400 으로 낸다.
        LifezoneAnalysisResultVO result = lifezoneAnalysisService.analyze(req.toAnalysisRequest());
        List<LifezoneCellVO> cells = result.getCells();
        if (cells == null || cells.isEmpty()) {
            // 조회 API 는 0건도 200 이지만 보고서는 400 이다 — 열 수 없는 보고서를 목록에 남기지 않는다.
            // 이 시점엔 아직 아무것도 저장하지 않았고, 예외가 트랜잭션을 롤백하므로 세 표 모두 늘지 않는다.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "분석 결과가 없어 보고서를 만들 수 없습니다.");
        }

        // 지역명 — emdCd 가 있으면 TB_ADMDST 에서 emd_nm 과 sgg_nm 을 '함께' 읽어 둘 다 쓴다.
        String emdCd = result.getEmdCd();
        String sggNm = result.getSggNm();
        String emdNm = null;
        if (emdCd != null) {
            AdmdstNm admdst = resolveAdmdst(emdCd);
            emdNm = admdst.emdNm();
            // 🔴 분석 결과의 sggNm 을 덮어쓴다. emdCd 만 온 요청에는 sggNm 이 null 로 오고,
            //    그대로 저장하면 조회 SQL 의 `e.sgg_nm || ' ' || a.emd_nm` 이 NULL 연결이 되어
            //    지역명이 통째로 사라진다. 읍면동이 실제로 속한 시군구가 정답이므로 이 값이 우선한다.
            sggNm = admdst.sggNm();
        }
        String regionNm = LifezoneReportNaming.regionNm(sggNm, emdNm);

        // ── ② 실행 이력 1행 — 재현 메타를 anls_param_json 에 담는다 ─────────────────
        LocalDateTime now = LocalDateTime.now();
        AnlsExcn excn = new AnlsExcn();
        excn.setAnlsTypeCd(ANLS_TYPE_LIFEZONE);
        excn.setExcutrId(userId);                   // 로그인 사용자 — 해석·401 은 컨트롤러 책임
        excn.setSggNm(sggNm);                       // 지역 조건이 없으면 null (3형태를 그대로 표현)
        excn.setEmdCd(emdCd);
        excn.setAnlsBgngYmd(result.getBgngYmd());   // 요청 문자열이 아니라 정규화된 YYYYMMDD
        excn.setAnlsEndYmd(result.getEndYmd());
        excn.setAnlsParamJson(buildParamJson(result, req.getLimit()));
        excn.setExcnSttsCd(EXCN_STTS_DONE);
        excn.setExcnDt(now);
        anlsExcnRepository.save(excn);
        // excn_id 를 결과 행·보고서 행이 참조하므로 여기서 확정한다 (identity 채번은 flush 시점).
        anlsExcnRepository.flush();

        // ── ③ 분석 결과 1행 — 요약·순위·셀 전량을 rslt_json 에 담는다 ──────────
        //     ⚠️ excn_id 가 UNIQUE 다 — 실행 1건 = 결과 1행. 저장마다 실행을 새로 만든다.
        AnlsRslt rslt = new AnlsRslt();
        rslt.setExcnId(excn.getExcnId());
        rslt.setRsltJson(buildRsltJson(result));
        anlsRsltRepository.save(rslt);

        // ── ④ 보고서 1행 — MANUAL · file_path 는 NULL ─────────────────
        Rptp rptp = new Rptp();
        rptp.setCrtType(CRT_TYPE_MANUAL);
        rptp.setExcnId(excn.getExcnId());
        rptp.setRptpNm(LifezoneReportNaming.rptpNm(
                result.getAnlsTrgtNm(), regionNm, result.getGridSzNm()));
        rptp.setAnlsPrdExpln(LifezoneReportNaming.anlsPrdExpln(result.getBgngYmd(), result.getEndYmd()));
        rptp.setSttsCd(RPTP_STTS_DONE);
        rptp.setFileFmt(req.getFileFmt());
        // filePath 는 채우지 않는다 — 파일을 서버에 굽지 않고 프론트가 응답으로 문서를 그린다.
        // stngId·trgtPrdCd·crltnDeptCd·crltnCyclCd 도 자동 생성 전용 칸이라 null 로 둔다.
        rptp.setCreatrId(userId);
        rptp.setCreatrNm(ReportCreatorName.cut(userNm));   // 🔴 creatr_nm 은 VARCHAR(50)
        rptp.setCrtYmd(now);
        rptpRepository.save(rptp);
        rptpRepository.flush();

        log.info("생활권 보고서 생성: rptpId={} excnId={} 지역={} 기간={}~{} 격자={} 유형={} 분류={} 셀={}건 순위={}건 형식={}",
                rptp.getRptpId(), excn.getExcnId(), regionNm,
                result.getBgngYmd(), result.getEndYmd(), result.getGridSzNm(),
                result.getAnlsTrgtCd(), result.getClsfMthd(),
                cells.size(), result.getRanking().size(), req.getFileFmt());

        LifezoneReportVO vo = new LifezoneReportVO();
        copyBody(result, vo);
        vo.setRptpId(rptp.getRptpId());
        vo.setExcnId(excn.getExcnId());
        vo.setRptpNm(rptp.getRptpNm());
        vo.setRegionNm(regionNm);
        vo.setAnlsPrdExpln(rptp.getAnlsPrdExpln());
        vo.setFileFmt(rptp.getFileFmt());
        vo.setCrtYmd(rptp.getCrtYmd());
        return vo;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 스냅샷 조회 (GET)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>순서가 중요하다 — <b>스냅샷 1쿼리 → 404 판정 3종 → 헤더 → 조립</b>. 판정을 뒤로 미루면
     * 열 수 없는 보고서를 위해 JOIN 6개짜리 헤더 조회를 먼저 치게 된다.
     *
     * <p>🔴 <b>{@code ranking} 은 저장 순서·저장 등급 그대로 낸다</b> — {@code rnk} 가 함께 저장돼
     * 있어 재정렬이 필요 없고(정렬 tie-break 는 저장 시점에 이미 확정됐다), 등급도 순위 행만이라
     * 저장해 두었다(15행 상한은 없앴지만 셀 전량보다 작다). <b>재판정이 필요한 것은 {@code cells} 뿐</b>이며 저장된
     * {@code gradeCriteria[].minVl} 로 판정한다(셀 전량이라 등급을 저장하지 않는다).
     */
    @Override
    @Transactional(readOnly = true)
    public LifezoneReportVO selectReportSnapshot(Long rptpId) {
        // ── ① 스냅샷 원문 — 유형·결과·재현 메타·실행 조건을 한 쿼리로 ────────────────
        LifezoneSnapshotVO snap = reportMapper.selectLifezoneSnapshot(rptpId);
        if (snap == null) {
            // 404-①: 보고서 자체가 없다.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서를 찾을 수 없습니다: " + rptpId);
        }
        if (!ANLS_TYPE_LIFEZONE.equals(snap.getAnlsTypeCd())) {
            // 404-②: 다른 유형(또는 실행 미연결) 보고서다. 이 경로로 열 수 있는 것은 생활권뿐이다.
            //        400 이 아니라 404 인 이유: 요청 자체는 정상이고 "그 경로에 그 자원이 없다".
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "생활권 시뮬레이션 보고서가 아닙니다: " + rptpId
                            + " (analysisType=" + snap.getAnlsTypeCd() + ")");
        }
        if (snap.getRsltJson() == null || snap.getRsltJson().isBlank()) {
            // 404-③: 유형은 맞지만 스냅샷이 비었다(시드로 만들어진 실행 행이 그렇다).
            //        여기서 끊지 않으면 빈 셀 + NULL 경계가 200 으로 나가 화면이 빈 문서를 그린다.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "보고서에 저장된 분석 결과가 없습니다: " + rptpId);
        }

        // ── ② 헤더 4칸은 기존 단건 조회를 재사용한다 ────────────────────────────────
        //     🔴 지역명 3단 CASE 를 새 SQL 에 복제하지 않는다 — 규칙이 두 곳에 살면 조용히 갈린다.
        ReportListVO header = reportMapper.selectReportById(rptpId);
        if (header == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서를 찾을 수 없습니다: " + rptpId);
        }

        // ── ③ 재현 메타 → 조건·문구·경계 ────────────────────────────────────────────
        JsonNode param = readTree(snap.getAnlsParamJson(), "anls_param_json", rptpId);
        JsonNode rsltNode = readTree(snap.getRsltJson(), "rslt_json", rptpId);

        List<LifezoneGradeCriterionVO> criteria = parseCriteria(param, rptpId);
        // 🔴 LifezoneGradeScale 을 Jackson 으로 '역'직렬화하지 않는다 — @JsonIgnore int[] minVls 를
        //    가진 record 라 정규 생성자가 null 을 받아 ValueInstantiationException 으로 터진다.
        //    저장된 gradeCriteria[].minVl 로 경계를 되살려 정규 생성자에 직접 넣는다.
        LifezoneGradeScale scale = rebuildScale(param, criteria, rptpId);

        LifezoneReportVO vo = new LifezoneReportVO();

        // 헤더 7칸
        vo.setRptpId(header.getRptpId());
        vo.setExcnId(snap.getExcnId());
        vo.setRptpNm(header.getRptpNm());
        vo.setRegionNm(header.getRegionNm());
        vo.setAnlsPrdExpln(header.getAnlsPrdExpln());
        vo.setFileFmt(header.getFileFmt());
        vo.setCrtYmd(header.getCrtYmd());

        // 조건 echo — 기간·지역은 TB_ANLS_EXCN 컬럼이, 나머지는 anls_param_json 이 갖는다
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
        vo.setUnit(text(param, "unit"));
        vo.setSectionTitle(text(param, "sectionTitle"));
        vo.setSectionSub(text(param, "sectionSub"));

        // 가용 유형은 enum 전량 투영이라 저장하지 않는다 — 지금 구현 상태를 그대로 보여야 한다
        vo.setAvailableTargets(LifezoneTargetVO.allOf());
        vo.setWeights(List.of());               // 배타 선택이라 항상 빈 배열
        vo.setGradeScale(scale);
        vo.setGradeCriteria(criteria);

        // ⚠️ dataBgngYmd·dataEndYmd 는 채우지 않는다 — 원천 보유 구간은 '지금'의 값이라
        //    스냅샷이 아니다. 굳혀 두면 옛 값이 사실인 척한다(의도된 비대칭 — LifezoneReportVO 참고).

        // ── ④ 결과 — 요약은 그대로, 순위도 그대로, 셀만 경계로 재판정 ────────────────
        applySummary(rsltNode.path("summary"), vo);
        vo.setRanking(parseRanking(rsltNode, rptpId));
        vo.setCells(parseCells(rsltNode, scale, rptpId));
        return vo;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 저장 값 조립
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 분석 결과 JSON — {@code {"summary":{…},"ranking":[…],"cells":[…]}}.
     *
     * <p>🔴 <b>결과만 담는다.</b> {@code gridSz}·{@code anlsTrgtCd} 같은 조건은
     * {@link #buildParamJson} 쪽이 갖는다 — 두 곳에 같은 값을 두면 어느 쪽이 정본인지 알 수 없다.
     *
     * <p>🔴 <b>{@code cells} 는 6칸만 담는다</b>({@code gridCd}·{@code anlsVl}·{@code ctrLat}·
     * {@code ctrLot}·{@code ring}·{@code emdNm}). {@code grade} 는 저장된 경계로 재판정하고
     * {@code cntrPoiNm} 은 {@code {읍면동명} 격자 {코드}} 로 재조립하므로, 전량(최대 2,015칸)에
     * 두 칸을 더 실을 이유가 없다(관례).
     *
     * <p>반대로 <b>{@code ranking} 은 VO 를 통째로</b> 싣는다 — 순위 행(심각·경고·주의)뿐이라 셀 전량보다 작고,
     * {@code cntrPoiNm}(VWorld POI)·{@code dtl}(세부 내역)·{@code grade} 는 재계산으로 되살릴 수
     * 없거나(외부 API) 되살리면 원본과 갈릴 수 있는 값이다. 키가 {@link LifezoneRankVO} 필드명과
     * 같아 조회할 때 변환이 없다.
     */
    private String buildRsltJson(LifezoneAnalysisResultVO result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totGridCnt", result.getTotGridCnt());
        summary.put("severeCnt", result.getSevereCnt());
        summary.put("warnCnt", result.getWarnCnt());
        summary.put("cautionCnt", result.getCautionCnt());
        summary.put("normalCnt", result.getNormalCnt());
        summary.put("lowCnt", result.getLowCnt());

        List<Map<String, Object>> cellRows = new ArrayList<>(result.getCells().size());
        for (LifezoneCellVO cell : result.getCells()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("gridCd", cell.getGridCd());
            row.put("anlsVl", cell.getAnlsVl());
            row.put("ctrLat", cell.getCtrLat());
            row.put("ctrLot", cell.getCtrLot());
            row.put("ring", cell.getRing());
            row.put("emdNm", cell.getEmdNm());
            cellRows.add(row);
        }

        Map<String, Object> rslt = new LinkedHashMap<>();
        rslt.put("summary", summary);
        rslt.put("ranking", result.getRanking());
        rslt.put("cells", cellRows);
        try {
            return objectMapper.writeValueAsString(rslt);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "분석 결과 직렬화에 실패했습니다", e);
        }
    }

    /**
     * 재현 메타 JSON — 조회 조건 밖에서 결과를 되살리는 데 필요한 값 전부.
     *
     * <p>🔴 <b>{@code clsfMthd} 와 {@code gradeCriteria} 가 핵심</b>이다. 등급 경계가
     * <b>조회 결과에서</b> 산출되므로, 어느 방식으로 낸 어떤 경계였는지를 굳히지 않으면 보고서를
     * 다시 열 때 셀 등급을 재현할 방법이 없다 — 원천이 한 건만 늘어도 경계가 움직인다.
     *
     * <p>{@code gradeScale} 은 {@link LifezoneGradeScale} 을 그대로 직렬화한다
     * ({@code {method, cap, width}} 3키 — {@code minVls} 는 {@code @JsonIgnore} 라 나가지 않고
     * {@code gradeCriteria[].minVl} 이 같은 값을 이미 싣는다).
     *
     * @param limit 요청값 그대로. 결과 개수가 아니다 — 상한 때문에 결과가 더 적을 수 있고,
     *              "무엇을 요구했는가" 가 재현에 필요한 값이다. 생략이면 {@code null}
     *              (= 상한 없음 · 심각·경고·주의 전부)로 그대로 남긴다
     */
    private String buildParamJson(LifezoneAnalysisResultVO result, Integer limit) {
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("gridSz", result.getGridSz());
        param.put("gridSzNm", result.getGridSzNm());
        param.put("anlsTrgtCd", result.getAnlsTrgtCd());
        param.put("anlsTrgtNm", result.getAnlsTrgtNm());
        param.put("clsfMthd", result.getClsfMthd());
        param.put("clsfMthdNm", result.getClsfMthdNm());
        param.put("limit", limit);
        param.put("unit", result.getUnit());
        param.put("sectionTitle", result.getSectionTitle());
        param.put("sectionSub", result.getSectionSub());
        param.put("gradeScale", result.getGradeScale());
        param.put("gradeCriteria", result.getGradeCriteria());
        try {
            return objectMapper.writeValueAsString(param);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "분석 파라미터 직렬화에 실패했습니다", e);
        }
    }

    /**
     * 분석 응답 본문을 보고서 응답으로 옮긴다 — <b>저장한 값 그대로</b> 돌려준다.
     *
     * <p>{@link LifezoneReportVO} 가 {@link LifezoneAnalysisResultVO} 를 상속하므로 필드 정의는
     * 한 벌뿐이지만, <b>인스턴스를 갈아탈 때는 값 복사가 필요하다</b>(부모 인스턴스를 자식으로
     * 캐스팅할 수는 없다). 칸이 늘면 여기도 한 줄 늘어야 한다.
     */
    private static void copyBody(LifezoneAnalysisResultVO src, LifezoneReportVO dst) {
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
        dst.setUnit(src.getUnit());
        dst.setSectionTitle(src.getSectionTitle());
        dst.setSectionSub(src.getSectionSub());
        dst.setAvailableTargets(src.getAvailableTargets());
        dst.setGradeScale(src.getGradeScale());
        dst.setGradeCriteria(src.getGradeCriteria());
        dst.setWeights(src.getWeights());
        dst.setTotGridCnt(src.getTotGridCnt());
        dst.setSevereCnt(src.getSevereCnt());
        dst.setWarnCnt(src.getWarnCnt());
        dst.setCautionCnt(src.getCautionCnt());
        dst.setNormalCnt(src.getNormalCnt());
        dst.setLowCnt(src.getLowCnt());
        dst.setDataBgngYmd(src.getDataBgngYmd());
        dst.setDataEndYmd(src.getDataEndYmd());
        dst.setCells(src.getCells());
        dst.setRanking(src.getRanking());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 스냅샷 역직렬화
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 저장된 JSON 원문을 트리로 읽는다.
     *
     * <p>⚠️ 실패는 <b>500</b> 이다 — 빈 값으로 흘리면 본문 없는 보고서가 200 으로 나간다.
     * "결과가 아예 없다" 는 경우는 앞선 404-③ 판정이 이미 끊었다.
     */
    private JsonNode readTree(String json, String field, Long rptpId) {
        if (json == null || json.isBlank()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "보고서 스냅샷에 " + field + " 가 없습니다: rptpId=" + rptpId);
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "보고서 스냅샷 " + field + " 역직렬화에 실패했습니다: rptpId=" + rptpId, e);
        }
    }

    /** 등급 기준 5행. 순서·값 모두 저장된 그대로 쓴다 — 범례 문구({@code lgndTxt})까지 굳혀 뒀다. */
    private List<LifezoneGradeCriterionVO> parseCriteria(JsonNode param, Long rptpId) {
        JsonNode node = param.path("gradeCriteria");
        if (!node.isArray() || node.size() != GRADE_CNT) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "보고서 스냅샷의 gradeCriteria 가 " + GRADE_CNT + "행이 아닙니다: rptpId=" + rptpId);
        }
        return objectMapper.convertValue(node, new TypeReference<List<LifezoneGradeCriterionVO>>() { });
    }

    /**
     * 저장된 경계로 {@link LifezoneGradeScale} 을 되살린다.
     *
     * <p>🔴 <b>Jackson 역직렬화를 쓰지 않는다.</b> {@code @JsonIgnore int[] minVls} 를 가진 record 라
     * 정규 생성자가 {@code null} 을 받아 {@code ValueInstantiationException} 으로 터진다
     * ({@code treeToValue(node, LifezoneAnalysisResultVO.class)} 로 스냅샷을 한 번에 읽으려 하면
     * 정확히 이걸 밟는다). 경계는 {@code gradeCriteria[].minVl} 이 이미 싣고 있으므로 그것으로
     * 배열을 세워 정규 생성자에 직접 넣는다.
     *
     * <p>배열 순서는 {@link LifezoneGrade} 선언 순서다 — 저장 배열의 순서에 기대지 않고
     * <b>등급 이름으로 자리를 찾는다</b>(행 순서가 바뀌어도 경계가 뒤섞이지 않게).
     */
    private LifezoneGradeScale rebuildScale(JsonNode param, List<LifezoneGradeCriterionVO> criteria,
                                            Long rptpId) {
        JsonNode scaleNode = param.path("gradeScale");
        if (scaleNode.isMissingNode() || scaleNode.isNull()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "보고서 스냅샷에 gradeScale 이 없습니다: rptpId=" + rptpId);
        }
        LifezoneClsfMthd mthd = LifezoneClsfMthd.ofCode(scaleNode.path("method").asText(null));
        if (mthd == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "보고서 스냅샷의 분류방법을 알 수 없습니다: rptpId=" + rptpId
                            + " method=" + scaleNode.path("method").asText(null));
        }
        int[] minVls = new int[GRADE_CNT];
        boolean[] filled = new boolean[GRADE_CNT];
        for (LifezoneGradeCriterionVO row : criteria) {
            LifezoneGrade grade;
            try {
                grade = LifezoneGrade.valueOf(row.getGrade());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "보고서 스냅샷의 등급코드를 알 수 없습니다: rptpId=" + rptpId
                                + " grade=" + row.getGrade(), e);
            }
            minVls[grade.ordinal()] = row.getMinVl();
            filled[grade.ordinal()] = true;
        }
        for (int i = 0; i < GRADE_CNT; i++) {
            if (!filled[i]) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "보고서 스냅샷에 " + LifezoneGrade.values()[i].name() + " 경계가 없습니다: rptpId=" + rptpId);
            }
        }
        return new LifezoneGradeScale(scaleNode.path("cap").asInt(), scaleNode.path("width").asInt(),
                mthd, minVls);
    }

    /** 요약 6수치 — <b>재계산 없이</b> 저장값을 그대로 옮긴다(그것이 스냅샷의 정의다). */
    private void applySummary(JsonNode summary, LifezoneReportVO vo) {
        vo.setTotGridCnt(summary.path("totGridCnt").asInt());
        vo.setSevereCnt(summary.path("severeCnt").asInt());
        vo.setWarnCnt(summary.path("warnCnt").asInt());
        vo.setCautionCnt(summary.path("cautionCnt").asInt());
        vo.setNormalCnt(summary.path("normalCnt").asInt());
        vo.setLowCnt(summary.path("lowCnt").asInt());
    }

    /** 순위 목록 — 🔴 <b>저장 순서·저장 등급 그대로</b>. 재정렬·재판정하지 않는다. */
    private List<LifezoneRankVO> parseRanking(JsonNode rslt, Long rptpId) {
        JsonNode node = rslt.path("ranking");
        if (!node.isArray()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "저장된 분석 결과에 ranking 배열이 없습니다: rptpId=" + rptpId);
        }
        return objectMapper.convertValue(node, new TypeReference<List<LifezoneRankVO>>() { });
    }

    /**
     * 격자 셀 — 저장된 6칸을 읽고 <b>{@code grade} 와 {@code cntrPoiNm} 두 칸만 되살린다</b>.
     *
     * <p>{@code grade} 는 저장된 경계({@code scale})로 재판정하고, {@code cntrPoiNm} 은 분석 서비스와
     * <b>같은 규칙</b>으로 재조립한다 — 저장된 격자 중심 키로 구역명 캐시를 <b>한 번에</b> 찾아 있으면 그 이름,
     * 없으면 같은 폴백 함수. 문구 규칙을 여기 복제하면 화면 두 곳이 조용히 갈린다.
     */
    private List<LifezoneCellVO> parseCells(JsonNode rslt, LifezoneGradeScale scale, Long rptpId) {
        JsonNode node = rslt.path("cells");
        if (!node.isArray()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "저장된 분석 결과에 cells 배열이 없습니다: rptpId=" + rptpId);
        }
        List<LifezoneCellVO> cells =
                objectMapper.convertValue(node, new TypeReference<List<LifezoneCellVO>>() { });
        Map<String, String> cached = loadCachedZoneNames(cells);
        for (LifezoneCellVO cell : cells) {
            LifezoneGrade grade = scale.gradeOf(cell.getAnlsVl());
            // 값>0 격자만 저장했으므로 null 이 올 일은 없다(방어 — LOW 로 떨어뜨린다)
            cell.setGrade(grade != null ? grade.name() : LifezoneGrade.LOW.name());
            String cacheNm = cached.get(cacheKeyOf(cell.getCtrLat(), cell.getCtrLot()));
            cell.setCntrPoiNm(cacheNm != null
                    ? cacheNm : LifezoneAnalysisServiceImpl.fallbackZoneNm(cell.getEmdNm(), cell.getGridCd()));
        }
        return cells;
    }

    /**
     * 셀 중심 키 → 캐시 이름. 쿼리는 <b>1회</b>다(키 쌍을 unnest 로 펼친다).
     * 키는 {@code ZoneNameResolver.key} 와 같은 {@code setScale(5, HALF_UP)} — 좌표가 없는 셀은 건너뛰고,
     * 키가 하나도 없으면 쿼리를 생략한다.
     */
    private Map<String, String> loadCachedZoneNames(List<LifezoneCellVO> cells) {
        Set<String> keys = new LinkedHashSet<>();
        for (LifezoneCellVO cell : cells) {
            String key = cacheKeyOf(cell.getCtrLat(), cell.getCtrLot());
            if (key != null) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            return Map.of();
        }
        StringBuilder lats = new StringBuilder();
        StringBuilder lots = new StringBuilder();
        for (String key : keys) {
            int bar = key.indexOf('|');
            if (lats.length() > 0) {
                lats.append(',');
                lots.append(',');
            }
            lats.append(key, 0, bar);
            lots.append(key, bar + 1, key.length());
        }
        Map<String, String> names = new HashMap<>();
        for (CachedZoneNmRow row : lifezoneAnalysisMapper.selectCachedZoneNames(lats.toString(), lots.toString())) {
            if (row.getZoneNm() != null && !row.getZoneNm().isBlank()) {
                names.put(cacheKeyOf(row.getLatKey(), row.getLotKey()), row.getZoneNm());
            }
        }
        return names;
    }

    /** 캐시 키 문자열 {@code {lat5}|{lot5}} — 좌표가 비면 null */
    private static String cacheKeyOf(BigDecimal lat, BigDecimal lot) {
        if (lat == null || lot == null) {
            return null;
        }
        return lat.setScale(5, RoundingMode.HALF_UP).toPlainString()
                + "|" + lot.setScale(5, RoundingMode.HALF_UP).toPlainString();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 지역명
    // ─────────────────────────────────────────────────────────────────────────────

    /** {@code TB_ADMDST} 한 행에서 함께 읽은 읍면동명·시군구명 (지역명 조립 입력). */
    private record AdmdstNm(String emdNm, String sggNm) {
    }

    /**
     * {@code emdCd → (emdNm, sggNm)}. 🔴 <b>둘을 함께</b> 읽는다 — {@code sggNm} 을 비워 두면
     * 스냅샷 조회의 {@code sgg_nm || ' ' || emd_nm} 이 NULL 연결이 되어 지역명이 통째로 사라진다.
     *
     * <p>{@code TB_ADMDST} 를 원천으로 하는 {@link RegionService} 를 쓴다 — 지역명 CASE 가 보는
     * 표와 같아야 POST 응답과 GET 응답이 갈리지 않는다.
     */
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

    /** JSON 문자열 칸 — 없으면 {@code null}(빈 문자열로 바꾸지 않는다). */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
