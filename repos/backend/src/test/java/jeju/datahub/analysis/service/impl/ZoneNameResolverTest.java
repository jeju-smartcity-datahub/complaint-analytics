package jeju.datahub.analysis.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jeju.datahub.analysis.service.impl.VworldCallSupport.LookupStatus;
import jeju.datahub.analysis.service.impl.VworldPlaceClient.NearestPlace;
import jeju.datahub.analysis.service.impl.VworldReverseGeocodeClient.ReverseAddress;
import jeju.datahub.analysis.service.impl.ZoneNameResolver.ZoneName;

/**
 * {@link ZoneNameResolver} 단위 테스트.
 *
 * <p>검증 대상은 <b>폴백 순서 · 캐시 히트 · 캐시 저장 정책(확정 실패는 저장 / 일시 실패는 미저장)
 * · 외부 호출 상한</b>이다. DB·HTTP 를 띄우지 않고 매퍼와 두 클라이언트를 Mockito 로 갈아끼운다.
 *
 * <p>패키지가 {@code ...service.impl} 인 이유는 {@link ZoneNameCacheVO} 와 상수들이 impl 패키지에
 * 있기 때문이다.
 */
@ExtendWith(MockitoExtension.class)
class ZoneNameResolverTest {

    /** 아라일동 군집 중심(실측) — SQL 이 zone_nm 을 NULL 로 남긴 군집이다. */
    private static final BigDecimal LAT = new BigDecimal("33.477490");
    private static final BigDecimal LOT = new BigDecimal("126.541882");
    private static final String EMD = "아라일동";

    @Mock
    private ZoneNameCacheMapper cacheMapper;

    @Mock
    private ZoneNameCacheWriter cacheWriter;

    @Mock
    private VworldPlaceClient placeClient;

    @Mock
    private VworldReverseGeocodeClient reverseGeocodeClient;

    private ZoneNameResolver resolver(int maxLookups) {
        return new ZoneNameResolver(cacheMapper, cacheWriter, placeClient, reverseGeocodeClient, maxLookups, 3);
    }

    private static ZoneNameCacheVO cacheRow(String zoneNm, String src, Integer distM) {
        ZoneNameCacheVO row = new ZoneNameCacheVO();
        row.setZoneNm(zoneNm);
        row.setZoneNmSrc(src);
        row.setPoiDistM(distM);
        return row;
    }

    @Test
    @DisplayName("캐시 히트: 외부를 한 번도 부르지 않고 저장된 이름을 그대로 쓴다")
    void cacheHitSkipsExternalCalls() {
        given(cacheMapper.selectZoneName(any(), any()))
                .willReturn(cacheRow("아라일동 아라초교", ZoneNameResolver.SRC_POI, 146));

        ZoneName name = resolver(12).newSession().resolve(LAT, LOT, EMD);

        assertThat(name.zoneNm()).isEqualTo("아라일동 아라초교");
        assertThat(name.zoneNmSrc()).isEqualTo(ZoneNameResolver.SRC_POI);
        assertThat(name.poiDistM()).isEqualTo(146);
        verifyNoInteractions(placeClient, reverseGeocodeClient);
    }

    @Test
    @DisplayName("좌표키는 소수 5자리 반올림이다 — 33.477490 → 33.47749")
    void roundsCoordinateKeyToFiveDecimals() {
        given(cacheMapper.selectZoneName(any(), any())).willReturn(cacheRow("x", ZoneNameResolver.SRC_POI, 1));

        resolver(12).newSession().resolve(LAT, LOT, EMD);

        ArgumentCaptor<BigDecimal> lat = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> lot = ArgumentCaptor.forClass(BigDecimal.class);
        verify(cacheMapper).selectZoneName(lat.capture(), lot.capture());
        assertThat(lat.getValue()).isEqualByComparingTo("33.47749");
        assertThat(lot.getValue()).isEqualByComparingTo("126.54188");
        assertThat(lat.getValue().scale()).isEqualTo(5);
    }

    @Test
    @DisplayName("NOT_FOUND 캐시 히트: 확정 실패라 외부를 다시 부르지 않고 '{읍면동} 일대' 로 간다")
    void notFoundCacheHitGoesStraightToEmdFallback() {
        given(cacheMapper.selectZoneName(any(), any()))
                .willReturn(cacheRow(null, ZoneNameResolver.SRC_NOT_FOUND, null));

        ZoneName name = resolver(12).newSession().resolve(LAT, LOT, EMD);

        assertThat(name.zoneNm()).isEqualTo("아라일동 일대");
        assertThat(name.zoneNmSrc()).isEqualTo(ZoneNameResolver.SRC_EMD);
        verifyNoInteractions(placeClient, reverseGeocodeClient);
    }

