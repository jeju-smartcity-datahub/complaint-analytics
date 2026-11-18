package jeju.datahub.batch.pblpklt;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jeju.datahub.batch.admdst.AdmDistMatch;
import jeju.datahub.batch.admdst.AdmDistResolver;
import jeju.datahub.batch.geocode.CompositeGeocoder;
import jeju.datahub.batch.geocode.JejuBounds;
import jeju.datahub.batch.pblpklt.api.PblpkltOriginRow;
import jeju.datahub.batch.geocode.Coordinate;
import jeju.datahub.batch.geocode.GeocodeOutcome;

/**
 * 가공 단계 메인 Processor — 이름 폴백 · 유형 분류 · 지오코딩 · 행정동 해석.
 *
 * <pre>
 * 1. pklt_nm 폴백 — pklt_nm → pklt_addr → "(이름 없음)"          (§7-2)
 * 2. 유형 분류 — PblpkltCodeMapping.resolveTypeCd(코드, 이름)     (§7-4)
 * 3. 좌표 확보
 *      ├ 원본 lat/lot 둘 다 채워짐 → 그대로 사용
 *      └ 어느 하나라도 NULL → CompositeGeocoder.geocode(pklt_addr → pklt_nm)  (§7-1)
 *           ├ FOUND → 좌표 확보
 *           ├ NOT_FOUND → GEOCODE_FAILED 예외
 *           └ TRANSIENT → null 반환(filter)
 * 4. 행정동 해석                                                   (§7-3)
 *      ├ 1) AdmDistResolver 공간매칭          (최우선)
 *      ├ 2) emd_nm 이름 조회 (parking.tb_admdst) (폴백)
 *      └ 3) 둘 다 실패 → EMD_UNRESOLVED 예외
 * </pre>
 *
 * <h2>🔁 2026-09-18 — ID 배정 단계가 사라졌다</h2>
 * 종전 1단계는 인메모리 배정기가 {@code origin_pk} 를 조회해
 * {@code {읍면동}-{순번}} 을 매기는 일이었다. 그 카운터가 스킵·롤백마다 순번을 소모했고,
 * 이제 {@code pklt_id} 는 <b>DB 가 IDENTITY 로 매긴다</b>(마이그레이션 70). 이 Processor 는
 * 원천 키를 {@link PblpkltRecord#originPk()} 로 그대로 나르기만 한다.
 *
 * <h2>🔴 분류를 지오코딩보다 먼저 한다</h2>
 * cvlcpt 와 같은 쿼터 절감 설계. {@code ETC} 라도 좌표가 없으면 지오코딩이 도는 점만 다르다 —
 * {@code ETC} 는 "여전히 실재하는 주차장" 이기 때문이다.
 *
 * <h2>🔴 {@code TRANSIENT} 를 {@code NOT_FOUND} 와 같이 다루면 안 된다</h2>
 * 일시 장애는 {@code null} 반환(filter)으로 처리한다. 스킵 테이블에 넣으면 Reader 의
 * {@code NOT EXISTS} 가 다음 실행에서 그 행을 영구 제외한다.
 *
 * <h2>🔴 지오코딩 입력(§7-1) — {@code pklt_addr} 우선, 원문 적재와 분리</h2>
 * 새 원천은 {@code pklt_addr}("한림리 1011")을 실제로 준다. 입력 우선순위는
 * <b>{@code pklt_addr} → (비면) {@code pklt_nm}</b> 이고, 둘 다 비면 재시도해도 같으므로
 * {@code GEOCODE_FAILED}(note {@code ADDR_EMPTY})로 확정 스킵한다 —
 * {@code Reason} 을 늘리지 않는 이유는 실행 이력 지표가 사유 2컬럼 고정이라 새 사유가 집계에서
 * 조용히 사라지기 때문이다.
 *
 * <h2>🔴 접두 2단 시도 + 제주 범위 가드 (🔁 2026-09-29 확정)</h2>
 * 주소에 시·군 접두가 없어(실측 0건) VWorld 가 <b>제주 밖 동명 지번을 물어왔다</b> — 미결로 둔
 * 대가가 {@code tb_pblpklt} <b>315행의 육지 좌표</b>였다(대구·강릉·경주 등, 2026-09-29 실측).
 * 이제 접두를 붙이고 <b>범위를 벗어난 좌표는 채택하지 않는다.</b>
 *
 <pre>
 * 후보 1) {접두}{sgg_nm} {emd_nm} {주소}   ← 가장 온전한 주소
 * 후보 2) {접두}{sgg_nm} {주소}            ← emd_nm 을 뺀다 (아래 ②)
 * 후보 3) {접두}{주소}                     ← 최후 수단
 * 후보 4) {주소}                           ← 🔴 접두가 빈 값일 때만(되돌림 스위치). 접두가 설정된
 *                                            동안 원문으로 폴백하지 않는다 — 315행을 만든 경로다.
 * </pre>
 *
 * <p>🔁 <b>2026-09-29 — {@code sgg_nm} 을 접두에 넣는다(후보 1·2 신설).</b> 종전에는 시군구를 빼
 * 뒀다 — 좌표 정확도에 기여가 <b>0건</b>이었기 때문이다(실측: {@code {시군구} {emd_nm}} 과
 * {@code {emd_nm}} 이 전 행 동일 좌표). 넣는 이유는 <b>정확도가 아니라 3단(장소검색) 부활</b>이다:
 * VWorld 장소검색은 정규 주소({@code 제주특별자치도 제주시 한경면 신창리 581})를 돌려주는데 우리
 * 질의에 시군구가 없으면 {@code matchesQuery} 의 정확 일치가 <b>영원히 실패</b>해
 * ({@code place=ADDR_MISMATCH}) 찾은 좌표를 버렸다.
 *
 * <p>🔴 <b>{@code sgg_nm} 은 원천이 준다 — 역조회하지 않는다.</b> 신규 데이터셋
 * ({@code test01.tb_pblpklt})이 100% 채워 보내므로 {@code emd_nm} → {@code tb_admdst} 역조회와
 * 동명 중복({@code 월평동} 이 제주시·서귀포시 둘) 문제를 겪지 않는다. 🔴 옛 데이터셋에는 이 컬럼이
 * 없어 SELECT 가 실패한다 — 데이터셋과 원천 테이블을 <b>짝으로</b> 바꿔야 한다.
 *
 * <p>② <b>후보 2가 {@code emd_nm} 을 빼는 이유</b> — 원천 {@code emd_nm} 은 <b>행정동</b>인데
 * {@code pklt_addr} 은 <b>법정동</b>일 수 있다({@code 도남동 65-3} + {@code emd_nm=이도이동}).
 * 둘을 함께 쓰면 {@code … 이도이동 도남동 65-3} 이라는 모순 주소가 되어 반드시 실패한다.
 * 시군구만 남기면 {@code 제주특별자치도 제주시 도남동 65-3} 으로 온전해진다.
 *
 * <p>🔴 <b>{@code TRANSIENT} 가 나오면 즉시 멈추고 다음 후보로 넘어가지 않는다.</b> 후보 2는
 * 정밀도가 낮은 수단이므로, 일시 장애 때문에 후보 2로 내려가면 <b>장애가 정밀도 하락으로 굳는다.</b>
 * 그 행은 {@code null} 반환(filter)으로 이번 실행에서만 빠지고 다음 실행이 후보 1부터 다시 시도한다.
 *
 * <p>🔴 접두는 <b>지오코딩 입력에만</b> 붙고 {@code PblpkltRecord.pkltAddr}(= DB {@code pklt_addr})
 * 에는 붙지 않는다 — 적재는 늘 원문이다.
 *
 * <h2>🔴 행정동 이름 폴백(§7-3)은 폴백일 뿐이다 — 순서를 뒤집지 않는다</h2>
 * 이름은 중복·표기 흔들림이 있고(행정동명 {@code 화북동} vs 법정동명 {@code 화북일동}),
 * 좌표가 실제로 가리키는 법정동과 다를 수 있다. 순서를 뒤집으면 좌표는 맞는데 행정동만 틀린 행이
 * 조용히 생긴다. 실측 실효도 정확 일치 기준 <b>75.2%(1,709/2,272)</b> 로 만능이 아니다
 * (나머지는 행정동 이름이라 법정동 표 {@code tb_admdst} 에 없다).
 *
 * <p>🔴 <b>같은 이름이 둘 이상인 법정동은 폴백 대상에서 제외</b>한다({@code 월평동} 이 제주시·
 * 서귀포시 두 곳). 어느 쪽인지 모르는 채 하나를 고르면 틀린 값이 조용히 들어간다.
 *
 * <p>폴백으로 채운 건수는 {@link #emdNameFallback()} 으로 노출돼
 * {@code batch.tb_btch_excn_hstry.emd_name_fallback} 에 남는다 — 안 남기면
 * "공간매칭이 잘 되고 있다" 는 착각이 굳는다.
 */
