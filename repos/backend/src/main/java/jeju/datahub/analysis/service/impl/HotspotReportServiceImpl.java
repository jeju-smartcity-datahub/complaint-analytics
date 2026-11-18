package jeju.datahub.analysis.service.impl;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.HotspotAnalysisResultVO;
import jeju.datahub.analysis.service.HotspotAnalysisService;
import jeju.datahub.analysis.service.HotspotAnalysisService.HotspotAnalysisWithWorkbook;
import jeju.datahub.analysis.service.GradeCriterionVO;
import jeju.datahub.analysis.service.HotspotGrade;
import jeju.datahub.analysis.service.HotspotExcnMetaVO;
import jeju.datahub.analysis.service.HotspotReportCreateRequest;
import jeju.datahub.analysis.service.HotspotReportService;
import jeju.datahub.analysis.service.HotspotReportVO;
import jeju.datahub.analysis.service.HotspotWorkbookVO;
import jeju.datahub.analysis.service.HotspotZoneVO;
import jeju.datahub.report.service.ReportCreatorName;
import jeju.datahub.report.service.ReportListVO;
import jeju.datahub.report.service.impl.ReportMapper;
import jeju.datahub.report.service.impl.Rptp;
import jeju.datahub.report.service.impl.RptpRepository;
import lombok.RequiredArgsConstructor;

/**
 * 집중구역 분석 보고서 생성 서비스 구현체. 전자정부 {@link EgovAbstractServiceImpl} 상속.
 *
 * <p>처리 순서는 넷이다 — ①같은 조건으로 <b>재분석</b> → ②실행 이력 1행 → ③분석 결과 1행
 * ({@code TB_ANLS_RSLT.rslt_json} 에 순위 전체를 담는다) → ④보고서 1행. 분석 산식·구역명 폴백·등급 매핑은 {@link HotspotAnalysisService} 가 이미 하므로
 * 여기서 다시 구현하지 않는다.
 *
 * <h2>🔴 화면이 보낸 숫자를 저장하지 않는다</h2>
 * 요청 바디에는 <b>조회 조건과 파일형식만</b> 있고 점수·건수가 없다. 화면이 계산한 값을 받아
 * 그대로 굳히면 보고서가 화면의 오류까지 영구 보존하고, 같은 조건을 다시 분석했을 때와 값이
 * 달라져도 아무도 알 수 없다. 그래서 저장 직전에 서버가 한 번 더 분석한다.
 *
 * <h2>🔴 트랜잭션 하나로 세 표를 묶는다</h2>
 * 실행 이력만 남고 보고서가 없는 <b>반쪽 상태</b>를 만들지 않는다. 0건 판정({@link #createReport})이
 * 이력 저장보다 <b>앞</b>에 오는 것도 같은 이유이며, 뒤에서 예외가 나면 세 표가 함께 롤백된다.
 *
 * <p>⚠️ {@link HotspotAnalysisService#analyze} 는 {@code @Transactional(readOnly = true)} 지만
 * 여기서 부르면 이 쓰기 트랜잭션에 <b>참여</b>한다(REQUIRED) — readOnly 는 새 트랜잭션을 열 때만
 * 적용되므로 이후 INSERT 가 막히지 않는다. 그 안의 구역명 캐시 쓰기는 {@code REQUIRES_NEW} 로
 * 분리돼 있어 이 트랜잭션이 롤백돼도 캐시는 남는다(외부 API 재호출을 아끼는 쪽이 맞다).
 */
@Service
@RequiredArgsConstructor
public class HotspotReportServiceImpl extends EgovAbstractServiceImpl implements HotspotReportService {

    private static final Logger log = LoggerFactory.getLogger(HotspotReportServiceImpl.class);

    /** 분석유형코드 ({@code ANLS_TYPE_CD}) — 이 값이 목록의 출처 칸을 "집중구역분석" 으로 만든다 */
    private static final String ANLS_TYPE_HOTSPOT = "HOTSPOT";

