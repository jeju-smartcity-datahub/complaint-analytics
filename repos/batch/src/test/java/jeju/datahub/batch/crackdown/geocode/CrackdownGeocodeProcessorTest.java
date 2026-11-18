package jeju.datahub.batch.crackdown.geocode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import jeju.datahub.batch.admdst.AdmDistMatch;
import jeju.datahub.batch.admdst.AdmDistResolver;
import jeju.datahub.batch.geocode.CompositeGeocoder;
import jeju.datahub.batch.geocode.Coordinate;
import jeju.datahub.batch.geocode.GeocodeOutcome;
import jeju.datahub.batch.geocode.GeocodeQuotaExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CrackdownGeocodeProcessor} 단위 테스트 — TSK-4054 Step 6.
 *
 * <p>지오코더·공간매칭은 mock, 주소 빌더는 실물(표 주입 생성자)이다. 검증 대상은 "언제 부르고 언제 안
 * 부르는가" 와 실패 3갈래 처리다.
 */
class CrackdownGeocodeProcessorTest {

    private static final String JIBUN_ADDR = "노형동 1234-5";
    private static final String JIBUN_QUERY = "제주특별자치도 제주시 노형동 1234-5";
    private static final String EMD_CD = "50110107";
    private static final Coordinate POINT = Coordinate.of("126.5312", "33.4996");

    private final CompositeGeocoder geocoder = mock(CompositeGeocoder.class);
    private final AdmDistResolver resolver = mock(AdmDistResolver.class);
    private final CrackdownGeocodeProcessor processor = new CrackdownGeocodeProcessor(
            new CrackdownGeocodeAddress(Map.of("노형동", "제주시")), geocoder, resolver);

    @Test
    @DisplayName("JIBUN FOUND + 행정동 매칭 → 좌표·emd_cd")
    void jibunFoundAndContained() {
        when(geocoder.geocode(JIBUN_QUERY)).thenReturn(GeocodeOutcome.found(POINT));
        when(resolver.resolve(POINT.lat(), POINT.lot())).thenReturn(AdmDistMatch.matched(EMD_CD));

        CrackdownCoord coord = processor.process(JIBUN_ADDR);

        assertThat(coord).isEqualTo(new CrackdownCoord(JIBUN_ADDR, POINT.lat(), POINT.lot(), EMD_CD));
    }

    @Test
    @DisplayName("ROAD 는 도로명 입력으로 지오코더를 부른다")
    void roadUsesSidoPrefixedQuery() {
        when(geocoder.geocode("제주특별자치도 연북로 12")).thenReturn(GeocodeOutcome.found(POINT));
        when(resolver.resolve(POINT.lat(), POINT.lot())).thenReturn(AdmDistMatch.matched(EMD_CD));

        assertThat(processor.process("연북로 12").emdCd()).isEqualTo(EMD_CD);
    }

    @Test
    @DisplayName("🔴 결과의 crdn_addr 는 원문 그대로 — Writer 의 정확 일치 조건이 trim 된 값으로 빗나가지 않는다")
    void keepsRawAddress() {
        String raw = "  " + JIBUN_ADDR + " ";
        when(geocoder.geocode(JIBUN_QUERY)).thenReturn(GeocodeOutcome.found(POINT));
        when(resolver.resolve(POINT.lat(), POINT.lot())).thenReturn(AdmDistMatch.matched(EMD_CD));

        assertThat(processor.process(raw).crdnAddr()).isEqualTo(raw);
    }

    @Test
    @DisplayName("FOUND + 경계 밖 → EMD_UNRESOLVED, 좌표 반환 없음 · note 는 좌표·거리만")
    void foundButOutsideBoundary() {
        when(geocoder.geocode(JIBUN_QUERY)).thenReturn(GeocodeOutcome.found(POINT));
        when(resolver.resolve(POINT.lat(), POINT.lot()))
                .thenReturn(AdmDistMatch.unresolved(EMD_CD, "건입동", 60.25));

        CrackdownGeocodeSkippableException e = skipOf(JIBUN_ADDR);

        assertThat(e.reason()).isEqualTo(CrackdownGeocodeSkippableException.Reason.EMD_UNRESOLVED);
        assertThat(e.address()).isEqualTo(JIBUN_ADDR);
        assertThat(e.note())
                .isEqualTo("kind=JIBUN, lat=" + POINT.lat() + " lot=" + POINT.lot()
                        + ", 최근접 50110107(건입동) 60.25m > 버퍼 50m")
                .doesNotContain("노형동");
        assertThat(e.getMessage()).doesNotContain("노형동");
    }