    @Test
    @DisplayName("② POI 채택: 읍면동명을 앞에 붙이고 거리와 함께 캐시에 저장한다")
    void adoptsPoiAndCachesIt() {
        given(placeClient.findNearestLandmark(any(), any(), anyString()))
                .willReturn(new NearestPlace(LookupStatus.FOUND, "아라초교", 146));

        ZoneName name = resolver(12).newSession().resolve(LAT, LOT, EMD);

        assertThat(name.zoneNm()).isEqualTo("아라일동 아라초교");
        assertThat(name.zoneNmSrc()).isEqualTo(ZoneNameResolver.SRC_POI);
        verifyNoInteractions(reverseGeocodeClient);

        verify(cacheWriter).save(any(), any(), eq("아라일동 아라초교"), eq(ZoneNameResolver.SRC_POI), eq(146));
    }

    @Test
    @DisplayName("③ 역지오코딩: 이름이 이미 읍면동으로 시작하면 접두를 중복해 붙이지 않는다")
    void fallsBackToReverseGeocodeWithoutDuplicatingEmd() {
        given(placeClient.findNearestLandmark(any(), any(), anyString()))
                .willReturn(new NearestPlace(LookupStatus.NOT_FOUND, null, 0));
        given(reverseGeocodeClient.findAddress(any(), any()))
                .willReturn(new ReverseAddress(LookupStatus.FOUND, "아라일동 2304-4"));

        ZoneName name = resolver(12).newSession().resolve(LAT, LOT, EMD);

        assertThat(name.zoneNm()).isEqualTo("아라일동 2304-4");
        assertThat(name.zoneNmSrc()).isEqualTo(ZoneNameResolver.SRC_REVERSE_GEOCODE);
        assertThat(name.poiDistM()).isNull();
        verify(cacheWriter).save(any(), any(), eq("아라일동 2304-4"),
                eq(ZoneNameResolver.SRC_REVERSE_GEOCODE), isNull());
    }

    @Test
    @DisplayName("둘 다 확정 실패면 NOT_FOUND 를 저장한다 — 다음 조회의 재호출을 막는다")
    void cachesDeterministicNotFound() {
        given(placeClient.findNearestLandmark(any(), any(), anyString()))
                .willReturn(new NearestPlace(LookupStatus.NOT_FOUND, null, 0));
        given(reverseGeocodeClient.findAddress(any(), any()))
                .willReturn(new ReverseAddress(LookupStatus.NOT_FOUND, null));

        ZoneName name = resolver(12).newSession().resolve(LAT, LOT, EMD);

        assertThat(name.zoneNm()).isEqualTo("아라일동 일대");
        assertThat(name.zoneNmSrc()).isEqualTo(ZoneNameResolver.SRC_EMD);

        verify(cacheWriter).save(any(), any(), isNull(), eq(ZoneNameResolver.SRC_NOT_FOUND), isNull());
    }

