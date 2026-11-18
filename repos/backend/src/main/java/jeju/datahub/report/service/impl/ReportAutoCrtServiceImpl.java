package jeju.datahub.report.service.impl;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.analysis.service.HotspotReportCreateRequest;
import jeju.datahub.analysis.service.HotspotReportService;
import jeju.datahub.analysis.service.HotspotReportVO;
import jeju.datahub.complaint.service.ComplaintReportCreateRequest;
import jeju.datahub.complaint.service.ComplaintReportService;
import jeju.datahub.complaint.service.ComplaintReportVO;
import jeju.datahub.report.service.ReportAutoCrtRequest;
import jeju.datahub.report.service.ReportAutoCrtService;
import jeju.datahub.report.service.ReportAutoCrtVO;
import jeju.datahub.report.service.ReportCreatorName;
import jeju.datahub.report.service.ReportSettingVO;
import lombok.extern.slf4j.Slf4j;

/**
 * 보고서 자동 생성 서비스 구현체. (TSK-4027 Step 4)
 *
 * <h2>처리 순서 (계획서 4-1)</h2>
 *
 * <pre>
 * ① 설정 조회·검증   stngId 로 1행. 없거나 del_dt 가 차 있으면 400.
 *                     요청의 rptpTypeCd·crltnCyclCd 가 설정과 다르면 400
 *                     (배치가 낡은 값을 들고 있다 — 그 사이 설정이 수정됐다)
 * ② 멱등 확인        (stng_id, trgt_prd_cd) 로 이미 있는 보고서를 찾는다.
 *                     있으면 그 ID 로 created=false — 생성 경로를 아예 타지 않는다 (D5)
 * ③ 지역 변환        CVLCPT  : sggNm → sggCd (LEFT(emd_cd,5)), emdCd 는 그대로
 *                     HOTSPOT : sggNm·emdCd 를 그대로 (요청 계약이 이름 기반)      (D10)
 * ④ 유형 분기·생성   기존 서비스를 부른다 — 집계를 여기 복제하지 않는다            (D1)
 *                     생성자 = 설정 등록자(stng.creatr_id·creatr_nm)          (TSK-4257 D10)
 * ⑤ AUTO 표식 덮기   crt_type='AUTO' · stng_id · trgt_prd_cd · crltn_dept_cd ·
 *                     crltn_cycl_cd + rptp_nm(ReportAutoCrtNaming 규칙)            (D4)
 * ⑥ last_crt_dt      갱신하지 않는다 — 배치가 한다                                 (D9)
 * </pre>
 *
 * <h2>🔴 ⑤ 가 이 클래스의 존재 이유다</h2>
 *
 * <p>기존 생성 서비스는 {@code crt_type='MANUAL'} 을 <b>하드코딩</b>하고 자동 생성 4칸을 명시적으로
 * null 로 둔다. 그대로 부르면 자동 생성분이 화면의 <b>직접 생성 탭</b>에 배포부서·주기 칸이 빈 행으로
 * 나타난다. 그래서 생성 <b>직후 같은 트랜잭션 안에서</b> {@link Rptp} 엔티티를 다시 읽어 네 칸과
 * 이름을 덮어쓴다 — JPA 변경 감지가 UPDATE 한 번으로 처리하고, 커밋이 하나라 {@code MANUAL} 중간
 * 상태가 밖에서 보이지 않는다.
 *
 * <p>🔴 <b>기존 서비스의 시그니처를 바꾸지 않는다</b>(계획서 4-2 의 채택안). {@code crtType}
 * 파라미터를 추가하는 쪽은 수동 생성 API 5곳의 계약·테스트를 함께 흔든다.
 *
 * <h2>🔴 트랜잭션을 왜 {@code @Transactional} 이 아니라 {@link TransactionTemplate} 로 여는가</h2>
 *
 * <p>요구는 둘이고 서로 부딪힌다 — ③④⑤ 는 <b>한 트랜잭션</b>이어야 하고(⑤ 가 분리되면 MANUAL 로
 * 커밋된 행이 잠깐 보인다), 동시에 ⑤ 의 UPDATE 가 {@code uq_rptp_stng_prd} 를 위반하면
 * ({@code ②} 와 {@code ⑤} 사이에 다른 실행이 끼어든 경우) <b>500 이 아니라 200 {@code created=false}</b>
 * 여야 한다(D5 — 500 이면 배치가 일시 실패로 오해해 다음 실행에 또 시도한다).
 *
 * <p>🔴 이 메서드에 {@code @Transactional} 을 걸고 <b>그 안에서</b>
 * {@link DataIntegrityViolationException} 을 잡으면 접히지 않는다 — 제약 위반이 나는 순간 Hibernate
 * 가 트랜잭션을 <b>rollback-only</b> 로 표시하므로, 예외를 삼키고 정상 반환해도 커밋 시점에
 * {@link UnexpectedRollbackException} 이 터져 결국 500 이 된다. 그래서 트랜잭션 경계를
 * {@code ③④⑤} 로 좁히고(그 안은 여전히 한 트랜잭션이다 — 기존 생성 서비스의
 * {@code @Transactional} 도 여기에 <b>참여</b>한다), 충돌 처리는 <b>롤백이 끝난 뒤</b> 바깥에서 한다.
 * 목적("한 커밋" + "충돌은 200")은 그대로 지키면서 이 함정만 피하는 형태다.
 *
 * <h2>🔴 0건 정책은 중화하지 않는다 (D7)</h2>
 *
 * <p>{@code CVLCPT} 는 0건도 정식 보고서를 남기고 {@code HOTSPOT} 은 0건이면 400 을 던진다.
 * 그 400 을 여기서 삼키면 배치가 "성공" 으로 세어 스킵 사유가 사라진다 — <b>그대로 전파</b>한다.
 */