@Component
public class PblpkltGeocodeProcessor implements ItemProcessor<PblpkltFetchRow, PblpkltRecord> {

    private static final Logger log = LoggerFactory.getLogger(PblpkltGeocodeProcessor.class);

    /** DB 컬럼 {@code NUMERIC(12,10)}/{@code NUMERIC(13,10)} 과 동일한 소수 자릿수. */
    static final int COORD_SCALE = 10;

    /** {@code pklt_nm}·{@code pklt_addr} 이 모두 비었을 때 쓰는 이름. 48-PBLPKLT-DIRECT.sql 표기와 같다. */
    static final String PKLT_NM_UNKNOWN = "(이름 없음)";

    /** 주소가 아예 없어 지오코딩을 시도조차 못 한 건의 note. {@code Reason} 은 늘리지 않는다(§7-1). */
    static final String NOTE_ADDR_EMPTY = "ADDR_EMPTY";

    /**
     * 좌표를 받았지만 제주 범위 밖이라 거부한 건의 note 접두.
     * 🔴 {@code Reason} 을 늘리지 않고 note 로 구분한다 — 실행 이력 지표가 사유 2컬럼 고정이라
     * 새 사유는 집계에서 조용히 사라진다({@link #NOTE_ADDR_EMPTY} 와 같은 규율).
     */
    static final String NOTE_OUT_OF_JEJU = "OUT_OF_JEJU";