    @Test
    @DisplayName("🔴 일시 실패(타임아웃·5xx)는 저장하지 않는다 — 장애 한 번이 영구 '일대' 로 굳지 않게")
    void doesNotCacheTransientFailure() {
        given(placeClient.findNearestLandmark(any(), any(), anyString()))
                .willReturn(new NearestPlace(LookupStatus.TRANSIENT_FAILURE, null, 0));
        given(reverseGeocodeClient.findAddress(any(), any()))
                .willReturn(new ReverseAddress(LookupStatus.TRANSIENT_FAILURE, null));

        ZoneName name = resolver(12).newSession().resolve(LAT, LOT, EMD);

        assertThat(name.zoneNm()).isEqualTo("아라일동 일대");
        assertThat(name.zoneNmSrc()).isEqualTo(ZoneNameResolver.SRC_EMD);
        verify(cacheWriter, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("POI 가 일시 실패면 역지오코딩 결과를 쓰되 캐시에 굳히지 않는다")
    void doesNotCacheReverseResultWhenPoiWasTransient() {
        given(placeClient.findNearestLandmark(any(), any(), anyString()))
                .willReturn(new NearestPlace(LookupStatus.TRANSIENT_FAILURE, null, 0));
        given(reverseGeocodeClient.findAddress(any(), any()))
                .willReturn(new ReverseAddress(LookupStatus.FOUND, "아라일동 2304-4"));

        ZoneName name = resolver(12).newSession().resolve(LAT, LOT, EMD);

        assertThat(name.zoneNm()).isEqualTo("아라일동 2304-4");
        verify(cacheWriter, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("외부 호출 상한: 캐시 미스가 상한을 넘으면 더 부르지 않고 '일대' 로 내려간다")
    void stopsCallingExternalBeyondLookupCap() {
        given(placeClient.findNearestLandmark(any(), any(), anyString()))
                .willReturn(new NearestPlace(LookupStatus.NOT_FOUND, null, 0));
        given(reverseGeocodeClient.findAddress(any(), any()))
                .willReturn(new ReverseAddress(LookupStatus.NOT_FOUND, null));

        ZoneNameResolver.Session session = resolver(2).newSession();
        session.resolve(LAT, LOT, EMD);
        session.resolve(new BigDecimal("33.50000"), new BigDecimal("126.50000"), "연동");
        ZoneName third = session.resolve(new BigDecimal("33.51000"), new BigDecimal("126.51000"), "노형동");

        assertThat(third.zoneNm()).isEqualTo("노형동 일대");
        assertThat(third.zoneNmSrc()).isEqualTo(ZoneNameResolver.SRC_EMD);
        verify(placeClient, times(2)).findNearestLandmark(any(), any(), anyString());
        verify(reverseGeocodeClient, times(2)).findAddress(any(), any());
    }

    @Test
    @DisplayName("🔴 연속 일시 실패 차단: 3건 연속 일시 실패면 그 세션은 외부를 더 부르지 않는다")
    void stopsCallingExternalAfterConsecutiveTransientFailures() {
        given(placeClient.findNearestLandmark(any(), any(), anyString()))
                .willReturn(new NearestPlace(LookupStatus.TRANSIENT_FAILURE, null, 0));
        given(reverseGeocodeClient.findAddress(any(), any()))
                .willReturn(new ReverseAddress(LookupStatus.TRANSIENT_FAILURE, null));

        ZoneNameResolver.Session session = resolver(200).newSession();
        for (int i = 0; i < 10; i++) {
            ZoneName name = session.resolve(new BigDecimal("33.5" + i), new BigDecimal("126.5" + i), "연동");
            assertThat(name.zoneNmSrc()).isEqualTo(ZoneNameResolver.SRC_EMD);
        }

        verify(placeClient, times(3)).findNearestLandmark(any(), any(), anyString());
        verify(reverseGeocodeClient, times(3)).findAddress(any(), any());
        // 세션 단위다 — 새 요청은 다시 시도한다
        resolver(200).newSession().resolve(LAT, LOT, EMD);
        verify(placeClient, times(4)).findNearestLandmark(any(), any(), anyString());
    }

    @Test
    @DisplayName("연속 일시 실패 차단은 '연속' 만 센다 — 사이에 정상 조회가 끼면 다시 0 부터")
    void transientCounterResetsOnHealthyLookup() {
        given(placeClient.findNearestLandmark(any(), any(), anyString()))
                .willReturn(new NearestPlace(LookupStatus.TRANSIENT_FAILURE, null, 0),
                        new NearestPlace(LookupStatus.TRANSIENT_FAILURE, null, 0),
                        new NearestPlace(LookupStatus.NOT_FOUND, null, 0),
                        new NearestPlace(LookupStatus.TRANSIENT_FAILURE, null, 0),
                        new NearestPlace(LookupStatus.TRANSIENT_FAILURE, null, 0),
                        new NearestPlace(LookupStatus.NOT_FOUND, null, 0));
        given(reverseGeocodeClient.findAddress(any(), any()))
                .willReturn(new ReverseAddress(LookupStatus.NOT_FOUND, null));

        ZoneNameResolver.Session session = resolver(200).newSession();
        for (int i = 0; i < 6; i++) {
            session.resolve(new BigDecimal("33.5" + i), new BigDecimal("126.5" + i), "연동");
        }

        verify(placeClient, times(6)).findNearestLandmark(any(), any(), anyString());
    }

    @Test
    @DisplayName("캐시 저장이 실패해도 이름은 그대로 나온다 — 캐시는 성능 장치일 뿐이다")
    void survivesCacheWriteFailure() {
        given(placeClient.findNearestLandmark(any(), any(), anyString()))
                .willReturn(new NearestPlace(LookupStatus.FOUND, "아라초교", 146));
        org.mockito.BDDMockito.willThrow(new IllegalStateException("DB down"))
                .given(cacheWriter).save(any(), any(), any(), any(), any());

        ZoneName name = resolver(12).newSession().resolve(LAT, LOT, EMD);

        assertThat(name.zoneNm()).isEqualTo("아라일동 아라초교");
    }

    @Test
    @DisplayName("읍면동명이 없으면(중심점이 행정경계 밖) '위치 미상' 으로 마무리한다")
    void unknownWhenEmdMissing() {
        assertThat(ZoneNameResolver.emdFallback(null).zoneNm()).isEqualTo(ZoneNameResolver.UNKNOWN_ZONE_NM);
        assertThat(ZoneNameResolver.emdFallback("  ").zoneNm()).isEqualTo(ZoneNameResolver.UNKNOWN_ZONE_NM);
    }

    @Test
    @DisplayName("구역명은 100자에서 자른다 — TB_ZONE_NM_CACHE.zone_nm 길이와 같다")
    void truncatesTo100Chars() {
        String longTitle = "가".repeat(200);

        assertThat(ZoneNameResolver.compose("연동", longTitle)).hasSize(100);
        assertThat(ZoneNameResolver.compose("연동", null)).isNull();
    }
}