    /** 분석실행상태코드 ({@code EXCN_STTS_CD}) — 저장이 곧 완료다(비동기 실행이 아니다) */
    private static final String EXCN_STTS_DONE = "DONE";

    /** 보고서상태코드 ({@code RPTP_STTS_CD}) — 파일을 굽지 않으므로 생성 즉시 완료다 */
    private static final String RPTP_STTS_DONE = "DONE";

    /**
     * 생성구분 ({@code RPTP_CRT_TYPE_CD}). 🔴 이 값이 보고서 목록의 <b>직접 생성 탭</b>을 정한다 —
     * {@code AUTO} 로 저장하면 자동 생성 탭에 섞여 배포부서·주기 칸이 빈 행으로 나타난다.
     */
    private static final String CRT_TYPE_MANUAL = "MANUAL";

    /** 점수 산정 방식 — 조회 결과 1위 = 100 인 상대 점수. 키가 없는 스냅샷은 구 기준값 방식이다. */
    private static final String ANLS_MTHD_LIST_MAX = "LIST_MAX";

    private final HotspotAnalysisService hotspotAnalysisService;
    private final HotspotReportMapper hotspotReportMapper;
    private final AnlsExcnRepository anlsExcnRepository;
    private final AnlsRsltRepository anlsRsltRepository;
    private final RptpRepository rptpRepository;
    /**
     * 스냅샷 조회용. 🔴 {@code report} 도메인의 매퍼를 {@code analysis} 서비스가 주입한다 —
     * 층이 달라(표현/서비스 ↔ 영속성) 순환 참조가 생기지 않는다. {@link ReportMapper} 는 아무 빈도
     * 주입하지 않는 MyBatis 인터페이스다. 반대로 {@code report} 서비스가 이 조립을 갖게 하면
     * 보고서 도메인이 {@code HotspotGrade} 임계값까지 알게 된다.
     */
    private final ReportMapper reportMapper;
    private final ObjectMapper objectMapper;

