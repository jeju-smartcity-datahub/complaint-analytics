package jeju.datahub.lifezone.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.common.DateRanges;
import jeju.datahub.analysis.service.DataRangeVO;
import jeju.datahub.analysis.service.impl.ZoneNameResolver;
import jeju.datahub.analysis.service.impl.ZoneNameResolver.ZoneName;
import jeju.datahub.lifezone.service.LifezoneAnalysisRequest;
import jeju.datahub.lifezone.service.LifezoneAnalysisResultVO;
import jeju.datahub.lifezone.service.LifezoneAnalysisService;
import jeju.datahub.lifezone.service.LifezoneCellVO;
import jeju.datahub.lifezone.service.LifezoneClsfMthd;
import jeju.datahub.lifezone.service.LifezoneDtlVO;
import jeju.datahub.lifezone.service.LifezoneGrade;
import jeju.datahub.lifezone.service.LifezoneGradeCriterionVO;
import jeju.datahub.lifezone.service.LifezoneGradeScale;
import jeju.datahub.lifezone.service.LifezoneRankVO;
import jeju.datahub.lifezone.service.LifezoneTarget;
import jeju.datahub.lifezone.service.LifezoneTargetVO;

/**
 * 생활권 격자 분석 서비스 구현체. 전자정부 {@link EgovAbstractServiceImpl} 상속. (TSK-4088 Step 4)
 *
 * <p>처리 순서는 계획서 그대로 12단계다 — ①검증 → ②기간 변환 → ③격자 집계 → ④경계 산출·등급 판정 →
 * ⑤꼭짓점 링 변환 → ⑥세부내역 파싱 → ⑦구역명(폴백 + 상위 N POI) → ⑧요약 집계 → ⑨순위 →
 * ⑩가용 유형 → ⑪등급 기준 → ⑫가중치(빈 배열).
 *
 * <h2>🔴 경계는 전 셀을 다 받은 뒤에야 정해진다 (D17)</h2>
 * {@link LifezoneGradeScale} 은 <b>조회 결과 전체</b>에서 경계를 뽑는다(기본 자연분류 Jenks ·
 * 요청 {@code clsfMthd} 로 등간격 선택 가능). 셀 하나를 볼 때마다 판정할 수 없다 — 매퍼가 돌려준
 * <b>전 셀의 {@code anlsVl}</b> 로 먼저 경계를 만들고 그 다음에 셀별 등급을 매긴다.
 * 순서를 뒤집으면 경계가 상수로 굳는다.
 *
 * <h2>🔴 {@code @Transactional(readOnly = true)} (D4)</h2>
 * 이 서비스는 <b>아무것도 저장하지 않는다</b>. 유일한 쓰기인 구역명 캐시는
 * {@code ZoneNameCacheWriter}({@code REQUIRES_NEW})가 독립 트랜잭션에서 처리하므로 readOnly
 * 안에서도 안전하다(집중구역과 같은 구조).
 *
 * <h2>입력은 여기서 끊는다</h2>
 * HTTP 파라미터(기간 문자열·지역명·격자크기·개수)가 SQL 로 그대로 흘러가지 않게 이 계층에서
 * 파싱·대조·클램프한 뒤 {@link LifezoneQueryParam} 으로만 매퍼에 넘긴다. 매퍼 XML 은 전부
 * {@code #{}} 바인딩이라 이 두 겹으로 인젝션 경로가 없다.
 *
 * <h2>가중치를 쓰지 않는다 (D8)</h2>
 * 분석유형이 <b>배타 선택</b>이라 지표를 섞지 않는다 — {@code weights} 는 항상 빈 배열이며
 * {@code AnalysisWeightService} 를 <b>호출하지 않는다</b>. 호출해서 버리면 "가중치를 쓰는데
 * 안 보인다" 는 오해가 남는다.
 */
