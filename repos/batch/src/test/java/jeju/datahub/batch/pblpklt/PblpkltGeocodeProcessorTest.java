package jeju.datahub.batch.pblpklt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import jeju.datahub.batch.admdst.AdmDistMatch;
import jeju.datahub.batch.admdst.AdmDistResolver;
import jeju.datahub.batch.geocode.CompositeGeocoder;
import jeju.datahub.batch.geocode.Coordinate;
import jeju.datahub.batch.geocode.GeocodeOutcome;
import jeju.datahub.batch.geocode.JejuBounds;
import jeju.datahub.batch.pblpklt.api.PblpkltOriginRow;

/**
 * {@link PblpkltGeocodeProcessor} 단위 테스트 — 재배선 6케이스.
 *
 * <p>협력자(지오코딩 · 공간매칭 · 이름 폴백 조회)는 전부 mock 이다. 여기서 검증하는
 * 것은 <b>무엇을 입력으로 넣고 어떤 값을 골라 적재하는가</b> 이지 VWorld·PostGIS 동작이 아니다.
 *
 * <h2>§7 재배선의 요점</h2>
 * <ol>
 *   <li>지오코딩 입력이 {@code pklt_nm} 이 아니라 <b>{@code pklt_addr}</b> 이다 (§7-1)</li>
 *   <li>{@code pklt_nm} 이 비면 이름 폴백이 {@code pklt_id}("12")가 아니라 <b>주소</b>다 (§7-2)</li>
 *   <li>공간매칭이 실패해도 {@code emd_nm} 이름 폴백으로 살아난다 (§7-3)</li>
 *   <li>유형은 이름 매핑으로 정하고 미매핑은 {@code ETC} 다 (§7-4)</li>
 *   <li>주소·이름이 둘 다 없으면 {@code GEOCODE_FAILED}(note {@code ADDR_EMPTY}) (§7-1)</li>
 * </ol>
 */
class PblpkltGeocodeProcessorTest {

    private static final String EMD_CD = "50130250";
    private static final Coordinate POINT = Coordinate.of("126.2521123456", "33.2185123456");

