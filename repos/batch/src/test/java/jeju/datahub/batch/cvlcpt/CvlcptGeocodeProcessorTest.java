package jeju.datahub.batch.cvlcpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import jeju.datahub.batch.admdst.AdmDistMatch;
import jeju.datahub.batch.admdst.AdmDistResolver;
import jeju.datahub.batch.geocode.CompositeGeocoder;
import jeju.datahub.batch.cvlcpt.api.ComplaintOriginRow;
import jeju.datahub.batch.geocode.Coordinate;
import jeju.datahub.batch.geocode.GeocodeOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link CvlcptGeocodeProcessor} 단위 테스트 — 분기 순서와 3분기 처리가 핵심이다.
 *
 * <p>지오코딩·공간매칭은 스텁이다. 여기서 검증하는 것은 "언제 부르고 언제 안 부르는가"와 "각
 * 결과를 어떻게 처리하는가"이지 외부 API·PostGIS 동작이 아니다(그쪽은
 * {@code CompositeGeocoderTest}/{@code AdmDistResolverTest} 가 본다).
 */
class CvlcptGeocodeProcessorTest {

    private static final String EMD_CD = "50110104";
    private static final String ADDRESS = "제주특별자치도 제주시 이도이동 1176";
    private static final Coordinate POINT = Coordinate.of("126.5312345678912", "33.4996123456789");

    private final CompositeGeocoder geocoder = mock(CompositeGeocoder.class);
    private final AdmDistResolver admDistResolver = mock(AdmDistResolver.class);
    private final CvlcptGeocodeProcessor processor = new CvlcptGeocodeProcessor(geocoder, admDistResolver);

    // ── 1. 유형 분류 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("원천이 분류를 주지 않으면 dclr_type CASE 로 정한다 — 기본 경로")
    void classifiesByDclrTypeWhenSourceGivesNothing() throws Exception {
        givenGeocodeSucceeds();

        CvlcptRecord record = processor.process(row("횡단보도"));

        assertThat(record.cvlcptTypeCd()).isEqualTo("02");
        assertThat(processor.sourceTypeCds()).isZero();
    }

    @Test
    @DisplayName("🔴 원천이 cvlcpt_type_cd 를 주면 그대로 쓴다 — CASE 결과와 달라도 원천이 이긴다")
    void prefersSourceTypeCodeOverOwnClassification() throws Exception {
        givenGeocodeSucceeds();

        // dclr_type='횡단보도' 의 CASE 결과는 02 다. 원천이 03 을 주면 03 으로 적재된다.
        CvlcptRecord record = processor.process(new Row().dclrType("횡단보도").cvlcptTypeCd("03").build());

        assertThat(record.cvlcptTypeCd()).isEqualTo("03");
        assertThat(processor.sourceTypeCds()).isEqualTo(1);
        // 우리가 분류한 것이 아니므로 미분류 집계에도 잡히지 않는다.
        assertThat(processor.unclassified()).isZero();
    }

    @Test
    @DisplayName("🔴 원천 코드가 공통코드 밖이면 채택하지 않고 자체 분류로 되돌아간다")
    void ignoresUnknownSourceTypeCode() throws Exception {
        givenGeocodeSucceeds();

        // '07' 은 CVLCPT_TYPE_CD 에 없는 값이다. 그대로 넣으면 화면 유형 필터가 이 행을 못 찾는다.
        CvlcptRecord record = processor.process(new Row().dclrType("횡단보도").cvlcptTypeCd("07").build());

        assertThat(record.cvlcptTypeCd()).isEqualTo("02");   // CASE 로 되돌아갔다
        assertThat(processor.sourceTypeCds()).isZero();
    }

    @Test
    @DisplayName("원천 코드가 없으면 prk_cvlcpt_type 이 dclr_type CASE 보다 우선한다")
    void prefersSourceTypeNameOverCaseRule() throws Exception {
        givenGeocodeSucceeds();

        // dclr_type 은 CASE 좌변 밖(→99)이지만 원천이 분류명을 줬으므로 04 로 살아난다.
        //    원천이 새 유형 표기를 보내기 시작해도 놓치지 않는 경로가 이것이다.
        CvlcptRecord record = processor.process(
                new Row().dclrType("전기차충전구역위반").prkCvlcptType("친환경차충전구역 위반").build());

        assertThat(record.cvlcptTypeCd()).isEqualTo("04");
        assertThat(processor.unclassified()).isZero();
    }

