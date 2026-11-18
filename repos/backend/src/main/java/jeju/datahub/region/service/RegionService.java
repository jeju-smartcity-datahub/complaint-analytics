package jeju.datahub.region.service;

import java.util.List;

/**
 * 행정구역(시군구 → 읍면동) 조회 서비스 인터페이스.
 *
 * <p>보고서 설정(TSK-4026 D12)·알림(TSK-4028 D9) <b>공용</b>이라 두 도메인 패키지 어느 쪽에도 넣지 않고
 * 독립 패키지 {@code jeju.datahub.region} 에 둔다.
 */
public interface RegionService {

    /** 시군구 → 읍면동 트리 전체 조회 (읍면동코드 오름차순) */
    List<RegionVO> selectRegions();
}