    /**
     * 공간매칭이 짚은 행정동과 원천 {@code emd_nm} 이 어긋난 건의 anomaly 유형.
     *
     * <p>🔴 <b>관측만 한다 — 거부하지 않는다.</b> 제주 범위 가드를 통과한 좌표 중
     * "제주 안이지만 틀린 위치"를 드러내는 유일한 단서다(실측: {@code 396} + {@code emd_nm=용담이동}
     * 에 시·도 접두만 붙이면 서귀포 중문 좌표가 나온다). 거부로 만들면 행정동명/법정동명 표기 차이와
     * 경계 인접 때문에 멀쩡한 행이 대량 스킵된다(§7-3 정확도 75.2%).
     */
    static final String ANOMALY_EMD_MISMATCH = "EMD_MISMATCH";

    /** 이름 폴백 사전 — 법정동 74행뿐이라 한 번 읽어 들고 있는다. */
    private static final String ADMDST_NAME_SQL = "SELECT emd_nm, emd_cd FROM parking.tb_admdst";

    private final CompositeGeocoder geocoder;
    private final AdmDistResolver admDistResolver;
    private final JdbcTemplate jdbc;

    /** 제주 범위 정본 — 장소검색 bbox 와 같은 값을 읽는다. */
    private final JejuBounds jejuBounds;

    /**
     * 지오코딩 입력 앞에 붙일 접두(운영값 {@code "제주특별자치도 "}).
     * 🔴 <b>비우면 접두·후보 체인이 모두 꺼져 종전 동작(원문 1회)으로 돌아간다</b> — 되돌림
     * 스위치이자 315행을 만든 재발 경로다. 🔴 적재되는 {@code pklt_addr} 원문에는 영향이 없다.
     */
    private final String geocodeAddrPrefix;

