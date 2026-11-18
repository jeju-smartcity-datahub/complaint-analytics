package jeju.datahub.complaint.service.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
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

import jeju.datahub.analysis.service.HotspotRankingRequest;
import jeju.datahub.analysis.service.HotspotRankingResultVO;
import jeju.datahub.analysis.service.HotspotRankingService;
import jeju.datahub.analysis.service.HotspotRankingZoneVO;
import jeju.datahub.analysis.service.HotspotView;
import jeju.datahub.analysis.service.impl.AnlsExcn;
import jeju.datahub.analysis.service.impl.AnlsExcnRepository;
import jeju.datahub.analysis.service.impl.AnlsRslt;
import jeju.datahub.analysis.service.impl.AnlsRsltRepository;
import jeju.datahub.common.DateRanges;
import jeju.datahub.complaint.service.ComplaintAdmdstNmVO;
import jeju.datahub.complaint.service.ComplaintKpiVO;
import jeju.datahub.complaint.service.ComplaintReportCreateRequest;
import jeju.datahub.complaint.service.ComplaintReportService;
import jeju.datahub.complaint.service.ComplaintReportVO;
import jeju.datahub.complaint.service.ComplaintService;
import jeju.datahub.complaint.service.ComplaintSnapshotVO;
import jeju.datahub.complaint.service.ComplaintUnit;
import jeju.datahub.complaint.service.CrdnCompareVO;
import jeju.datahub.complaint.service.EmdCompareVO;
import jeju.datahub.complaint.service.NegEmdCompareVO;
import jeju.datahub.complaint.service.ShortageCompareVO;
import jeju.datahub.report.service.ReportCreatorName;
import jeju.datahub.report.service.ReportListVO;
import jeju.datahub.report.service.impl.ReportMapper;
import jeju.datahub.report.service.impl.Rptp;
import jeju.datahub.report.service.impl.RptpRepository;
import lombok.RequiredArgsConstructor;

