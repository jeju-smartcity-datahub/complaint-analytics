package jeju.datahub.parking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import jeju.datahub.parking.service.ParkingLotVO;

/**
 * 공영주차장 목록 API 통합 테스트. ({@code GET /api/parking-lots})
 *
 * <p>이 테스트가 생기기 전에는 {@code /api/parking-lots} 를 타는 테스트가 0건이라
 * {@code ParkingLotMapper.xml} 이 {@code ./gradlew test} 에서 한 번도 실행되지 않았다
 * (MyBatis XML 은 {@code compileJava} 대상이 아니라 {@code processResources} 로만 복사된다).
 * 그 SQL 에 공통코드 {@code PKLT_TYPE_CD} LEFT JOIN 1건이 더해졌으므로,
 * <b>그 조인이 끊기면 컴파일은 통과하고 런타임에 값만 null 이 되는</b> 조용한 실패가 난다.
 *
 * <p>전략은 {@code analysis/HotspotRankingIntegrationTest} 와 같다 — {@code RANDOM_PORT} +
 * {@link TestRestTemplate}, {@code test} 프로파일이 보안 체인을 꺼서 인증 없이 호출된다
 * ({@code app.auth.security-enabled=false}). 🔴 인증 경로를 타지 않으므로
 * {@code parking.tb_lgn_hstry} 에 행을 만들지 않는다 — 정리 훅이 필요 없다.
 * DB 를 바꾸지 않으므로 복원도 필요 없다.
 *
 * <h2>🔴 데이터 값을 상수로 박지 않는다</h2>
 * {@code TB_PBLPKLT} 는 배치({@code pblpkltFetchJob})가 소유한 표다 — 재수집 한 번이면
 * 건수·이름·좌표가 통째로 바뀐다. 그래서 기대값은 리터럴이 아니라 <b>불변식</b>이다.
 * <ul>
 *   <li>200 이고 배열이다 (컨텍스트·매퍼가 살아 있다).</li>
 *   <li>유형코드가 있으면 유형명도 있다 — 공통코드 조인이 끊기지 않았다는 확인.
 *       <b>어떤 이름인지는 단언하지 않는다.</b></li>
 *   <li>{@code sggCd} 로 좁힌 결과는 전체보다 크지 않다 (필터가 조건을 <i>더한다</i>).</li>
 * </ul>
 * ⚠️ 적재분이 0건인 CI 에서도 통과해야 하므로 {@code isNotEmpty()} 를 단언하지 않는다
 * (0건이면 2번 단언은 자연히 건너뛴다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ParkingLotIntegrationTest {

    private static final String PARKING_LOTS_URL = "/api/parking-lots";

    /** 제주시. 🔴 존재를 단언하는 값이 아니라 "좁혀진다" 를 보이기 위한 입력일 뿐이다. */
    private static final String SGG_CD_JEJU = "50110";

    private static final ParameterizedTypeReference<List<ParkingLotVO>> LIST_OF_LOTS =
            new ParameterizedTypeReference<>() { };

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("전체 조회 — 200 이고 배열이다 (건수를 단언하지 않는다)")
    void parkingLotsReturnsArray() {
        assertThat(parkingLots(null)).isNotNull();
    }

    @Test
    @DisplayName("유형코드가 있으면 유형명도 있다 — 공통코드 PKLT_TYPE_CD 조인 확인 (값은 박지 않는다)")
    void parkingLotTypeNameAccompaniesTypeCode() {
        List<ParkingLotVO> lots = parkingLots(null);
        if (lots.isEmpty()) {
            return; // 적재분 0건 (CI) — 이 불변식은 검사 대상이 없다
        }
        ParkingLotVO first = lots.get(0);
        if (first.getPkltTypeCd() == null) {
            return; // 원천 결측 — 조인할 키 자체가 없다
        }
        assertThat(first.getPkltTypeNm())
                .as("pkltTypeCd=%s 인데 pkltTypeNm 이 null — TB_COM_CD(PKLT_TYPE_CD) 조인이 끊겼다",
                        first.getPkltTypeCd())
                .isNotNull();
    }

    @Test
    @DisplayName("sggCd 로 좁히면 전체보다 크지 않다 (필터가 조건을 더한다)")
    void sggCdFilterDoesNotWiden() {
        int all = parkingLots(null).size();
        int filtered = parkingLots(SGG_CD_JEJU).size();
        assertThat(filtered)
                .as("sggCd=%s 결과(%d)가 전체(%d)보다 많다 — 필터가 OR 로 붙었는지 확인하라",
                        SGG_CD_JEJU, filtered, all)
                .isLessThanOrEqualTo(all);
    }

    // ────────────────────────────────────────────────────────────── 헬퍼

    /** {@code sggCd} 가 null 이면 파라미터 자체를 붙이지 않는다(전체 조회). */
    private List<ParkingLotVO> parkingLots(String sggCd) {
        ResponseEntity<List<ParkingLotVO>> response = sggCd == null
                ? restTemplate.exchange(PARKING_LOTS_URL, HttpMethod.GET, null, LIST_OF_LOTS)
                : restTemplate.exchange(PARKING_LOTS_URL + "?sggCd={sggCd}", HttpMethod.GET, null,
                        LIST_OF_LOTS, Map.of("sggCd", sggCd));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).as("응답 바디가 배열이 아니다").isNotNull();
        return response.getBody();
    }
}