    @Test
    @DisplayName("prk_cvlcpt_type 원문은 적재된다 — 재분류의 근거라 버리지 않는다")
    void keepsSourceTypeNameForReclassification() throws Exception {
        givenGeocodeSucceeds();

        CvlcptRecord record = processor.process(
                new Row().dclrType("횡단보도").prkCvlcptType("인도·횡단보도 점유").build());

        assertThat(record.prkCvlcptType()).isEqualTo("인도·횡단보도 점유");
        assertThat(record.dclrType()).isEqualTo("횡단보도");
    }

    @ParameterizedTest(name = "dclr_type={0} → {1}")
    @DisplayName("dclr_type 을 CASE 에 태운다 — 좌변 10종 전부")
    @CsvSource({
            "횡단보도,02", "인도,02",
            "교차로 모퉁이,01", "소화전,01", "버스정류소,01", "어린이보호구역,01", "소방차전용구역,01",
            "장애인전용구역,03",
            "친환경차충전구역,04",
            "기타불법주정차,05"})
    void classifiesByCaseRule(String dclrType, String expectedCd) throws Exception {
        givenGeocodeSucceeds();

        assertThat(processor.process(row(dclrType)).cvlcptTypeCd()).isEqualTo(expectedCd);
    }

    // ── 2. 미분류(99) — 지오코딩 생략 ──────────────────────────────────────────

    @Test
    @DisplayName("🔴 미분류 행은 지오코딩을 호출하지 않고 99 로 적재된다 (좌표·emd_cd NULL)")
    void skipsGeocodingForUnclassifiedRow() throws Exception {
        // 교통위반(고속도로 포함)은 원천 최다 미분류 유형(29,179건) — CASE 좌변 밖이다.
        CvlcptRecord record = processor.process(row("교통위반(고속도로 포함)"));

        assertThat(record.cvlcptTypeCd()).isEqualTo("99");
        assertThat(record.lat()).isNull();
        assertThat(record.lot()).isNull();
        assertThat(record.emdCd()).isNull();
        // 분류를 지오코딩보다 먼저 하는 설계의 전부가 이 한 줄이다 — 쿼터를 쓰지 않는다.
        verifyNoInteractions(geocoder);
        // (admDistResolver 는 원천 emd_cd 검증 때문에 매 행 호출된다 — 비싼 공간매칭만 본다.)
        verify(admDistResolver, never()).resolve(any(), any());
        assertThat(processor.unclassified()).isEqualTo(1);
    }

    @Test
    @DisplayName("미지 유형 값은 WARN 대상으로 집계되되 스킵 예외가 되지 않는다")
    void countsUnknownTypeWithoutSkipping() throws Exception {
        processor.process(row("불법광고물"));
        processor.process(row("불법광고물"));
        processor.process(row("기타 생활불편"));

        assertThat(processor.unknownTypeCounts())
                .containsEntry("불법광고물", 2L)
                .containsEntry("기타 생활불편", 1L);
        assertThat(processor.unclassified()).isEqualTo(3);
        processor.logStats(); // 비율 경고 경로가 터지지 않는지 확인
    }

    @Test
    @DisplayName("dclr_type 표기가 매핑 밖이면 99 — 원천 표기 변경이 조용히 묻히지 않는다")
    void treatsUnknownTypeAsUnclassified() throws Exception {
        // '장애인전용구역'(→03)에 한 글자만 다른 값. 원천이 표기를 바꾸면 이런 모습이 된다.
        CvlcptRecord record = processor.process(row("장애인 전용구역"));

        assertThat(record.cvlcptTypeCd()).isEqualTo("99");
        // 어떤 값이 99 를 밀어올렸는지가 원천 표기 변경 탐지의 핵심이다.
        assertThat(processor.unknownTypeCounts()).containsKey("장애인 전용구역");
        // 미분류는 지오코딩을 건너뛴다 — 쿼터 절감 설계의 전제다.
        verifyNoInteractions(geocoder);
    }