@Service
public class LifezoneAnalysisServiceImpl extends EgovAbstractServiceImpl implements LifezoneAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(LifezoneAnalysisServiceImpl.class);

    /**
     * 기간 파라미터 형식. {@code uuuu}(연) + {@link ResolverStyle#STRICT} 라 {@code 20251301}·
     * {@code 20250230} 같은 실재하지 않는 날짜도 걸러진다({@code yyyy} + SMART 면 조용히 보정된다).
     */
    private static final DateTimeFormatter YMD =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    /** {@code TB_GRID} 가 보유한 격자크기(m) 4종. 그 외는 400 이다. */
    private static final Set<Integer> ALLOWED_GRID_SZ = Set.of(1000, 500, 250, 100);

    /** 순위 개수 하한. 🔁 TSK-4227 D21 — 기본·상한은 없앴다(생략 = 심각·경고·주의 전부). */
    private static final int MIN_LIMIT = 1;

    /** {@code limit} 생략 = 상한 없음. 루프 조건 {@code ranking.size() >= limit} 이 닿지 않는 값이다. */
    private static final int UNLIMITED = Integer.MAX_VALUE;

    private final LifezoneAnalysisMapper lifezoneAnalysisMapper;
    private final ZoneNameResolver zoneNameResolver;
    private final LifezoneRegionResolver regionResolver;
    private final LifezoneTargetCatalog lifezoneTargetCatalog;
    private final ObjectMapper objectMapper;

    public LifezoneAnalysisServiceImpl(LifezoneAnalysisMapper lifezoneAnalysisMapper,
                                       ZoneNameResolver zoneNameResolver,
                                       LifezoneRegionResolver regionResolver,
                                       LifezoneTargetCatalog lifezoneTargetCatalog,
                                       ObjectMapper objectMapper) {
        this.lifezoneAnalysisMapper = lifezoneAnalysisMapper;
        this.zoneNameResolver = zoneNameResolver;
        this.regionResolver = regionResolver;
        this.lifezoneTargetCatalog = lifezoneTargetCatalog;
        this.objectMapper = objectMapper;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public LifezoneAnalysisResultVO analyze(LifezoneAnalysisRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "조회 조건이 없습니다");
        }

        // ── ① 입력 검증 ─────────────────────────────────────────────────────────
        LifezoneTarget target = resolveTarget(req.getAnlsTrgtCd());
        int gridSz = validateGridSz(req.getGridSz());
        LifezoneClsfMthd clsfMthd = LifezoneClsfMthdResolver.resolve(target, req.getClsfMthd());
        LocalDate bgngYmd = parseYmd(req.getBgngYmd(), "bgngYmd");
        LocalDate endYmd = parseYmd(req.getEndYmd(), "endYmd");
        DateRanges.validate(bgngYmd, endYmd);
        String emdCd = LifezoneRegionResolver.normalizeKey(req.getEmdCd(), "emdCd");
        String sggNm = LifezoneRegionResolver.normalizeKey(req.getSggNm(), "sggNm");
        LifezoneRegionResolver.Region region = regionResolver.resolve(sggNm, emdCd);
        int limit = resolveLimit(req.getLimit());

        // ── ② 기간 변환 — 반열림 구간의 상한은 endYmd + 1일 00:00 (당일 포함) ───────
        LifezoneQueryParam param = new LifezoneQueryParam();
        param.setGridSz(gridSz);
        param.setBgngDt(bgngYmd.atStartOfDay());
        param.setEndDtExcl(endYmd.plusDays(1).atStartOfDay());
        param.setEmdCd(emdCd);
        param.setSggCd(region.sggCd());

        // ── ③ 격자 집계 (값>0 전량 · anlsVl 내림차순) ──────────────────────────────
        List<LifezoneCellRow> rows = lifezoneAnalysisMapper.selectCells(param);
        if (rows == null) {
            rows = List.of();
        }

        // ── ④ 구간 경계 산출 (🔴 전 셀을 다 받은 뒤) ────────────────────────────────
        List<Integer> values = new ArrayList<>(rows.size());
        for (LifezoneCellRow row : rows) {
            values.add(row.getAnlsVl());
        }
        LifezoneGradeScale scale = LifezoneGradeScale.of(values, clsfMthd);

        // ── ④~⑦ 셀 조립 (등급 판정 · 링 변환 · 폴백 구역명) ─────────────────────────
        //
        // 🔴 응답 VO 와 매퍼 원본을 **짝으로 묶어** 들고 간다.
        //    순위 조립(⑨)은 응답에 없는 값(dtlJson · emdNm)을 원본에서 꺼내야 하는데,
        //    두 리스트를 따로 들고 같은 인덱스로 짝지으면 아래 `continue` 가 한 번이라도
        //    걸리는 순간 그 지점부터 한 칸씩 어긋난다 — 그러면 순위 행의 세부 내역과 POI 이름이
        //    **다른 격자 것으로 조용히 바뀐다**(예외도 로그도 없다).
        //    지금은 매퍼의 hit CTE 가 INNER JOIN 이라 값 0 행이 없어 도달하지 않지만,
        //    LEFT JOIN 으로 바꾸는 한 줄이면 발동한다. 방어 코드가 스스로 위험을 만들지 않게
        //    인덱스 가정 자체를 없앤다.
        List<CellPair> pairs = new ArrayList<>(rows.size());
        for (LifezoneCellRow row : rows) {
            LifezoneGrade grade = scale.gradeOf(row.getAnlsVl());
            if (grade == null) {
                continue;   // 값 0 — 매퍼가 이미 걸러 주지만 계약을 여기서도 지킨다
            }
            LifezoneCellVO cell = new LifezoneCellVO();
            cell.setGridCd(row.getGridCd());
            cell.setAnlsVl(row.getAnlsVl());
            cell.setGrade(grade.name());
            cell.setCtrLat(row.getCtrLat());
            cell.setCtrLot(row.getCtrLot());
            cell.setRing(parseRing(row.getRingJson(), row.getGridCd()));   // ⑤
            cell.setEmdNm(row.getEmdNm());
            // ⑥ 전 셀 — 캐시 이름이 있으면 그것, 없으면 폴백(TSK-4339 · 외부 호출 0)
            cell.setCntrPoiNm(row.getCacheZoneNm() != null
                    ? row.getCacheZoneNm() : fallbackZoneNm(row.getEmdNm(), row.getGridCd()));
            pairs.add(new CellPair(cell, row));
        }
        List<LifezoneCellVO> cells = new ArrayList<>(pairs.size());
        for (CellPair pair : pairs) {
            cells.add(pair.cell());
        }

        // ── ⑨ 순위(심각·경고·주의 전부 — limit 지정 시 그 값까지) + ⑦ 순위 행만 POI 로 교체 ──────
        List<LifezoneRankVO> ranking = buildRanking(pairs, limit);

        // ── ⑧ ⑩ ⑪ ⑫ 응답 조립 ───────────────────────────────────────────────────
        return buildResult(target, gridSz, clsfMthd, bgngYmd, endYmd, region, emdCd,
                scale, cells, ranking);
    }

    /** {@inheritDoc} — enum 전량 투영이라 DB 를 보지 않는다(설정 패널이 마운트 시 1회 부른다). */
    @Override
    public List<LifezoneTargetVO> selectTargets() {
        // 🔴 정적 allOf() 가 아니라 카탈로그다 — 설정 패널은 **유형별 원천 보유 구간**까지 알아야
        //    "기간 기본값으로는 0건" 을 실행 전에 경고할 수 있다 (TSK-4089).
        return lifezoneTargetCatalog.selectTargets();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // ① 입력 검증
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 분석내용코드 → {@link LifezoneTarget}. 미지원 코드도, <b>미구현 유형도</b> 400 이다(D2).
     *
     * <p>미구현 유형을 200 + 빈 결과로 내리지 않는 이유는 "데이터가 없는 것"과 "기능이 아직 없는 것"이
     * 화면에서 구별되지 않기 때문이다. 목록({@code availableTargets})에는 그대로 실려 나간다.
     */
    private static LifezoneTarget resolveTarget(String anlsTrgtCd) {
        LifezoneTarget target = LifezoneTarget.ofCode(anlsTrgtCd);
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "지원하지 않는 분석내용코드입니다: " + anlsTrgtCd + " (사용 가능: " + targetCodes() + ")");
        }
        if (!target.isAvailable()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    target.getAnlsTrgtNm() + " 분석은 아직 제공하지 않습니다: " + target.getUnavailableRsn());
        }
        // 🔴 전용 핸들러가 따로 있는 유형(수급)을 여기서 처리하면 **민원건수 분석 결과가
        //    "수급" 이라는 이름표를 달고 200 으로 나간다.** 격자 체계·기간 컬럼·응답 형태가
        //    전부 달라 조용한 오답이 되므로 경로를 알려 주고 끊는다 (TSK-4089).
        if (!target.isDefaultHandled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    target.getAnlsTrgtNm() + " 분석은 전용 경로로 조회합니다: GET /api/analysis/lifezone"
                            + target.getAnlsPathSfx());
        }
        return target;
    }

    private static String targetCodes() {
        List<String> codes = new ArrayList<>();
        for (LifezoneTarget t : LifezoneTarget.values()) {
            codes.add(t.name());
        }
        return String.join(", ", codes);
    }

    /** 격자크기는 {@code TB_GRID} 가 보유한 4종뿐이다 — 그 외는 조용히 0건이 아니라 400 이다. */
    private static int validateGridSz(int gridSz) {
        if (!ALLOWED_GRID_SZ.contains(gridSz)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "격자크기는 1000 | 500 | 250 | 100 (m) 중 하나여야 합니다: " + gridSz);
        }
        return gridSz;
    }

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

    /*
     * 🔴 지역 조건 정규화·해석은 {@link LifezoneRegionResolver} 한 곳이 진다 (TSK-4089).
     *
     * 종전에는 이 클래스 안의 private 메서드였다. 수급 분석이 **같은 규칙**을 써야 해서 꺼냈다 —
     * "키를 아예 넣지 않는 것이 전체이고 빈 문자열은 400", "emdCd 단독이면 sggCd 를 만들지 않는다"
     * 처럼 미묘한 규칙이라 두 벌로 두면 반드시 갈린다.
     */

    /**
     * 순위 개수. 🔁 TSK-4227 D21 — <b>생략({@code null})이면 상한 없음</b>({@link #UNLIMITED} —
     * 심각·경고·주의 셀 전부), 지정하면 {@code max(}{@value #MIN_LIMIT}{@code , limit)} 까지다.
     * 종전 기본 15 · 상한 30 클램프는 없앴다.
     *
     * <p>외부 API(VWorld) 호출량은 이 값이 아니라 {@link ZoneNameResolver} 세션 상한
     * ({@code analysis.zone-name.max-lookups-per-run})이 묶는다 — {@link #buildRanking} 주석 참고.
     * HTTP 경로는 {@code @Valid} 가 0 이하를 먼저 400 으로 내지만, 이 서비스를 직접 호출하는 내부
     * 사용처(보고서 저장)를 위해 여기서도 하한을 한 번 더 묶는다.
     */
    private static int resolveLimit(Integer requested) {
        if (requested == null) {
            return UNLIMITED;
        }
        return Math.max(MIN_LIMIT, requested);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // ⑤ 꼭짓점 링 · ⑥ 세부내역
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * {@code ST_AsGeoJSON} 폴리곤 → 화면이 쓰는 {@code [[위도, 경도], …]} <b>4점</b>(D6).
     *
     * <p>두 가지를 뒤집는다 — GeoJSON 은 {@code [경도, 위도]} 순서이고(Leaflet 은 그 반대),
     * 폴리곤 링은 <b>첫 점을 끝에 되풀이해 닫혀 있다</b>(5점). 마지막 점을 버려 4점으로 만든다.
     *
     * <p>격자는 4326 에서 축정렬이 아니라 bbox 로 그리면 셀이 겹친다 — 그래서 bbox 가 아니라
     * 꼭짓점 링을 그대로 내린다. 파싱 실패는 그 셀만 빈 링으로 두고 넘어간다(요청 전체를
     * 500 으로 끊지 않는다 — 나머지 셀은 멀쩡하다).
     */
    private List<List<BigDecimal>> parseRing(String ringJson, String gridCd) {
        List<List<BigDecimal>> ring = new ArrayList<>(4);
        if (ringJson == null || ringJson.isBlank()) {
            return ring;
        }
        try {
            JsonNode coords = objectMapper.readTree(ringJson).path("coordinates");
            // Polygon 은 [[[lon,lat],…]], MultiPolygon 은 한 겹 더 깊다 — 숫자 쌍이 나올 때까지 내려간다.
            while (coords.isArray() && coords.size() > 0
                    && coords.get(0).isArray() && coords.get(0).path(0).isArray()) {
                coords = coords.get(0);
            }
            if (!coords.isArray() || coords.isEmpty()) {
                return ring;
            }
            // 닫힌 링의 마지막 점(= 첫 점)을 버린다.
            int end = coords.size();
            if (end > 1 && coords.get(0).equals(coords.get(end - 1))) {
                end--;
            }
            for (int i = 0; i < end; i++) {
                JsonNode pt = coords.get(i);
                if (!pt.isArray() || pt.size() < 2) {
                    continue;
                }
                ring.add(List.of(pt.get(1).decimalValue(), pt.get(0).decimalValue()));
            }
        } catch (JsonProcessingException e) {
            log.warn("격자 꼭짓점 링 파싱 실패 — 빈 링으로 둡니다. gridCd={} 사유={}", gridCd, e.getClass().getSimpleName());
            log.debug("격자 꼭짓점 링 파싱 실패 상세", e);   // 4.1 — 예외 원문(toString)은 DEBUG 로만 (TSK-4336)
            return new ArrayList<>();
        }
        return ring;
    }

    /**
     * 매퍼가 만든 {@code dtl_json}({@code jsonb_agg}) → {@link LifezoneDtlVO} 목록 (D16).
     * {@code null}(해당 격자에 유형 매칭이 없음)이면 <b>빈 배열</b>이다 — 화면이 {@code null} 을
     * 분기하지 않게 한다.
     */
    private List<LifezoneDtlVO> parseDtl(String dtlJson, String gridCd) {
        if (dtlJson == null || dtlJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            LifezoneDtlVO[] parsed = objectMapper.readValue(dtlJson, LifezoneDtlVO[].class);
            return new ArrayList<>(Arrays.asList(parsed));   // List.of 는 null 원소에 NPE(5.1) — 파싱 결과를 그대로 담는다
        } catch (JsonProcessingException e) {
            log.warn("세부 내역 파싱 실패 — 빈 배열로 둡니다. gridCd={} 사유={}", gridCd, e.getClass().getSimpleName());
            log.debug("세부 내역 파싱 실패 상세", e);   // 4.1 — 예외 원문(toString)은 DEBUG 로만 (TSK-4336)
            return new ArrayList<>();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // ⑦ 구역명 · ⑨ 순위
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 생활권 폴백 구역명 — {@code {읍면동명} 격자 {격자코드}}, 읍면동이 없으면 {@code 격자 {격자코드}}.
     *
     * <p>🔴 <b>이 문구의 유일한 출처</b>다. 같은 패키지의 {@code LifezoneReportServiceImpl} 이
     * 보고서 스냅샷을 복원할 때도 이 메서드를 부른다 — {@code cells[].cntrPoiNm} 은 전량이라
     * 저장하지 않고 재조립하는데(TSK-4088 Step 11), 규칙을 복제하면 분석 화면과 보고서 화면의
     * 구역명이 조용히 갈린다. 그래서 {@code private} 이 아니라 <b>패키지 전용</b>이다.
     */
    static String fallbackZoneNm(String emdNm, String gridCd) {
        if (emdNm == null || emdNm.isBlank()) {
            return "격자 " + gridCd;
        }
        return emdNm.trim() + " 격자 " + gridCd;
    }

    /**
     * 응답 셀과 그 셀을 만든 매퍼 원본의 짝.
     *
     * <p>순위 조립이 응답에 없는 값(<b>세부 내역 원문</b> {@code dtlJson} · POI 조회용 <b>읍면동명</b>
     * {@code emdNm})을 원본에서 꺼내야 해서 둘을 함께 들고 다닌다. 두 리스트를 따로
     * 들고 같은 인덱스로 짝지으면 셀이 하나라도 걸러질 때 조용히 어긋난다 — 그 사고를 타입으로 막는다.
     */
    private record CellPair(LifezoneCellVO cell, LifezoneCellRow row) {
    }

    /**
     * <b>심각·경고·주의</b> 셀 전부(요청 {@code limit} 지정 시 그 값까지)에 순위를 매기고, <b>그 행에만</b> POI 구역명을 시도한다(D10).
     *
     * <p>🔁 <b>TSK-4227 — 심각·경고·주의만</b>. 보통·낮음 셀은 건너뛰고
     * ({@link LifezoneGrade#isListed()}) <b>실린 행 수가 {@code limit} 에 닿을 때까지</b> 순회한다.
     * 🔁 D21 — {@code limit} 생략이면 {@link #UNLIMITED} 라 listed 셀 전부가 실린다(종전 기본 15 폐지).
     * 순위({@code rnk})는 실린 순서({@code ranking.size() + 1})다 — 건너뛴 셀이 번호를 먹지 않는다.
     * 지도({@code cells})·요약 수치·{@code gradeCriteria} 는 그대로 5등급이다(D6).
     *
     * <p>🔴 POI 조회 좌표는 <b>격자 중심 고정</b>({@code ctrLat/Lot})이다 (D10 개정 — TSK-4339, 종전: 민원 무게중심) —
     * 조건(기간·지역)이 바뀌어도 캐시 키가 같아 외부 조회 상한에 걸리지 않는다. 혼잡도·수급 탭과 같은 좌표다.
     *
     * <p>⚠️ {@link ZoneNameResolver} 자체 폴백은 {@code {읍면동명} 일대}·{@code 위치 미상}
     * ({@code SRC_EMD})이라 생활권 화면 문구와 어긋난다. <b>그 경우 생활권 폴백
     * ({@code {읍면동명} 격자 {코드}})으로 되돌리는 것은 이 서비스의 책임</b>이다 — resolver 를
     * 고치면 집중구역 문구까지 바뀌므로 호출 쪽에서 흡수한다.
     *
     * <p>세션은 요청 1건마다 새로 만든다(빈에 상태를 두면 동시 요청이 외부 호출 상한을 나눠 쓴다).
     * 🔴 POI 조회는 <b>실린 행에만</b> 부른다 — 건너뛴 셀(보통·낮음)과 {@code limit} 이후 셀은
     * <b>부르지 않는다</b>. 셀 전량(최대 2,015칸)을 태우면 상한이 즉시 소진된다.
     *
     * <p>⚠️ <b>순위 행 수가 세션 외부 조회 상한({@code analysis.zone-name.max-lookups-per-run}, 기본 50)을
     * 넘으면</b>(D21 — 상한 없는 순위) 캐시에 없는 뒤쪽 행은 {@link ZoneNameResolver.Session#resolve} 가
     * 예외 없이 {@code SRC_EMD} 폴백({@code … 일대})을 돌려주고(WARN 1줄/행), {@link #resolveRankNm} 이
     * 그것을 생활권 폴백 {@code {읍면동} 격자 {코드}} 로 되돌린다. <b>순위 자체는 끊기지 않는다</b> —
     * 이름만 폴백이다. 캐시 히트는 상한을 소모하지 않으므로 같은 조회를 반복하면 POI 이름이 늘어난다.
     */
    private List<LifezoneRankVO> buildRanking(List<CellPair> pairs, int limit) {
        // 🔴 limit 은 UNLIMITED(Integer.MAX_VALUE)일 수 있다 — 초기 용량에 그대로 쓰면 OOM 이다
        List<LifezoneRankVO> ranking = new ArrayList<>(Math.min(limit, pairs.size()));
        if (pairs.isEmpty() || limit <= 0) {
            return ranking;
        }
        ZoneNameResolver.Session session = zoneNameResolver.newSession();
        for (CellPair pair : pairs) {
            if (ranking.size() >= limit) {
                break;
            }
            LifezoneCellVO cell = pair.cell();
            if (!LifezoneGrade.valueOf(cell.getGrade()).isListed()) {
                continue;   // 보통·낮음 — 지도·요약에만 남는다(TSK-4227 D5·D6)
            }
            LifezoneCellRow row = pair.row();   // 🔴 짝이 보장된다 — 인덱스로 맞추지 않는다

            LifezoneRankVO rank = new LifezoneRankVO();
            rank.setRnk(ranking.size() + 1);
            rank.setGridCd(cell.getGridCd());
            rank.setAnlsVl(cell.getAnlsVl());
            rank.setGrade(cell.getGrade());
            rank.setCtrLat(cell.getCtrLat());      // 순위 행 클릭 시 지도 이동용 — 격자 중심이다
            rank.setCtrLot(cell.getCtrLot());
            rank.setDtl(parseDtl(row.getDtlJson(), row.getGridCd()));
            rank.setCntrPoiNm(resolveRankNm(session, row, cell.getCntrPoiNm()));
            ranking.add(rank);
        }
        return ranking;
    }

    private String resolveRankNm(ZoneNameResolver.Session session, LifezoneCellRow row, String fallback) {
        ZoneName resolved = session.resolve(row.getCtrLat(), row.getCtrLot(), row.getEmdNm());
        if (resolved == null || resolved.zoneNm() == null || resolved.zoneNm().isBlank()
                || ZoneNameResolver.SRC_EMD.equals(resolved.zoneNmSrc())) {
            // resolver 가 자기 폴백('… 일대' · '위치 미상')으로 내려갔다 → 생활권 폴백으로 되돌린다
            return fallback;
        }
        return resolved.zoneNm();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // ⑧ ⑩ ⑪ ⑫ 응답 조립
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 격자크기 표시명. <b>1000 만 {@code 1km}</b> 이고 나머지는 {@code {n}m} 다.
     *
     * <p>🔴 {@code gridSz + "m"} 로 두면 1000 이 {@code 1000m} 이 되는데, D3 가 정한 화면 라벨은
     * {@code 1km} 다 — 설정 패널의 격자 버튼·결과 패널의 {@code .size-chip}·범례 아래
     * {@code .sim-gridchip} 이 전부 이 값을 그대로 쓰므로 여기서 어긋나면 화면 세 곳이 함께 틀린다.
     */
    private String gridSzNm(int gridSz) {
        return gridSz == 1000 ? "1km" : gridSz + "m";
    }

    private LifezoneAnalysisResultVO buildResult(LifezoneTarget target, int gridSz,
                                                 LifezoneClsfMthd clsfMthd,
                                                 LocalDate bgngYmd, LocalDate endYmd,
                                                 LifezoneRegionResolver.Region region, String emdCd,
                                                 LifezoneGradeScale scale,
                                                 List<LifezoneCellVO> cells,
                                                 List<LifezoneRankVO> ranking) {
        LifezoneAnalysisResultVO result = new LifezoneAnalysisResultVO();

        // 조건 echo — 요청 문자열이 아니라 '정규화된 값'을 되비춘다(공백·미지정이 그대로 새지 않게).
        result.setBgngYmd(bgngYmd.format(YMD));
        result.setEndYmd(endYmd.format(YMD));
        result.setSggNm(region.sggNm());
        result.setEmdCd(emdCd);
        result.setAnlsTrgtCd(target.name());
        result.setAnlsTrgtNm(target.getAnlsTrgtNm());
        result.setGridSz(gridSz);
        result.setGridSzNm(gridSzNm(gridSz));
        result.setClsfMthd(clsfMthd.name());
        result.setClsfMthdNm(clsfMthd.getClsfMthdNm());
        result.setUnit(target.getUnit());
        result.setSectionTitle(target.getSectionTitle());
        result.setSectionSub(target.getSectionSub());

        // ⑩ 가용 유형 — 미구현 유형도 목록에는 들어간다(프론트가 이 목록으로 세그먼트를 그린다).
        result.setAvailableTargets(LifezoneTargetVO.allOf());

        // ⑪ 등급 기준 — 🔴 조회 결과에서 산출된 경계다. 0건일 때도 채워 보낸다(범례가 비지 않게).
        result.setGradeScale(scale);
        result.setGradeCriteria(LifezoneGradeCriterionVO.allOf(scale, target.getUnit()));

        // ⑫ 가중치 — D8. 배타 선택이라 항상 빈 배열이다.
        result.setWeights(List.of());

        // ⑧ 요약 집계 — 등급별 5개 합이 곧 totGridCnt 다(어느 셀도 등급을 못 받지 않는다).
        result.setTotGridCnt(cells.size());
        for (LifezoneCellVO cell : cells) {
            switch (LifezoneGrade.valueOf(cell.getGrade())) {
                case SEVERE -> result.setSevereCnt(result.getSevereCnt() + 1);
                case WARN -> result.setWarnCnt(result.getWarnCnt() + 1);
                case CAUTION -> result.setCautionCnt(result.getCautionCnt() + 1);
                case NORMAL -> result.setNormalCnt(result.getNormalCnt() + 1);
                case LOW -> result.setLowCnt(result.getLowCnt() + 1);
            }
        }

        // 0건이어도 200 이며, 이때도 보유 구간은 채워 보낸다 — 화면이 "언제 데이터가 있는지" 안내한다.
        DataRangeVO range = lifezoneAnalysisMapper.selectDataRange();
        if (range != null) {
            result.setDataBgngYmd(range.getDataBgngYmd());
            result.setDataEndYmd(range.getDataEndYmd());
        }

        result.setCells(cells);
        result.setRanking(ranking);

        if (cells.isEmpty()) {
            log.info("생활권 격자 0건 — 조건에 데이터가 없습니다. 기간={}~{} 지역(sggCd={}, emdCd={}) "
                            + "격자={}m 보유구간={}~{}",
                    result.getBgngYmd(), result.getEndYmd(), region.sggCd(), emdCd, gridSz,
                    result.getDataBgngYmd(), result.getDataEndYmd());
        } else {
            log.debug("생활권 격자 분석 완료: 격자={}m 분류={} 셀={}건 순위={}건 최대={} 폭={}",
                    gridSz, scale.method(), cells.size(), ranking.size(), scale.cap(), scale.width());
        }
        return result;
    }
}
