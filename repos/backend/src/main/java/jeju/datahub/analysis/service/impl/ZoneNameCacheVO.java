package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 구역명 캐시 1행 ({@code parking.TB_ZONE_NM_CACHE}). MyBatis 조회·저장 전용 VO다.
 *
 * <p>컬럼(스네이크) → 필드(카멜) 매핑은 {@code map-underscore-to-camel-case=true} 로 자동 처리한다.
 * <b>JPA 엔티티가 아니다</b> — 자세한 이유는 {@link ZoneNameCacheMapper} 주석 참고.
 *
 * <p>{@code impl} 패키지에 두는 이유: 이 표는 폴백 체인의 내부 사정이라 API 응답에 노출되지 않는다.
 * 외부로 나가는 것은 {@code HotspotZoneVO.zoneNm} / {@code zoneNmSrc} 뿐이다.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ZoneNameCacheVO {

    /** 위도키 — 군집 중심 위도를 소수 5자리(약 1m)로 반올림한 값. PK 앞자리 */
    private BigDecimal latKey;

    /** 경도키 — 군집 중심 경도를 소수 5자리(약 1m)로 반올림한 값. PK 뒷자리 */
    private BigDecimal lotKey;

    /**
     * 구역명 — 화면에 그대로 쓰는 <b>최종 이름</b>(읍면동명 접두까지 붙은 100자 이내).
     * {@code zoneNmSrc = NOT_FOUND} 면 {@code null} 이다.
     */
    private String zoneNm;

    /**
     * 구역명 출처 — {@code POI} / {@code REVERSE_GEOCODE} / {@code NOT_FOUND}.
     *
     * <p>{@code NOT_FOUND} 는 "POI 도 역지오코딩도 결과가 없었다"는 <b>확정</b> 실패이며,
     * 캐시 히트로 재호출을 막는 것이 저장 목적이다. 일시 실패는 애초에 저장되지 않는다.
     */
    private String zoneNmSrc;

    /** 장소거리 — POI 채택 시 중심점까지 거리(m, 진단용). POI 외 출처는 {@code null} */
    private Integer poiDistM;

    /** 조회일시 — 외부 API 로 이름을 확정한 시각 */
    private LocalDateTime inqDt;
}
