package jeju.datahub.batch.cvlcpt;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import jeju.datahub.batch.admdst.AdmDistMatch;
import jeju.datahub.batch.admdst.AdmDistResolver;
import jeju.datahub.batch.cvlcpt.api.ComplaintOriginRow;
import jeju.datahub.batch.geocode.CompositeGeocoder;
import jeju.datahub.batch.geocode.Coordinate;
import jeju.datahub.batch.geocode.GeocodeOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * 수집 API 한 행 → 적재 직전 레코드.
 *
 * <h2>🔁 2026-08-24 — 입력이 staging 행이 아니라 API 행이다</h2>
 * {@code parking.tb_cvlcpt_origin} 이 사라지면서 파이프라인이 1단이 됐다
 * (47-CVLCPT-DIRECT.sql). 이 Processor 는 종전 ②{@code cvlcptGeocodeJob} 의 것이지만
 * 이제 ①{@code complaintFetchJob} 의 Step 안에서 API 응답 행({@link ComplaintOriginRow})을
 * 직접 받는다.
 *
 * <h2>🔴 2026-09-01 (TSK-4109) — 가공값은 "원천이 주면 쓰고, 없으면 만든다"</h2>
 * 새 원천 {@code jeju_smart_city_test.tb_cvlcpt} 는 우리 서비스 테이블과 컬럼 구성이 같아
 * {@code cvlcpt_type_cd}·{@code emd_cd}·{@code lat}/{@code lot}·{@code src_cd} 자리를 갖는다.
 * <b>2026-09-01 실측으로는 78,386행 전량 비어 있어 지금은 네 항목 모두 우리가 계산</b>하지만,
 * 원천이 언제 채워 보낼지는 우리가 정하지 않는다. 그래서 <b>항목마다 독립적으로</b> 판단한다 —
 * "좌표는 왔는데 행정동은 안 온" 절반짜리 응답에서도 나머지만 계산하면 된다.
 *
 * <table>
 *   <caption>항목별 우선순위</caption>
 *   <tr><th>항목</th><th>원천이 주면</th><th>없으면</th><th>원천값을 버리는 조건</th></tr>
 *   <tr><td>{@code cvlcpt_type_cd}</td><td>그대로</td>
 *       <td>{@code prk_cvlcpt_type} → {@code dclr_type} CASE → {@code 99}</td>
 *       <td>공통코드에 없는 값</td></tr>
 *   <tr><td>{@code lat}/{@code lot}</td><td>그대로(지오코딩 <b>생략</b>)</td>
 *       <td>{@link CompositeGeocoder} 3단 폴백</td>
 *       <td>한쪽만 오거나 좌표 범위 밖</td></tr>
 *   <tr><td>{@code emd_cd}</td><td>그대로(공간매칭 <b>생략</b>)</td>
 *       <td>좌표로 {@link AdmDistResolver} 2단 공간매칭</td>
 *       <td>{@code tb_admdst} 에 없는 코드(FK 위반 방지)</td></tr>
 *   <tr><td>{@code src_cd}</td><td>그대로</td><td>{@code src_path} 매핑(없으면 {@code ETC})</td>
 *       <td>공통코드에 없는 값</td></tr>
 * </table>
 *
 * <p><b>원천값을 그대로 신뢰하지 않고 한 겹 검증하는 이유</b>: 이 네 값은 전부 화면의 필터·지도가
 * 직접 읽는 값이고, {@code emd_cd} 는 FK 까지 걸려 있다. 검증 없이 통과시키면 잘못된 값이
 * "조용히 안 보이는 행"이 되거나 청크 전체를 롤백시킨다. 검증에서 떨어진 값은 <b>버리는 것이
 * 아니라 자체 계산 경로로 되돌린다</b> — 그래야 원천 품질이 나빠져도 적재가 멈추지 않는다.
 *
 * <pre>
 * 1. 유형 분류 (원천 cvlcpt_type_cd → prk_cvlcpt_type → dclr_type CASE)
 *      └ 어디에도 없으면 → 99 + WARN 로그 (스킵 테이블 기록 아님)
 * 2. 원천 좌표·행정동 채택 (검증 통과분만)
 * 3. cvlcpt_type_cd == 99 → 지오코딩·공간매칭을 새로 하지 않는다
 * 4. 주차민원(01~05) 중 부족한 것만:
 *      좌표 없음 → CompositeGeocoder.geocode(dclr_ocrn_rgn)
 *        ├ FOUND     → 좌표 확보
 *        ├ NOT_FOUND → GEOCODE_FAILED 스킵 예외
 *        └ TRANSIENT → null 반환(filter). 스킵 기록 없음
 *      행정동 없음 → AdmDistResolver.resolve(좌표)
 *        └ 실패 → EMD_UNRESOLVED 스킵 예외
 * 5. 나머지 필드 매핑 (날짜 조립·SRC_CD·감정코드·좌표 스케일)
 * </pre>
 *
 * <h2>🔴 분류를 지오코딩보다 먼저 한다</h2>
 * 순서를 바꾸면 미분류 37,508행(전체의 47.9%)에까지 지오코딩 쿼터를 쓴다. 이 행들은 애초에 좌표가
 * 필요 없다(화면에 안 나온다) — 순서 자체가 쿼터 절감 설계다. 원천 좌표를 받는 경로가 생겨도
 * 이 순서는 그대로다: <b>공짜로 온 좌표는 담되, 없는 좌표를 새로 사 오지는 않는다.</b>
 *
 * <h2>🔴 {@code TRANSIENT} 를 {@code NOT_FOUND} 와 같이 다루면 안 된다</h2>
 * {@code NOT_FOUND} 는 "그 주소는 원래 없다"라서 스킵 테이블에 넣어 영구 제외해도 되지만,
 * {@code TRANSIENT} 는 "지금 못 물어봤다"일 뿐이다. 스킵 기록해 버리면 Reader 의
 * {@code NOT EXISTS} 가 다음 실행에서도 그 행을 빼 버려 <b>일시 장애가 영구 제외로 굳는다</b>
 * (D8 개정이 고친 바로 그 버그). 그래서 {@code null} 을 반환해 이번 실행에서만 걸러 낸다 —
 * Spring Batch 는 {@code null} 을 "이 행은 쓰지 않음"으로 처리하고 아무 기록도 남기지 않는다.
 *
 * <h2>미지 유형 로그는 값 단위로 한 번만 남긴다</h2>
 * 계획서는 "미지 {@code type} 값을 WARN 으로 기록"하라고 하는데, 행마다 찍으면 수만 줄이 된다.
 * 목적이 <b>원천 표기 변경 감지</b>(예: {@code 장애인전용구역} → {@code 장애인전용구역 위반} 으로
 * 바뀌어 전량이 조용히 99 로 빠지는 사고)이므로 <b>distinct 값 기준 1회</b>로 줄이고, 값별 누계는
 * {@link #unknownTypeCounts()} 로 노출한다. Job 종료 시 {@link #logStats()} 가 전체 비율을 남긴다.
 */
@Component
public class CvlcptGeocodeProcessor implements ItemProcessor<ComplaintOriginRow, CvlcptRecord> {

    private static final Logger log = LoggerFactory.getLogger(CvlcptGeocodeProcessor.class);

    /** DB 컬럼 {@code NUMERIC(12,10)}/{@code NUMERIC(13,10)} 과 동일한 소수 자릿수. */
    static final int COORD_SCALE = 10;

    /**
     * 원천 좌표를 채택할 수 있는 절대값 상한 — WGS84 정의역이자 <b>DB 컬럼의 정수부 한계</b>다.
     * {@code NUMERIC(12,10)} 은 소수 10자리를 빼면 정수부가 2자리뿐이라 100 이상은 담기지 않고,
     * 담으려 하면 그 행이 아니라 <b>청크 전체</b>가 numeric overflow 로 롤백된다.
     */
    static final BigDecimal MAX_LAT = new BigDecimal("90");
    static final BigDecimal MAX_LOT = new BigDecimal("180");

    /**
     * 미지 유형 비율 경고선. 원천 표기가 통째로 바뀌면 이 비율이 급등한다.
     *
     * <p>⚠️ 정상 상태에서도 47.9%(비주차 민원)라 이 값만으로는 사고를 못 가린다 — 절대 비율이 아니라
     * <b>실행 간 변화</b>를 보기 위한 관측치다. {@link #logStats()} 가 값별 내역과 함께 남긴다.
     */
    static final double UNKNOWN_TYPE_WARN_RATIO = 0.05;

    /**
     * 지오코더가 단계 내역을 주지 못했을 때의 {@code note}.
     *
     * <p>평시엔 쓰이지 않는다({@link CompositeGeocoder} 가 항상 내역을 채운다) — 내역이 비어
     * {@code note} 가 통째로 NULL 이 되는 상황만은 막는 최후 방어선이다.
     */
    static final String GEOCODE_FAILED_FALLBACK_NOTE = "지오코딩 3단 폴백 모두 주소 미발견(단계 내역 없음)";

    private final CompositeGeocoder geocoder;
    private final AdmDistResolver admDistResolver;

    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong unclassified = new AtomicLong();
    private final AtomicLong geocoded = new AtomicLong();
    private final AtomicLong transientFiltered = new AtomicLong();

    /** 원천이 이미 판정해 줘서 우리가 계산하지 않은 건수 — 항목별. */
    private final AtomicLong sourceTypeCds = new AtomicLong();
    private final AtomicLong sourceCoordinates = new AtomicLong();
    private final AtomicLong sourceEmdCds = new AtomicLong();

    /** 미지 {@code dclr_type} 값 → 건수. 키가 처음 생길 때만 WARN 을 찍는다. */
    private final Map<String, AtomicLong> unknownTypeCounts = new ConcurrentHashMap<>();

    public CvlcptGeocodeProcessor(CompositeGeocoder geocoder, AdmDistResolver admDistResolver) {
        this.geocoder = geocoder;
        this.admDistResolver = admDistResolver;
    }

    /**
     * @return 적재할 레코드. <b>{@code null} 이면 이번 실행에서만 제외</b>(지오코딩 일시 장애)이며
     *         스킵 테이블에 기록되지 않아 다음 실행에 Reader 가 다시 집어 온다
     * @throws GeocodeSkippableException 실패 확정 — {@code GEOCODE_FAILED}(주소 없음) 또는
     *         {@code EMD_UNRESOLVED}(행정동 경계 밖). Step 이 {@code .skip()} 으로 받아 기록한다
     */
    @Override
    public CvlcptRecord process(ComplaintOriginRow row) {
        processed.incrementAndGet();

        // ── 1. 유형 분류 — 지오코딩보다 먼저다(쿼터 절감 전제)
        String typeCd = resolveTypeCd(row);

        // ── 2. 원천이 이미 준 좌표·행정동을 먼저 채택한다(검증 통과분만).
        //      한쪽 좌표만 온 행은 지도에 찍을 수 없으므로 통째로 버리고 지오코딩으로 되돌린다.
        BigDecimal lat = usableCoordinate(row.lat(), MAX_LAT);
        BigDecimal lot = usableCoordinate(row.lot(), MAX_LOT);
        if (lat == null || lot == null) {
            lat = null;
            lot = null;
        } else {
            sourceCoordinates.incrementAndGet();
        }

        String emdCd = null;
        if (admDistResolver.isKnownEmdCd(row.emdCd())) {
            emdCd = row.emdCd().strip();
            sourceEmdCds.incrementAndGet();
        }

        // ── 3·4. 미분류(99)에는 지오코딩 쿼터를 쓰지 않는다. 주차민원(01~05) 중
        //         원천이 채워 주지 않은 것만 우리가 만든다.
        if (!CvlcptCodeMapping.TYPE_CD_ETC.equals(typeCd)) {
            if (lat == null) {
                GeocodeOutcome outcome = geocoder.geocode(row.dclrOcrnRgn());
                switch (outcome.status()) {
                    case TRANSIENT -> {
                        // 스킵 기록 금지 — 다음 실행에 다시 시도한다.
                        transientFiltered.incrementAndGet();
                        log.debug("지오코딩 일시 장애로 이번 실행에서 제외 cvlcpt_id={} addr={}",
                                row.cvlcptId(), row.dclrOcrnRgn());
                        throw new IllegalStateException("민원 지오코딩 일시 장애 — load_dt 누락 방지를 위해 중단합니다.");
                    }
                    // note 에는 단계별 판정을 그대로 싣는다 — "어느 타입에서 걸렸는지",
                    // "부번제거까지 갔는지"가 사후 원인 분석의 전부다(Step 7-4).
                    case NOT_FOUND -> throw new GeocodeSkippableException(
                            GeocodeSkippableException.Reason.GEOCODE_FAILED,
                            trackingId(row), row.dclrOcrnRgn(),
                            outcome.detail() == null ? GEOCODE_FAILED_FALLBACK_NOTE : outcome.detail());
                    case FOUND -> {
                        Coordinate point = outcome.point();
                        lat = point.lat();
                        lot = point.lot();
                    }
                }
            }
            if (emdCd == null) {
                emdCd = resolveEmdCd(row, lat, lot);
            }
            geocoded.incrementAndGet();
        }

        // ── 5. 나머지 필드 매핑
        //
        // 🔴 발생일시는 원천 ocrn_dt 를 **그대로** 쓴다 — 시·분·초가 여기서 살아남는다.
        //    원천이 값을 주지 않거나 파싱이 깨진 행만 신고일 자정으로 대체한다(ocrn_dt 는 NOT NULL).
        LocalDateTime ocrnDt = Optional.ofNullable(row.ocrnDt())
                .orElseGet(() -> atStartOfDay(row.dclrYmd()));
        return new CvlcptRecord(
                row.cvlcptId(),
                typeCd,
                emdCd,
                scaled(lat),
                scaled(lot),
                ocrnDt,
                // 유입경로도 원천 코드 우선 — 없으면 src_path 원문을 코드로 옮긴다.
                Optional.ofNullable(CvlcptCodeMapping.srcCdIfKnown(row.srcCd()))
                        .orElseGet(() -> CvlcptCodeMapping.srcCd(row.srcPath())),
                row.cvlcptCn(),
                // 원천 load_dt를 보존한다. 결측을 답변일/현재 시각으로 대체하면 증분 누락이 생긴다.
                row.loadDt(),
                // 감정은 원천 원문(좋음/보통/나쁨 · 종전 긍정/부정/보통) → APRS_CD 코드로 변환한다.
                CvlcptCodeMapping.aprsCd(row.aprsRaw()),
                // ↓ 여기부터는 원천 원문 그대로다. 가공하지 않는다 —
                //   staging 이 사라진 지금 이 값들이 곧 "원본" 이다.
                row.dclrYmd(),
                row.ttl(),
                row.dclrOcrnRgn(),
                row.prgrsStts(),
                row.ansPrcsStts(),
                row.ansYmd(),
                row.aplcntSe(),
                row.dclrFld(),
                row.rwrdYn(),
                row.prcsInstCnt(),
                row.prcsInstNm(),
                row.prcsInstPicNm(),
                row.dclrGrpNm(),
                row.mbrGrpNm(),
                row.dclrType(),
                // 🔁 2026-09-01 — 리터럴 null 을 그만두고 원천 원문을 적재한다. 분류의 입력으로
                //    쓴 값이 행에 남아 있어야 규칙을 고쳤을 때 재분류할 수 있다(TSK-4109).
                row.prkCvlcptType(),
                row.srcPath());
    }

    /**
     * 유형 코드를 정한다 — <b>원천 코드 → 원천 분류명 → {@code dclr_type} CASE</b> 3단.
     *
     * @return {@code 01~05}, 어디에서도 정하지 못하면 {@code 99}
     */
    private static String trackingId(ComplaintOriginRow row) {
        return row.cvlcptId() != null ? row.cvlcptId()
                : jeju.datahub.batch.collect.SourceFingerprint.hash(row.dclrYmd(), row.ttl(), row.cvlcptCn(), row.dclrOcrnRgn());
    }

    private String resolveTypeCd(ComplaintOriginRow row) {
        // ① 원천이 이미 코드로 판정해 줬으면 그대로 쓴다. 공통코드에 없는 값은 채택하지 않고
        //    ②③ 으로 넘긴다 — 화면 필터가 못 읽는 코드를 적재하느니 우리가 다시 분류하는 편이 낫다.
        String fromSource = CvlcptCodeMapping.typeCdIfKnown(row.cvlcptTypeCd());
        if (fromSource != null) {
            sourceTypeCds.incrementAndGet();
            return fromSource;
        }

        // ②③ prk_cvlcpt_type(원천 분류명) 우선, 없으면 dclr_type 을 CASE 에 태운다.
        //     2026-09-01 실측: 두 경로의 결과가 40,878행에서 전수 일치(불일치 0)라 ②를 살려도
        //     기존 분류 결과는 바뀌지 않는다. ②는 원천이 CASE 좌변 밖의 새 유형을 보내기
        //     시작했을 때 그것을 놓치지 않기 위한 경로다.
        String typeName = CvlcptCodeMapping.typeName(row.prkCvlcptType(), row.dclrType());
        String typeCd = CvlcptCodeMapping.typeCd(typeName);
        if (typeCd != null) {
            return typeCd;
        }
        unclassified.incrementAndGet();
        // 분류 실패의 원인이 된 원본 값 — CASE 를 통과하지 못했으면 dclr_type 원문을 남긴다.
        String rawValue = typeName != null ? typeName : row.dclrType();
        unknownTypeCounts.computeIfAbsent(String.valueOf(rawValue), value -> {
            log.warn("유형 분류 미지 값 — {} 로 적재(지오코딩 생략). value='{}' cvlcpt_id={}",
                    CvlcptCodeMapping.TYPE_CD_ETC, value, row.cvlcptId());
            return new AtomicLong();
        }).incrementAndGet();
        return CvlcptCodeMapping.TYPE_CD_ETC;
    }

    /**
     * 원천 좌표를 채택할 수 있으면 그대로, 아니면 {@code null}(호출자가 지오코딩으로 되돌린다).
     *
     * <p>범위를 벗어난 값을 그대로 넘기면 DB 가 numeric overflow 로 <b>청크 전체</b>를 롤백시킨다
     * ({@link #MAX_LAT} 주석). 조용히 0 으로 깎지 않고 <b>없는 셈 치는</b> 이유는, 그래야 정상
     * 경로인 지오코딩이 그 행을 다시 살려 내기 때문이다.
     */
    static BigDecimal usableCoordinate(BigDecimal value, BigDecimal absLimit) {
        if (value == null || value.abs().compareTo(absLimit) > 0) {
            return null;
        }
        return value;
    }

    /**
     * 공간매칭. 실패는 <b>좌표 + 최근접 행정동/거리</b>를 note 에 실어 스킵 예외로 올린다.
     *
     * <p>예: {@code lat=33.5275699752 lot=126.5446207166, 최근접 50110107(건입동) 60.25m > 버퍼 50m}
     * — 이 한 줄로 "버퍼만 넓히면 살아나는 건(60m)"과 "좌표가 잘못된 건(5km)"이 구분된다(Step 7-4).
     */
    private String resolveEmdCd(ComplaintOriginRow row, BigDecimal lat, BigDecimal lot) {
        AdmDistMatch match = admDistResolver.resolve(lat, lot);
        if (match.matched()) {
            return match.emdCd();
        }
        throw new GeocodeSkippableException(
                GeocodeSkippableException.Reason.EMD_UNRESOLVED,
                trackingId(row), row.dclrOcrnRgn(),
                "lat=" + lat + " lot=" + lot + ", " + match.describeNearest());
    }

    /**
     * {@code char(10)} 날짜 문자열 → {@code 00:00:00} 타임스탬프.
     *
     * <p>🔁 2026-08-24 — 발생일시({@code ocrn_dt})는 더 이상 이 메서드를 거치지 않는다. 원천
     * {@code ocrn_dt} 가 시·분·초를 주므로 그대로 쓴다. 여기 남은 쓰임은 답변일({@code ans_ymd})→
     * {@code load_dt} 조립과, 원천이 발생일시를 주지 않은 행의 대체값뿐이다.
     *
     * <p>신고일·답변일은 날짜뿐이라 시각이 없다. 형식이 깨진 값은 삼키지 않고
     * {@link java.time.format.DateTimeParseException} 으로 터뜨린다 — 스킵 사유 2종
     * ({@code GEOCODE_FAILED}/{@code EMD_UNRESOLVED})에 해당하지 않는 <b>원천 품질 문제</b>라
     * 조용히 넘기면 잘못된 날짜로 적재되거나 행이 사라진다.
     *
     * @return 빈 값이면 {@code null}
     */
    private static LocalDateTime atStartOfDay(String ymd) {
        if (ymd == null || ymd.isBlank()) {
            return null;
        }
        return LocalDate.parse(ymd.strip()).atStartOfDay();
    }

    /**
     * 좌표를 DB 컬럼 자릿수로 맞춘다.
     *
     * <p>{@link Coordinate} 가 이미 10자리로 만들어 두지만(캐시 히트 경로와 값이 달라지지 않게)
     * 여기서 한 번 더 확정한다 — 좌표 출처가 늘어도(다른 제공자, 캐시, <b>원천이 준 값</b>, 테스트)
     * Writer 로 가는 값의 자릿수는 이 한 줄이 보장한다. 이미 10자리면 값이 바뀌지 않는다.
     */
    private static BigDecimal scaled(BigDecimal value) {
        return value == null ? null : value.setScale(COORD_SCALE, RoundingMode.HALF_UP);
    }

    /** 처리한 행 수(스킵·필터 포함). */
    public long processed() {
        return processed.get();
    }

    /** 미분류(99)로 적재한 행 수 — 지오코딩을 건너뛴 수와 같다. */
    public long unclassified() {
        return unclassified.get();
    }

    /** 좌표·행정동까지 확보한 주차민원 행 수(원천이 준 값을 그대로 쓴 행 포함). */
    public long geocoded() {
        return geocoded.get();
    }

    /** 일시 장애로 수집을 중단시킨 행 수 — 다음 실행에 다시 시도한다. */
    public long transientFiltered() {
        return transientFiltered.get();
    }

    /** 원천이 준 {@code cvlcpt_type_cd} 를 그대로 채택한 행 수 — 우리가 분류하지 않은 수다. */
    public long sourceTypeCds() {
        return sourceTypeCds.get();
    }

    /** 원천이 준 좌표를 그대로 채택한 행 수 — 그만큼 지오코딩 왕복을 아꼈다. */
    public long sourceCoordinates() {
        return sourceCoordinates.get();
    }

    /** 원천이 준 {@code emd_cd} 를 그대로 채택한 행 수 — 그만큼 공간매칭 쿼리를 아꼈다. */
    public long sourceEmdCds() {
        return sourceEmdCds.get();
    }

    /** 미지 유형 값별 누계(원천 표기 변경 감지용). */
    public Map<String, Long> unknownTypeCounts() {
        return unknownTypeCounts.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get()));
    }

    /**
     * 카운터·미지 유형 누계를 비운다 — <b>Job 시작 시점에 호출한다</b>(Step 7-4).
     *
     * <p>싱글턴 빈이라 리셋하지 않으면 같은 JVM 에서 두 번째로 돌린 Job 의 종료 요약에 <b>1회차
     * 수치가 섞인다.</b> 미지 유형 맵도 함께 비워야 "이번 실행에서 새로 나타난 표기"를 볼 수 있다
     * (값별 WARN 도 실행마다 한 번씩 다시 남는다).
     */
    public void resetStats() {
        processed.set(0);
        unclassified.set(0);
        geocoded.set(0);
        transientFiltered.set(0);
        sourceTypeCds.set(0);
        sourceCoordinates.set(0);
        sourceEmdCds.set(0);
        unknownTypeCounts.clear();
    }

    /** Job 종료 시 한 번 호출한다(Step 7 리스너). 공간매칭 통계는 {@link AdmDistResolver#logStats()} 가 남긴다. */
    public void logStats() {
        long total = processed.get();
        double unknownRatio = total == 0 ? 0 : (double) unclassified.get() / total;
        log.info("민원 가공 요약 — 처리 {}건 / 지오코딩·매칭 완료 {}건 / 미분류(99) {}건({}%) / 일시장애 제외 {}건",
                total, geocoded.get(), unclassified.get(),
                String.format("%.1f", unknownRatio * 100), transientFiltered.get());
        // 원천이 채워 주기 시작하면 이 줄의 수치가 0 에서 올라간다 — 그때 지오코딩 쿼터가
        // 왜 줄었는지를 이 한 줄로 설명할 수 있어야 한다(2026-09-01 현재는 전부 0 이 정상).
        log.info("원천 제공 가공값 채택 — 유형코드 {}건 / 좌표 {}건 / 행정동 {}건 (나머지는 배치가 계산)",
                sourceTypeCds.get(), sourceCoordinates.get(), sourceEmdCds.get());
        if (unknownRatio > UNKNOWN_TYPE_WARN_RATIO) {
            log.warn("미지 유형 비율 {}% (경고선 {}%). 값별 내역={} — 원천 표기가 바뀌었는지 확인할 것",
                    String.format("%.1f", unknownRatio * 100),
                    String.format("%.0f", UNKNOWN_TYPE_WARN_RATIO * 100), unknownTypeCounts());
        }
    }
}
