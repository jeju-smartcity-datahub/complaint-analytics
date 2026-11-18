package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.common.DateRanges;
import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.AnlsVrblOvrd;
import jeju.datahub.analysis.service.AnlsVrblScope;
import jeju.datahub.analysis.service.DataRangeVO;
import jeju.datahub.analysis.service.GradeCriterionVO;
import jeju.datahub.analysis.service.HotspotAnalysisRequest;
import jeju.datahub.analysis.service.HotspotAnalysisResultVO;
import jeju.datahub.analysis.service.HotspotAnalysisService;
import jeju.datahub.analysis.service.HotspotGrade;
import jeju.datahub.analysis.service.HotspotGradeBndry;
import jeju.datahub.analysis.service.HotspotWorkbookConstVO;
import jeju.datahub.analysis.service.HotspotWorkbookVO;
import jeju.datahub.analysis.service.HotspotWorkbookZoneVO;
import jeju.datahub.analysis.service.HotspotZoneVO;
import jeju.datahub.analysis.service.impl.HotspotWeightResolver.HotspotWeights;
import jeju.datahub.analysis.service.impl.ZoneNameResolver.ZoneName;
import jeju.datahub.region.service.EmdVO;
import jeju.datahub.region.service.RegionService;
import jeju.datahub.region.service.RegionVO;

/**
 * 불법주차 집중구역 분석 서비스 구현체. 전자정부 {@link EgovAbstractServiceImpl} 상속. (TSK-4032 Step 5)
 *
 * <p>처리 순서는 6단계다 — ①정규화·검증 → ②가중치 로드 → ③군집 집계 → ④구역명 폴백 →
 * ⑤등급 매핑 → ⑥응답 조립. 앞 단계 산출물(Step 1~4)을 조립하기만 하고 산식·폴백 규칙을
 * 여기서 다시 구현하지 않는다.
 *
 * <h2>🔴 {@code @Transactional(readOnly = true)} 를 지운다면 그 전에 읽을 것 (D7)</h2>
 * 이 서비스는 <b>분석 결과를 저장하지 않는다</b>. 유일한 쓰기인 구역명 캐시는
 * {@link ZoneNameCacheWriter}({@code REQUIRES_NEW})가 <b>독립 트랜잭션</b>에서 처리하므로
 * readOnly 안에서도 안전하다 — 그 분리가 없으면 캐시 INSERT 가 25006 으로 실패하면서 부모
 * 트랜잭션까지 abort 시킨다. readOnly 는 그 계약을 지키는 쪽의 표시이며, 회귀 테스트가 이를 지킨다.
 *
 * <h2>입력은 여기서 끊는다</h2>
 * HTTP 파라미터(기간 문자열·지역명·개수)가 SQL 로 <b>그대로 흘러가지 않게</b> 이 계층에서
 * 파싱·대조·클램프한 뒤 {@link HotspotQueryParam} 으로만 매퍼에 넘긴다. 매퍼 XML 은 전부
 * {@code #{}} 바인딩이라 이 두 겹으로 인젝션 경로가 없다.
 */