    /** 후보 1에 원천 {@code emd_nm} 을 포함할지. 실측 18/30 → 26/30 의 차이를 만든 스위치다. */
    private final boolean geocodePrefixWithEmd;

    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong geocoded = new AtomicLong();
    private final AtomicLong coordinateSkipped = new AtomicLong();
    private final AtomicLong transientFiltered = new AtomicLong();
    /** 공간매칭 실패 후 {@code emd_nm} 이름 조회로 채운 건수(§7-3 지표). */
    private final AtomicLong emdNameFallback = new AtomicLong();
    /** 후보 1이 실패해 후보 2(정밀도 낮은 수단)로 내려간 건수 — 늘어나면 원천 주소 품질을 의심한다. */
    private final AtomicLong prefixFallback = new AtomicLong();
    /** 🔴 좌표를 받았으나 제주 범위 밖이라 거부한 횟수(후보별 누적). 0 이 정상이다. */
    private final AtomicLong outOfJejuRejected = new AtomicLong();
    /** 공간매칭 행정동 ≠ 원천 {@code emd_nm} 인 건수(관측만, 적재는 그대로 진행). */
    private final AtomicLong emdMismatch = new AtomicLong();
    /** {@code ETC} 로 적재한 건수 — 값별 내역은 {@link #unknownTypeCounts()}. */
    private final AtomicLong unclassified = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> unmappedTypes = new ConcurrentHashMap<>();

    /** {@code emd_nm} → {@code emd_cd}. 이름이 중복인 항목은 담지 않는다(위 주석). 지연 로딩. */
    private volatile Map<String, String> emdNameToCd;

    public PblpkltGeocodeProcessor(
            CompositeGeocoder geocoder,
            AdmDistResolver admDistResolver,
            JdbcTemplate jdbc,
            JejuBounds jejuBounds,
            @Value("${batch.pblpklt.geocode-addr-prefix:}") String geocodeAddrPrefix,
            @Value("${batch.pblpklt.geocode-prefix-with-emd:true}") boolean geocodePrefixWithEmd) {
        this.geocoder = geocoder;
        this.admDistResolver = admDistResolver;
        this.jdbc = jdbc;
        this.jejuBounds = jejuBounds;
        this.geocodeAddrPrefix = geocodeAddrPrefix == null ? "" : geocodeAddrPrefix;
        this.geocodePrefixWithEmd = geocodePrefixWithEmd;

        if (this.geocodeAddrPrefix.isEmpty()) {
            // 🔴 315행(육지 좌표)을 만든 상태다 — 의도한 것인지 드러나게 남긴다.
            log.warn("공영주차장 지오코딩 접두(batch.pblpklt.geocode-addr-prefix)가 비어 있습니다 "
                    + "— 원천 주소에 시·군이 없어 제주 밖 좌표가 적재될 수 있습니다.");
        }
    }