@Slf4j
@Service
public class ReportAutoCrtServiceImpl extends EgovAbstractServiceImpl implements ReportAutoCrtService {

    /** 보고서종류 — 민원현황. 기존 {@code ComplaintReportService} 로 간다. */
    private static final String TYPE_CVLCPT = "CVLCPT";

    /** 보고서종류 — 집중구역분석. 기존 {@code HotspotReportService} 로 간다. */
    private static final String TYPE_HOTSPOT = "HOTSPOT";

    /** 생성구분 — 자동 생성. 이 값이 보고서 목록의 `자동 생성 보고서` 탭에 뜨게 한다. */
    private static final String CRT_TYPE_AUTO = "AUTO";

    /** {@code YYYYMMDD} 파싱 형식 — 요청 VO 의 {@code @Pattern("\\d{8}")} 과 같은 모양이다. */
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("uuuuMMdd");

    private final ReportMapper reportMapper;
    private final RptpRepository rptpRepository;

    /**
     * 🔴 <b>유형 분기의 한쪽</b>. 집계를 복제하지 않고 이 서비스를 그대로 부른다(D1) —
     * 민원 5섹션 집계 규칙이 두 곳으로 갈리는 것을 막는다.
     */
    private final ComplaintReportService complaintReportService;

    /** 🔴 <b>유형 분기의 다른 쪽</b>. DBSCAN 군집화를 복제하지 않고 이 서비스를 그대로 부른다(D1). */
    private final HotspotReportService hotspotReportService;

    /** ③④⑤ 를 한 트랜잭션으로 묶는다 — 클래스 javadoc 의 "왜 {@code @Transactional} 이 아닌가" 참고. */
    private final TransactionTemplate transactionTemplate;

    public ReportAutoCrtServiceImpl(ReportMapper reportMapper,
                                    RptpRepository rptpRepository,
                                    ComplaintReportService complaintReportService,
                                    HotspotReportService hotspotReportService,
                                    PlatformTransactionManager transactionManager) {
        this.reportMapper = reportMapper;
        this.rptpRepository = rptpRepository;
        this.complaintReportService = complaintReportService;
        this.hotspotReportService = hotspotReportService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** {@inheritDoc} */
    @Override
    public ReportAutoCrtVO createReport(ReportAutoCrtRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "요청 본문이 없습니다");
        }