/**
 * 민원현황 보고서 생성 서비스 구현체. 전자정부 {@link EgovAbstractServiceImpl} 상속. (TSK-4164 Step 3)
 *
 * <p>처리 순서는 넷이다 — ①입력 정규화(기간 검증 + 지역 코드 → 이름) → ②같은 조건으로 <b>재집계</b>
 * → ③세 표에 한 벌 저장 → ④저장한 값 그대로 201. 집계 자체는 {@link ComplaintService} 가 이미
 * 하므로 여기서 다시 구현하지 않는다(D2).
 *
 * <h2>🔴 화면이 보낸 숫자를 저장하지 않는다 (D2)</h2>
 * 요청 바디에는 <b>조회 조건과 파일형식만</b> 있고 건수·비율이 없다. 화면이 계산한 값을 굳히면
 * 보고서가 화면의 오류까지 영구 보존하고, 같은 조건을 다시 집계했을 때와 값이 달라져도 아무도 모른다.
 *
 * <h2>🔴 트랜잭션 하나로 세 표를 묶는다 (D1)</h2>
 * 실행 이력만 남고 보고서가 없는 <b>반쪽 상태</b>를 만들지 않는다. 지역 코드 검증(D4)이 저장보다
 * <b>앞</b>에 오는 것도 같은 이유이며, 뒤에서 예외가 나면 세 표가 함께 롤백된다.
 *
 * <h2>🔴 0건 판정 단계가 없다 (D5) — 집중구역과 정책이 다르다</h2>
 * {@code HotspotReportServiceImpl} 은 결과 0건을 <b>400</b> 으로 끊지만 여기는 끊지 <b>않는다</b>.
 * 민원현황 보고서는 핵심 지표 4종이 본체이고 {@code 0건}·{@code 부정 0%} 는 그 자체로 유효한 값이며,
 * 주기별 자동 생성이 붙으면 주기마다 빠짐없이 1건이 쌓여야 목록에 구멍이 나지 않는다.
 * <b>잘못된 요청(없는 지역 코드·역전 구간)만</b> 아무것도 남기지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ComplaintReportServiceImpl extends EgovAbstractServiceImpl implements ComplaintReportService {

    private static final Logger log = LoggerFactory.getLogger(ComplaintReportServiceImpl.class);

    /** 분석유형코드 ({@code ANLS_TYPE_CD}) — 이 값이 목록의 출처 칸을 "민원현황" 으로 만든다 */
    private static final String ANLS_TYPE_CVLCPT = "CVLCPT";

    /** 분석실행상태코드 ({@code EXCN_STTS_CD}) — 저장이 곧 완료다(비동기 실행이 아니다) */
    private static final String EXCN_STTS_DONE = "DONE";

    /** 보고서상태코드 ({@code RPTP_STTS_CD}) — 파일을 굽지 않으므로 생성 즉시 완료다(D6) */
    private static final String RPTP_STTS_DONE = "DONE";

    /**
     * 생성구분 ({@code RPTP_CRT_TYPE_CD}). 🔴 이 값이 보고서 목록의 <b>직접 생성 탭</b>을 정한다 —
     * {@code AUTO} 로 저장하면 자동 생성 탭에 섞여 배포부서·주기 칸이 빈 행으로 나타난다.
     */
    private static final String CRT_TYPE_MANUAL = "MANUAL";

    /**
     * 저장 형식 {@code YYYYMMDD}. STRICT 라 {@code 20260931} 처럼 실재하지 않는 날짜는 파싱에서 걸린다
     * ({@code @Pattern("\\d{8}")} 은 형식만 본다).
     */
    private static final DateTimeFormatter YMD =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    private final ComplaintService complaintService;
    /**
     * 🔴 {@code analysis}·{@code report} 도메인의 JPA 리포지터리를 {@code complaint} 서비스가 주입한다 —
     * 층이 달라(서비스 ↔ 영속성) 순환 참조가 없다. {@code HotspotReportServiceImpl} 이 이미 같은
     * 방향으로 교차 주입한다(D1 — 보고서 3표를 도메인마다 새로 만들지 않는다).
     */
    private final AnlsExcnRepository anlsExcnRepository;
    private final AnlsRsltRepository anlsRsltRepository;
    private final RptpRepository rptpRepository;
    private final ComplaintReportMapper complaintReportMapper;
    /**
     * 스냅샷 조회의 <b>헤더 5칸</b>({@code rptpNm}·{@code regionNm}·{@code anlsPrdExpln}·
     * {@code fileFmt}·{@code crtYmd})용 (Step 4). 🔴 {@code report} 도메인의 매퍼를
     * {@code complaint} 서비스가 주입한다 — 층이 달라(서비스 ↔ 영속성) 순환 참조가 없고,
     * {@code HotspotReportServiceImpl} 이 같은 이유로 같은 매퍼를 주입한다.
     *
     * <p>{@code selectComplaintSnapshot} 이 헤더를 주지 않는 것은 의도다 — {@code regionNm} 의
     * 지역 3단 CASE 와 {@code anls_prd_expln} 은 {@code selectReportById} 가 이미 조립하므로
     * 여기 복제하면 같은 규칙이 두 곳에 살아 조용히 갈린다.
     */
    private final ReportMapper reportMapper;
    /**
     * 보고서 §4·§5 (단속 집중·수요 부족) 재집계용 (TSK-4197 D8). 🔴 화면이 보낸 숫자를 쓰지 않는다 —
     * 조회 화면과 <b>같은 서비스</b>를 같은 조건으로 두 번 더 불러 서버가 다시 집계한다.
     * {@code analysis} 도메인 서비스를 {@code complaint} 서비스가 주입하는 것은 이미
     * 리포지터리·매퍼에서 하고 있는 것과 같은 방향이라 순환 참조가 없다.
     */
    private final HotspotRankingService hotspotRankingService;
    private final ObjectMapper objectMapper;

    /**
     * {@inheritDoc}
     *
     * <p>생성 일시({@code excn_dt}·{@code crt_ymd})는 서버 실시각({@link LocalDateTime#now()})이다 —
     * 목록 최신순({@code crt_ymd DESC})의 정렬 기준이다.
     */
    @Override
    @Transactional
    public ComplaintReportVO createReport(ComplaintReportCreateRequest req, String userId, String userNm) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "요청 본문이 없습니다");
        }

        // ── ① 입력 정규화 — 저장 전에 끊는다 ──────────────────────────────────────
        LocalDate bgng = parseYmd(req.getBgngYmd(), "bgngYmd");
        LocalDate end = parseYmd(req.getEndYmd(), "endYmd");
        // 🔴 역전·366일 상한. @Pattern 은 형식만 보므로 이 호출이 없으면 역순 구간이 "정상 보고서" 로 쌓인다
        //    (D5 가 0건을 201 로 만들기 때문에 조용히 통과한다).
        DateRanges.validate(bgng, end);

        // 빈 문자열은 "지정 안 함" 으로 접는다 — 그대로 두면 집계 필터가 빈 값으로 걸려 0행이 된다.
        String sggCd = trimToNull(req.getSggCd());
        String emdCd = trimToNull(req.getEmdCd());
        req.setSggCd(sggCd);
        req.setEmdCd(emdCd);
        req.setCvlcptTypeCd(trimToNull(req.getCvlcptTypeCd()));

        Region region = resolveRegion(sggCd, emdCd);
        String regionNm = ComplaintReportNaming.regionNm(region.sggNm(), region.emdNm());

        // ── ② 같은 조건으로 다시 집계한다 (D2) ────────────────────────────────────
        // 🔴 파싱·검증이 끝난 날짜를 넘긴다 — toComplaintRequest 는 파싱하지 않는다.
        ComplaintKpiVO kpi = complaintService.getKpi(req.toComplaintRequest(bgng, end));
        List<EmdCompareVO> emdCompare = kpi.emdCompare() != null ? kpi.emdCompare() : new ArrayList<>();
        List<NegEmdCompareVO> negEmdCompare =
                kpi.negEmdCompare() != null ? kpi.negEmdCompare() : new ArrayList<>();
        // 🆕 TSK-4311 — 전기 기간·건수. §3~§5 증감률과 같은 "직전 동일 길이 구간" 식이다.
        // 🔴 emdCompare 의 prevCnt 합으로 구하지 않는다 — 이번 기간 0건인 동이 빠져 더 작게 나온다.
        LocalDate prevBgng = ComplaintUnit.prevStart(bgng, end);
        LocalDate prevEnd = bgng.minusDays(1);
        long prevTotalCount = complaintService.getTotalCount(req.toComplaintRequest(prevBgng, prevEnd));
        String prevAnlsPrdExpln =
                ComplaintReportNaming.anlsPrdExpln(prevBgng.format(YMD), prevEnd.format(YMD));
        ComplaintReportVO.Kpi reportKpi = new ComplaintReportVO.Kpi(
                kpi.totalCount(), kpi.negativityRatio(), kpi.sentimentDist(),
                kpi.enforcementCount(), kpi.shortageCount(),
                prevAnlsPrdExpln, prevTotalCount);
        // 🔴 0건 판정이 없다 — totalCount == 0 이어도 그대로 저장한다(D5).

        // 🔴 §4·§5 도 화면이 보낸 숫자를 쓰지 않는다 — 같은 조건으로 서버가 다시 집계한다
        //    (TSK-4197 D8 · TSK-4164 D2). 요청 바디는 바뀌지 않는다.
        List<CrdnCompareVO> crdnCompare = toCrdnCompare(
                rankZones(req, bgng, end, HotspotView.ENFORCEMENT));
        List<ShortageCompareVO> shortageCompare = toShortageCompare(
                rankZones(req, bgng, end, HotspotView.SHORTAGE));

        String bgngYmd = bgng.format(YMD);
        String endYmd = end.format(YMD);

        // ── ③ 3표 저장 — 한 트랜잭션 (D1) ────────────────────────────────────────
        LocalDateTime now = LocalDateTime.now();
        AnlsExcn excn = new AnlsExcn();
        excn.setAnlsTypeCd(ANLS_TYPE_CVLCPT);
        excn.setExcutrId(userId);                   // 로그인 사용자 — 해석·401 은 컨트롤러 책임
        // 🔴 저장되는 지역은 emdCd 조인 결과가 우선한다(D4) — 집계 필터는 sggCd·emdCd 를 AND 로
        //    좁히지만 이름표는 읍면동이 실제로 속한 시군구가 정답이다. 요청한 sggCd 는 아래
        //    anls_param_json 에 그대로 남는다.
        excn.setSggNm(region.sggNm());
        excn.setEmdCd(emdCd);
        excn.setAnlsBgngYmd(bgngYmd);
        excn.setAnlsEndYmd(endYmd);
        excn.setAnlsParamJson(buildParamJson(sggCd, emdCd, req.getCvlcptTypeCd()));
        excn.setExcnSttsCd(EXCN_STTS_DONE);
        excn.setExcnDt(now);
        anlsExcnRepository.save(excn);
        // 🔴 결과·보고서 행이 excn_id 를 참조하므로 여기서 확정한다(identity 채번은 flush 시점).
        anlsExcnRepository.flush();

        AnlsRslt rslt = new AnlsRslt();
        rslt.setExcnId(excn.getExcnId());
        rslt.setRsltJson(buildRsltJson(reportKpi, emdCompare, negEmdCompare,
                crdnCompare, shortageCompare));
        anlsRsltRepository.save(rslt);

        Rptp rptp = new Rptp();
        rptp.setCrtType(CRT_TYPE_MANUAL);
        rptp.setExcnId(excn.getExcnId());
        rptp.setRptpNm(ComplaintReportNaming.rptpNm(regionNm));
        rptp.setAnlsPrdExpln(ComplaintReportNaming.anlsPrdExpln(bgngYmd, endYmd));
        rptp.setSttsCd(RPTP_STTS_DONE);
        rptp.setFileFmt(req.getFileFmt());
        // filePath 는 채우지 않는다 — 파일을 서버에 굽지 않고 프론트가 응답으로 문서를 그린다(D6).
        // stngId·trgtPrdCd·crltnDeptCd·crltnCyclCd 도 자동 생성 전용 칸이라 null 로 둔다.
        rptp.setCreatrId(userId);
        rptp.setCreatrNm(ReportCreatorName.cut(userNm));   // 🔴 creatr_nm 은 VARCHAR(50)
        rptp.setCrtYmd(now);
        rptpRepository.save(rptp);
        rptpRepository.flush();

        log.info("민원현황 보고서 생성: rptpId={} excnId={} 지역={} 기간={}~{} 민원={}건 형식={}",
                rptp.getRptpId(), excn.getExcnId(), regionNm, bgngYmd, endYmd,
                reportKpi.totalCount(), req.getFileFmt());

        // ── ④ 응답 조립 — 저장한 값 그대로 (프론트는 이 응답 하나로 문서를 그린다) ──
        ComplaintReportVO vo = new ComplaintReportVO();
        vo.setRptpId(rptp.getRptpId());
        vo.setExcnId(excn.getExcnId());
        vo.setRptpNm(rptp.getRptpNm());
        vo.setRegionNm(regionNm);
        vo.setAnlsPrdExpln(rptp.getAnlsPrdExpln());
        vo.setFileFmt(rptp.getFileFmt());
        vo.setCrtYmd(rptp.getCrtYmd());
        vo.setKpi(reportKpi);
        vo.setEmdCompare(emdCompare);
        vo.setNegEmdCompare(negEmdCompare);
        vo.setCrdnCompare(crdnCompare);
        vo.setShortageCompare(shortageCompare);
        return vo;
    }

    /**
     * {@inheritDoc}
     *
     * <p>순서가 중요하다 — <b>메타 → 404 판정 3종 → 헤더 5칸 → 본문 역직렬화</b>.
     * 판정을 뒤로 미루면 열 수 없는 보고서를 위해 JOIN 6개짜리 헤더 조회를 먼저 치게 된다
     * ({@code HotspotReportServiceImpl#selectReportSnapshot} 과 같은 순서).
     *
     * <h2>🔴 "0건" 은 404 사유가 아니다 (D5)</h2>
     * 집중구역은 {@code zones} 가 0행이면 404 지만 여기는 <b>200</b> 이다 —
     * {@code emdCompare=[]}·{@code totalCount=0} 은 저장된 정상 값이고, 그 규칙을 옮기면
     * 0건 보고서가 목록에는 남고 영영 열리지 않는다. 404 는 <b>행 자체가 없을 때</b>만이다.
     */
    @Override
    @Transactional(readOnly = true)
    public ComplaintReportVO selectReportSnapshot(Long rptpId) {
        // ── ① 메타 + 본문 원문 — 404 판정 3종을 한 쿼리로 ────────────────────────────
        ComplaintSnapshotVO snapshot = complaintReportMapper.selectComplaintSnapshot(rptpId);
        if (snapshot == null) {
            // 404-①: 보고서 자체가 없다.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서를 찾을 수 없습니다: " + rptpId);
        }
        if (!ANLS_TYPE_CVLCPT.equals(snapshot.getAnlsTypeCd())) {
            // 404-②: 다른 유형(또는 실행 미연결 — anlsTypeCd 가 null)이다. 이 경로로 열 수 있는 것은
            //        민원현황뿐이다. 400 이 아니라 404 인 이유: 요청 자체는 정상이고 "그 경로에 그 자원이 없다".
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "민원현황 보고서가 아닙니다: " + rptpId
                            + " (analysisType=" + snapshot.getAnlsTypeCd() + ")");
        }
        String rsltJson = snapshot.getRsltJson();
        if (rsltJson == null || rsltJson.isBlank()) {
            // 404-③: 유형은 맞지만 TB_ANLS_RSLT 행이 없다(시드 자동 생성 보고서 13건이 그렇다 — D7).
            //        🔴 "배열이 비었다" 와 혼동하지 않는다 — 빈 배열은 정상이고 200 이다(D5).
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "보고서에 저장된 분석 결과가 없습니다: " + rptpId);
        }

        // ── ② 헤더 5칸은 기존 단건 조회를 재사용한다 ────────────────────────────────
        // 🔴 지역 3단 CASE·기간 표기를 새 SQL 에 복제하지 않는다 — 규칙이 두 곳에 살면 조용히 갈린다.
        //    위 스냅샷 조회가 행을 찾았으므로 여기서 null 이 올 일은 없다(동시 삭제 방어).
        ReportListVO header = reportMapper.selectReportById(rptpId);
        if (header == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보고서를 찾을 수 없습니다: " + rptpId);
        }

        // ── ③ 본문 — rslt_json 한 칸에서 키 5종을 되살린다 (D9) ─────────────────────
        // 키가 조회 응답 필드명 그대로라 변환이 없다. 저장한 그대로가 곧 POST 201 과 같은 모양이다.
        ComplaintReportVO vo = new ComplaintReportVO();
        vo.setRptpId(header.getRptpId());       // path 변수로도 알 수 있지만 집중구역과 같게 헤더를 쓴다
        vo.setExcnId(snapshot.getExcnId());
        vo.setRptpNm(header.getRptpNm());
        vo.setRegionNm(header.getRegionNm());
        vo.setAnlsPrdExpln(header.getAnlsPrdExpln());
        // 🔴 fileFmt 를 빠뜨리기 쉽다 — 출처가 selectReportById 뿐이라 빠지면 스냅샷 GET 의
        //    이 칸만 null 이 되어 POST 응답과 모양이 갈린다(화면이 PDF/DOCX 를 못 가린다).
        vo.setFileFmt(header.getFileFmt());
        vo.setCrtYmd(header.getCrtYmd());

        JsonNode root = parseRsltJson(rsltJson, rptpId);
        vo.setKpi(convertKpi(root.path("kpi"), rptpId));
        // 🔴 배열이 비거나 키가 없어도 빈 목록으로 200 이다 — 0건 보고서가 정상이기 때문이다(D5).
        vo.setEmdCompare(convertList(root.path("emdCompare"),
                new TypeReference<List<EmdCompareVO>>() { }, "emdCompare", rptpId));
        vo.setNegEmdCompare(convertList(root.path("negEmdCompare"),
                new TypeReference<List<NegEmdCompareVO>>() { }, "negEmdCompare", rptpId));
        // 🔴 2026-09-30 이전 보고서에는 이 두 키가 아예 없다 — convertList 가 키 부재를 빈 목록으로
        //    돌려 200 을 낸다(TSK-4197 D9). 예외를 던지면 옛 보고서가 전부 500 이 된다.
        //    접근은 위 세 줄과 같은 root.path(...) 로 통일한다(일관성 — get() 도 방어되긴 한다).
        vo.setCrdnCompare(convertList(root.path("crdnCompare"),
                new TypeReference<List<CrdnCompareVO>>() { }, "crdnCompare", rptpId));
        vo.setShortageCompare(convertList(root.path("shortageCompare"),
                new TypeReference<List<ShortageCompareVO>>() { }, "shortageCompare", rptpId));
        return vo;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // ① 입력 정규화
    // ─────────────────────────────────────────────────────────────────────────────

    /** {@code YYYYMMDD} 파싱. 형식 오류·실재하지 않는 날짜는 <b>400</b> 이다(SQL 로 흘려보내지 않는다). */
    private static LocalDate parseYmd(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 는 필수입니다 (YYYYMMDD)");
        }
        try {
            return LocalDate.parse(value.trim(), YMD);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 는 YYYYMMDD 형식의 실재하는 날짜여야 합니다: " + value);
        }
    }

    /**
     * 지역 코드 → 지역명 (D4). 세 갈래다.
     *
     * <table border="1">
     *   <caption>지역 정규화</caption>
     *   <tr><th>요청</th><th>결과</th></tr>
     *   <tr><td>{@code emdCd} 있음</td><td>{@code emd_cd} 조인의 {@code sgg_nm}+{@code emd_nm}
     *       (🔴 {@code sggCd} 가 함께 와도 <b>조인 결과가 이름표를 정한다</b>)</td></tr>
     *   <tr><td>{@code sggCd} 만</td><td>{@code LEFT(emd_cd,5)} 대조로 얻은 {@code sgg_nm}</td></tr>
     *   <tr><td>둘 다 없음</td><td>{@code (null, null)} → 전체 지역</td></tr>
     * </table>
     *
     * <p>없는 코드는 데이터가 아니라 <b>잘못된 요청</b>이라 저장 앞에서 400 으로 끊는다(D5 예외).
     */
    private Region resolveRegion(String sggCd, String emdCd) {
        if (emdCd != null) {
            ComplaintAdmdstNmVO admdst = complaintReportMapper.selectAdmdstNmByEmdCd(emdCd);
            if (admdst == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "존재하지 않는 읍면동코드입니다: " + emdCd);
            }
            // 🔴 sgg_nm 을 함께 받는다 — emd_nm 만 있고 sgg_nm 이 없으면 ComplaintReportNaming 이 끊는다.
            return new Region(admdst.getSggNm(), admdst.getEmdNm());
        }
        if (sggCd != null) {
            String sggNm = complaintReportMapper.selectSggNmBySggCd(sggCd);
            if (sggNm == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "존재하지 않는 시군구코드입니다: " + sggCd);
            }
            return new Region(sggNm, null);
        }
        return new Region(null, null);
    }

    /** 정규화된 지역명 한 벌. {@code emdNm} 이 있으면 {@code sggNm} 도 반드시 있다(D4). */
    private record Region(String sggNm, String emdNm) {
    }

    /** {@code null}·빈 문자열·공백만 있는 값을 모두 "지정 안 함"으로 모은다. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // ③ 저장 값 조립
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 재현 메타 JSON ({@code anls_param_json}).
     * {@code {"sggCd":"50110","emdCd":null,"cvlcptTypeCd":null}}
     *
     * <p>"이 보고서가 어떤 조건으로 나왔나" 를 묻는 <b>유일한 근거</b>다. {@code TB_ANLS_EXCN} 이
     * 저장하는 것은 {@code sgg_nm}(이름)·{@code emd_cd} 뿐이라, 요청한 {@code sggCd} 와
     * {@code cvlcptTypeCd} 는 여기 말고 남을 자리가 없다 — 특히 {@code sggCd} 와 {@code emdCd} 가
     * 함께 온 요청에서는 이름표가 {@code emdCd} 조인 결과를 따르므로(D4) 요청한 {@code sggCd} 의
     * 흔적이 이 JSON 에만 남는다.
     *
     * <p>키를 <b>값이 null 이어도 싣는다</b> — 나중에 "조건이 없었다" 와 "그 시절엔 그 칸이 없었다"
     * 를 구분할 수 있어야 한다.
     */
    private String buildParamJson(String sggCd, String emdCd, String cvlcptTypeCd) {
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("sggCd", sggCd);
        param.put("emdCd", emdCd);
        param.put("cvlcptTypeCd", cvlcptTypeCd);
        try {
            return objectMapper.writeValueAsString(param);
        } catch (JsonProcessingException e) {
            // 🔴 삼키지 않는다 — 조용히 넘기면 재현 근거가 없는 보고서가 정상처럼 쌓인다.
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "분석 파라미터 직렬화에 실패했습니다", e);
        }
    }

    /**
     * 같은 조건으로 분석레이어 순위를 <b>다시 집계</b>한다 (TSK-4197 D8).
     *
     * <p>🔴 기간은 <b>절대 구간</b>({@code startDt}/{@code endDt})으로 넘긴다 —
     * {@code HotspotRankingRequest.hasExplicitRange()} 가 참이어야 {@code unit}
     * ({@code YESTERDAY} 기본값) 로 폴백하지 않는다. 보고서는 요청받은 구간을 집계해야 한다.
     *
     * <p>⚠️ {@code cvlcptTypeCd} 도 그대로 넘긴다 — 조회 화면과 같은 필터가 걸려야 같은 표가 나온다.
     * {@code sggCd}·{@code emdCd} 는 {@code createReport} 가 이미 정규화해 넣어 둔 값이다.
     */
    private List<HotspotRankingZoneVO> rankZones(ComplaintReportCreateRequest req,
                                                 LocalDate bgng, LocalDate end,
                                                 HotspotView view) {
        HotspotRankingRequest rankingReq = new HotspotRankingRequest();
        rankingReq.setView(view.queryValue());
        rankingReq.setStartDt(bgng);
        rankingReq.setEndDt(end);
        rankingReq.setSggCd(req.getSggCd());
        rankingReq.setEmdCd(req.getEmdCd());
        rankingReq.setCvlcptTypeCd(req.getCvlcptTypeCd());
        HotspotRankingResultVO result = hotspotRankingService.rank(rankingReq);
        if (result == null || result.getZones() == null) {
            return new ArrayList<>();
        }
        return result.getZones();
    }

    /**
     * {@code view=enforcement} zone → 경량 {@link CrdnCompareVO} (D7).
     *
     * <p>🔴 {@code bndryGeoJson}(수 MB)·마커 좌표·{@code typeCnts} 를 <b>복사하지 않는다</b> —
     * 이 목록은 {@code rslt_json} 한 칸에 통째로 굳으므로 경계를 담으면 보고서 1건이 수 MB 가 된다.
     * <p>🔴 {@code zoneNm} → {@code emdNm} 으로 이름이 바뀐다(같은 응답의 다른 두 배열과 맞춘다).
     */
    private static List<CrdnCompareVO> toCrdnCompare(List<HotspotRankingZoneVO> zones) {
        List<CrdnCompareVO> list = new ArrayList<>(zones.size());
        for (HotspotRankingZoneVO z : zones) {
            list.add(new CrdnCompareVO(
                    z.getRank(), z.getEmdCd(), z.getZoneNm(),
                    z.getCrdnCnt(), z.getCvlcptCnt(),
                    z.getDeltaPct(), z.isDeltaPctInferred(), z.getPrevCrdnCnt(),
                    z.getTopCrdnMthd(), z.getTopCrdnMthdRatio()));
        }
        return list;
    }

    /**
     * {@code view=shortage} zone → 경량 {@link ShortageCompareVO} (D7).
     *
     * <p>🔴 {@code bndryGeoJson} 을 복사하지 않는다 — {@code HotspotRankingServiceImpl} 이
     * shortage 경계를 채워 주지만 여기서 <b>버린다</b>(D7 은 "저장·응답" 을 막는 것이고
     * 조회 비용 최적화는 이번 범위가 아니다).
     * <p>⚠️ {@code shortageRatio} 는 0~1 소수 4자리이고 {@code shortageDeltaPp} 는 이미 %p 단위다 —
     * 여기서 단위를 바꾸지 않고 그대로 옮긴다({@link ShortageCompareVO} 참고).
     */
    private static List<ShortageCompareVO> toShortageCompare(List<HotspotRankingZoneVO> zones) {
        List<ShortageCompareVO> list = new ArrayList<>(zones.size());
        for (HotspotRankingZoneVO z : zones) {
            list.add(new ShortageCompareVO(
                    z.getRank(), z.getEmdCd(), z.getZoneNm(),
                    z.getShortageRatio(), z.getPkltCpctySum(), z.getDemandIndex(),
                    z.getShortageDeltaPp(), z.isDeltaPctInferred(), z.getPrevShortageRatio()));
        }
        return list;
    }

    /**
     * 분석 결과 JSON ({@code rslt_json}) — <b>키 5종</b> {@code kpi}·{@code emdCompare}·
     * {@code negEmdCompare}·{@code crdnCompare}·{@code shortageCompare}
     * (D9 · 뒤 둘은 🆕 TSK-4197).
     *
     * <p>키 이름은 {@code GET /api/complaints/gis} 응답 필드명 <b>그대로</b>이고 값도 조회 응답 객체를
     * 그대로 직렬화한다 — 스냅샷 조회(Step 4)에서 변환이 없다.
     *
     * <p>🔴 <b>0건이어도 키 5종을 모두 넣는다</b>(빈 배열 {@code []}). 키를 빠뜨리면 스냅샷 파서가
     * {@code undefined.map} 으로 죽는다 — <b>빈 배열과 키 없음은 다르다</b>(D5).
     *
     * <p>⚠️ {@code lastSyncDt} 는 담지 않는다 — 조회 조건과 무관한 전 행 {@code MAX(load_dt)} 라
     * 스냅샷의 일부가 아니다(D9).
     */
    private String buildRsltJson(ComplaintReportVO.Kpi kpi,
                                 List<EmdCompareVO> emdCompare,
                                 List<NegEmdCompareVO> negEmdCompare,
                                 List<CrdnCompareVO> crdnCompare,
                                 List<ShortageCompareVO> shortageCompare) {
        Map<String, Object> rslt = new LinkedHashMap<>();
        rslt.put("kpi", kpi);
        rslt.put("emdCompare", emdCompare);
        rslt.put("negEmdCompare", negEmdCompare);
        // 🆕 TSK-4197 — 보고서 §4·§5. 🔴 전량 저장한다(자르는 것은 프론트 템플릿 — D6).
        //    🔴 0건이어도 빈 배열로 담는다(D5 — 키 없음과 빈 배열은 다르다).
        rslt.put("crdnCompare", crdnCompare);
        rslt.put("shortageCompare", shortageCompare);
        try {
            return objectMapper.writeValueAsString(rslt);
        } catch (JsonProcessingException e) {
            // 🔴 삼키지 않는다 — rslt_json 이 비면 스냅샷 GET 이 영구 404 다.
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "분석 결과 직렬화에 실패했습니다", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // ④ 스냅샷 복원 (Step 4) — 저장 JSON 3종을 응답 모양으로 되살린다
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * {@code rslt_json} 원문을 트리로 읽는다.
     *
     * <p>🔴 파싱 실패는 <b>404</b> 다(500 이 아니다) — 계획서의 404 판정 3종 중 "저장된 결과가 없다"
     * 에 <b>역직렬화 실패</b>가 함께 묶여 있다. 읽을 수 없는 본문은 보고서로서 존재하지 않는 것과
     * 같고, 500 으로 내면 화면이 "일시 장애" 로 읽어 계속 다시 시도한다.
     */
    private JsonNode parseRsltJson(String rsltJson, Long rptpId) {
        try {
            JsonNode root = objectMapper.readTree(rsltJson);
            if (root == null || !root.isObject()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "보고서에 저장된 분석 결과가 없습니다: " + rptpId);
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "보고서에 저장된 분석 결과를 읽을 수 없습니다: " + rptpId, e);
        }
    }

    /**
     * {@code rslt_json.kpi} → {@link ComplaintReportVO.Kpi} (5칸 record).
     *
     * <p>🔴 {@code kpi} 는 <b>0건이어도 반드시 있다</b>({@code totalCount=0}) — 없다면 저장 경로가
     * 아닌 무언가가 쓴 행이므로 404 로 끊는다. 빈 배열({@code emdCompare=[]})과는 다른 상황이다.
     */
    private ComplaintReportVO.Kpi convertKpi(JsonNode kpiNode, Long rptpId) {
        if (kpiNode == null || !kpiNode.isObject()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "보고서에 저장된 분석 결과에 kpi 가 없습니다: " + rptpId);
        }
        try {
            return objectMapper.convertValue(kpiNode, ComplaintReportVO.Kpi.class);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "보고서에 저장된 분석 결과를 읽을 수 없습니다: " + rptpId, e);
        }
    }

    /**
     * {@code rslt_json.emdCompare}·{@code negEmdCompare} → 조회 응답과 <b>같은 타입</b>의 목록
     * ({@link EmdCompareVO}·{@link NegEmdCompareVO} — D9 로 키가 같아 변환이 없다).
     *
     * <p>🔴 <b>비었거나 키가 없어도 404 가 아니다</b>(D5) — 빈 목록으로 200 을 낸다. 집중구역의
     * {@code 0행 → 404} 를 여기 옮기면 0건 보고서가 영영 열리지 않는다. 배열이 <b>아닌</b> 값이
     * 들어 있는 것만 읽을 수 없는 본문으로 보고 404 다.
     */
    private <T> List<T> convertList(JsonNode arrayNode, TypeReference<List<T>> type,
                                    String key, Long rptpId) {
        if (arrayNode == null || arrayNode.isMissingNode() || arrayNode.isNull()) {
            // 키가 없는 옛 보고서도 열리게 둔다 — 저장 경로는 0건에도 키를 넣지만(D5) 방어로 남긴다.
            return new ArrayList<>();
        }
        if (!arrayNode.isArray()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "보고서에 저장된 분석 결과의 " + key + " 가 배열이 아닙니다: " + rptpId);
        }
        try {
            List<T> list = objectMapper.convertValue(arrayNode, type);
            return list != null ? list : new ArrayList<>();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "보고서에 저장된 분석 결과를 읽을 수 없습니다: " + rptpId + " (" + key + ")", e);
        }
    }
}