    @Override
    public PblpkltRecord process(PblpkltFetchRow item) {
        processed.incrementAndGet();
        // 앞 단계(anomaly 분류·식별자 검증)가 감싼 API 행을 꺼낸다. 곁가지 관측 결과는
        // 그대로 들고 가 Writer 가 같은 트랜잭션에서 기록한다.
        PblpkltOriginRow row = item.origin();

        // ── 1. pklt_nm 폴백 (§7-2) — 원천 65% 가 null 이다.
        //      종전에는 pklt_id 를 넣어 주차장명이 "12" 가 됐다. 주소를 먼저 쓴다.
        String pkltNm = firstNonBlank(row.pkltNm(), row.pkltAddr(), PKLT_NM_UNKNOWN);

        // ── 2. 유형 분류 (§7-4) — 원천이 코드를 주면 그 값, 아니면 이름 매핑.
        String typeCd = PblpkltCodeMapping.resolveTypeCd(row.pkltTypeCd(), row.pkltTypeNm());
        if (PblpkltCodeMapping.TYPE_CD_ETC.equals(typeCd)) {
            unclassified.incrementAndGet();
            // 값별로 WARN 은 첫 1건만 남긴다 — 2,272행이 전부 같은 미지 값이면 로그가 덮인다.
            String rawType = row.pkltTypeCd() != null && !row.pkltTypeCd().isBlank()
                    ? row.pkltTypeCd()
                    : row.pkltTypeNm();
            unmappedTypes.computeIfAbsent(String.valueOf(rawType), value -> {
                log.warn("유형 분류 미지 값 — ETC 로 적재. value='{}' origin_pk={}",
                        value, row.pkltId());
                return new AtomicLong();
            }).incrementAndGet();
        }

        // ── 3. 좌표 확보
        // 원천 좌표는 Double 이다(API 응답). DB 컬럼 자릿수에 맞춰 BigDecimal 로 옮긴다.
        BigDecimal lat = row.lat() == null ? null : scaled(BigDecimal.valueOf(row.lat()));
        BigDecimal lot = row.lot() == null ? null : scaled(BigDecimal.valueOf(row.lot()));
        if (lat == null || lot == null) {
            // 🔴 지오코딩 입력 = pklt_addr → (비면) pklt_nm. 적재용 원문(row.pkltAddr())과 다르다.
            String geocodeSource = firstNonBlank(row.pkltAddr(), row.pkltNm());
            if (geocodeSource == null) {
                // 주소도 이름도 없다 — 재시도해도 같으므로 확정 스킵. Reason 은 늘리지 않고
                // note 로 구분한다(§7-1).
                throw new PblpkltGeocodeSkippableException(
                        PblpkltGeocodeSkippableException.Reason.GEOCODE_FAILED,
                        row.pkltId(), null, NOTE_ADDR_EMPTY);
            }
            // 후보를 순서대로 시도하고 첫 '제주 범위 안' 좌표를 채택한다.
            List<String> candidates = geocodeCandidates(geocodeSource, row.emdNm(), row.sggNm());
            String lastInput = null;
            String lastDetail = null;
            for (int i = 0; i < candidates.size() && lat == null; i++) {
                String candidate = candidates.get(i);
                lastInput = candidate;
                GeocodeOutcome outcome = geocoder.geocode(candidate);
                switch (outcome.status()) {
                    case TRANSIENT -> {
                        // 🔴 다음 후보로 내려가지 않는다 — 후보 2는 정밀도가 낮아, 일시 장애가
                        //    정밀도 하락으로 굳는다. 이번 실행에서만 빼고 다음 실행이 후보 1부터 다시 한다.
                        transientFiltered.incrementAndGet();
                        log.debug("지오코딩 일시 장애로 이번 실행에서 제외 origin_pk={} 후보={}",
                                row.pkltId(), candidate);
                        return null;
                    }
                    case NOT_FOUND -> lastDetail = outcome.detail() == null
                            ? "지오코딩 4단 폴백 모두 주소 미발견"
                            : outcome.detail();
                    case FOUND -> {
                        Coordinate point = outcome.point();
                        BigDecimal candLat = scaled(point.lat());
                        BigDecimal candLot = scaled(point.lot());
                        if (!jejuBounds.contains(candLat, candLot)) {
                            // 🔴 여기가 315행을 만든 구멍이다. 좌표를 받았어도 제주 밖이면 버리고
                            //    다음 후보로 넘어간다 — 채택하면 이름 폴백이 emd_cd 를 채워
                            //    "제주 행정동 코드 + 육지 좌표" 가 조용히 적재된다.
                            outOfJejuRejected.incrementAndGet();
                            lastDetail = NOTE_OUT_OF_JEJU + "(" + jejuBounds.describe(candLat, candLot) + ")";
                            log.debug("제주 범위 밖 좌표를 거부했습니다. origin_pk={} 후보={} {}",
                                    row.pkltId(), candidate, lastDetail);
                            continue;
                        }
                        lat = candLat;
                        lot = candLot;
                        geocoded.incrementAndGet();
                        if (i > 0) {
                            prefixFallback.incrementAndGet();
                        }
                    }
                }
            }
            if (lat == null) {
                // 모든 후보가 미발견이거나 범위 밖 — 재시도해도 같으므로 확정 스킵.
                // 🔴 Reason 을 늘리지 않는다(실행 이력 지표가 사유 2컬럼 고정). 구분은 note 가 진다.
                throw new PblpkltGeocodeSkippableException(
                        PblpkltGeocodeSkippableException.Reason.GEOCODE_FAILED,
                        row.pkltId(), lastInput, lastDetail);
            }
        } else {
            coordinateSkipped.incrementAndGet();
        }

        // ── 4. 행정동 해석 (§7-3) — 공간매칭 우선, 실패 시에만 이름 폴백.
        AdmDistMatch match = admDistResolver.resolve(lat, lot);
        String emdCd;
        List<String> anomalyTypes = item.anomalyTypes();
        if (match.matched()) {
            emdCd = match.emdCd();
            // 🔴 좌표가 제주 안이어도 '엉뚱한 제주'일 수 있다.
            //    원천 emd_nm 을 코드로 바꿔 공간매칭 결과와 대조한다. 이름 형태로 비교하지 않는
            //    이유는 행정동명(화북동) vs 법정동명(화북일동) 차이가 섞여 있기 때문이다 —
            //    사전에 없는 이름은 null 이 되어 조용히 넘어간다(허위 경보를 만들지 않는다).
            String emdCdByName = resolveEmdCdByName(row.emdNm());
            if (emdCdByName != null && !emdCdByName.equals(emdCd)) {
                emdMismatch.incrementAndGet();
                anomalyTypes = withAnomaly(anomalyTypes, ANOMALY_EMD_MISMATCH);
                log.debug("행정동 불일치(관측만) origin_pk={} emd_nm={}({}) ↔ 공간매칭={} lat={} lot={}",
                        row.pkltId(), row.emdNm(), emdCdByName, emdCd, lat, lot);
            }
        } else {
            emdCd = resolveEmdCdByName(row.emdNm());
            if (emdCd == null) {
                throw new PblpkltGeocodeSkippableException(
                        PblpkltGeocodeSkippableException.Reason.EMD_UNRESOLVED,
                        row.pkltId(), row.pkltAddr(),
                        "lat=" + lat + " lot=" + lot + ", " + match.describeNearest()
                                + ", emd_nm 폴백도 실패(emd_nm=" + row.emdNm() + ")");
            }
            emdNameFallback.incrementAndGet();
            log.debug("공간매칭 실패 → emd_nm 이름 폴백으로 채움. origin_pk={} emd_nm={} emd_cd={}",
                    row.pkltId(), row.emdNm(), emdCd);
        }

        return new PblpkltRecord(
                // 🔴 원천이 준 키 그대로 — 마지막 관측 원천 키(origin_pk)이자 이상주소 기록의 키다.
                //    pklt_id 는 DB 가 IDENTITY 로 매기므로 여기서 만들지 않는다.
                row.pkltId(),
                pkltNm,
                emdCd,
                lat,
                lot,
                row.pkltCpcty(),
                typeCd,
                // 🔴 원천이 준 주소 원문 그대로 — 지오코딩 접두를 붙이지 않는다.
                row.pkltAddr(),
                // 🔴 앞 단계 분류 결과 + (있으면) EMD_MISMATCH. Writer 가 같은 트랜잭션에 기록한다.
                anomalyTypes,
                // ── 원천 확장 25종 (값 변형 없음)
                row.orgnlId(),
                row.orgnlSn(),
                row.emdNm(),
                row.pkltTypeNm(),
                row.pkltar(),
                row.prkCrgYn(),
                row.prkCrgCn(),
                row.prkCrgSwtcYm(),
                row.instlYm(),
                row.instlYmd(),
                row.rttrt(),
                row.dayAvgVhclCnt(),
                row.incmAmt(),
                row.expndAmt(),
                row.mngNope(),
                row.mngDeptNm(),
                row.pkltMthdNm(),
                row.evChrgrCnt(),
                row.ownshpTypeNm(),
                row.prkSlotGrd(),
                row.obstclCn(),
                row.obstclSlotCnt(),
                row.trfcSftySignInstlYn(),
                row.stprInstlYn(),
                row.rmrkCn());
    }