    // ── 3. GeocodeOutcome 3분기 ────────────────────────────────────────────────

    @Test
    @DisplayName("FOUND → 좌표로 공간매칭까지 진행한다")
    void geocodesAndResolvesEmdCdForParkingComplaint() throws Exception {
        givenGeocodeSucceeds();

        CvlcptRecord record = processor.process(row("소화전"));

        assertThat(record.emdCd()).isEqualTo(EMD_CD);
        assertThat(record.lat()).isEqualByComparingTo(POINT.lat());
        assertThat(record.lot()).isEqualByComparingTo(POINT.lot());
        verify(geocoder).geocode("제주특별자치도 제주시 이도이동 1176");
        assertThat(processor.geocoded()).isEqualTo(1);
    }

    @Test
    @DisplayName("NOT_FOUND → GEOCODE_FAILED 스킵 예외 (스킵 테이블 기록 대상)")
    void throwsGeocodeFailedWhenAddressNotFound() {
        when(geocoder.geocode(anyString())).thenReturn(GeocodeOutcome.notFound());

        assertThatThrownBy(() -> processor.process(row("소화전")))
                .isInstanceOf(GeocodeSkippableException.class)
                .extracting(e -> ((GeocodeSkippableException) e).reason())
                .isEqualTo(GeocodeSkippableException.Reason.GEOCODE_FAILED);

        // 좌표가 없으니 공간매칭은 시도조차 안 한다.
        verify(admDistResolver, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("🔵 GEOCODE_FAILED 의 note 는 단계별 판정 그대로다 — 부번제거까지 갔는지가 보인다")
    void putsGeocodeStageDetailIntoSkipNote() {
        String detail = "parcel=NOT_FOUND, road=NOT_FOUND, "
                + "stripped(제주특별자치도 제주시 구좌읍 상도리 3204)=NOT_FOUND";
        when(geocoder.geocode(anyString())).thenReturn(GeocodeOutcome.notFound(detail));

        assertThatThrownBy(() -> processor.process(row("소화전")))
                .isInstanceOf(GeocodeSkippableException.class)
                .satisfies(e -> assertThat(((GeocodeSkippableException) e).note()).isEqualTo(detail));
    }

    @Test
    @DisplayName("단계 내역이 없으면 note 가 NULL 이 되지 않게 최후 문구를 넣는다")
    void fallsBackToDefaultNoteWhenDetailMissing() {
        when(geocoder.geocode(anyString())).thenReturn(GeocodeOutcome.notFound());

        assertThatThrownBy(() -> processor.process(row("소화전")))
                .isInstanceOf(GeocodeSkippableException.class)
                .satisfies(e -> assertThat(((GeocodeSkippableException) e).note())
                        .isEqualTo(CvlcptGeocodeProcessor.GEOCODE_FAILED_FALLBACK_NOTE));
    }

    @Test
    @DisplayName("🔴 TRANSIENT는 실패 처리해 이후 load_dt가 워터마크를 앞당기지 못하게 한다")
    void filtersRowOnTransientFailure() throws Exception {
        when(geocoder.geocode(anyString())).thenReturn(GeocodeOutcome.transientFailure());

        assertThatThrownBy(() -> processor.process(row("소화전")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("load_dt");
        assertThat(processor.transientFiltered()).isEqualTo(1);
        verify(admDistResolver, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("공간매칭 실패 → EMD_UNRESOLVED 스킵 예외 (note 에 좌표 + 최근접 행정동/거리)")
    void throwsEmdUnresolvedWhenSpatialMatchFails() {
        when(geocoder.geocode(anyString())).thenReturn(GeocodeOutcome.found(POINT));
        when(admDistResolver.resolve(any(), any()))
                .thenReturn(AdmDistMatch.unresolved("50110107", "건입동", 60.2481));

        assertThatThrownBy(() -> processor.process(row("소화전")))
                .isInstanceOf(GeocodeSkippableException.class)
                .satisfies(e -> {
                    GeocodeSkippableException skip = (GeocodeSkippableException) e;
                    assertThat(skip.reason()).isEqualTo(GeocodeSkippableException.Reason.EMD_UNRESOLVED);
                    assertThat(skip.cvlcptId()).isEqualTo("SPP-2501-0125445");
                    assertThat(skip.address()).isEqualTo("제주특별자치도 제주시 이도이동 1176");
                    // 계획서 Step 7-4 표의 note 형식 그대로 — 좌표(지오코딩은 됐다)와
                    // 최근접 거리(버퍼를 넓히면 살아나는지)가 한 줄에 다 있어야 한다.
                    assertThat(skip.note()).isEqualTo(
                            "lat=33.4996123457 lot=126.5312345679, 최근접 50110107(건입동) 60.25m > 버퍼 50m");
                });
    }

    @Test
    @DisplayName("동일 실행에서 유형별로 지오코딩 호출 여부가 갈린다 (미분류만 건너뜀)")
    void callsGeocoderOnlyForParkingComplaints() throws Exception {
        givenGeocodeSucceeds();

        processor.process(row("소화전"));                    // 01
        processor.process(row("교통위반(고속도로 포함)"));      // 99
        processor.process(row("인도"));                      // 02
        processor.process(row("불법광고물"));                 // 99

        verify(geocoder, times(2)).geocode(anyString());
        verify(admDistResolver, times(2)).resolve(any(), any());
        assertThat(processor.processed()).isEqualTo(4);
        assertThat(processor.unclassified()).isEqualTo(2);
    }

    // ── 4. 나머지 필드 매핑 ────────────────────────────────────────────────────

    @Test
    @DisplayName("원천이 발생일시를 주지 않으면 ocrn_dt만 신고일 자정으로 조립하고 원천 load_dt 결측은 유지한다")
    void mapsIdentityAndTimestamps() throws Exception {
        givenGeocodeSucceeds();

        CvlcptRecord record = processor.process(row("소화전"));

        assertThat(record.originPk()).isEqualTo("SPP-2501-0125445");
        assertThat(record.cvlcptCn()).isEqualTo("소화전 앞 불법주차");
        assertThat(record.ocrnDt()).isEqualTo(LocalDateTime.of(2025, 1, 1, 0, 0, 0));
        assertThat(record.loadDt()).isNull();
    }

    @Test
    @DisplayName("답변일 유무와 관계없이 원천 load_dt 결측을 그대로 보존한다")
    void fallsBackToOcrnDtWhenAnswerDateMissing() throws Exception {
        givenGeocodeSucceeds();

        CvlcptRecord blank = processor.process(new Row().cvlcptId("SPP-1").ansYmd("").build());
        CvlcptRecord nullAns = processor.process(new Row().cvlcptId("SPP-2").ansYmd(null).build());

        assertThat(blank.loadDt()).isNull();
        assertThat(nullAns.loadDt()).isNull();
    }

    @ParameterizedTest(name = "src_path={0} → {1}")
    @DisplayName("src_cd 는 SRC_CD_MAP, 매핑 밖이면 ETC")
    @CsvSource({"모바일앱,APP", "포털,PORTAL", "API,API", "콜센터,CALL", "우편,ETC"})
    void mapsSrcCd(String srcPath, String expected) throws Exception {
        givenGeocodeSucceeds();

        CvlcptRecord record = processor.process(new Row().srcPath(srcPath).build());

        assertThat(record.srcCd()).isEqualTo(expected);
    }

    @Test
    @DisplayName("src_path 가 null/공백이면 ETC — NOT NULL 컬럼은 아니지만 코드값을 비우지 않는다")
    void mapsBlankSrcPathToEtc() throws Exception {
        givenGeocodeSucceeds();

        CvlcptRecord record = processor.process(new Row().srcPath(null).build());

        assertThat(record.srcCd()).isEqualTo("ETC");
    }

    @Test
    @DisplayName("lat/lot 은 소수 10자리로 반올림된다 (NUMERIC(12,10)/NUMERIC(13,10))")
    void roundsCoordinatesToTenDecimals() throws Exception {
        // 15자리 원본을 직접 넣어 Coordinate 스케일링과 무관하게 Processor 가 확정하는지 본다.
        when(geocoder.geocode(anyString())).thenReturn(GeocodeOutcome.found(new Coordinate(
                new BigDecimal("33.499612345678912"), new BigDecimal("126.531234567891234"))));
        when(admDistResolver.resolve(any(), any())).thenReturn(AdmDistMatch.matched(EMD_CD));

        CvlcptRecord record = processor.process(row("소화전"));

        assertThat(record.lat().scale()).isEqualTo(10);
        assertThat(record.lot().scale()).isEqualTo(10);
        assertThat(record.lat()).isEqualByComparingTo(new BigDecimal("33.4996123457"));
        assertThat(record.lot()).isEqualByComparingTo(new BigDecimal("126.5312345679"));
    }

    @Test
    @DisplayName("공간매칭에는 반올림된 좌표가 그대로 넘어간다 — 적재값과 매칭 기준이 어긋나지 않는다")
    void resolvesEmdCdWithTheSameCoordinatesThatGetPersisted() throws Exception {
        givenGeocodeSucceeds();

        CvlcptRecord record = processor.process(row("소화전"));

        verify(admDistResolver).resolve(record.lat(), record.lot());
    }

    @Test
    @DisplayName("미분류 행에는 공간매칭도 부르지 않는다 — resolve(null, null) 로 새는 경로가 없다")
    void neverResolvesWithoutCoordinates() throws Exception {
        processor.process(row("불법광고물"));

        verify(admDistResolver, never()).resolve(any(), any());
    }

    // ── 5. 원천이 채워 준 가공값 우선 (TSK-4109) ────────────────────────────────

    @Test
    @DisplayName("🔴 원천이 좌표를 주면 지오코딩을 부르지 않는다 — 쿼터를 태우지 않는 것이 요점이다")
    void usesSourceCoordinatesWithoutGeocoding() throws Exception {
        when(admDistResolver.resolve(any(), any())).thenReturn(AdmDistMatch.matched(EMD_CD));

        CvlcptRecord record = processor.process(
                new Row().coordinates("33.4996", "126.5312").build());

        assertThat(record.lat()).isEqualByComparingTo(new BigDecimal("33.4996"));
        assertThat(record.lot()).isEqualByComparingTo(new BigDecimal("126.5312"));
        verifyNoInteractions(geocoder);
        assertThat(processor.sourceCoordinates()).isEqualTo(1);
        // 좌표는 왔지만 행정동은 안 왔으므로 공간매칭은 돈다 — 항목별로 독립 판단한다.
        verify(admDistResolver).resolve(any(), any());
        assertThat(record.emdCd()).isEqualTo(EMD_CD);
    }

    @Test
    @DisplayName("🔴 원천이 행정동을 주면 공간매칭을 부르지 않는다")
    void usesSourceEmdCdWithoutSpatialMatch() throws Exception {
        when(geocoder.geocode(anyString())).thenReturn(GeocodeOutcome.found(POINT));
        givenSourceEmdCdIsKnown("50110107");

        CvlcptRecord record = processor.process(new Row().emdCd("50110107").build());

        assertThat(record.emdCd()).isEqualTo("50110107");
        verify(admDistResolver, never()).resolve(any(), any());
        assertThat(processor.sourceEmdCds()).isEqualTo(1);
        // 행정동은 왔지만 좌표는 안 왔으므로 지오코딩은 돈다 — 지도에 찍으려면 좌표가 필요하다.
        verify(geocoder).geocode(ADDRESS);
    }

    @Test
    @DisplayName("좌표·행정동·유형이 모두 오면 외부 호출이 한 번도 없다")
    void skipsEveryLookupWhenSourceProvidesEverything() throws Exception {
        givenSourceEmdCdIsKnown("50110107");

        CvlcptRecord record = processor.process(new Row()
                .cvlcptTypeCd("02").emdCd("50110107").coordinates("33.4996", "126.5312").build());

        assertThat(record.cvlcptTypeCd()).isEqualTo("02");
        assertThat(record.emdCd()).isEqualTo("50110107");
        assertThat(record.lat()).isEqualByComparingTo(new BigDecimal("33.4996"));
        verifyNoInteractions(geocoder);
        verify(admDistResolver, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("🔴 원천 emd_cd 가 tb_admdst 에 없으면 채택하지 않는다 — FK 위반이 청크를 통째로 롤백시킨다")
    void ignoresUnknownSourceEmdCd() throws Exception {
        givenGeocodeSucceeds();   // isKnownEmdCd 는 스텁하지 않아 false 다 = 미지 코드

        CvlcptRecord record = processor.process(new Row().emdCd("99999999").build());

        assertThat(record.emdCd()).isEqualTo(EMD_CD);   // 공간매칭 결과로 되돌아갔다
        verify(admDistResolver).resolve(any(), any());
        assertThat(processor.sourceEmdCds()).isZero();
    }

    @Test
    @DisplayName("🔴 좌표가 한쪽만 오면 둘 다 버리고 지오코딩한다 — 반쪽 좌표는 지도에 찍을 수 없다")
    void ignoresHalfCoordinatePair() throws Exception {
        givenGeocodeSucceeds();

        CvlcptRecord record = processor.process(new Row().coordinates("33.4996", null).build());

        assertThat(record.lat()).isEqualByComparingTo(POINT.lat());
        assertThat(record.lot()).isEqualByComparingTo(POINT.lot());
        verify(geocoder).geocode(ADDRESS);
        assertThat(processor.sourceCoordinates()).isZero();
    }

    @Test
    @DisplayName("🔴 범위 밖 좌표는 채택하지 않는다 — NUMERIC(12,10) overflow 가 청크를 롤백시킨다")
    void ignoresOutOfRangeSourceCoordinates() throws Exception {
        givenGeocodeSucceeds();

        // 위도 100 은 WGS84 정의역 밖이자 NUMERIC(12,10)(정수부 2자리)에 담기지 않는 값이다.
        CvlcptRecord record = processor.process(new Row().coordinates("100.0", "126.5312").build());

        assertThat(record.lat()).isEqualByComparingTo(POINT.lat());
        verify(geocoder).geocode(ADDRESS);
        assertThat(processor.sourceCoordinates()).isZero();
    }

    @Test
    @DisplayName("원천 좌표도 소수 10자리로 확정된다 — 출처가 달라도 적재 자릿수는 하나다")
    void scalesSourceCoordinatesToo() throws Exception {
        when(admDistResolver.resolve(any(), any())).thenReturn(AdmDistMatch.matched(EMD_CD));

        CvlcptRecord record = processor.process(
                new Row().coordinates("33.499612345678912", "126.531234567891234").build());

        assertThat(record.lat().scale()).isEqualTo(10);
        assertThat(record.lot().scale()).isEqualTo(10);
        assertThat(record.lat()).isEqualByComparingTo(new BigDecimal("33.4996123457"));
    }

    @Test
    @DisplayName("원천 src_cd 를 쓰되, 공통코드 밖이면 src_path 매핑으로 되돌아간다")
    void prefersSourceSrcCdWhenKnown() throws Exception {
        givenGeocodeSucceeds();

        assertThat(processor.process(new Row().srcPath("모바일앱").srcCd("PORTAL").build()).srcCd())
                .isEqualTo("PORTAL");
        // 'FAX' 는 SRC_CD 에 없는 값 — src_path(모바일앱) 매핑으로 되돌아간다.
        assertThat(processor.process(new Row().srcPath("모바일앱").srcCd("FAX").build()).srcCd())
                .isEqualTo("APP");
    }

    @Test
    @DisplayName("미분류(99) 행이라도 원천이 준 좌표·행정동은 버리지 않는다 — 공짜로 온 값이다")
    void keepsSourceValuesEvenForUnclassifiedRow() throws Exception {
        givenSourceEmdCdIsKnown("50110107");

        CvlcptRecord record = processor.process(new Row()
                .dclrType("불법광고물").emdCd("50110107").coordinates("33.4996", "126.5312").build());

        assertThat(record.cvlcptTypeCd()).isEqualTo("99");
        assertThat(record.emdCd()).isEqualTo("50110107");
        assertThat(record.lat()).isEqualByComparingTo(new BigDecimal("33.4996"));
        // 없는 좌표를 새로 사 오지는 않는다 — 쿼터 절감 설계는 그대로다.
        verifyNoInteractions(geocoder);
    }

    private void givenGeocodeSucceeds() {
        when(geocoder.geocode(anyString())).thenReturn(GeocodeOutcome.found(POINT));
        when(admDistResolver.resolve(any(), any())).thenReturn(AdmDistMatch.matched(EMD_CD));
    }

    /** 원천이 준 {@code emd_cd} 가 우리 행정동 코드 체계에 있는 상황. */
    private void givenSourceEmdCdIsKnown(String emdCd) {
        when(admDistResolver.isKnownEmdCd(emdCd)).thenReturn(true);
    }

    /** 원천 실 데이터(제주시청 인근 소화전 신고) 형태의 API 행. */
    private static ComplaintOriginRow row(String dclrType) {
        return new Row().dclrType(dclrType).build();
    }

    /**
     * 테스트용 API 행 빌더.
     *
     * <p>{@link ComplaintOriginRow} 는 26필드라 위치 인자로 조립하면 <b>한 값을 바꾸는 테스트마다
     * 26개를 다시 쓴다</b>. 실제로 그렇게 두었더니 원천 이관 때 전 테스트가 한꺼번에 깨졌다.
     * 기본값은 "원천이 가공값을 주지 않는 행"(2026-09-01 실원천과 같은 상태)이고, 검증 대상만
     * 갈아 끼운다.
     */
    private static final class Row {

        private String cvlcptId = "SPP-2501-0125445";
        private String dclrYmd = "2025-01-01";
        /** null 이면 Processor 가 신고일 자정으로 대체한다 — 실원천에도 있는 경로다. */
        private LocalDateTime ocrnDt;
        private String srcPath = "모바일앱";
        private String ansYmd = "2025-01-03";
        private String dclrType = "소화전";
        private String prkCvlcptType;
        private String aprsRaw;
        // ↓ 원천이 채워 줄 수도 있는 가공 컬럼. 기본은 전부 비어 있다.
        private String cvlcptTypeCd;
        private String emdCd;
        private BigDecimal lat;
        private BigDecimal lot;
        private String srcCd;

        Row cvlcptId(String value) {
            this.cvlcptId = value;
            return this;
        }

        Row dclrType(String value) {
            this.dclrType = value;
            return this;
        }

        Row prkCvlcptType(String value) {
            this.prkCvlcptType = value;
            return this;
        }

        Row ansYmd(String value) {
            this.ansYmd = value;
            return this;
        }

        Row srcPath(String value) {
            this.srcPath = value;
            return this;
        }

        Row cvlcptTypeCd(String value) {
            this.cvlcptTypeCd = value;
            return this;
        }

        Row emdCd(String value) {
            this.emdCd = value;
            return this;
        }

        Row coordinates(String lat, String lot) {
            this.lat = lat == null ? null : new BigDecimal(lat);
            this.lot = lot == null ? null : new BigDecimal(lot);
            return this;
        }

        Row srcCd(String value) {
            this.srcCd = value;
            return this;
        }

        ComplaintOriginRow build() {
            return new ComplaintOriginRow(
                    cvlcptId, dclrYmd, ocrnDt,
                    "개인", "안전", srcPath,
                    ADDRESS,
                    "소화전 앞 불법주차", "소화전 앞 불법주차",
                    "처리완료", "답변완료", ansYmd, "N",
                    1, "제주시청", "담당자", null, null,
                    dclrType, prkCvlcptType, aprsRaw,
                    cvlcptTypeCd, emdCd, lat, lot, srcCd);
        }
    }
}
