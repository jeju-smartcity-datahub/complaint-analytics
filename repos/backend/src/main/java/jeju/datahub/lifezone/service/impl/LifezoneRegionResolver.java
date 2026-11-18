package jeju.datahub.lifezone.service.impl;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.region.service.EmdVO;
import jeju.datahub.region.service.RegionService;
import jeju.datahub.region.service.RegionVO;

/**
 * 생활권 분석의 <b>지역 필터 해석</b> — 민원건수·수급 두 분석이 공유한다. (TSK-4089)
 *
 * <p>수급 분석을 붙이면서 {@code LifezoneAnalysisServiceImpl} 안에 있던 같은 로직을 꺼냈다.
 * 규칙이 미묘해서(아래 세 가지) 두 벌로 두면 반드시 갈린다.
 *
 * <ol>
 *   <li>🔴 <b>키를 아예 넣지 않는 것</b>이 "전체" 다 — <b>빈 문자열은 400</b> 이다.
 *       빈 값을 전체로 받아 주면 프론트의 미초기화 상태가 조용히 전 지역 조회로 나간다.</li>
 *   <li>🔴 {@code emdCd} 만 오면 {@code sggCd} 를 <b>만들지 않는다</b> — 매퍼에서 {@code emdCd}
 *       가 우선이라 둘 다 걸면 조건이 중복된다.</li>
 *   <li>미등록 시군구명·읍면동코드는 400 이다. 조용히 전체로 넘기면 "필터가 걸린 줄 알았는데
 *       전 지역" 이 된다.</li>
 * </ol>
 *
 * <p>🔴 예외는 {@link ResponseStatusException} 이다 — 이 도메인을 덮는 전역 예외 핸들러가 없어
 * 다른 예외를 던지면 <b>500</b> 으로 나간다.
 */
@Component
public class LifezoneRegionResolver {

    private final RegionService regionService;

    public LifezoneRegionResolver(RegionService regionService) {
        this.regionService = regionService;
    }

    /**
     * 해석 결과.
     *
     * @param sggCd 시군구코드 5자리. 전 지역이거나 {@code emdCd} 만 온 경우 {@code null}
     * @param sggNm 시군구명. 전 지역이면 {@code null} (응답에 되비추는 값)
     */
    public record Region(String sggCd, String sggNm) {

        /** 전 지역 */
        public static final Region ALL = new Region(null, null);
    }

    /**
     * 빈 문자열을 400 으로 막고 앞뒤 공백을 턴다.
     *
     * @return 원래 {@code null} 이면 {@code null} (= 전체)
     */
    public static String normalizeKey(String value, String field) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 를 빈 값으로 보낼 수 없습니다. 전 지역이면 파라미터를 생략하세요.");
        }
        return trimmed;
    }

    /** 시군구명·읍면동코드를 검증해 매퍼가 쓸 지역 키로 바꾼다. 둘 다 {@code null} 이면 전 지역 */
    public Region resolve(String sggNm, String emdCd) {
        if (sggNm == null && emdCd == null) {
            return Region.ALL;
        }
        List<RegionVO> regions = regionService.selectRegions();
        if (emdCd != null && !existsEmd(regions, emdCd)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "존재하지 않는 읍면동코드입니다: " + emdCd);
        }
        if (sggNm == null) {
            return Region.ALL;   // emdCd 만 왔다 — 매퍼에서 emdCd 가 sggCd 보다 우선한다
        }
        for (RegionVO region : regions) {
            if (sggNm.equals(region.getSggNm())) {
                return new Region(sggCdOf(region), region.getSggNm());
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "등록되지 않은 시군구명입니다: " + sggNm + " (사용 가능: " + sggNames(regions) + ")");
    }

    private static boolean existsEmd(List<RegionVO> regions, String emdCd) {
        for (RegionVO region : regions) {
            for (EmdVO emd : region.getEmds()) {
                if (emdCd.equals(emd.getEmdCd())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 시군구코드는 소속 읍면동코드 앞 5자리다 — 시군구 마스터를 따로 조회하지 않는다 */
    private static String sggCdOf(RegionVO region) {
        for (EmdVO emd : region.getEmds()) {
            String code = emd.getEmdCd();
            if (code != null && code.length() >= 5) {
                return code.substring(0, 5);
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "시군구에 등록된 읍면동이 없어 지역 필터를 만들 수 없습니다: " + region.getSggNm());
    }

    private static String sggNames(List<RegionVO> regions) {
        List<String> names = new ArrayList<>(regions.size());
        for (RegionVO region : regions) {
            names.add(String.valueOf(region.getSggNm()));
        }
        return String.join(", ", names);
    }
}