    private final CompositeGeocoder geocoder = mock(CompositeGeocoder.class);
    private final AdmDistResolver admDistResolver = mock(AdmDistResolver.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    /** 운영 기본값과 같은 제주 범위. */
    private static final String JEJU_BBOX = "126.10,33.10,127.00,34.05";

    /** 접두 없음(되돌림 상태) — 종전 케이스들이 이 프로세서로 계속 검증된다. */
    private final PblpkltGeocodeProcessor processor = newProcessor("", true);

    private PblpkltGeocodeProcessor newProcessor(String prefix, boolean withEmd) {
        return new PblpkltGeocodeProcessor(
                geocoder, admDistResolver, jdbc, new JejuBounds(JEJU_BBOX), prefix, withEmd);
    }

    /**
     * 이름 사전({@code parking.tb_admdst})을 stub 한 프로세서.
     *
     * <p>사전은 {@code jdbc.queryForList(ADMDST_NAME_SQL)} 로 지연 로딩되므로 그 반환을 흉내낸다 —
     * 실제 쿼리 결과 형태(`emd_nm`/`emd_cd` 키를 가진 Map 리스트)를 그대로 준다.
     */
    private PblpkltGeocodeProcessor newProcessorWithEmdDict(Map<String, String> nameToCd) {
        List<Map<String, Object>> rows = nameToCd.entrySet().stream()
                .map(e -> Map.of("emd_nm", (Object) e.getKey(), "emd_cd", (Object) e.getValue()))
                .toList();
        when(jdbc.queryForList(anyString())).thenReturn(rows);
        return newProcessor("", true);
    }

    // ── ① 지오코딩 입력은 pklt_addr 이다 (§7-1) ───────────────────────────────

    @Test
    @DisplayName("🔴 ① 지오코딩 입력은 pklt_addr 이다 — pklt_nm 이 아니다")
    void geocodesWithParkingAddressNotName() throws Exception {
        givenGeocodeSucceeds();

        PblpkltRecord record = processor.process(row(
                PblpkltOriginRow.builder()
                        .pkltId("1916")
                        .pkltNm("대정상모 공영노외1 주차장")
                        .pkltAddr("상모리 3964-1")
                        .emdNm("대정읍")));

        verify(geocoder).geocode("상모리 3964-1");
        // 적재되는 pklt_addr 은 늘 원문이다(접두를 붙여도 이 값에는 영향이 없다).
        assertThat(record.pkltAddr()).isEqualTo("상모리 3964-1");
        assertThat(record.lat()).isEqualByComparingTo(POINT.lat());
        assertThat(record.lot()).isEqualByComparingTo(POINT.lot());
        assertThat(record.emdCd()).isEqualTo(EMD_CD);
    }

    @Test
    @DisplayName("주소가 비면 이름으로 지오코딩한다 — 입력 우선순위는 주소 → 이름")
    void fallsBackToNameWhenAddressMissing() throws Exception {
        givenGeocodeSucceeds();

        processor.process(row(PblpkltOriginRow.builder()
                .pkltId("1916").pkltNm("한림공영주차장").pkltAddr(null).emdNm("한림읍")));

        verify(geocoder).geocode("한림공영주차장");
    }

    @Test
    @DisplayName("접두 설정은 지오코딩 입력에만 붙는다 — 적재되는 pklt_addr 원문은 그대로다")
    void prefixesGeocodeInputOnly() throws Exception {
        PblpkltGeocodeProcessor prefixed = newProcessor("제주특별자치도 ", true);
        givenGeocodeSucceeds();

        PblpkltRecord record = prefixed.process(row(PblpkltOriginRow.builder()
                .pkltId("1916").pkltAddr("상모리 3964-1").emdNm("대정읍")));

        // 🔁 후보 1은 emd_nm 을 포함한다(실측 18/30 → 26/30).
        verify(geocoder).geocode("제주특별자치도 대정읍 상모리 3964-1");
        assertThat(record.pkltAddr()).isEqualTo("상모리 3964-1");
    }

    // ── 후보 체인 + 제주 범위 가드 ───────────────────────────────────

    @Test
    @DisplayName("🔴 후보 순서는 시군구+읍면동 → 시군구 → 접두만 이다 — 원문으로 폴백하지 않는다")
    void candidateOrderIsSggEmdThenSggThenPrefixOnly() {
        PblpkltGeocodeProcessor p = newProcessor("제주특별자치도 ", true);

        assertThat(p.geocodeCandidates("661-9", "건입동", "제주시")).containsExactly(
                "제주특별자치도 제주시 건입동 661-9",
                "제주특별자치도 제주시 661-9",
                "제주특별자치도 661-9");
        // 🔴 원문("661-9")은 후보에 없다 — 그게 육지 좌표를 만든 경로다.
        assertThat(p.geocodeCandidates("661-9", "건입동", "제주시")).doesNotContain("661-9");
    }

    @Test
    @DisplayName("🔴 후보 1에 시군구가 들어가야 장소검색 정규 주소와 일치한다 — ADDR_MISMATCH 해소")
    void firstCandidateCarriesSigungu() {
        // 실측: 질의 '제주특별자치도 한경면 신창리 581' 은 반환 '제주특별자치도 제주시 한경면 신창리 581'
        //       과 정확 일치에 실패해 버려졌다(place=ADDR_MISMATCH). 시군구를 넣으면 같아진다.
        assertThat(newProcessor("제주특별자치도 ", true)
                .geocodeCandidates("신창리 581", "한경면", "제주시"))
                .first().isEqualTo("제주특별자치도 제주시 한경면 신창리 581");
    }

    @Test
    @DisplayName("emd_nm 이 비면 시군구 후보부터 — 접두만 붙인 것과 2개")
    void skipsEmdCandidateWhenEmdMissing() {
        PblpkltGeocodeProcessor p = newProcessor("제주특별자치도 ", true);

        assertThat(p.geocodeCandidates("661-9", null, "제주시"))
                .containsExactly("제주특별자치도 제주시 661-9", "제주특별자치도 661-9");
        assertThat(p.geocodeCandidates("661-9", "  ", "제주시"))
                .containsExactly("제주특별자치도 제주시 661-9", "제주특별자치도 661-9");
    }

    @Test
    @DisplayName("sgg_nm 이 비면 종전처럼 읍면동 → 접두만 2개 — 옛 데이터셋에서도 동작한다")
    void fallsBackWhenSigunguMissing() {
        PblpkltGeocodeProcessor p = newProcessor("제주특별자치도 ", true);

        assertThat(p.geocodeCandidates("661-9", "건입동", null))
                .containsExactly("제주특별자치도 건입동 661-9", "제주특별자치도 661-9");
    }

    @Test
    @DisplayName("🔴 주소가 이미 그 동명으로 시작하면 후보 1을 건너뛴다 — 중복 주소는 반드시 실패한다")
    void skipsEmdCandidateWhenAddressAlreadyStartsWithIt() {
        PblpkltGeocodeProcessor p = newProcessor("제주특별자치도 ", true);

        // '… 이도이동 이도이동 1035-4' 는 만들 필요가 없다 — API 왕복만 버린다.
        assertThat(p.geocodeCandidates("이도이동 1035-4", "이도이동", "제주시")).containsExactly(
                "제주특별자치도 제주시 이도이동 1035-4",
                "제주특별자치도 이도이동 1035-4");
    }

    @Test
    @DisplayName("🔴 행정동(emd_nm)과 주소(법정동)가 어긋나도 시군구 후보가 온전한 주소를 만든다")
    void sigunguOnlyCandidateSurvivesEmdConflict() {
        // 원천 emd_nm=이도이동(행정동) · 주소=도남동 65-3(법정동) → 둘을 함께 쓰면 모순 주소가 된다.
        assertThat(newProcessor("제주특별자치도 ", true)
                .geocodeCandidates("도남동 65-3", "이도이동", "제주시"))
                .contains("제주특별자치도 제주시 도남동 65-3");
    }

    @Test
    @DisplayName("접두가 비면 후보는 원문 1개 — 되돌림 스위치가 종전 동작을 그대로 준다")
    void emptyPrefixFallsBackToLegacySingleCandidate() {
        assertThat(newProcessor("", true).geocodeCandidates("661-9", "건입동", "제주시"))
                .containsExactly("661-9");
    }

    @Test
    @DisplayName("geocode-prefix-with-emd=false 면 읍면동 후보만 빠진다")
    void emdCandidateCanBeTurnedOff() {
        assertThat(newProcessor("제주특별자치도 ", false).geocodeCandidates("661-9", "건입동", "제주시"))
                .containsExactly("제주특별자치도 제주시 661-9", "제주특별자치도 661-9");
    }

    @Test
    @DisplayName("🔴 후보 1이 제주 범위 밖 좌표를 주면 거부하고 후보 2를 시도한다")
    void rejectsOutOfJejuAndTriesNextCandidate() throws Exception {
        PblpkltGeocodeProcessor p = newProcessor("제주특별자치도 ", true);
        when(admDistResolver.resolve(any(), any())).thenReturn(AdmDistMatch.matched(EMD_CD));
        // 후보 1 → 강릉(37.74, 128.89) · 후보 2 → 제주
        when(geocoder.geocode("제주특별자치도 건입동 661-9"))
                .thenReturn(GeocodeOutcome.found(Coordinate.of("128.8963", "37.7445")));
        when(geocoder.geocode("제주특별자치도 661-9")).thenReturn(GeocodeOutcome.found(POINT));

        PblpkltRecord record = p.process(row(PblpkltOriginRow.builder()
                .pkltId("1916").pkltAddr("661-9").emdNm("건입동")));

        assertThat(record.lat()).isEqualByComparingTo(POINT.lat());
        assertThat(p.outOfJejuRejected()).isEqualTo(1);
        assertThat(p.prefixFallback()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 모든 후보가 범위 밖이면 GEOCODE_FAILED + note OUT_OF_JEJU — 육지 좌표는 적재되지 않는다")
    void skipsWhenEveryCandidateIsOutOfJeju() {
        PblpkltGeocodeProcessor p = newProcessor("제주특별자치도 ", true);
        when(geocoder.geocode(anyString()))
                .thenReturn(GeocodeOutcome.found(Coordinate.of("128.8963", "37.7445")));

        assertThatThrownBy(() -> p.process(row(PblpkltOriginRow.builder()
                .pkltId("1916").pkltAddr("661-9").emdNm("건입동"))))
                .isInstanceOf(PblpkltGeocodeSkippableException.class)
                .satisfies(thrown -> {
                    PblpkltGeocodeSkippableException e = (PblpkltGeocodeSkippableException) thrown;
                    // 🔴 Reason 을 늘리지 않는다 — 구분은 note 가 진다.
                    assertThat(e.reason())
                            .isEqualTo(PblpkltGeocodeSkippableException.Reason.GEOCODE_FAILED);
                    assertThat(e.note()).contains(PblpkltGeocodeProcessor.NOTE_OUT_OF_JEJU)
                            .contains("37.7445");
                });
        assertThat(p.outOfJejuRejected()).isEqualTo(2);
        // 공간매칭까지 가지 않는다 — 좌표를 채택하지 않았으므로.
        verifyNoInteractions(admDistResolver);
    }

    @Test
    @DisplayName("🔴 후보 1이 TRANSIENT 면 즉시 멈춘다 — 일시 장애가 정밀도 하락으로 굳으면 안 된다")
    void stopsOnTransientWithoutTryingNextCandidate() throws Exception {
        PblpkltGeocodeProcessor p = newProcessor("제주특별자치도 ", true);
        when(geocoder.geocode("제주특별자치도 건입동 661-9"))
                .thenReturn(GeocodeOutcome.transientFailure());

        assertThat(p.process(row(PblpkltOriginRow.builder()
                .pkltId("1916").pkltAddr("661-9").emdNm("건입동")))).isNull();

        // 🔴 후보 2를 부르지 않는다. 불렀다면 정밀도 낮은 좌표가 장애 때문에 채택된다.
        verify(geocoder, never()).geocode("제주특별자치도 661-9");
        assertThat(p.outOfJejuRejected()).isZero();
    }

    @Test
    @DisplayName("🔴 원천 sgg_nm 이 실제 지오코딩 입력에 실린다 — 배선 확인")
    void sourceSigunguReachesGeocodeInput() throws Exception {
        PblpkltGeocodeProcessor p = newProcessor("제주특별자치도 ", true);
        when(admDistResolver.resolve(any(), any())).thenReturn(AdmDistMatch.matched(EMD_CD));
        when(geocoder.geocode("제주특별자치도 제주시 한경면 신창리 581"))
                .thenReturn(GeocodeOutcome.found(POINT));

        p.process(row(PblpkltOriginRow.builder()
                .pkltId("1916").pkltAddr("신창리 581").emdNm("한경면").sggNm("제주시")));

        verify(geocoder).geocode("제주특별자치도 제주시 한경면 신창리 581");
    }

    // ── 행정동 불일치 관측 ──────────────────────────────────

    @Test
    @DisplayName("🔴 공간매칭 행정동 ≠ 원천 emd_nm 이면 EMD_MISMATCH 를 남긴다 — 거부하지 않고 적재한다")
    void recordsEmdMismatchWithoutRejecting() throws Exception {
        givenGeocodeSucceeds();                                  // 공간매칭 → EMD_CD(50130250)
        // 원천 emd_nm 은 다른 행정동을 가리킨다 — 사전은 tb_admdst 조회 결과를 stub 한다.
        PblpkltGeocodeProcessor p = newProcessorWithEmdDict(Map.of("용담이동", "50110109"));

        PblpkltRecord record = p.process(row(PblpkltOriginRow.builder()
                .pkltId("1916").pkltAddr("396").emdNm("용담이동")));

        // 🔴 적재는 그대로 진행된다 — 관측 장치이지 거부 조건이 아니다.
        assertThat(record.emdCd()).isEqualTo(EMD_CD);
        assertThat(record.anomalyTypes()).contains(PblpkltGeocodeProcessor.ANOMALY_EMD_MISMATCH);
        assertThat(p.emdMismatch()).isEqualTo(1);
    }

    @Test
    @DisplayName("원천 emd_nm 이 사전에 없으면 불일치로 보지 않는다 — 허위 경보를 만들지 않는다")
    void doesNotFlagWhenSourceEmdNameIsUnknown() throws Exception {
        givenGeocodeSucceeds();
        // '화북동'(행정동명)은 법정동 사전에 없다 → null → 대조 불가 → 관측하지 않는다.
        PblpkltGeocodeProcessor p = newProcessorWithEmdDict(Map.of());

        PblpkltRecord record = p.process(row(PblpkltOriginRow.builder()
                .pkltId("1916").pkltAddr("396").emdNm("화북동")));

        assertThat(record.anomalyTypes()).doesNotContain(PblpkltGeocodeProcessor.ANOMALY_EMD_MISMATCH);
        assertThat(p.emdMismatch()).isZero();
    }

    @Test
    @DisplayName("공간매칭과 원천 emd_nm 이 같으면 아무것도 남기지 않는다")
    void noAnomalyWhenEmdAgrees() throws Exception {
        givenGeocodeSucceeds();
        PblpkltGeocodeProcessor p = newProcessorWithEmdDict(Map.of("대정읍", EMD_CD));

        PblpkltRecord record = p.process(row(PblpkltOriginRow.builder()
                .pkltId("1916").pkltAddr("상모리 3964-1").emdNm("대정읍")));

        assertThat(record.anomalyTypes()).doesNotContain(PblpkltGeocodeProcessor.ANOMALY_EMD_MISMATCH);
        assertThat(p.emdMismatch()).isZero();
    }

    @Test
    @DisplayName("후보 1이 NOT_FOUND 면 후보 2를 시도한다 — 미발견은 다음 후보로 넘긴다")
    void triesNextCandidateOnNotFound() throws Exception {
        PblpkltGeocodeProcessor p = newProcessor("제주특별자치도 ", true);
        when(admDistResolver.resolve(any(), any())).thenReturn(AdmDistMatch.matched(EMD_CD));
        when(geocoder.geocode("제주특별자치도 이도이동 도남동 65-3"))
                .thenReturn(GeocodeOutcome.notFound("place=NOT_FOUND"));
        when(geocoder.geocode("제주특별자치도 도남동 65-3")).thenReturn(GeocodeOutcome.found(POINT));

        PblpkltRecord record = p.process(row(PblpkltOriginRow.builder()
                .pkltId("1916").pkltAddr("도남동 65-3").emdNm("이도이동")));

        assertThat(record.lat()).isEqualByComparingTo(POINT.lat());
        assertThat(p.prefixFallback()).isEqualTo(1);
    }

    @Test
    @DisplayName("원천이 좌표를 주면 지오코딩을 부르지 않는다 — 쿼터를 태우지 않는다")
    void usesSourceCoordinatesWithoutGeocoding() throws Exception {
        when(admDistResolver.resolve(any(), any())).thenReturn(AdmDistMatch.matched(EMD_CD));

        PblpkltRecord record = processor.process(row(PblpkltOriginRow.builder()
                .pkltId("1916").pkltAddr("상모리 3964-1").emdNm("대정읍")
                .lat(33.2185).lot(126.2521)));

        verifyNoInteractions(geocoder);
        assertThat(record.lat()).isEqualByComparingTo(new BigDecimal("33.2185"));
        assertThat(record.lat().scale()).isEqualTo(PblpkltGeocodeProcessor.COORD_SCALE);
    }

    // ── ② 이름 폴백은 주소다 (§7-2) ───────────────────────────────────────────

    @Test
    @DisplayName("🔴 ② pklt_nm 이 null 이면 이름은 pklt_addr 이다 — '12'(pklt_id)가 되지 않는다")
    void namesRowAfterAddressWhenNameMissing() throws Exception {
        givenGeocodeSucceeds();

        PblpkltRecord record = processor.process(row(PblpkltOriginRow.builder()
                .pkltId("12").pkltNm(null).pkltAddr("한림리 1011").emdNm("한림읍")));

        assertThat(record.pkltNm()).isEqualTo("한림리 1011");
        assertThat(record.pkltNm()).isNotEqualTo("12");
    }

    @Test
    @DisplayName("이름도 주소도 없지만 좌표가 있으면 '(이름 없음)' 으로 적재된다 (NOT NULL 방어)")
    void usesUnknownNamePlaceholderAsLastResort() throws Exception {
        when(admDistResolver.resolve(any(), any())).thenReturn(AdmDistMatch.matched(EMD_CD));

        PblpkltRecord record = processor.process(row(PblpkltOriginRow.builder()
                .pkltId("12").pkltNm(null).pkltAddr(null).emdNm("한림읍")
                .lat(33.2185).lot(126.2521)));

        assertThat(record.pkltNm()).isEqualTo(PblpkltGeocodeProcessor.PKLT_NM_UNKNOWN);
    }

    // ── ③ 공간매칭 실패 → emd_nm 이름 폴백 (§7-3) ─────────────────────────────

    @Test
    @DisplayName("🔴 ③ 공간매칭이 실패해도 emd_nm 이 있으면 이름 폴백으로 emd_cd 를 채운다")
    void fallsBackToEmdNameWhenSpatialMatchFails() throws Exception {
        when(geocoder.geocode(anyString())).thenReturn(GeocodeOutcome.found(POINT));
        when(admDistResolver.resolve(any(), any()))
                .thenReturn(AdmDistMatch.unresolved("50110107", "건입동", 60.2481));
        givenAdmDistNameTable(Map.of("대정읍", EMD_CD));

        PblpkltRecord record = processor.process(row(PblpkltOriginRow.builder()
                .pkltId("1916").pkltAddr("상모리 3964-1").emdNm("대정읍")));

        assertThat(record.emdCd()).isEqualTo(EMD_CD);
        // 폴백으로 채운 건수는 실행 이력 지표로 나간다 — 안 남기면 "공간매칭이 잘 된다" 는 착각이 굳는다.
        assertThat(processor.emdNameFallback()).isEqualTo(1);
    }

    @Test
    @DisplayName("공간매칭도 이름 폴백도 실패하면 EMD_UNRESOLVED — note 에 좌표·최근접·emd_nm 이 남는다")
    void throwsEmdUnresolvedWhenBothFail() {
        when(geocoder.geocode(anyString())).thenReturn(GeocodeOutcome.found(POINT));
        when(admDistResolver.resolve(any(), any()))
                .thenReturn(AdmDistMatch.unresolved("50110107", "건입동", 60.2481));
        givenAdmDistNameTable(Map.of());   // 이름 표에 없다

        assertThatThrownBy(() -> processor.process(row(PblpkltOriginRow.builder()
                .pkltId("1916").pkltAddr("상모리 3964-1").emdNm("없는읍"))))
                .isInstanceOf(PblpkltGeocodeSkippableException.class)
                .satisfies(e -> {
                    PblpkltGeocodeSkippableException skip = (PblpkltGeocodeSkippableException) e;
                    assertThat(skip.reason())
                            .isEqualTo(PblpkltGeocodeSkippableException.Reason.EMD_UNRESOLVED);
                    assertThat(skip.originPk()).isEqualTo("1916");
                    assertThat(skip.address()).isEqualTo("상모리 3964-1");
                    assertThat(skip.note()).contains("건입동", "emd_nm 폴백도 실패", "없는읍");
                });
        assertThat(processor.emdNameFallback()).isZero();
    }

    @Test
    @DisplayName("🔴 이름 폴백은 폴백일 뿐이다 — 공간매칭이 성공하면 사전이 달라도 그 결과가 이긴다")
    void spatialMatchWinsOverNameTable() throws Exception {
        givenGeocodeSucceeds();                       // 공간매칭 → EMD_CD(50130250)
        // 사전은 전혀 다른 행정동을 가리킨다.
        PblpkltGeocodeProcessor p = newProcessorWithEmdDict(Map.of("대정읍", "50110109"));

        PblpkltRecord record = p.process(row(PblpkltOriginRow.builder()
                .pkltId("1916").pkltAddr("상모리 3964-1").emdNm("대정읍")));

        // 🔴 §7-3 의 본질 — 순서를 뒤집지 않는다. 적재되는 emd_cd 는 공간매칭 결과다.
        assertThat(record.emdCd()).isEqualTo(EMD_CD);
        // 폴백으로 채운 것이 아니므로 폴백 카운터는 늘지 않는다.
        assertThat(p.emdNameFallback()).isZero();
    }

    /*
     * 🔁 2026-09-29 — 이 테스트는 종전에 `verify(jdbc, never()).queryForList(...)` 로
     *    "공간매칭이 성공하면 이름 표를 읽지도 않는다" 를 확인했다. EMD_MISMATCH 관측을
     *    위해 성공 경로에서도 사전을 읽게 되어 그 기계적 단정은 더 이상 성립하지 않는다.
     *
     *    🔴 지키려던 불변식(= 공간매칭이 이름 폴백보다 우선한다)은 그대로이며, 위 테스트가 그것을
     *    더 직접적으로 확인한다 — 사전이 다른 값을 줘도 적재값이 공간매칭 결과임을 단정한다.
     *    사전은 74행 1회 지연 로딩 후 캐시되므로 읽는 비용도 무시할 수 있다.
     */

    // ── ④⑤ 유형 분류 (§7-4) ──────────────────────────────────────────────────

    @Test
    @DisplayName("④ pklt_type_nm='공한지' → VACLOT")
    void classifiesVacantLot() throws Exception {
        givenGeocodeSucceeds();

        PblpkltRecord record = processor.process(row(PblpkltOriginRow.builder()
                .pkltId("1916").pkltAddr("상모리 3964-1").emdNm("대정읍").pkltTypeNm("공한지")));

        assertThat(record.pkltTypeCd()).isEqualTo("VACLOT");
        // 원문도 함께 적재한다 — 재분류의 입력이라 버리지 않는다.
        assertThat(record.pkltTypeNm()).isEqualTo("공한지");
        assertThat(processor.unclassified()).isZero();
    }

    @Test
    @DisplayName("⑤ 미매핑 유형은 ETC 로 적재하되 값별로 집계된다 — 지오코딩은 그대로 돈다")
    void classifiesUnknownTypeAsEtc() throws Exception {
        givenGeocodeSucceeds();

        PblpkltRecord record = processor.process(row(PblpkltOriginRow.builder()
                .pkltId("1916").pkltAddr("상모리 3964-1").emdNm("대정읍").pkltTypeNm("지하복합")));

        assertThat(record.pkltTypeCd()).isEqualTo("ETC");
        assertThat(processor.unclassified()).isEqualTo(1);
        assertThat(processor.unknownTypeCounts()).containsEntry("지하복합", 1L);
        // 🔴 ETC 라도 실재하는 주차장이라 좌표는 끝까지 확보한다(cvlcpt 의 99 와 다른 점).
        verify(geocoder).geocode("상모리 3964-1");
        assertThat(record.lat()).isNotNull();
    }

    // ── ⑥ 주소·이름이 둘 다 없다 (§7-1) ──────────────────────────────────────

    @Test
    @DisplayName("🔴 ⑥ 주소·이름이 둘 다 비면 GEOCODE_FAILED(note=ADDR_EMPTY) — 재시도해도 같다")
    void throwsAddrEmptyWhenNothingToGeocode() {
        assertThatThrownBy(() -> processor.process(row(PblpkltOriginRow.builder()
                .pkltId("12").pkltNm("   ").pkltAddr(null).emdNm("한림읍"))))
                .isInstanceOf(PblpkltGeocodeSkippableException.class)
                .satisfies(e -> {
                    PblpkltGeocodeSkippableException skip = (PblpkltGeocodeSkippableException) e;
                    assertThat(skip.reason())
                            .isEqualTo(PblpkltGeocodeSkippableException.Reason.GEOCODE_FAILED);
                    assertThat(skip.note()).isEqualTo(PblpkltGeocodeProcessor.NOTE_ADDR_EMPTY);
                    assertThat(skip.originPk()).isEqualTo("12");
                });

        // 부를 주소가 없으니 쿼터를 쓰지 않는다.
        verifyNoInteractions(geocoder);
        verify(admDistResolver, never()).resolve(any(), any());
    }

    // ── 지오코딩 3분기 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("NOT_FOUND → GEOCODE_FAILED 스킵 예외 (note 는 단계 내역 그대로)")
    void throwsGeocodeFailedWhenAddressNotFound() {
        when(geocoder.geocode(anyString())).thenReturn(GeocodeOutcome.notFound("parcel=NOT_FOUND"));

        assertThatThrownBy(() -> processor.process(row(PblpkltOriginRow.builder()
                .pkltId("1916").pkltAddr("없는주소 1").emdNm("대정읍"))))
                .isInstanceOf(PblpkltGeocodeSkippableException.class)
                .satisfies(e -> assertThat(((PblpkltGeocodeSkippableException) e).note())
                        .isEqualTo("parcel=NOT_FOUND"));

        verify(admDistResolver, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("🔴 TRANSIENT → null 반환(filter). 스킵 예외가 아니다 — 일시 장애를 영구 제외로 굳히지 않는다")
    void filtersRowOnTransientFailure() throws Exception {
        when(geocoder.geocode(anyString())).thenReturn(GeocodeOutcome.transientFailure());

        PblpkltRecord record = processor.process(row(PblpkltOriginRow.builder()
                .pkltId("1916").pkltAddr("상모리 3964-1").emdNm("대정읍")));

        assertThat(record).isNull();
        assertThat(processor.transientFiltered()).isEqualTo(1);
        verify(admDistResolver, never()).resolve(any(), any());
    }

    // ── 원천 확장 25종을 값 변형 없이 나른다 ─────────────────────────────────

    @Test
    @DisplayName("확장 25종은 값을 바꾸지 않고 그대로 record 로 넘어간다")
    void carriesExtendedSourceFieldsUnchanged() throws Exception {
        givenGeocodeSucceeds();

        PblpkltRecord record = processor.process(row(PblpkltOriginRow.builder()
                .pkltId("1916").pkltAddr("상모리 3964-1").emdNm("대정읍")
                .orgnlId("ORG-1916").orgnlSn(131).pkltTypeNm("노외공영")
                .pkltar(new BigDecimal("1073.000000000")).prkCrgYn("Y").prkCrgCn("30분 500원")
                .prkCrgSwtcYm("202602").instlYm("202510").instlYmd("20251015")
                .rttrt(new BigDecimal("1.46")).dayAvgVhclCnt(54)
                .incmAmt(new BigDecimal("2118400")).expndAmt(new BigDecimal("31000"))
                .mngNope(2).mngDeptNm("교통행정과").pkltMthdNm("직각주차").evChrgrCnt(4)
                .ownshpTypeNm("공유").prkSlotGrd("A").obstclCn("경계석").obstclSlotCnt(1)
                .trfcSftySignInstlYn("Y").stprInstlYn("N").rmrkCn("재조사 대상")
                .pkltCpcty(37)));

        assertThat(record.orgnlId()).isEqualTo("ORG-1916");
        assertThat(record.orgnlSn()).isEqualTo(131);
        assertThat(record.emdNm()).isEqualTo("대정읍");
        assertThat(record.pkltar()).isEqualByComparingTo(new BigDecimal("1073"));
        assertThat(record.rttrt()).isEqualByComparingTo(new BigDecimal("1.46"));
        assertThat(record.dayAvgVhclCnt()).isEqualTo(54);
        assertThat(record.incmAmt()).isEqualByComparingTo(new BigDecimal("2118400"));
        assertThat(record.expndAmt()).isEqualByComparingTo(new BigDecimal("31000"));
        assertThat(record.mngNope()).isEqualTo(2);
        assertThat(record.mngDeptNm()).isEqualTo("교통행정과");
        assertThat(record.pkltMthdNm()).isEqualTo("직각주차");
        assertThat(record.evChrgrCnt()).isEqualTo(4);
        assertThat(record.ownshpTypeNm()).isEqualTo("공유");
        assertThat(record.prkSlotGrd()).isEqualTo("A");
        assertThat(record.obstclCn()).isEqualTo("경계석");
        assertThat(record.obstclSlotCnt()).isEqualTo(1);
        assertThat(record.trfcSftySignInstlYn()).isEqualTo("Y");
        assertThat(record.stprInstlYn()).isEqualTo("N");
        assertThat(record.rmrkCn()).isEqualTo("재조사 대상");
        assertThat(record.prkCrgYn()).isEqualTo("Y");
        assertThat(record.prkCrgCn()).isEqualTo("30분 500원");
        assertThat(record.prkCrgSwtcYm()).isEqualTo("202602");
        assertThat(record.instlYm()).isEqualTo("202510");
        assertThat(record.instlYmd()).isEqualTo("20251015");
        assertThat(record.pkltCpcty()).isEqualTo(37);
        // 🔴 원천 키가 originPk 에 그대로 실린다 — pklt_id 는 DB 가 매기므로 레코드에 없다.
        assertThat(record.originPk()).isEqualTo("1916");
    }

    @Test
    @DisplayName("anomaly 관측 결과는 Writer 까지 그대로 전달된다")
    void carriesAnomalyTypesToWriter() throws Exception {
        givenGeocodeSucceeds();

        PblpkltRecord record = processor.process(new PblpkltFetchRow(
                PblpkltOriginRow.builder()
                        .pkltId("1916").pkltAddr("한림리 산 1011").emdNm("한림읍").build(),
                List.of("SAN_PREFIX")));

        assertThat(record.anomalyTypes()).containsExactly("SAN_PREFIX");
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private void givenGeocodeSucceeds() {
        when(geocoder.geocode(anyString())).thenReturn(GeocodeOutcome.found(POINT));
        when(admDistResolver.resolve(any(), any())).thenReturn(AdmDistMatch.matched(EMD_CD));
    }

    /** {@code parking.tb_admdst} 이름 표(이름 폴백 사전)의 내용을 정한다. */
    private void givenAdmDistNameTable(Map<String, String> nameToCd) {
        List<Map<String, Object>> rows = nameToCd.entrySet().stream()
                .map(e -> {
                    Map<String, Object> r = new HashMap<>();
                    r.put("emd_nm", e.getKey());
                    r.put("emd_cd", e.getValue());
                    return r;
                })
                .toList();
        when(jdbc.queryForList(anyString())).thenReturn(rows);
    }

    private static PblpkltFetchRow row(PblpkltOriginRow.Builder builder) {
        return new PblpkltFetchRow(builder.build(), List.of());
    }
    @Test
    void completeJejuAddressDoesNotGetProvinceOrCityTwice() {
        var candidates=newProcessor("제주특별자치도 ",true).geocodeCandidates(
                "제주특별자치도 제주시 건입동 1440-2", "건입동", "제주시");
        assertThat(candidates.get(0)).isEqualTo("제주특별자치도 제주시 건입동 1440-2");
        assertThat(candidates).noneMatch(value -> value.contains("제주특별자치도 제주시 건입동 제주특별자치도"));
    }

    @Test
    void multipleAddressQueriesKeepOriginalFirstAndOnlyThenTryRepresentativeParcel() {
        var candidates=newProcessor("제주특별자치도 ",true).geocodeCandidates(
                "서부두길 18-2, 16", "건입동", "제주시");
        assertThat(candidates).contains("제주특별자치도 제주시 서부두길 18-2, 16",
                "제주특별자치도 제주시 서부두길 18-2");
        assertThat(candidates.indexOf("제주특별자치도 제주시 서부두길 18-2"))
                .isGreaterThan(candidates.indexOf("제주특별자치도 제주시 서부두길 18-2, 16"));
    }

}