        // ── ① 설정 조회·검증 ────────────────────────────────────────────────────
        // selectSettingById 는 del_dt 조건을 걸지 않는다 — "없음" 과 "이미 삭제됨" 을 여기서 가른다.
        ReportSettingVO stng = reportMapper.selectSettingById(req.getStngId());
        if (stng == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "존재하지 않는 자동 생성 설정입니다: " + req.getStngId());
        }
        if (stng.getDelDt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "삭제된 자동 생성 설정입니다: " + req.getStngId());
        }

        String rptpTypeCd = trimToNull(req.getRptpTypeCd());
        String crltnCyclCd = trimToNull(req.getCrltnCyclCd());
        // 🔴 배치가 낡은 값을 들고 있는 경우를 여기서 끊는다 — 설정이 그 사이 수정됐다는 뜻이라,
        //    그대로 만들면 "설정과 다른 보고서" 가 그 설정의 이름으로 적재된다.
        if (!Objects.equals(rptpTypeCd, trimToNull(stng.getRptpTypeCd()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "요청의 rptpTypeCd 가 설정과 다릅니다 (요청=" + rptpTypeCd
                            + ", 설정=" + stng.getRptpTypeCd() + ")");
        }
        if (!Objects.equals(crltnCyclCd, trimToNull(stng.getCrltnCyclCd()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "요청의 crltnCyclCd 가 설정과 다릅니다 (요청=" + crltnCyclCd
                            + ", 설정=" + stng.getCrltnCyclCd() + ")");
        }
        if (!TYPE_CVLCPT.equals(rptpTypeCd) && !TYPE_HOTSPOT.equals(rptpTypeCd)) {
            // 자동 생성 대상은 2종뿐이다 (ANLS_TYPE_CD 의 addl_atrb1 IN ('RPTP','BOTH')).
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "자동 생성할 수 없는 보고서 종류입니다: " + rptpTypeCd);
        }

        String trgtPrdCd = trimToNull(req.getTrgtPrdCd());
        LocalDate bgng = parseYmd(req.getBgngYmd(), "bgngYmd");
        LocalDate end = parseYmd(req.getEndYmd(), "endYmd");

        // ── ② 멱등 확인 — 빠른 경로 (최종 보증은 uq_rptp_stng_prd 다, D5) ───────
        ReportAutoCrtVO existing = findExisting(req.getStngId(), trgtPrdCd);
        if (existing != null) {
            log.info("보고서 자동 생성 건너뜀(이미 있음): stngId={} trgtPrdCd={} rptpId={}",
                    req.getStngId(), trgtPrdCd, existing.getRptpId());
            return existing;
        }

        // ── ③ 지역 변환 (D10) ───────────────────────────────────────────────────
        String sggNm = trimToNull(req.getSggNm());
        String emdCd = trimToNull(req.getEmdCd());
        Region region = resolveRegion(rptpTypeCd, sggNm, emdCd);

        // 보고서명은 생성 전에 만든다 — 규칙 위반(주기 코드·기준월 이상)을 세 표를 늘리기 전에 400 으로 끊는다.
        String rptpNm = buildRptpNm(stng, crltnCyclCd, rptpTypeCd, trgtPrdCd, bgng, end, region);

        // ── 생성자 = 설정 등록자 (TSK-4257 D10·D11) ──────────────────────────────
        // 🔴 내부 API POST /api/internal/reports/auto-crt 는 무인증 + X-API-Key 라 세션 사용자가 없다.
        //    그래서 ① 에서 읽은 설정의 등록자를 생성자로 쓴다(종전 상수 system/관리자 는 폐지 — TSK-4202 Step 3).
        //    AUTO 열람은 생성자가 아니라 배포 부서(crltn_dept_cd)·M0402 로 판정하므로(TSK-4257 D3·D1)
        //    이 값은 열람 범위를 바꾸지 않는다 — 목록의 "생성자" 표시와 실행 이력의 주체만 정한다.
        // 🔴 서비스 시그니처에 null 폴백을 두지 않는다 — 두면 미인증 401(TSK-4202 D5)이 무력화된다.
        //    여기서 넘기는 것은 폴백이 아니라 확정값이다(creatr_id 는 NOT NULL).
        // ⚠️ 생성 서비스가 같은 값을 tb_anls_excn.excutr_id 로도 쓴다 — 실행자도 설정 등록자가 된다(D14 허용).
        String creatrId = stng.getCreatrId();
        // D11 — TSK-4202 이전 설정은 creatr_nm 이 NULL 일 수 있다. "생성자" 칸이 비지 않게 ID 로 채운다.
        String creatrNm = ReportCreatorName.cut(
                trimToNull(stng.getCreatrNm()) != null ? stng.getCreatrNm() : creatrId);

        // ── ③④⑤ 한 트랜잭션 ───────────────────────────────────────────────────
        try {
            return transactionTemplate.execute(status ->
                    createAndMark(req, rptpTypeCd, crltnCyclCd, trgtPrdCd, region, emdCd, rptpNm,
                            creatrId, creatrNm));
        } catch (DataIntegrityViolationException | UnexpectedRollbackException e) {
            // ②와 ⑤ 사이에 다른 실행이 같은 (stng_id, trgt_prd_cd) 를 먼저 적재했다.
            // 이번 트랜잭션은 통째로 롤백됐으므로 MANUAL 잔재가 남지 않는다 — 그 행을 돌려주고 접는다(D5).
            ReportAutoCrtVO raced = findExisting(req.getStngId(), trgtPrdCd);
            if (raced == null) {
                // 유니크 충돌이 아닌 다른 무결성 위반이다 — 접으면 진짜 고장이 숨는다.
                throw e;
            }
            log.info("보고서 자동 생성 경합 — 이미 적재된 행을 돌려준다: stngId={} trgtPrdCd={} rptpId={}",
                    req.getStngId(), trgtPrdCd, raced.getRptpId());
            return raced;
        }
        // ⑥ last_crt_dt 는 갱신하지 않는다 — 배치가 성공한 설정만 갱신한다(D9).
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // ③④⑤ — 한 트랜잭션 안
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 유형에 맞는 기존 생성 서비스를 부르고(④), 그 결과 행에 자동 생성 표식을 덮어쓴다(⑤).
     *
     * <p>🔴 기존 생성 서비스의 {@code @Transactional} 이 이 트랜잭션에 <b>참여</b>하므로
     * 생성과 표식 덮기가 한 커밋이다.
     *
     * @param creatrId 생성자ID — 설정 등록자(TSK-4257 D10). 실행 행의 {@code excutr_id} 로도 쓰인다(D14)
     * @param creatrNm 생성자명 — 설정 등록자명, 비면 {@code creatrId}(D11). 이미 50자로 잘린 값이다
     */
    private ReportAutoCrtVO createAndMark(ReportAutoCrtRequest req, String rptpTypeCd,
                                          String crltnCyclCd, String trgtPrdCd,
                                          Region region, String emdCd, String rptpNm,
                                          String creatrId, String creatrNm) {
        // ── ④ 유형 분기·생성 — 집계는 기존 서비스가 한다 (D1) ────────────────────
        Long rptpId;
        if (TYPE_CVLCPT.equals(rptpTypeCd)) {
            ComplaintReportCreateRequest cr = new ComplaintReportCreateRequest();
            cr.setBgngYmd(req.getBgngYmd());
            cr.setEndYmd(req.getEndYmd());
            cr.setSggCd(region.sggCd());
            cr.setEmdCd(emdCd);
            // 🔴 fileFmt 를 채우지 않는다 — 자동 생성분은 NULL 이다(D14). 이 서비스에는 @Validated 가
            //    없어 요청 VO 의 @NotBlank 가 발동하지 않으므로 null 이 그대로 통과한다.
            ComplaintReportVO created = complaintReportService.createReport(cr, creatrId, creatrNm);
            rptpId = created.getRptpId();
        } else {
            HotspotReportCreateRequest hr = new HotspotReportCreateRequest();
            hr.setSggNm(region.filterSggNm());
            hr.setEmdCd(emdCd);
            hr.setBgngYmd(req.getBgngYmd());
            hr.setEndYmd(req.getEndYmd());
            // limit 은 기본값 15 를 쓴다 — 설정에 개수 칸이 없다.
            // 🔴 결과 0건이면 여기서 400 이 던져진다(D7). 삼키지 않는다 — 배치의 스킵 판정 근거다.
            HotspotReportVO created = hotspotReportService.createReport(hr, creatrId, creatrNm);
            rptpId = created.getRptpId();
        }

        // ── ⑤ AUTO 표식 덮기 (D4) ───────────────────────────────────────────────
        Rptp rptp = rptpRepository.findById(rptpId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, "방금 만든 보고서를 찾을 수 없습니다: " + rptpId));
        rptp.setCrtType(CRT_TYPE_AUTO);
        rptp.setStngId(req.getStngId());
        rptp.setTrgtPrdCd(trgtPrdCd);
        rptp.setCrltnDeptCd(trimToNull(req.getCrltnDeptCd()));
        rptp.setCrltnCyclCd(crltnCyclCd);
        rptp.setRptpNm(rptpNm);
        // 🔴 flush 로 UPDATE 를 지금 내보낸다 — uq_rptp_stng_prd 위반을 호출부가 잡을 수 있게 하기
        //    위해서다. 커밋까지 미루면 예외가 트랜잭션 경계 밖에서 터져 200 으로 접을 수 없다.
        rptpRepository.saveAndFlush(rptp);

        log.info("보고서 자동 생성: rptpId={} stngId={} type={} trgtPrdCd={} 이름={}",
                rptp.getRptpId(), req.getStngId(), rptpTypeCd, trgtPrdCd, rptpNm);
        return toVo(rptp, true);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 보조
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 이미 적재된 같은 {@code (stngId, trgtPrdCd)} 보고서. 없으면 {@code null}.
     *
     * <p>동적 조건 없는 2칸 조회지만 {@code TB_RPTP} 에는 조회 전용 엔티티를 두지 않는 관례라
     * 매퍼로 ID 만 찾고, 나머지 칸은 PK 단건 조회(JPA)로 읽는다
     * ({@code rule_persistence-jpa-mybatis.md}).
     */
    private ReportAutoCrtVO findExisting(Long stngId, String trgtPrdCd) {
        Long rptpId = reportMapper.selectAutoCrtRptpId(stngId, trgtPrdCd);
        if (rptpId == null) {
            return null;
        }
        return rptpRepository.findById(rptpId).map(r -> toVo(r, false)).orElse(null);
    }

    /**
     * 자동 생성 보고서명(D4). 기간표기는 {@link ReportAutoCrtNaming} 이 설정의 주기·기준월로 다시 만든다.
     *
     * <p>🔴 배치는 {@code trgtPrdCd}·구간만 보내고 <b>기간표기(prdLabel)는 보내지 않는다</b> —
     * 이름 규칙을 배치에 두면 두 레포로 갈린다(D6). 그래서 여기서 되살린다: 대상기간의 <b>종료일
     * 다음 날</b>은 정확히 그 다음 구간의 첫날이라, 그 날짜로 {@code targetPeriod()} 를 부르면
     * 배치가 산출한 것과 같은 구간이 나온다.
     *
     * <p>멱등키는 요청값({@code trgtPrdCd})을 그대로 쓴다 — 되살린 값과 어긋나면 WARN 만 남긴다.
     * 여기서 400 을 내면 규칙이 한쪽만 바뀐 순간 자동 생성이 통째로 멈춘다.
     */
    private String buildRptpNm(ReportSettingVO stng, String crltnCyclCd, String rptpTypeCd,
                               String trgtPrdCd, LocalDate bgng, LocalDate end, Region region) {
        try {
            ReportAutoCrtNaming.TargetPeriod computed =
                    ReportAutoCrtNaming.targetPeriod(crltnCyclCd, stng.getCrltnMm(), end.plusDays(1));
            if (!Objects.equals(computed.trgtPrdCd(), trgtPrdCd)) {
                log.warn("대상기간 코드가 설정으로 되살린 값과 다릅니다 — 요청값을 멱등키로 쓴다:"
                                + " 요청={} 산출={} (stngId={}, 구간={}~{})",
                        trgtPrdCd, computed.trgtPrdCd(), stng.getStngId(), bgng, end);
            }
            ReportAutoCrtNaming.TargetPeriod period = new ReportAutoCrtNaming.TargetPeriod(
                    trgtPrdCd, bgng.format(YMD), end.format(YMD), computed.prdLabel());
            return ReportAutoCrtNaming.rptpNm(period, rptpTypeCd, region.nameSggNm(), region.emdNm());
        } catch (IllegalArgumentException e) {
            // 설정값이 규칙 밖이다(주기 코드·기준월). 확정 실패라 400 — 배치가 스킵 + 사유 기록으로 받는다.
            // 🔴 응답 사유는 고정 문구 — 예외 메시지는 서버 로그에만(시큐어코딩 4.1 — TSK-4336).
            //    배치는 4xx 본문을 읽지 않고 상태코드로만 스킵을 판정한다(BackendReportClient).
            log.warn("보고서명을 만들 수 없습니다(400) — stngId={} 주기={} 사유={}",
                    stng.getStngId(), crltnCyclCd, e.getClass().getSimpleName());
            log.debug("보고서명을 만들 수 없습니다(400) 상세", e);   // 4.1 — 예외 원문은 DEBUG 로만 (TSK-4336)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "보고서명을 만들 수 없습니다. 자동 생성 설정의 배포 주기·기준월을 확인하세요.", e);
        }
    }

    /**
     * 지역 변환(③ · D10). 요청은 <b>설정 원문</b>({@code sggNm}·{@code emdCd})을 싣고 오므로
     * 유형별 요청 계약에 맞춰 여기서 바꾼다 — 배치가 바꾸지 않는다.
     *
     * <ul>
     *   <li>{@code CVLCPT} — 시군구는 <b>코드</b>({@code sggCd})다. {@code tb_admdst} 에
     *       {@code sgg_cd} 컬럼이 없어 {@code LEFT(emd_cd,5)} 가 곧 코드다.</li>
     *   <li>{@code HOTSPOT} — 시군구는 <b>이름</b>({@code sggNm}) 그대로다.</li>
     * </ul>
     *
     * <p>보고서명에 쓸 지역은 별도다 — {@code emdCd} 가 있으면 그 읍면동이 <b>실제로 속한</b>
     * 시군구명을 쓴다(요청이 시군구를 비워 보냈어도 이름이 {@code null} 로 새지 않게).
     */
    private Region resolveRegion(String rptpTypeCd, String sggNm, String emdCd) {
        String nameSggNm = sggNm;
        String emdNm = null;
        if (emdCd != null) {
            emdNm = reportMapper.selectEmdNmByEmdCd(emdCd);
            if (emdNm == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "존재하지 않는 읍면동코드입니다: " + emdCd);
            }
            nameSggNm = reportMapper.selectSggNmByEmdCd(emdCd);
        }
        String sggCd = null;
        if (TYPE_CVLCPT.equals(rptpTypeCd) && sggNm != null) {
            sggCd = reportMapper.selectSggCdBySggNm(sggNm);
            if (sggCd == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "존재하지 않는 시군구명입니다: " + sggNm);
            }
        }
        return new Region(sggCd, sggNm, nameSggNm, emdNm);
    }

    /**
     * 변환된 지역 한 벌.
     *
     * @param sggCd      {@code CVLCPT} 생성 요청에 실을 시군구 <b>코드</b> (없으면 null)
     * @param filterSggNm {@code HOTSPOT} 생성 요청에 실을 시군구 <b>이름</b> — 요청 원문 그대로다
     * @param nameSggNm  보고서명에 쓸 시군구명 — {@code emdCd} 가 있으면 그 읍면동의 시군구다
     * @param emdNm      보고서명에 쓸 읍면동명 (없으면 null)
     */
    private record Region(String sggCd, String filterSggNm, String nameSggNm, String emdNm) {
    }

    private ReportAutoCrtVO toVo(Rptp rptp, boolean created) {
        ReportAutoCrtVO vo = new ReportAutoCrtVO();
        vo.setRptpId(rptp.getRptpId());
        vo.setExcnId(rptp.getExcnId());
        vo.setRptpNm(rptp.getRptpNm());
        vo.setTrgtPrdCd(rptp.getTrgtPrdCd());
        vo.setCreated(created);
        return vo;
    }

    /**
     * {@code YYYYMMDD} 파싱. 🔴 형식 오류를 <b>400</b> 으로 끊는다 —
     * {@code DateTimeParseException} 을 그대로 흘리면 이 경로를 덮는 {@code @ControllerAdvice} 가
     * 없어 500 이 되고, 배치가 일시 실패로 오해해 계속 재시도한다.
     */
    private static LocalDate parseYmd(String value, String field) {
        String v = trimToNull(value);
        if (v == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 는 필수입니다 (YYYYMMDD)");
        }
        try {
            return LocalDate.parse(v, YMD);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 는 YYYYMMDD 형식이어야 합니다: " + value, e);
        }
    }

    /** {@code null}·빈 문자열·공백만 있는 값을 모두 "지정 안 함"으로 모은다. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