    /**
     * {@code emd_nm} 정확 일치로 {@code emd_cd} 를 찾는다 — <b>공간매칭이 실패했을 때만</b> 부른다.
     *
     * @return 못 찾으면 {@code null}(이름이 비었거나, 표에 없거나, 같은 이름이 둘 이상)
     */
    private String resolveEmdCdByName(String emdNm) {
        if (emdNm == null || emdNm.isBlank()) {
            return null;
        }
        return emdNameToCd().get(emdNm.strip());
    }

    /**
     * {@code parking.tb_admdst}(74행)를 한 번 읽어 이름 사전을 만든다.
     *
     * <p>🔴 <b>중복 이름은 담지 않는다</b> — 어느 쪽인지 모르는 채 하나를 고르면 틀린
     * {@code emd_cd} 가 조용히 들어간다. 담지 않으면 그 행은 {@code EMD_UNRESOLVED} 로 드러난다.
     */
    private Map<String, String> emdNameToCd() {
        Map<String, String> cached = emdNameToCd;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (emdNameToCd == null) {
                Map<String, String> loaded = new HashMap<>();
                Set<String> duplicated = new HashSet<>();
                for (Map<String, Object> r : jdbc.queryForList(ADMDST_NAME_SQL)) {
                    Object name = r.get("emd_nm");
                    Object code = r.get("emd_cd");
                    if (name == null || code == null || name.toString().isBlank()) {
                        continue;
                    }
                    String key = name.toString().strip();
                    if (loaded.put(key, code.toString()) != null) {
                        duplicated.add(key);
                    }
                }
                duplicated.forEach(loaded::remove);
                if (!duplicated.isEmpty()) {
                    log.info("emd_nm 이름 폴백 사전에서 중복 이름 {}건 제외 — {}",
                            duplicated.size(), duplicated);
                }
                emdNameToCd = Map.copyOf(loaded);
            }
            return emdNameToCd;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    private static BigDecimal scaled(BigDecimal v) {
        return v == null ? null : v.setScale(COORD_SCALE, RoundingMode.HALF_UP);
    }

    // ── 이력 지표 노출 (§7-3 5번) — 종전에는 logStats() 뿐이라 리스너가 읽을 수 없었다.

    public long transientFiltered() {
        return transientFiltered.get();
    }

    /** 공간매칭 실패 후 {@code emd_nm} 이름 조회로 채운 건수. */
    public long emdNameFallback() {
        return emdNameFallback.get();
    }

    /**
     * 후보 1({@code emd_nm} 포함)이 실패해 후보 2로 내려간 건수.
     * 늘어나면 원천 {@code emd_nm} 표기나 주소 품질이 흔들린 것이다.
     */
    public long prefixFallback() {
        return prefixFallback.get();
    }

    /**
     * 🔴 좌표를 받았으나 제주 범위 밖이라 거부한 횟수.
     * <b>0 이 정상</b>이며, 꾸준히 잡히면 접두가 듣지 않는 주소 패턴이 있다는 신호다.
     */
    public long outOfJejuRejected() {
        return outOfJejuRejected.get();
    }

    /** 공간매칭 실패 후 이름 폴백으로 채운 건수 대비, 불일치로 관측된 건수. */
    public long emdMismatch() {
        return emdMismatch.get();
    }

    /**
     * anomaly 유형을 덧붙인 새 리스트. {@code item.anomalyTypes()} 는 불변이라 그대로 못 넣는다.
     * 같은 유형이 이미 있으면 그대로 둔다(테이블 UNIQUE 와 중복 INSERT 를 미리 피한다).
     */
    private static List<String> withAnomaly(List<String> existing, String type) {
        if (existing.contains(type)) {
            return existing;
        }
        List<String> merged = new ArrayList<>(existing.size() + 1);
        merged.addAll(existing);
        merged.add(type);
        return List.copyOf(merged);
    }

    /**
     * 지오코딩 입력 후보를 우선순위대로 만든다 (실측 근거는 클래스 javadoc).
     *
     * <p>package-private 인 이유는 <b>후보 순서와 개수가 이 클래스의 계약</b>이라 테스트가 직접
     * 확인해야 하기 때문이다 — {@code process()} 를 통해서는 외부 호출 없이 볼 수 없다.
     *
     * @param source 지오코딩 입력 원문({@code pklt_addr} → 비면 {@code pklt_nm})
     * @param emdNm  원천이 준 읍면동명. 비어 있으면 후보 1을 만들지 않는다
     */
    List<String> geocodeCandidates(String source, String emdNm, String sggNm) {
        if (geocodeAddrPrefix.isEmpty()) {
            // 되돌림 스위치 — 종전 동작(원문 1회) 그대로. 🔴 접두가 설정된 동안에는
            // 이 후보를 쓰지 않는다(원문 조회가 육지 좌표를 만든 경로다).
            return List.of(source);
        }

        String raw = source.trim();
        String province = geocodeAddrPrefix.trim();
        String input = raw;
        List<String> candidates = new ArrayList<>();
        // 원천이 이미 제주 접두를 주면 그대로 먼저 조회한다. 같은 접두를 두 번 붙이지 않는다.
        if (input.startsWith(province)) {
            addUnique(candidates, input);
            input = input.substring(province.length()).trim();
        }
        String emd = emdNm == null ? "" : emdNm.trim();
        String sgg = sggNm == null ? "" : sggNm.trim();
        if (input.startsWith("제주시 ") || input.startsWith("서귀포시 ")) {
            int boundary = input.indexOf(' ');
            sgg = input.substring(0, boundary);
            input = input.substring(boundary + 1).trim();
        }
        addAreaCandidates(candidates, input, emd, sgg);
        // 원문 전체가 실패한 뒤에만 다필지의 첫 주소를 대표 위치로 조회한다.
        // 저장 주소/식별 기준은 계속 원문 전체이며, 서로 다른 다필지 주소를 합치지 않는다.
        int comma = input.indexOf(',');
        if (comma > 0) {
            String firstAddress = input.substring(0, comma).trim();
            if (firstAddress.matches(".*[0-9].*")) addAreaCandidates(candidates, firstAddress, emd, sgg);
        }
        return List.copyOf(candidates);
    }

    private void addAreaCandidates(List<String> candidates, String source, String emd, String sgg) {
        String area = sgg.isEmpty() ? "" : sgg + " ";
        if (geocodePrefixWithEmd && !emd.isEmpty() && !source.startsWith(emd)) {
            addUnique(candidates, geocodeAddrPrefix + area + emd + " " + source);
        }
        if (!area.isEmpty()) addUnique(candidates, geocodeAddrPrefix + area + source);
        addUnique(candidates, geocodeAddrPrefix + source);
    }

    /** 같은 문자열을 두 번 조회하지 않는다 — 후보가 겹치면 쿼터만 버린다. */
    private static void addUnique(List<String> candidates, String candidate) {
        if (!candidates.contains(candidate)) {
            candidates.add(candidate);
        }
    }

    /** {@code ETC} 로 적재한 건수. */
    public long unclassified() {
        return unclassified.get();
    }

    /** 미지 유형 값별 누계 — {@code batch.tb_btch_excn_hstry.unknown_types}(JSONB) 로 간다. */
    public Map<String, Long> unknownTypeCounts() {
        return unmappedTypes.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }

    /**
     * 🔴 이 빈은 싱글턴이다 — 같은 JVM 에서 Job 을 두 번 돌리면 2회차 지표에 1회차 수치가
     * 그대로 더해진다. Job 시작 시점에 비운다({@link PblpkltFetchHistoryListener#beforeJob}).
     */
    public void resetStats() {
        processed.set(0);
        geocoded.set(0);
        coordinateSkipped.set(0);
        transientFiltered.set(0);
        emdNameFallback.set(0);
        unclassified.set(0);
        unmappedTypes.clear();
    }

    public void logStats() {
        log.info("pblpklt 가공 stats — processed={} geocoded={} coordinateSkipped={} " +
                        "transientFiltered={} emdNameFallback={} unclassified={} unmappedTypes={}",
                processed.get(), geocoded.get(), coordinateSkipped.get(),
                transientFiltered.get(), emdNameFallback.get(), unclassified.get(),
                unknownTypeCounts());
    }
}
