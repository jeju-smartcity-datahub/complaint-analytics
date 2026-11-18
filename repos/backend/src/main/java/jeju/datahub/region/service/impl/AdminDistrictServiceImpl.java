package jeju.datahub.region.service.impl;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.region.service.AdminDistrictFeatureCollection;
import jeju.datahub.region.service.AdminDistrictFeatureVO;
import jeju.datahub.region.service.AdminDistrictRequest;
import jeju.datahub.region.service.AdminDistrictService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 행정동 경계 GeoJSON 조회 서비스 구현체. eGovFrame {@link EgovAbstractServiceImpl} 상속 (기존 패턴).
 *
 * < 가이드 §4 결정: {@code ST_AsGeoJSON(bndry_geom)} 결과를 String 으로 받아 Jackson
 * {@link ObjectMapper} 로 JsonNode 변환해 응답 envelope 에 채운다.
 *
 * < 가이드 §4 결정 (성능 주의): 74개 행정동 × MultiPolygon 의 응답이 수 MB 가능. gzip
 * 압축·클라이언트 캐싱 권장은 가이드 본문이 안내한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminDistrictServiceImpl extends EgovAbstractServiceImpl implements AdminDistrictService {

    private final AdminDistrictMapper adminDistrictMapper;

    @Override
    public AdminDistrictFeatureCollection findAll(AdminDistrictRequest req) {
        List<AdminDistrictFeatureVO> rows = adminDistrictMapper.selectAdminDistricts(
                req.getSggCd(), req.getEmdCd());

        ObjectMapper mapper = new ObjectMapper();
        for (AdminDistrictFeatureVO row : rows) {
            if (row.getGeometryRaw() != null) {
                try {
                    row.setGeometry(mapper.readTree(row.getGeometryRaw()));
                } catch (IOException e) {
                    // ST_AsGeoJSON 결과 파싱 실패 — polygon 자체가 잘못된 형태. 한 건만 빠뜨리고 계속.
                    log.warn("행정동 geometry 파싱 실패: {}", e.getClass().getSimpleName());
                    log.debug("행정동 geometry 파싱 실패 상세", e);   // 4.1 — 예외 원문은 DEBUG 로만 (TSK-4336)
                    row.setGeometry(null);
                }
            }
            if (row.getPropertiesRaw() != null) {
                try {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> map = mapper.readValue(row.getPropertiesRaw(), java.util.Map.class);
                    row.setProperties(map);
                } catch (IOException e) {
                    log.warn("행정동 properties 파싱 실패: {}", e.getClass().getSimpleName());
                    log.debug("행정동 properties 파싱 실패 상세", e);   // 4.1 — 예외 원문은 DEBUG 로만 (TSK-4336)
                    row.setProperties(null);
                }
            }
        }

        AdminDistrictFeatureCollection vo = new AdminDistrictFeatureCollection();
        vo.setFeatures(rows);
        return vo;
    }

    /** 매퍼에서 가져온 row 의 properties 를 구성할 때 쓸 헬퍼 (현재는 SQL 에서 직접 주입하지 않으므로 미사용). */
    static Map<String, Object> props(String emdCd, String emdNm, String sggCd) {
        Map<String, Object> m = new HashMap<>();
        m.put("emdCd", emdCd);
        m.put("emdNm", emdNm);
        m.put("sggCd", sggCd);
        return m;
    }
}