    @Test
    @DisplayName("NOT_FOUND → GEOCODE_FAILED · note 의 괄호 값(주소·지번)을 걷어낸다")
    void notFound() {
        when(geocoder.geocode(JIBUN_QUERY)).thenReturn(GeocodeOutcome.notFound(
                "parcel=NOT_FOUND, road=NOT_FOUND, place=ADDR_MISMATCH(노형동 1234-5), "
                        + "stripped(제주특별자치도 제주시 노형동 1234)=NOT_FOUND"));

        CrackdownGeocodeSkippableException e = skipOf(JIBUN_ADDR);

        assertThat(e.reason()).isEqualTo(CrackdownGeocodeSkippableException.Reason.GEOCODE_FAILED);
        assertThat(e.address()).isEqualTo(JIBUN_ADDR);
        assertThat(e.note()).isEqualTo(
                "kind=JIBUN, parcel=NOT_FOUND, road=NOT_FOUND, place=ADDR_MISMATCH, stripped=NOT_FOUND");
        verifyNoInteractions(resolver);
    }

    @Test
    @DisplayName("note 정리는 값 아닌 설명 괄호를 남기고, 내역이 없으면 대체 문구")
    void withoutValuesKeepsDescriptions() {
        assertThat(CrackdownGeocodeProcessor.withoutValues("parcel=NOT_FOUND, stripped=(해당없음)"))
                .isEqualTo("parcel=NOT_FOUND, stripped=(해당없음)");
        assertThat(CrackdownGeocodeProcessor.withoutValues("주소 캐시 히트(이전 실행에서 폴백 체인 모두 NOT_FOUND)"))
                .isEqualTo("주소 캐시 히트(이전 실행에서 폴백 체인 모두 NOT_FOUND)");
        assertThat(CrackdownGeocodeProcessor.withoutValues("stripped(노형동 (가) 12)=NOT_FOUND"))
                .isEqualTo("stripped=NOT_FOUND");
        assertThat(CrackdownGeocodeProcessor.withoutValues(null)).isEqualTo(CrackdownGeocodeProcessor.NO_DETAIL);
    }

    @Test
    @DisplayName("TRANSIENT → null, transientFiltered 1 → resetStats 후 0")
    void transientIsFiltered() {
        when(geocoder.geocode(JIBUN_QUERY)).thenReturn(GeocodeOutcome.transientFailure("parcel=TRANSIENT"));

        assertThat(processor.process(JIBUN_ADDR)).isNull();
        assertThat(processor.transientFiltered()).isEqualTo(1);
        verifyNoInteractions(resolver);

        processor.resetStats();
        assertThat(processor.transientFiltered()).isZero();
    }

    @Test
    @DisplayName("쿼터 초과 → GeocodeQuotaExceededException 그대로 전파")
    void quotaPropagates() {
        when(geocoder.geocode(JIBUN_QUERY)).thenThrow(new GeocodeQuotaExceededException("OVER_REQUEST_LIMIT"));

        assertThatThrownBy(() -> processor.process(JIBUN_ADDR))
                .isInstanceOf(GeocodeQuotaExceededException.class);
        verifyNoInteractions(resolver);
        assertThat(processor.transientFiltered()).isZero();
    }

    @Test
    @DisplayName("🔴 PLACE → 지오코더를 부르지 않고 GEOCODE_FAILED (Step 4 생략) · note 에 주소 없음")
    void placeIsNotGeocoded() {
        String place = "공항입구교차로";

        CrackdownGeocodeSkippableException e = skipOf(place);

        assertThat(e.reason()).isEqualTo(CrackdownGeocodeSkippableException.Reason.GEOCODE_FAILED);
        assertThat(e.address()).isEqualTo(place);
        assertThat(e.note()).isEqualTo("kind=PLACE, place=NOT_ATTEMPTED(step4-skipped)").doesNotContain(place);
        verify(geocoder, never()).geocode(any());
        verifyNoInteractions(resolver);
    }

    private CrackdownGeocodeSkippableException skipOf(String addr) {
        try {
            processor.process(addr);
        } catch (CrackdownGeocodeSkippableException e) {
            return e;
        }
        throw new AssertionError("CrackdownGeocodeSkippableException 이 나지 않았다");
    }
}