    /**
     * {@inheritDoc}
     *
     * <p>생성 일시({@code excn_dt}·{@code crt_ymd})는 서버 실시각({@link LocalDateTime#now()})이다 —
     * 목록·대시보드의 최신순({@code crt_ymd DESC}) 정렬 기준이다.
     */
    @Override
    @Transactional
    public HotspotReportVO createReport(HotspotReportCreateRequest req, String userId, String userNm) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "요청 본문이 없습니다");
        }

        // ── ① 같은 조건으로 다시 분석한다 ────────────────────────────────────
        //    엑셀 재계산 데이터(wbk)를 같은 조건·같은 구역명 세션으로 함께 구한다(형식 무관).
        HotspotAnalysisWithWorkbook analyzed = hotspotAnalysisService.analyzeWithWorkbook(req.toAnalysisRequest());
        HotspotAnalysisResultVO result = analyzed.result();
        HotspotWorkbookVO wbk = analyzed.wbk();
        List<HotspotZoneVO> zones = result.getZones();
        if (zones == null || zones.isEmpty()) {
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
            AdmdstNmVO admdst = hotspotReportMapper.selectAdmdstNm(emdCd);
            if (admdst == null) {
                // analyze() 가 이미 실재를 확인했으므로 정상 흐름에서는 오지 않는다(방어).
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "존재하지 않는 읍면동코드입니다: " + emdCd);
            }
            emdNm = admdst.getEmdNm();
            // 🔴 요청/분석 결과의 sggNm 을 덮어쓴다. resolveRegion() 은 emdCd 만 온 요청에 null 을 주고,
            //    그대로 저장하면 조회 SQL 의 `e.sgg_nm || ' ' || a.emd_nm` 이 NULL 연결이 되어
            //    지역명이 통째로 사라진다. 읍면동이 실제로 속한 시군구가 정답이므로 이 값이 우선한다.
            sggNm = admdst.getSggNm();
        }
        String regionNm = HotspotReportNaming.regionNm(sggNm, emdNm);

        // ── ② 실행 이력 1행 — 재현 메타를 anls_param_json 에 담는다 ──────────
        LocalDateTime now = LocalDateTime.now();
        AnlsExcn excn = new AnlsExcn();
        excn.setAnlsTypeCd(ANLS_TYPE_HOTSPOT);
        excn.setExcutrId(userId);                   // 로그인 사용자 — 해석·401 은 컨트롤러 책임
        excn.setSggNm(sggNm);                       // 지역 조건이 없으면 null (3형태를 그대로 표현)
        excn.setEmdCd(emdCd);
        excn.setAnlsBgngYmd(result.getBgngYmd());   // 요청 문자열이 아니라 정규화된 YYYYMMDD
        excn.setAnlsEndYmd(result.getEndYmd());
        excn.setAnlsParamJson(buildParamJson(result, req.getLimit()));
        excn.setExcnSttsCd(EXCN_STTS_DONE);
        excn.setExcnDt(now);
        anlsExcnRepository.save(excn);
        // excn_id 를 순위 행·보고서 행이 참조하므로 여기서 확정한다 (identity 채번은 flush 시점).
        anlsExcnRepository.flush();

        // ── ③ 분석 결과 1행 — 순위 전체를 rslt_json 에 담는다 (실행 1건 = 결과 1행) ──
        AnlsRslt rslt = new AnlsRslt();
        rslt.setExcnId(excn.getExcnId());
        rslt.setRsltJson(buildRsltJson(zones, wbk));
        anlsRsltRepository.save(rslt);

        // ── ④ 보고서 1행 — MANUAL · file_path 는 NULL ───────────────────────
        Rptp rptp = new Rptp();
        rptp.setCrtType(CRT_TYPE_MANUAL);
        rptp.setExcnId(excn.getExcnId());
        rptp.setRptpNm(HotspotReportNaming.rptpNm(regionNm));
        rptp.setAnlsPrdExpln(HotspotReportNaming.anlsPrdExpln(result.getBgngYmd(), result.getEndYmd()));
        rptp.setSttsCd(RPTP_STTS_DONE);
        rptp.setFileFmt(req.getFileFmt());
        // filePath 는 채우지 않는다 — 파일을 서버에 굽지 않고 프론트가 응답으로 문서를 그린다.
        // stngId·trgtPrdCd·crltnDeptCd·crltnCyclCd 도 자동 생성 전용 칸이라 null 로 둔다.
        rptp.setCreatrId(userId);
        rptp.setCreatrNm(ReportCreatorName.cut(userNm));   // 🔴 creatr_nm 은 VARCHAR(50)
        rptp.setCrtYmd(now);
        rptpRepository.save(rptp);
        rptpRepository.flush();

        log.info("집중구역 보고서 생성: rptpId={} excnId={} 지역={} 기간={}~{} 구역={}건 형식={}",
                rptp.getRptpId(), excn.getExcnId(), regionNm,
                result.getBgngYmd(), result.getEndYmd(), zones.size(), req.getFileFmt());

        return buildResponse(rptp, excn, result, wbk, regionNm);
    }

    /**
     * {@inheritDoc}
     *
     * <p>순서가 중요하다 — <b>메타 → 404 판정 3종 → 헤더 → 본문</b>. 판정을 뒤로 미루면 열 수 없는
     * 보고서를 위해 JSONB 본문 조회와 JOIN 6개짜리 헤더 조회를 먼저 치게 된다.
     */
    @Override
    @Transactional(readOnly = true)
    public HotspotReportVO selectReportSnapshot(Long rptpId) {
        // ── ① 실행 메타 — 세 판정을 한 쿼리로 (유형·행수·재현 파라미터) ─────────────
        HotspotExcnMetaVO meta = reportMapper.selectHotspotExcnMeta(rptpId);
        if (meta == null) {
            // 404-①: 보고서 자체가 없다.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서를 찾을 수 없습니다: " + rptpId);
        }
        if (!ANLS_TYPE_HOTSPOT.equals(meta.getAnlsTypeCd())) {
            // 404-②: 다른 유형(또는 실행 미연결) 보고서다. 이 경로로 열 수 있는 것은 집중구역뿐이다.
            //        400 이 아니라 404 인 이유: 요청 자체는 정상이고 "그 경로에 그 자원이 없다".
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "집중구역 분석 보고서가 아닙니다: " + rptpId
                            + " (analysisType=" + meta.getAnlsTypeCd() + ")");
        }
        if (meta.getRsltCnt() == 0) {
            // 404-③: 유형은 맞지만 스냅샷이 비었다(시드 자동 생성 보고서가 그렇다).
            //        여기서 끊지 않으면 빈 zones + NULL 가중치가 200 으로 나가 화면이 빈 문서를 그린다.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "보고서에 저장된 분석 결과가 없습니다: " + rptpId);
        }

        // ── ② 헤더 4칸은 기존 단건 조회를 재사용한다 ────────────────────────────────
        // 🔴 지역명 3단 CASE 를 새 SQL 에 복제하지 않는다 — 규칙이 두 곳에 살면 조용히 갈린다.
        //    위 메타 조회가 행을 찾았으므로 여기서 null 이 올 일은 없다(동시 삭제 방어).
        ReportListVO header = reportMapper.selectReportById(rptpId);
        if (header == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서를 찾을 수 없습니다: " + rptpId);
        }

        // ── ③ 순위 목록 — rslt_json 한 칸을 Jackson 으로 되살린다 ──────────────
        // 메타 조회가 rsltCnt > 0 을 확인했으므로 여기서 null 이 올 일은 없다(동시 삭제 방어).
        String rsltJson = reportMapper.selectHotspotSnapshot(rptpId);
        if (rsltJson == null || rsltJson.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "보고서에 저장된 분석 결과가 없습니다: " + rptpId);
        }
        List<HotspotZoneVO> zones = parseZones(rsltJson, rptpId);
        // 순위 비율 등급은 저장한 값을 쓴다(점수로 되살릴 수 없다). grade 가 없는 구 스냅샷만 점수로 판정한다.
        for (HotspotZoneVO zone : zones) {
            HotspotGrade grade = zone.getGrade() != null
                    ? HotspotGrade.valueOf(zone.getGrade())
                    : HotspotGrade.of(zone.getSnthsScr());
            zone.setGrade(grade.name());
            zone.setGradeNm(grade.getGradeNm());
            // emdNm·zoneNmSrc 는 rslt_json 에 담지 않으므로 null 로 둔다 — 의도된 비대칭이다.
        }

        HotspotReportVO vo = new HotspotReportVO();
        vo.setRptpId(header.getRptpId());
        vo.setExcnId(meta.getExcnId());
        vo.setRptpNm(header.getRptpNm());
        vo.setRegionNm(header.getRegionNm());
        vo.setAnlsPrdExpln(header.getAnlsPrdExpln());
        vo.setFileFmt(header.getFileFmt());
        vo.setCrtYmd(header.getCrtYmd());
        vo.setWeights(parseWeights(meta.getAnlsParamJson(), rptpId));
        vo.setWgvlAjmtYn(parseWgvlAjmtYn(meta.getAnlsParamJson()));
        vo.setAnlsMthd(parseAnlsMthd(meta.getAnlsParamJson()));
        vo.setGradeCriteria(parseGradeCriteria(meta.getAnlsParamJson()));
        vo.setVrblAjmt(parseVrblAjmt(meta.getAnlsParamJson()));
        vo.setZones(zones);
        vo.setWbk(parseWbk(rsltJson, rptpId));
        return vo;
    }

    /**
     * 저장된 재현 메타에서 가중치 목록을 되살린다.
     *
     * <p>가중치 마스터({@code TB_ANLS_VRBL_STNG})를 조인하지 <b>않는다</b> — 그러면 설정을
     * 고치는 순간 과거 보고서의 부제({@code 민원 60% / 단속 40%})까지 소급해 바뀐다. 보고서는
     * 생성 시점의 기록이므로 출처는 {@code anls_param_json} <b>뿐</b>이다.
     *
     * <p>파싱 실패는 500 이 아니라 <b>빈 목록 + WARN</b> 이다 — 구역 15행이라는 본문은 멀쩡한데
     * 부제 한 줄 때문에 보고서 전체를 못 열게 만들 이유가 없다. NULL 인 실행 행(시드)도 같은 경로다.
     */
    private List<AnalysisWeightVO> parseWeights(String anlsParamJson, Long rptpId) {
        if (anlsParamJson == null || anlsParamJson.isBlank()) {
            log.warn("보고서 스냅샷에 분석 파라미터가 없습니다 (가중치 없이 응답): rptpId={}", rptpId);
            return new ArrayList<>();
        }
        try {
            JsonNode weightsNode = objectMapper.readTree(anlsParamJson).path("weights");
            if (!weightsNode.isArray()) {
                log.warn("분석 파라미터에 weights 배열이 없습니다 (가중치 없이 응답): rptpId={}", rptpId);
                return new ArrayList<>();
            }
            List<AnalysisWeightVO> weights =
                    objectMapper.convertValue(weightsNode, new TypeReference<List<AnalysisWeightVO>>() { });
            return weights != null ? weights : new ArrayList<AnalysisWeightVO>();
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("분석 파라미터 역직렬화 실패 (가중치 없이 응답): rptpId={} 사유={}", rptpId, e.getClass().getSimpleName());
            log.debug("분석 파라미터 역직렬화 실패 (가중치 없이 응답) 상세", e);   // 4.1 — 예외 원문은 DEBUG 로만
            return new ArrayList<>();
        }
    }

    /**
     * 저장된 재현 메타에서 가중치 조정 여부를 읽는다.
     *
     * <p>키가 없으면(구 스냅샷) {@code null} 이다 — {@code N} 으로 채우면 조정 여부를 모르는 과거 보고서가
     * "조정 안 함" 으로 읽힌다. 파싱 실패도 {@code null} 이다. 원인 로그는 {@link #parseWeights} 가 남긴다.
     */
    private String parseWgvlAjmtYn(String anlsParamJson) {
        return parseTextParam(anlsParamJson, "wgvlAjmtYn");
    }

    /**
     * 저장된 재현 메타에서 점수 산정 방식을 읽는다.
     *
     * <p>키가 없으면(구 스냅샷) {@code null} 이다 — 프론트가 이 값으로 산정 기준 문구를 가른다. 기본값으로 채우면
     * 기준값 방식으로 저장된 과거 보고서가 새 문구로 읽힌다.
     */
    private String parseAnlsMthd(String anlsParamJson) {
        return parseTextParam(anlsParamJson, "anlsMthd");
    }

    /**
     * 생성 당시 등급 비율을 되살린다 — 설정을 바꿔도 과거 보고서 문구가 소급해 바뀌면 안 된다.
     * 키가 없으면(구 스냅샷) {@code null} 이다.
     */
    private List<GradeCriterionVO> parseGradeCriteria(String anlsParamJson) {
        if (anlsParamJson == null || anlsParamJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(anlsParamJson).path("gradeCriteria");
            return node.isArray()
                    ? objectMapper.convertValue(node, new TypeReference<List<GradeCriterionVO>>() { })
                    : null;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // 손상된 스냅샷은 그 칸만 null 로 내린다(구 스냅샷과 같은 모양) — 보고서 열람은 막지 않는다 (4.2)
            log.warn("보고서 분석 파라미터 해석 실패 — null 로 처리: 사유={}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 생성 당시 분석 계수 적용 내역을 되살린다.
     * 키가 없으면(구 스냅샷) {@code null} 이다 — 빈 내역으로 채우면 "조정 안 함" 으로 읽힌다.
     */
    private Map<String, Object> parseVrblAjmt(String anlsParamJson) {
        if (anlsParamJson == null || anlsParamJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(anlsParamJson).path("vrblAjmt");
            return node.isObject()
                    ? objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() { })
                    : null;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // 손상된 스냅샷은 그 칸만 null 로 내린다(구 스냅샷과 같은 모양) — 보고서 열람은 막지 않는다 (4.2)
            log.warn("보고서 분석 파라미터 해석 실패 — null 로 처리: 사유={}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 저장된 엑셀 재계산 데이터를 되살린다.
     *
     * <p>키가 없으면(구 스냅샷) {@code null} 이다 — 프론트가 Excel 버튼을 끈다. 백필하지 않는다.
     * 파싱 실패도 500 이 아니라 {@code null} + WARN 이다 — 순위 본문은 멀쩡한데 부가 데이터 때문에
     * PDF·Word 까지 못 열게 만들 이유가 없다.
     */
    private HotspotWorkbookVO parseWbk(String rsltJson, Long rptpId) {
        try {
            JsonNode node = objectMapper.readTree(rsltJson).path("wbk");
            return node.isObject() ? objectMapper.convertValue(node, HotspotWorkbookVO.class) : null;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("저장된 엑셀 데이터 역직렬화 실패 (wbk 없이 응답): rptpId={} 사유={}", rptpId, e.getClass().getSimpleName());
            log.debug("저장된 엑셀 데이터 역직렬화 실패 (wbk 없이 응답) 상세", e);   // 4.1 — 예외 원문은 DEBUG 로만
            return null;
        }
    }

    private String parseTextParam(String anlsParamJson, String key) {
        if (anlsParamJson == null || anlsParamJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(anlsParamJson).path(key);
            return node.isTextual() ? node.textValue() : null;
        } catch (JsonProcessingException e) {
            // 손상된 스냅샷은 그 칸만 null 로 내린다 — 보고서 열람은 막지 않는다 (4.2)
            log.warn("보고서 분석 파라미터 해석 실패 — null 로 처리: key={} 사유={}", key, e.getClass().getSimpleName());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 저장 값 조립
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 분석 결과 JSON. {@code {"zones":[{"rnk":1,"zoneNm":…,"cvlcptCnt":…,"crdnCnt":…,
     * "snthsScr":…,"mrkLat":…,"mrkLot":…}, …]}}
     *
     * <p>{@code "wbk"} 키(엑셀 재계산 데이터 {@link HotspotWorkbookVO})를 함께 싣는다.
     * 위 8칸 규칙은 {@code zones} 에만 해당한다.
     *
     * <p>🔴 <b>객체로 감싼다.</b> 배열을 그대로 두면 생활권처럼 순위 8행 + 격자 전량 두 벌이 필요한
     * 분석에서 확장할 자리가 없다. 키 이름은 {@link HotspotZoneVO} 필드명과 같아 조회할 때 변환이 없다.
     *
     * <p>🔴 <b>정확히 8칸만 담는다.</b> {@link HotspotZoneVO} 를 통째로 직렬화하지 않는 이유:
     * <ul>
     *   <li>{@code emdNm}·{@code zoneNmSrc} — 종전 결과 표에 컬럼이 없어 <b>이관된 기존 11건에 그 두
     *       칸이 없다</b>. 새 보고서만 값이 생기면 같은 API 의 왕복 계약이 생성 시점에 따라 갈린다.</li>
     *   <li>{@code gradeNm} — 코드에서 되살린다. 🔁 {@code grade} 는 저장한다 — 순위 비율 등급은
     *       표시 범위 밖 구역을 알아야 판정되므로 스냅샷의 점수만으로 다시 계산할 수 없다.</li>
     * </ul>
     *
     * <p>점수·건수·좌표는 분석 결과를 <b>가공 없이</b> 옮긴다 — 여기서 반올림하거나 다시 계산하면
     * 같은 보고서의 생성 응답(POST)과 스냅샷 조회(GET)가 갈린다. 직렬화 경로도
     * {@link #buildParamJson}(= {@code anls_param_json})과 <b>같은 {@link ObjectMapper}</b> 다.
     */
    private String buildRsltJson(List<HotspotZoneVO> zones, HotspotWorkbookVO wbk) {
        List<Map<String, Object>> rows = new ArrayList<>(zones.size());
        for (HotspotZoneVO zone : zones) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rnk", zone.getRnk());
            row.put("zoneNm", zone.getZoneNm());
            row.put("cvlcptCnt", zone.getCvlcptCnt());
            row.put("crdnCnt", zone.getCrdnCnt());
            row.put("snthsScr", zone.getSnthsScr());
            row.put("grade", zone.getGrade());
            row.put("mrkLat", zone.getMrkLat());
            row.put("mrkLot", zone.getMrkLot());
            rows.add(row);
        }
        Map<String, Object> rslt = new LinkedHashMap<>();
        rslt.put("zones", rows);
        // 엑셀 재계산 데이터. 형식과 무관하게 싣는다(PDF 로 만든 보고서도 엑셀로 다시 받는다).
        //    HotspotWorkbookZoneVO 의 좌표·zoneNmSrc 는 @JsonIgnore 라 저장되지 않는다.
        rslt.put("wbk", wbk);
        try {
            return objectMapper.writeValueAsString(rslt);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "분석 결과 직렬화에 실패했습니다", e);
        }
    }

    /**
     * 저장된 {@code rslt_json} 에서 순위 목록을 되살린다.
     *
     * <p>키가 {@link HotspotZoneVO} 필드명과 같아 <b>매핑 변환이 없다</b>. {@code gradeNm} 과
     * 구 스냅샷의 {@code grade} 는 호출자가 {@link HotspotGrade} 로 채운다.
     *
     * <p>⚠️ 파싱 실패는 <b>500 이 아니라 빈 목록</b>이 아니다 — 여기서 빈 목록을 돌려주면 본문 없는
     * 보고서가 200 으로 나간다. 스냅샷이 깨진 것은 조용히 넘길 상태가 아니므로 500 으로 드러낸다.
     * "결과 자체가 없다" 는 경우는 앞선 {@code rsltCnt == 0} 판정이 이미 404 로 끊었다.
     */
    private List<HotspotZoneVO> parseZones(String rsltJson, Long rptpId) {
        try {
            JsonNode zonesNode = objectMapper.readTree(rsltJson).path("zones");
            if (!zonesNode.isArray()) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "저장된 분석 결과에 zones 배열이 없습니다: rptpId=" + rptpId);
            }
            List<HotspotZoneVO> zones =
                    objectMapper.convertValue(zonesNode, new TypeReference<List<HotspotZoneVO>>() { });
            return zones != null ? zones : new ArrayList<HotspotZoneVO>();
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "저장된 분석 결과 역직렬화에 실패했습니다: rptpId=" + rptpId, e);
        }
    }

    /**
     * 재현 메타 JSON. {@code {"eps":50,"minPoints":3,"limit":15,"weights":[…],"wgvlAjmtYn":"N",
     * "gradeCriteria":[{"grade":"SEVERE","gradeNm":"심각","maxRankPct":5},…],
     * "vrblAjmt":{"ajmtYn":"N","vrbls":[…]},
     * "scoreFormula":"min(CVLCPT_MM/30, 1)*60 + min(CRDN_MM/60, 1)*40"}}
     *
     * <p>🔴 {@code weights} 는 {@code AnalysisWeightVO} 를 <b>그대로</b> 직렬화한다(9필드).
     * 스냅샷 조회(GET)의 가중치 출처가 이 JSON <b>뿐</b>이라, 화면에 보이는 3필드만 골라 저장하면
     * 같은 보고서인데 POST 응답(9필드)과 GET 응답(3필드)의 계약이 갈린다.
     *
     * <p>{@code gradeCriteria} 는 생성 당시 적용한 등급 비율이다 — 설정값이라 저장해 두지 않으면
     * 설정을 바꾸는 순간 과거 문서의 설명이 실제 등급과 어긋난다.
     *
     * <p>{@code vrblAjmt} 는 분석 계수 적용 내역이다. 문서에는 아직 그리지 않지만
     * 저장을 미루면 그 사이 보고서는 복원할 수 없다. {@code gradeCriteria} 도 일시 입력을 반영한 적용값이다.
     *
     * <p>{@code scoreFormula} — 분석 응답의 산식 문자열을 <b>그대로</b> 싣는다.
     *
     * @param limit 요청값 그대로({@code null} = 전량). 결과 개수가 아니다 —
     *              주의(40점) 하한 때문에 요청 수보다 적을 수 있고,
     *              "무엇을 요구했는가" 가 재현에 필요한 값이다
     */
    private String buildParamJson(HotspotAnalysisResultVO result, Integer limit) {
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("eps", result.getEps());
        param.put("minPoints", result.getMinPoints());
        param.put("limit", limit);
        param.put("weights", result.getWeights());
        param.put("wgvlAjmtYn", result.getWgvlAjmtYn());
        param.put("gradeCriteria", result.getGradeCriteria());
        param.put("vrblAjmt", result.getVrblAjmt());
        param.put("scoreFormula", result.getScoreFormula());
        param.put("anlsMthd", ANLS_MTHD_LIST_MAX);
        try {
            return objectMapper.writeValueAsString(param);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "분석 파라미터 직렬화에 실패했습니다", e);
        }
    }

    /**
     * 응답 조립. <b>저장한 값 그대로</b> 돌려준다 — 프론트는 이 응답 하나로 문서를 그린다.
     *
     * <p>{@code HotspotReportVO.of(...)} 정적 팩터리를 두지 않고 여기서 조립하는 이유:
     * VO 는 {@code jeju.datahub.analysis.service}(표현 계층 계약)이고 {@link Rptp}·{@link AnlsExcn}
     * 은 {@code service.impl} 의 JPA 엔티티다. 팩터리를 VO 에 두면 계약 타입이 두 도메인의 영속성
     * 구현에 의존하게 되어, 엔티티가 바뀔 때 API 계약 파일이 함께 흔들린다.
     */
    private HotspotReportVO buildResponse(Rptp rptp, AnlsExcn excn,
                                          HotspotAnalysisResultVO result, HotspotWorkbookVO wbk,
                                          String regionNm) {
        HotspotReportVO vo = new HotspotReportVO();
        vo.setRptpId(rptp.getRptpId());
        vo.setExcnId(excn.getExcnId());
        vo.setRptpNm(rptp.getRptpNm());
        vo.setRegionNm(regionNm);
        vo.setAnlsPrdExpln(rptp.getAnlsPrdExpln());
        vo.setFileFmt(rptp.getFileFmt());
        vo.setCrtYmd(rptp.getCrtYmd());
        vo.setWeights(result.getWeights());
        vo.setWgvlAjmtYn(result.getWgvlAjmtYn());
        vo.setAnlsMthd(ANLS_MTHD_LIST_MAX);
        vo.setGradeCriteria(result.getGradeCriteria());
        vo.setVrblAjmt(result.getVrblAjmt());
        vo.setZones(result.getZones());
        vo.setWbk(wbk);
        return vo;
    }
}