@Service
public class HotspotAnalysisServiceImpl extends EgovAbstractServiceImpl implements HotspotAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(HotspotAnalysisServiceImpl.class);

    /**
     * 기간 파라미터 형식. {@code uuuu}(연) + {@link ResolverStyle#STRICT} 라 {@code 20251301}·
     * {@code 20250230} 같은 실재하지 않는 날짜도 걸러진다({@code yyyy} + SMART 면 조용히 보정된다).
     */
    private static final DateTimeFormatter YMD =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    /** 결과 개수 하한 (TSK-4172 D8). 상한·기본값은 없다 — 미지정이면 전량이다. */
    private static final int MIN_LIMIT = 1;

    /**
     * 종합점수 산식이 실제로 쓰는 변수코드.
     *
     * <p>⚠️ {@link HotspotWeightResolver} 가 계수를 만들 때 쓰는 목록과 <b>같은 집합</b>이어야 한다 —
     * 한쪽만 늘리면 점수(계수)와 화면 부제(echo)가 갈린다. 산식에 항을 추가할 때는
     * {@code HotspotAnalysisMapper.xml} · {@code HotspotWeightResolver} · 이 상수를 함께 고친다.
     */
    private static final String VRBL_COMPLAINT = "COMPLAINT";
    private static final String VRBL_ENFORCE = "ENFORCE";
    private static final Set<String> SCORED_VRBL_CDS = Set.of(VRBL_COMPLAINT, VRBL_ENFORCE);

    /** 계수 → 퍼센트 환산 배수. {@code scoreFormula} 문자열의 가중치를 퍼센트(0.6 → {@code 60})로 적는다. */
    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);

    private final HotspotAnalysisMapper hotspotAnalysisMapper;
    private final HotspotWeightResolver hotspotWeightResolver;
    private final HotspotGradeBndryResolver hotspotGradeBndryResolver;
    private final AnlsVrblOvrdResolver anlsVrblOvrdResolver;
    private final ZoneNameResolver zoneNameResolver;
    private final RegionService regionService;

    /** DBSCAN 이웃 반경(m). 미터 좌표계(EPSG:5179)로 군집화하므로 단위가 곧 m 이다(D2). */
    private final int eps;

    /** DBSCAN 최소 점 개수(유일좌표 기준). 이 미만이면 군집이 만들어지지 않는다(D2). */
    private final int minPoints;

    public HotspotAnalysisServiceImpl(HotspotAnalysisMapper hotspotAnalysisMapper,
                                      HotspotWeightResolver hotspotWeightResolver,
                                      HotspotGradeBndryResolver hotspotGradeBndryResolver,
                                      AnlsVrblOvrdResolver anlsVrblOvrdResolver,
                                      ZoneNameResolver zoneNameResolver,
                                      RegionService regionService,
                                      @Value("${analysis.hotspot.eps:50}") int eps,
                                      @Value("${analysis.hotspot.min-points:3}") int minPoints) {
        this.hotspotAnalysisMapper = hotspotAnalysisMapper;
        this.hotspotWeightResolver = hotspotWeightResolver;
        this.hotspotGradeBndryResolver = hotspotGradeBndryResolver;
        this.anlsVrblOvrdResolver = anlsVrblOvrdResolver;
        this.zoneNameResolver = zoneNameResolver;
        this.regionService = regionService;
        this.eps = eps;
        this.minPoints = minPoints;
    }

    /**
     * {@inheritDoc}
     *
     * <p>조회 전용이라 readOnly 다(D7) — 클래스 주석의 "지운다면 그 전에 읽을 것" 참고.
     */
    @Override
    @Transactional(readOnly = true)
    public HotspotAnalysisResultVO analyze(HotspotAnalysisRequest req) {
        return analyze(prepare(req), zoneNameResolver.newSession());
    }

    /**
     * {@inheritDoc}
     *
     * <p>파라미터 조립({@link #prepare})과 구역명 세션을 {@link #analyze} 와 <b>공유</b>한다 —
     * 세션을 둘 열면 보고서 1건의 외부 조회 상한이 두 배(100회)가 된다.
     */
    @Override
    @Transactional(readOnly = true)
    public HotspotAnalysisWithWorkbook analyzeWithWorkbook(HotspotAnalysisRequest req) {
        PreparedQuery q = prepare(req);
        ZoneNameResolver.Session session = zoneNameResolver.newSession();
        HotspotAnalysisResultVO result = analyze(q, session);
        HotspotWorkbookVO wbk = result.getZones().isEmpty()
                ? null      // 보고서는 0건이면 400 이다 — 쓰지 않을 후보 조회를 치지 않는다
                : buildWorkbook(q, result.getZones(), session);
        return new HotspotAnalysisWithWorkbook(result, wbk);
    }

    /**
     * {@link #prepare} 로 조립한 조건을 실행한다 — ③ 군집 집계 → ④ 구역명 → ⑤ 등급 → ⑥ 응답 조립.
     *
     * @param session 구역명 외부 조회 세션. 엑셀 후보 조회가 이어서 쓸 수 있게 호출자가 연다
     */
    private HotspotAnalysisResultVO analyze(PreparedQuery q, ZoneNameResolver.Session session) {
        // ── ③ 군집 집계 ─────────────────────────────────────────────────────────
        List<HotspotZoneVO> zones = hotspotAnalysisMapper.selectHotspotZones(q.param());
        if (zones == null) {
            zones = List.of();
        }

        // ── ④ 구역명 폴백 + ⑤ 등급 매핑 ──────────────────────────────────────────
        fillZoneNames(zones, session, SHOWN_ZONE_NAMES);
        fillGrades(zones, q.bndry());

        // ── ⑥ 응답 조립 ─────────────────────────────────────────────────────────
        return buildResult(q.req(), q.bgngYmd(), q.endYmd(), q.region(), q.emdCd(), q.weights(), q.bndry(),
                q.ovrd(), zones);
    }

    /**
     * {@link #analyze} 와 엑셀 후보 조회가 공유하는 조건 한 벌 — ① 정규화·검증과 ② 가중치 로드의 산출물.
     * 🔴 두 경로가 따로 조립하면 기간·지역·가중치·등급 비율이 갈려 엑셀이 화면과 다른 집합을 계산한다.
     */
    private record PreparedQuery(HotspotAnalysisRequest req,
                                 LocalDate bgngYmd, LocalDate endYmd,
                                 String emdCd, RegionLookup region,
                                 HotspotQueryParam param,
                                 AnlsVrblOvrd ovrd, HotspotGradeBndry bndry,
                                 HotspotWeights weights) {
    }

    /** ① 정규화·검증 + ② 가중치 로드 → 매퍼 파라미터. {@link #analyze} 와 {@link #analyzeWithWorkbook} 공용. */
    private PreparedQuery prepare(HotspotAnalysisRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "조회 조건이 없습니다");
        }

        // ── ① 정규화·검증 ────────────────────────────────────────────────────────
        LocalDate bgngYmd = parseYmd(req.getBgngYmd(), "bgngYmd");
        LocalDate endYmd = parseYmd(req.getEndYmd(), "endYmd");
        DateRanges.validate(bgngYmd, endYmd);

        String emdCd = normalizeRegionKey(req.getEmdCd(), "emdCd");
        String sggNm = normalizeRegionKey(req.getSggNm(), "sggNm");
        RegionLookup region = resolveRegion(sggNm, emdCd);
        Integer limit = clampLimit(req.getLimit());

        HotspotQueryParam param = new HotspotQueryParam();
        param.setSggCd(region.sggCd());
        param.setEmdCd(emdCd);
        param.setBgngDt(bgngYmd.atStartOfDay());
        // 반열림 구간의 상한 — endYmd 당일을 포함하려면 +1일 00:00 이어야 한다.
        param.setEndDtExcl(endYmd.plusDays(1).atStartOfDay());
        param.setEps(eps);
        param.setMinPoints(minPoints);
        param.setLimit(limit);
        // 일시 입력 — 보고서 경로의 Map 이 먼저이고, 없으면 쿼리 JSON 이다. 설정 표에는 쓰지 않는다 (TSK-4325)
        Map<String, BigDecimal> vrblVls = req.getVrblVlsMap() != null
                ? req.getVrblVlsMap()
                : AnlsVrblOvrd.parse(req.getVrblVls());
        AnlsVrblOvrd ovrd = anlsVrblOvrdResolver.resolve(AnlsVrblScope.HOTSPOT, vrblVls);
        // 표시 범위 — 주의 비율 밖(양호)은 응답에 싣지 않는다. 비율은 요청마다 설정에서 읽는다 (TSK-4321)
        // 🔴 등급 판정(gradeOf)과 조회 범위(매퍼 CEIL)가 같은 bndry 를 쓴다 — 입력값도 양쪽에 함께 닿는다
        HotspotGradeBndry bndry = hotspotGradeBndryResolver.resolve(ovrd);
        param.setMaxRankPct(bndry.pctOf(HotspotGrade.MIN_DISPLAYED));

        // ── ② 가중치 로드 (재정규화는 TSK-4020 서비스가 한다 — 여기서 다시 구현하지 않는다) ──
        //    입력값이 둘 다 없으면 설정값이다 — 대시보드·자동 생성처럼 채우지 않는 호출부는 그대로다 (TSK-4306 D1)
        HotspotWeights weights = hotspotWeightResolver.resolve(req.getCvlcptWgvl(), req.getCrdnWgvl());
        param.setCvlcptWgt(weights.cvlcptWgt());
        param.setCrdnWgt(weights.crdnWgt());
        // 엑셀 후보 조회 전용 — 적용 민원 가중치(%)의 출처는 실제 계산에 쓴 계수 하나다(TSK-4338 D3 — 요청값 아님).
        // 🔴 반올림하지 않는다 — wbk.cvlcptWgvl 과 scrCnts 의 같은 값 행이 짝이어야 엑셀 N 이 #N/A 가 되지 않는다.
        //    화면 조회(selectHotspotZones)는 이 칸을 읽지 않는다.
        param.setCvlcptWgvl(toPercent(weights.cvlcptWgt()));

        return new PreparedQuery(req, bgngYmd, endYmd, emdCd, region, param, ovrd, bndry, weights);
    }

    /**
     * 계수(0.6) → 퍼센트(60). 꼬리 0 만 걷는다(값은 그대로 — 반올림 없음). {@code 1E+2} 지수 표기가
     * JSON 에 실리지 않게 음수 스케일은 0 으로 되돌린다.
     */
    private static BigDecimal toPercent(BigDecimal coefficient) {
        BigDecimal pct = coefficient.multiply(PERCENT).stripTrailingZeros();
        return pct.scale() < 0 ? pct.setScale(0) : pct;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // ① 정규화·검증
    // ─────────────────────────────────────────────────────────────────────────────

    /** {@code YYYYMMDD} 파싱. 형식 오류·실재하지 않는 날짜는 400 이다(SQL 로 흘려보내지 않는다). */
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
     * 지역 조건 정규화. <b>키를 아예 넣지 않는 것</b>이 "전체"이며 빈 문자열은 400 이다
     * ({@link HotspotAnalysisRequest} 계약) — 오타로 필터가 조용히 풀리는 것을 막는다.
     */
    private static String normalizeRegionKey(String value, String field) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 를 빈 값으로 보낼 수 없습니다. 전 지역이면 파라미터를 생략하세요.");
        }
        return trimmed;
    }

    /** 시군구코드(=읍면동코드 앞 5자리)와 되비출 시군구명 한 벌. 둘 다 없으면 전 지역이다. */
    private record RegionLookup(String sggCd, String sggNm) {
        private static final RegionLookup ALL = new RegionLookup(null, null);
    }

    /**
     * {@code sggNm} → {@code sggCd} 변환 + {@code emdCd} 실재 확인.
     *
     * <p>코드표를 소스에 박지 않고 {@code TB_ADMDST}(= {@link RegionService})를 대조한다 —
     * 행정구역이 바뀌어도 코드 변경 없이 따라간다. 미등록 시군구·미존재 읍면동은 <b>400</b> 이다:
     * 조용히 전 지역으로 넓히면 오타 한 글자가 다른 결과를 정상 응답으로 위장한다.
     */
    private RegionLookup resolveRegion(String sggNm, String emdCd) {
        if (sggNm == null && emdCd == null) {
            return RegionLookup.ALL;
        }
        List<RegionVO> regions = regionService.selectRegions();

        if (emdCd != null && !existsEmd(regions, emdCd)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "존재하지 않는 읍면동코드입니다: " + emdCd);
        }
        if (sggNm == null) {
            return RegionLookup.ALL;   // emdCd 만 왔다 — 매퍼에서 emdCd 가 sggCd 보다 우선한다
        }
        for (RegionVO region : regions) {
            if (sggNm.equals(region.getSggNm())) {
                return new RegionLookup(sggCdOf(region), region.getSggNm());
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "등록되지 않은 시군구명입니다: " + sggNm + " (사용 가능: " + sggNames(regions) + ")");
    }

    private static boolean existsEmd(List<RegionVO> regions, String emdCd) {
        for (RegionVO region : regions) {
            for (EmdVO emd : region.getEmds()) {
                if (emdCd.equals(emd.getEmdCd())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 시군구코드 = 하위 읍면동코드의 앞 5자리(제주시 50110 / 서귀포시 50130).
     * {@code TB_ADMDST} 에 시군구코드 컬럼이 없어 이렇게 얻는다 — 매퍼도 같은 전제로
     * {@code emd_cd LIKE sggCd || '%'} 를 쓴다.
     */
    private static String sggCdOf(RegionVO region) {
        for (EmdVO emd : region.getEmds()) {
            String code = emd.getEmdCd();
            if (code != null && code.length() >= 5) {
                return code.substring(0, 5);
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "시군구에 등록된 읍면동이 없어 지역 필터를 만들 수 없습니다: " + region.getSggNm());
    }

    private static String sggNames(List<RegionVO> regions) {
        List<String> names = new ArrayList<>(regions.size());
        for (RegionVO region : regions) {
            names.add(String.valueOf(region.getSggNm()));
        }
        return String.join(", ", names);
    }

    /**
     * 결과 개수의 <b>하한만</b> 본다 (TSK-4172 D8) — {@code null} 은 그대로 {@code null}(전량)이고,
     * {@value #MIN_LIMIT} 미만은 {@value #MIN_LIMIT} 로 올린다.
     *
     * <p>🔁 <b>상한을 걷었다</b>. 종전에는 개수가 곧 외부 API(VWorld) 호출량이라 30 으로 묶었는데,
     * 그러면 "주의 이상 전부" 를 보여줄 수 없었다(화면·보고서가 늘 상위 N 으로 잘렸다).
     * 대신 표시 하한({@link HotspotGrade#MIN_DISPLAYED})이 결과 수를 줄여 주고, 그 대가인
     * 구역명 폴백 호출량 증가는 R6 으로 명시해 두었다 — 캐시가 빈 첫 조회에서만 늘어난다.
     *
     * <p>HTTP 경로는 {@code @Valid} 의 {@code @Min(1)} 이 먼저 400 을 내지만, 이 서비스를 직접
     * 호출하는 내부 사용처(대시보드 카드)를 위해 여기서도 한 번 더 묶는다 — LIMIT 이 SQL 로
     * 나가는 마지막 지점이 여기다.
     */
    private static Integer clampLimit(Integer requested) {
        if (requested == null) {
            return null;
        }
        return Math.max(MIN_LIMIT, requested);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // ④ 구역명 · ⑤ 등급
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 매퍼가 채우지 못한 구역명만 폴백 체인(캐시 → POI → 역지오코딩 → {@code {읍면동명} 일대})에
     * 태운다(D6). 이미 {@code CRDN_ADDR} 로 채워진 구역은 <b>건드리지 않는다</b> — 외부 호출
     * 상한(세션)을 이름이 이미 있는 구역이 소모하면 정작 필요한 구역이 {@code 일대} 로 밀린다.
     *
     * <p>세션은 요청 1건마다 새로 만든다(빈에 상태를 두면 동시 요청이 상한을 나눠 쓴다). 보고서 저장은
     * 분석과 엑셀 후보 조회가 <b>한 세션을 이어 쓴다</b>(TSK-4338 — {@link #analyzeWithWorkbook}).
     *
     * <p>표시 구역({@link HotspotZoneVO})과 엑셀 후보({@link HotspotWorkbookZoneVO})가 이 루프 하나를 쓴다 —
     * 두 VO 의 접근자만 {@link ZoneNameAccess} 로 갈아 끼운다(루프를 복사하면 폴백 규칙이 두 벌이 된다).
     */
    private static <T> void fillZoneNames(List<T> zones, ZoneNameResolver.Session session,
                                          ZoneNameAccess<T> access) {
        for (T zone : zones) {
            String zoneNm = access.zoneNm().apply(zone);
            if (zoneNm != null && !zoneNm.isBlank()) {
                continue;
            }
            // 중심좌표는 마커 좌표와 같다(매퍼가 같은 ST_Centroid 를 두 이름으로 내려준다).
            ZoneName resolved = session.resolve(access.lat().apply(zone), access.lot().apply(zone),
                    access.emdNm().apply(zone));
            access.setter().accept(zone, resolved);
        }
    }

    /** 구역명 폴백 루프가 읽고 쓰는 칸 — VO 별 접근자 한 벌. */
    private record ZoneNameAccess<T>(Function<T, String> zoneNm,
                                     Function<T, BigDecimal> lat,
                                     Function<T, BigDecimal> lot,
                                     Function<T, String> emdNm,
                                     BiConsumer<T, ZoneName> setter) {
    }

    private static final ZoneNameAccess<HotspotZoneVO> SHOWN_ZONE_NAMES = new ZoneNameAccess<>(
            HotspotZoneVO::getZoneNm, HotspotZoneVO::getMrkLat, HotspotZoneVO::getMrkLot, HotspotZoneVO::getEmdNm,
            (zone, nm) -> {
                zone.setZoneNm(nm.zoneNm());
                zone.setZoneNmSrc(nm.zoneNmSrc());
            });

    private static final ZoneNameAccess<HotspotWorkbookZoneVO> WORKBOOK_ZONE_NAMES = new ZoneNameAccess<>(
            HotspotWorkbookZoneVO::getZoneNm, HotspotWorkbookZoneVO::getMrkLat, HotspotWorkbookZoneVO::getMrkLot,
            HotspotWorkbookZoneVO::getEmdNm,
            (zone, nm) -> {
                zone.setZoneNm(nm.zoneNm());
                zone.setZoneNmSrc(nm.zoneNmSrc());
            });

    // ─────────────────────────────────────────────────────────────────────────────
    // 엑셀 재계산 데이터 (TSK-4338)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 후보 구역 + 상수 → {@code wbk}. 조건은 {@link #analyze} 와 같은 {@link PreparedQuery} 다.
     *
     * <p>구역명 — 🔴 표시 구역은 분석 결과의 이름을 <b>중심좌표</b>({@code mrkLat}·{@code mrkLot})로 짝지어
     * 그대로 복사한다. 같은 세션이라도 다시 풀면 상한이 소진된 뒤라 {@code {읍면동} 일대} 로 밀려 PDF 와
     * 이름이 갈린다. {@link HotspotZoneVO} 에는 {@code cid} 가 없어 좌표가 유일한 짝 키다(두 SQL 이 같은
     * {@code ST_Transform(t.ctr, 4326)} 식이다). 복사된 구역은 폴백 루프의 "이미 이름 있음" 분기로 건너뛴다.
     */
    private HotspotWorkbookVO buildWorkbook(PreparedQuery q, List<HotspotZoneVO> shownZones,
                                            ZoneNameResolver.Session session) {
        HotspotQueryParam param = q.param();
        List<HotspotWorkbookZoneVO> candidates = hotspotAnalysisMapper.selectHotspotWorkbookZones(param);
        if (candidates == null) {
            candidates = new ArrayList<>();
        }
        HotspotWorkbookConstVO consts = hotspotAnalysisMapper.selectHotspotWorkbookConst(param);

        Map<String, HotspotZoneVO> shownByCoord = new HashMap<>();
        for (HotspotZoneVO shown : shownZones) {
            shownByCoord.putIfAbsent(coordKey(shown.getMrkLat(), shown.getMrkLot()), shown);
        }
        int copied = 0;
        for (HotspotWorkbookZoneVO cand : candidates) {
            HotspotZoneVO shown = shownByCoord.get(coordKey(cand.getMrkLat(), cand.getMrkLot()));
            if (shown != null && shown.getZoneNm() != null) {
                cand.setZoneNm(shown.getZoneNm());
                cand.setZoneNmSrc(shown.getZoneNmSrc());
                copied++;
            }
        }
        if (copied < shownZones.size()) {
            // 정상이면 표시 구역은 전부 후보에 있다(D4). 짝이 덜 맞았다는 것은 두 SQL 의 군집이 갈렸다는 뜻이다.
            log.warn("⚠️ 엑셀 후보 중 표시 구역 이름 복사가 덜 됐습니다: 표시={} 복사={} 후보={}",
                    shownZones.size(), copied, candidates.size());
        }
        fillZoneNames(candidates, session, WORKBOOK_ZONE_NAMES);

        HotspotWorkbookVO wbk = new HotspotWorkbookVO();
        wbk.setCvlcptWgvl(param.getCvlcptWgvl());
        wbk.setMaxCvlcptCnt(consts != null ? consts.getMaxCvlcptCnt() : 0);
        wbk.setMaxCrdnCnt(consts != null ? consts.getMaxCrdnCnt() : 0);
        wbk.setScrCnts(consts != null && consts.getScrCnts() != null ? consts.getScrCnts() : new ArrayList<>());
        wbk.setZones(candidates);
        log.debug("집중구역 엑셀 데이터: 적용가중치={}% 후보={}건 가중치행={} 표시이름복사={}",
                wbk.getCvlcptWgvl(), candidates.size(), wbk.getScrCnts().size(), copied);
        return wbk;
    }

    /** 중심좌표 짝 키 — 스케일 차이(33.50 vs 33.5)에 흔들리지 않게 꼬리 0 을 걷는다. */
    private static String coordKey(BigDecimal lat, BigDecimal lot) {
        return (lat == null ? "null" : lat.stripTrailingZeros().toPlainString())
                + "," + (lot == null ? "null" : lot.stripTrailingZeros().toPlainString());
    }

    /** 순위 비율 → 등급 (TSK-4321). 판정은 {@link HotspotGradeBndry} 한 곳이다. */
    private static void fillGrades(List<HotspotZoneVO> zones, HotspotGradeBndry bndry) {
        for (HotspotZoneVO zone : zones) {
            HotspotGrade grade = bndry.gradeOf(zone.getScrRnk(), zone.getScrCnt());
            zone.setGrade(grade.name());
            zone.setGradeNm(grade.getGradeNm());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // ⑥ 응답 조립
    // ─────────────────────────────────────────────────────────────────────────────

    private HotspotAnalysisResultVO buildResult(HotspotAnalysisRequest req,
                                                LocalDate bgngYmd, LocalDate endYmd,
                                                RegionLookup region, String emdCd,
                                                HotspotWeights weights,
                                                HotspotGradeBndry bndry,
                                                AnlsVrblOvrd ovrd,
                                                List<HotspotZoneVO> zones) {
        HotspotAnalysisResultVO result = new HotspotAnalysisResultVO();

        // 조건 echo — 요청 문자열이 아니라 '정규화된 값'을 되비춘다(공백·미지정이 그대로 새지 않게).
        result.setBgngYmd(bgngYmd.format(YMD));
        result.setEndYmd(endYmd.format(YMD));
        result.setSggNm(region.sggNm());
        result.setEmdCd(emdCd);

        // 재현용 군집 파라미터
        result.setEps(eps);
        result.setMinPoints(minPoints);
        result.setScoreFormula(scoreFormula(weights));
        result.setWeights(scoredWeights(weights.weights()));
        result.setWgvlAjmtYn(weights.wgvlAjmtYn());

        // 등급 판정 기준(D5). enum 에서 파생하므로 조회 결과와 무관한 상수이며, 0건일 때도 채워 보낸다 —
        // 프론트 범례가 이 값으로 '심각 (상위 20%)' 같은 문구를 조립한다(기준을 화면에 박지 않기 위함).
        result.setGradeCriteria(GradeCriterionVO.displayedOf(bndry));
        result.setVrblAjmt(ovrd.toAjmtJson());

        // 0건이어도 200 이며, 이때도 보유 구간은 채워 보낸다(D11).
        DataRangeVO range = hotspotAnalysisMapper.selectDataRange();
        if (range != null) {
            result.setDataBgngYmd(range.getDataBgngYmd());
            result.setDataEndYmd(range.getDataEndYmd());
        }
        result.setZones(zones);

        if (zones.isEmpty()) {
            log.info("집중구역 0건 — 조건에 데이터가 없거나 군집이 형성되지 않았습니다. "
                            + "기간={}~{} 지역(sggCd={}, emdCd={}) eps={}m minPoints={} 보유구간={}~{}",
                    result.getBgngYmd(), result.getEndYmd(), region.sggCd(), emdCd, eps, minPoints,
                    result.getDataBgngYmd(), result.getDataEndYmd());
        }
        // 요청 원본의 limit 은 클램프될 수 있어 응답에 싣지 않는다(zones 길이가 실제 결과 개수다).
        log.debug("집중구역 분석 완료: 요청limit={} 결과={}건 산식={}", req.getLimit(), zones.size(),
                result.getScoreFormula());
        return result;
    }

    /**
     * 응답 {@code scoreFormula} — 🔁 TSK-4321 로 조회 결과 최댓값 기준 상대 점수를 적는다.
     * 예) {@code (CVLCPT/MAX_CVLCPT*60 + CRDN/MAX_CRDN*40) / MAX * 100}
     *
     * <p>계수가 0 인 항은 종전처럼 문자열에서 빠진다(산식에 없는 것과 0 배는 같다). 단속을 끄면
     * {@code (CVLCPT/MAX_CVLCPT*100) / MAX * 100} 이 된다.
     *
     * <p>🔴 곱셈 쪽은 계수가 아니라 <b>퍼센트</b>(0.6 → {@code 60})다 — 화면 부제의 가중치 표기와 같다.
     */
    private static String scoreFormula(HotspotWeights weights) {
        List<String> terms = new ArrayList<>(2);
        appendTerm(terms, "CVLCPT", weights.cvlcptWgt());
        appendTerm(terms, "CRDN", weights.crdnWgt());
        return "(" + String.join(" + ", terms) + ") / MAX * 100";
    }

    private static void appendTerm(List<String> terms, String label, BigDecimal coefficient) {
        if (coefficient == null || coefficient.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        // 계수는 스케일 6(0.600000)이라 그대로 쓰면 산식이 읽히지 않는다 → ×100 해 60 으로 다듬는다.
        terms.add(label + "/MAX_" + label + "*" + plain(coefficient.multiply(PERCENT)));
    }

    /** 산식 표기용 — 꼬리 0 을 걷어 {@code 60.000000 → 60} 으로 적는다. */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /**
     * 응답에 되비출 가중치 — <b>점수에 실제로 반영된 지표만</b> 남긴다.
     *
     * <p>🔴 {@code selectEffectiveWeights} 는 {@code use_yn='Y'} 가중치 변수를 <b>전부</b> 돌려주지만
     * 점수 산식이 아는 항은 {@code COMPLAINT}·{@code ENFORCE} 둘뿐이다. 전부 echo 하면
     * TSK-4020 이 {@code HOTSPOT} 에 변수를 추가하는 순간 화면 부제가 {@code 민원 50 / 단속 30 /
     * 기타 20} 으로 바뀌는데 실제 점수는 그대로라 <b>부제와 점수가 조용히 어긋난다</b>.
     * 그래서 걸러내되, 무시한 변수는 로그로 드러낸다(설정한 사람이 이유를 알아야 한다).
     */
    private static List<AnalysisWeightVO> scoredWeights(List<AnalysisWeightVO> effective) {
        if (effective == null || effective.isEmpty()) {
            return List.of();
        }
        List<AnalysisWeightVO> scored = new ArrayList<>(SCORED_VRBL_CDS.size());
        Set<String> ignored = new LinkedHashSet<>();
        for (AnalysisWeightVO weight : effective) {
            if (SCORED_VRBL_CDS.contains(weight.getVrblCd())) {
                scored.add(weight);
            } else {
                ignored.add(String.valueOf(weight.getVrblCd()));
            }
        }
        if (!ignored.isEmpty()) {
            log.warn("⚠️ 집중구역 응답 weights 에서 점수 미반영 변수를 제외했습니다: {}. "
                            + "반영하려면 HotspotAnalysisMapper.xml 의 점수 산식에 항을 추가하세요(반영=echo 가 계약).",
                    ignored);
        }
        return scored;
    }
}
