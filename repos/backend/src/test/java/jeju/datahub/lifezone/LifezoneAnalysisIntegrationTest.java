package jeju.datahub.lifezone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import jeju.datahub.lifezone.service.LifezoneAnalysisRequest;
import jeju.datahub.lifezone.service.LifezoneAnalysisResultVO;
import jeju.datahub.lifezone.service.LifezoneAnalysisService;
import jeju.datahub.lifezone.service.LifezoneCellVO;
import jeju.datahub.lifezone.service.LifezoneClsfMthd;
import jeju.datahub.lifezone.service.LifezoneDtlVO;
import jeju.datahub.lifezone.service.LifezoneGrade;
import jeju.datahub.lifezone.service.LifezoneGradeCriterionVO;
import jeju.datahub.lifezone.service.LifezoneGradeScale;
import jeju.datahub.lifezone.service.LifezoneRankVO;
import jeju.datahub.lifezone.service.LifezoneTarget;

/**
 * 생활권 시뮬레이션 API 통합 테스트. ({@code GET /api/analysis/lifezone})
 *
 * <p>{@code HotspotAnalysisIntegrationTest}와 같은 전략이다 —
 * {@code RANDOM_PORT} + {@link TestRestTemplate} 로 실제 HTTP 를 태우고, 로컬 PostgreSQL
 * ({@code ./local.sh infra})의 적재분을 그대로 쓴다. {@code @Transactional} 롤백은 쓰지 않는다
 * (요청이 별도 스레드·별도 커넥션이라 테스트 트랜잭션이 서버 쪽을 감싸지 못한다). 이 API 는
 * <b>조회 전용</b>이다 — 케이스 16 이 그것을 직접 단언한다. 🔁 단 케이스 24~26만 구역명 캐시
 * ({@code tb_zone_nm_cache}) 1행을 심고, {@code @AfterEach} {@link #restorePlantedZoneName()} 가
 * 그 키 한 행만 되돌린다(원래 행이 있었으면 4열 복원 · 없었으면 DELETE — 공유 캐시라 전체 삭제 금지).
 *
 * <h2>🔴 날짜를 상수로 박지 않는다</h2>
 * 민원 적재분은 배치가 소유해 재적재 한 번이면 구간이 통째로 옮겨간다(실측: {@code 20260704~20260831}
 * → {@code 20251104~20251231} → {@code 20260804~20260930}). 그래서 조회 기간은 전부
 * {@link #dataBgngYmd}·{@link #dataEndYmd} 에서 파생하며, 그 두 값은 매퍼
 * {@code selectDataRange} 와 <b>같은 SQL</b>로 DB 에서 읽는다({@link #dataRangeSql()}).
 * 형식 검증만 보는 케이스도 예외가 아니다 — 유효한 아무 날짜가 필요하면 보유 구간을 쓴다.
 *
 * <h2>🔴 등급 임계값을 상수로 박지 않는다</h2>
 * 경계는 <b>조회 결과 전체</b>에서 산출돼 조회 조건이 바뀌면 함께 바뀐다
 * (케이스 21 이 그것을 단언한다). 🔁 <b>민원건수 분류방법은 등간격({@code EQUAL_INTERVAL})
 * 1종으로 확정</b>됐다(종전 기본은 자연분류 Jenks · 목록 밖 값은 400 — 케이스 23). 그래서 등급 검증은 전부 응답 {@code gradeCriteria} 의
 * {@code minVl} 을 읽어 <b>그 값으로</b> 한다 — 테스트가 임계값을 스스로 안다고 가정하지 않는다.
 *
 * <h2>🔴 셀 수·cap 절대값은 DB 에서 다시 계산해 대조한다</h2>
 * 실측(전 구간·전 지역: 1km 341 · 500m 597 · 250m 1,079 · 100m 2,015칸)은 세 번의 재적재 내내
 * 같았지만 시드가 바뀌면 흔들릴 수 있다. 그래서 절대값이 필요한 곳은 매퍼와 같은 식
 * ({@link #expectedCellCount}·{@link #expectedCap})으로 DB 에서 재계산한 값과 비교한다.
 * {@code cap} 은 <b>최대값</b>이므로 재계산도 {@code max(n)} 이다 — 백분위가 아니다.
 *
 * <h2>🔴 민원 유형명을 상수로 박지 않는다</h2>
 * 세부 내역({@code dtl})의 유형명은 {@code TB_COM_CD}({@code CVLCPT_TYPE_CD})에서 읽어 대조한다.
 *
 * <h2>VWorld 실호출에 의존하지 않는다</h2>
 * 구역명 POI는 {@code ranking} 행에만 시도되고(요청당 외부 조회 상한 초과 행도 폴백), 키가 비었거나
 * 외부가 죽으면 생활권 폴백({@code {읍면동명} 격자 {코드}})으로 되돌아온다. {@code cells} 는
 * 매퍼 JOIN 으로 <b>캐시 이름만</b> 싣는다(외부 호출 0). 그래서 케이스 18 은 <b>캐시에 이름이 있는 셀 = 그 이름 ·
 * 없는 셀 = 폴백</b>을 단언한다(셀의 캐시 유무는 테스트가 JDBC 로 일괄 조회) — 키 유무·외부 상태와 무관하게 성립한다.
 *
 * <h2>⚠️ 응답 역직렬화에 전용 {@link ObjectMapper} 를 쓴다</h2>
 * {@code LifezoneGradeScale} 은 {@code minVls} 를 {@code @JsonIgnore} 로 감춘 record 라
 * (경계는 {@code gradeCriteria} 로 나가므로 중복을 없앤 설계) 기본 Jackson 으로는 <b>역</b>직렬화가
 * 되지 않는다 — 정규 생성자가 {@code null} 을 받아 터진다. API 는 직렬화 전용이라 운영에 문제가
 * 없지만 테스트는 읽어야 하므로, {@code cap}·{@code width} 만 살리는 역직렬화기를 테스트가 쥔다
 * ({@link #responseMapper()}). 그렇게 만든 {@code gradeScale} 의 {@code minVls} 는 <b>가짜</b>이므로
 * 등급 판정이 필요하면 {@link #scaleOf(LifezoneAnalysisResultVO)} 로 {@code gradeCriteria} 에서
 * 되살린 것을 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("생활권 시뮬레이션 API (GET /api/analysis/lifezone)")
class LifezoneAnalysisIntegrationTest {

    private static final String LIFEZONE_URL = "/api/analysis/lifezone";

    /** 응답 {@code bgngYmd}/{@code endYmd} 와 같은 표기 형식({@code YYYYMMDD}). */
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 조회 가능한 최대 구간(일, 양끝 포함). {@code jeju.datahub.common.DateRanges.MAX_RANGE_DAYS} 와
     * 같은 값이어야 한다 — 테스트가 독립적으로 다시 적는다.
     */
    private static final int MAX_RANGE_DAYS = 366;

    /** 등급 수. 임계값 상수를 박지 않기 위해 필요한 값은 전부 이 수에서 파생한다.
     *  구간 폭 규약({@code width = ceil(cap / GRADE_CNT)})도 이 수에 걸려 있다. */
    private static final int GRADE_CNT = LifezoneGrade.values().length;

    /** {@code TB_GRID} 보유 격자 4종 — <b>큰 것부터</b>. 케이스 2 의 단조 증가 순서다. */
    private static final int[] GRID_SIZES = { 1000, 500, 250, 100 };

    /** 요청 기본 격자크기. 결과를 보는 대부분의 케이스가 쓴다. */
    private static final int DEFAULT_GRID_SZ = 500;

    /** 가장 잘게 쪼개는 격자 — 좁은 지역에서 최대값을 끌어내리는 케이스 22 가 쓴다. */
    private static final int FINEST_GRID_SZ = 100;

    /** 보유 구간 밖 기간의 길이(일) — 케이스 8. 시작이 이미 구간 밖이라 길이는 아무 값이나 된다. */
    private static final int OUT_OF_RANGE_DAYS = 31;

    /** 보유하지 않는 격자크기(케이스 3). */
    private static final int UNSUPPORTED_GRID_SZ = 300;

    /** 제주도 좌표 범위 (케이스 13). 행정구역이 바뀌지 않는 한 고정이다. */
    private static final BigDecimal LAT_MIN = new BigDecimal("33");
    private static final BigDecimal LAT_MAX = new BigDecimal("34");
    private static final BigDecimal LOT_MIN = new BigDecimal("126");
    private static final BigDecimal LOT_MAX = new BigDecimal("127");

    /** 격자 폴백 구역명의 꼬리 — {@code {읍면동명} 격자 {코드}} / {@code 격자 {코드}} 양쪽에 공통이다. */
    private static final String GRID_NM_MARK = "격자 ";

    /** 응답 전용 역직렬화기 (클래스 주석 "⚠️ 응답 역직렬화" 참고). */
    private static final ObjectMapper RESPONSE_MAPPER = responseMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 케이스 4 의 메시지 확인용 — 오류 바디는 환경(devtools)에 따라 message 가 없다. */
    @Autowired
    private LifezoneAnalysisService lifezoneAnalysisService;

    /** 원천(민원) 보유 구간 시작일 — 매퍼 {@code selectDataRange} 와 같은 SQL 로 읽는다. */
    private String dataBgngYmd;

    /** 원천 보유 구간 종료일. */
    private String dataEndYmd;

    /**
     * 실제로 <b>요청 기간으로 쓰는</b> 시작일 = {@code max(dataBgngYmd, dataEndYmd - 365일)}.
     *
     * <p>🔴 보유 구간을 그대로 요청하면 상한이 없다 — {@code max} 는 수집 배치가 매일 밀어 올려
     * 구간이 하루씩 자라고, 366일({@code DateRanges.MAX_RANGE_DAYS})을 넘는 순간
     * {@link #fullPeriod()} 를 쓰는 전 케이스가 한꺼번에 400 을 받는다. 시작일만 자른다 —
     * 보유 구간이 1년 이내면 {@code dataBgngYmd} 와 같은 값이라 동작 변화가 없다.
     *
     * <p>⚠️ <b>요청 조건 echo</b>({@code body.getBgngYmd()})만 이 값과 대조한다.
     * <b>보유 구간 echo</b>({@code body.getDataBgngYmd()})는 요청과 무관하므로
     * {@link #dataBgngYmd} 그대로 대조한다.
     */
    private String queryBgngYmd;

    /** 민원이 가장 많은 시군구명 — 지역 필터 케이스(7·21)의 입력. 이름을 소스에 박지 않는다. */
    private String filterSggNm;

    /** {@link #filterSggNm} 의 시군구코드(=읍면동코드 앞 5자리). 기대 셀 수를 DB 에서 다시 셀 때 쓴다. */
    private String filterSggCd;

    /**
     * {@code cap}(=최대값)이 등급 수 이하로 떨어지는 읍면동코드(100m 격자 기준) — 케이스 22 의 입력.
     * "좁은 조회"를 <b>데이터에서 찾아</b> 쓴다(코드를 박으면 재적재에 깨진다).
     */
    private String narrowEmdCd;

    /** {@code TB_COM_CD} 의 민원유형 코드→명. 세부 내역 대조에 쓴다(유형명을 소스에 박지 않는다). */
    private Map<String, String> cvlcptTypeNms;

    /** 케이스 24 가 구역명 캐시에 심은 키. {@code null} 이면 심지 않았다(건너뜀) — 복원할 것이 없다. */
    private BigDecimal plantedLatKey;
    private BigDecimal plantedLotKey;

    /** 심기 전 그 키의 원래 캐시 행. {@code null} 이면 원래 행이 없었다 → 복원은 DELETE 다. */
    private CachedZoneNm originalCachedZoneNm;

    /**
     * 데이터에서 얻어야 하는 값을 먼저 확보한다. 적재분이 비어 있으면 개별 케이스가
     * {@code but was: 0} 으로 죽기 전에 <b>여기서 원인이 드러나게</b> 실패시킨다.
     */
    @BeforeEach
    void resolveDataFixtures() {
        String[] range = jdbcTemplate.queryForObject(dataRangeSql(),
                (rs, rowNum) -> new String[] { rs.getString(1), rs.getString(2) });
        assertThat(range).isNotNull();
        dataBgngYmd = range[0];
        dataEndYmd = range[1];
        assertThat(dataBgngYmd)
                .as("원천(TB_CVLCPT)이 비어 있다 — 배치 적재분을 먼저 확인하라")
                .matches("\\d{8}");
        assertThat(dataEndYmd).matches("\\d{8}");
        queryBgngYmd = clampToMaxRange(dataBgngYmd, dataEndYmd);

        filterSggNm = jdbcTemplate.queryForObject(
                "SELECT a.sgg_nm FROM parking.tb_cvlcpt c"
                        + "  JOIN parking.tb_admdst a ON a.emd_cd = c.emd_cd"
                        + " WHERE c.cvlcpt_type_cd <> '99' AND c.lat IS NOT NULL"
                        + " GROUP BY a.sgg_nm ORDER BY count(*) DESC, a.sgg_nm LIMIT 1",
                String.class);
        assertThat(filterSggNm).as("민원이 붙은 시군구가 없다").isNotBlank();

        filterSggCd = jdbcTemplate.queryForObject(
                "SELECT min(left(emd_cd, 5)) FROM parking.tb_admdst WHERE sgg_nm = ?",
                String.class, filterSggNm);
        assertThat(filterSggCd).matches("\\d{5}");

        List<String> narrowCandidates = jdbcTemplate.queryForList(narrowEmdSql(), String.class,
                FINEST_GRID_SZ, queryBgngYmd, dataEndYmd, GRADE_CNT);
        narrowEmdCd = narrowCandidates.isEmpty() ? null : narrowCandidates.get(0);
        assertThat(narrowEmdCd)
                .as("최대값이 %d 이하인 읍면동이 없다 — 케이스 22 의 전제(단조 보정 발동)가 성립하지 않는다",
                        GRADE_CNT)
                .isNotBlank();

        cvlcptTypeNms = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT cd_vl, cd_nm FROM parking.tb_com_cd WHERE group_cd = 'CVLCPT_TYPE_CD'",
                rs -> {
                    cvlcptTypeNms.put(rs.getString(1), rs.getString(2));
                });
        assertThat(cvlcptTypeNms).as("TB_COM_CD 에 민원유형 코드가 없다").isNotEmpty();
    }

    /**
     * 케이스 24 가 심은 캐시 1행을 되돌린다. 심은 키 한 행만 다룬다(공유 캐시라 전체 삭제 금지).
     * 케이스가 중간에 실패해도 돈다.
     */
    @AfterEach
    void restorePlantedZoneName() {
        if (plantedLatKey == null || plantedLotKey == null) {
            return;
        }
        if (originalCachedZoneNm != null) {
            jdbcTemplate.update("UPDATE parking.tb_zone_nm_cache"
                            + "   SET zone_nm = ?, zone_nm_src = ?, poi_dist_m = ?, inq_dt = ?"
                            + " WHERE lat_key = ? AND lot_key = ?",
                    originalCachedZoneNm.zoneNm(), originalCachedZoneNm.zoneNmSrc(),
                    originalCachedZoneNm.poiDistM(), originalCachedZoneNm.inqDt(),
                    plantedLatKey, plantedLotKey);
        } else {
            jdbcTemplate.update("DELETE FROM parking.tb_zone_nm_cache WHERE lat_key = ? AND lot_key = ?",
                    plantedLatKey, plantedLotKey);
        }
        plantedLatKey = null;
        plantedLotKey = null;
        originalCachedZoneNm = null;
    }

    // ─────────────────────────────────────────────────────── 1 · 2. 정상 조회 · 격자 4종

    @Test
    @DisplayName("1. 전 지역 · 보유 전 구간 · 500m — 200 · cells 비지 않음 · anlsVl 내림차순 · ranking 은 심각·경고·주의만")
    void allRegionsFullPeriodReturnsRankedCells() {
        LifezoneAnalysisResultVO body = analyzeOk(fullPeriod());

        // 조건 echo — 전 지역이라 지역 필드는 null 이고, 기간은 정규화된 값이 되비친다
        assertThat(body.getBgngYmd()).isEqualTo(queryBgngYmd);
        assertThat(body.getEndYmd()).isEqualTo(dataEndYmd);
        assertThat(body.getSggNm()).isNull();
        assertThat(body.getEmdCd()).isNull();
        assertThat(body.getGridSz()).isEqualTo(DEFAULT_GRID_SZ);
        assertThat(body.getGridSzNm()).isEqualTo(DEFAULT_GRID_SZ + "m");
        assertThat(body.getAnlsTrgtCd()).isEqualTo(LifezoneTarget.CVLCPT_CNT.name());
        assertThat(body.getAnlsTrgtNm()).isEqualTo(LifezoneTarget.CVLCPT_CNT.getAnlsTrgtNm());
        assertThat(body.getUnit()).isEqualTo(LifezoneTarget.CVLCPT_CNT.getUnit());

        // 보유 구간 echo 는 요청 기간과 무관하게 매퍼가 채운다
        assertThat(body.getDataBgngYmd()).isEqualTo(dataBgngYmd);
        assertThat(body.getDataEndYmd()).isEqualTo(dataEndYmd);

        List<LifezoneCellVO> cells = body.getCells();
        assertThat(cells).as("보유 구간 전체를 봤는데 격자가 하나도 없다 — 적재분을 확인하라").isNotEmpty();

        // 절대값은 DB 에서 매퍼와 같은 식으로 다시 세어 대조한다
        assertThat(cells).hasSize(expectedCellCount(DEFAULT_GRID_SZ, null, null));
        assertThat(body.getTotGridCnt()).isEqualTo(cells.size());

        // 값>0 격자 전량 · 분석값 내림차순
        for (int i = 0; i < cells.size(); i++) {
            LifezoneCellVO cell = cells.get(i);
            assertThat(cell.getAnlsVl()).as("cells 는 값>0 격자만 담는다").isPositive();
            assertThat(cell.getGridCd()).isNotBlank();
            assertThat(cell.getGrade()).isNotBlank();
            if (i > 0) {
                assertThat(cell.getAnlsVl())
                        .as("분석값은 내림차순이어야 한다 (index %d)", i)
                        .isLessThanOrEqualTo(cells.get(i - 1).getAnlsVl());
            }
        }

        // 순위는 셀 목록 중 심각·경고·주의(listed)만 순서대로 떼어 만든다
        //    (보통·낮음은 지도·요약에만 남는다). 등간격은 쏠려 리스트가 몇 줄일 수 있다 — 고장이 아니다.
        //    limit 생략 = 상한 없음: listed 셀 '전부' 가 실린다(크기·순서 일치).
        List<LifezoneRankVO> ranking = body.getRanking();
        List<LifezoneCellVO> listed = listedCells(cells);
        assertThat(ranking)
                .as("limit 생략이면 심각·경고·주의 셀 전부가 순위에 실린다 (15개 상한 없음)")
                .hasSameSizeAs(listed)
                .hasSize(body.getSevereCnt() + body.getWarnCnt() + body.getCautionCnt());
        for (int i = 0; i < ranking.size(); i++) {
            LifezoneRankVO rank = ranking.get(i);
            assertThat(LifezoneGrade.valueOf(rank.getGrade()).isListed())
                    .as("ranking 은 SEVERE·WARN·CAUTION 만 싣는다 (%s)", rank.getGrade())
                    .isTrue();
            assertThat(rank.getRnk()).as("rnk 는 1 부터 빈틈없이 오름차순").isEqualTo(i + 1);
            assertThat(rank.getGridCd()).isEqualTo(listed.get(i).getGridCd());
            assertThat(rank.getAnlsVl()).isEqualTo(listed.get(i).getAnlsVl());
            assertThat(rank.getGrade()).isEqualTo(listed.get(i).getGrade());
            assertThat(rank.getCntrPoiNm()).as("구역명은 폴백까지 거쳐 최종적으로 비지 않는다").isNotBlank();
            assertDtlContract(rank);
        }
    }

    @Test
    @DisplayName("2. 격자 4종 순회 — 전부 200 · gridSz 되비침 · 셀 수가 작아질수록 단조 증가")
    void cellCountGrowsMonotonicallyAsGridShrinks() {
        int previous = 0;
        for (int gridSz : GRID_SIZES) {
            Map<String, Object> params = fullPeriod();
            params.put("gridSz", gridSz);
            LifezoneAnalysisResultVO body = analyzeOk(params);

            assertThat(body.getGridSz()).isEqualTo(gridSz);
            // 화면 라벨 — 1000 만 "1km" 이고 나머지는 "{n}m" 다 (LifezoneAnalysisServiceImpl#gridSzNm)
            assertThat(body.getGridSzNm()).isEqualTo(gridSz == 1000 ? "1km" : gridSz + "m");
            assertThat(body.getCells())
                    .as("%dm 격자 셀 수는 DB 재계산값과 같아야 한다", gridSz)
                    .hasSize(expectedCellCount(gridSz, null, null));
            assertThat(body.getCells().size())
                    .as("격자가 작아지면 셀 수는 늘어난다 (%dm)", gridSz)
                    .isGreaterThan(previous);
            previous = body.getCells().size();
        }
    }

    // ─────────────────────────────────────────────────────── 3 ~ 6 · 9 · 10. 입력 검증

    @Test
    @DisplayName("3. 보유하지 않는 격자크기(300m) — 400")
    void unsupportedGridSizeIsRejected() {
        Map<String, Object> params = fullPeriod();
        params.put("gridSz", UNSUPPORTED_GRID_SZ);
        assertThat(statusOf(params)).isEqualTo(400);
    }

    @Test
    @DisplayName("4. 전용 경로 유형(SPLY_DMND · CNGST)을 기본 핸들러로 보내면 — 400 + 메시지에 전용 경로")
    void dedicatedPathTargetsAreRejectedByDefaultHandler() {
        // CNGST 가 켜지면서 미구현 유형이 0개가 됐다. 미구현 사유 경로(!isAvailable)는
        //    LifezoneTargetTest 의 "전 유형 available" 단언이 대신 지킨다.
        for (LifezoneTarget target : LifezoneTarget.values()) {
            if (target.isDefaultHandled()) {
                continue;
            }
            Map<String, Object> params = fullPeriod();
            params.put("anlsTrgtCd", target.name());
            assertThat(statusOf(params))
                    .as("전용 경로 유형 %s 을 기본 핸들러가 처리하면 민원건수 결과가 그 이름표를 달고 200 으로 나간다",
                            target.name())
                    .isEqualTo(400);

            // 오류 바디의 message 는 환경(devtools)에 따라 있고 없고가 갈린다 —
            // 메시지 자체는 같은 코드 경로인 서비스 호출로 확인한다.
            // 시큐어코딩 5.1 — null 로 시작하는 변수 없이 예외와 그 내용을 한 번에 단언한다.
            assertThatThrownBy(() -> lifezoneAnalysisService.analyze(requestOf(target.name())))
                    .as("%s 은 400 으로 끊겨야 한다", target.name())
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                        assertThat(e.getStatusCode().value()).isEqualTo(400);
                        assertThat(e.getReason())
                                .as("전용 경로(%s)가 메시지에 실려야 한다", target.getAnlsPathSfx())
                                .contains("전용 경로로 조회합니다: GET /api/analysis/lifezone" + target.getAnlsPathSfx());
                    });
        }
        assertThat(LifezoneTarget.values())
                .as("전용 경로 유형이 하나도 없다면 이 케이스가 아무것도 검증하지 않는다")
                .anyMatch(t -> !t.isDefaultHandled());
    }

    @Test
    @DisplayName("5. anlsTrgtCd 생략 — 기본값 CVLCPT_CNT 가 적용된다")
    void omittedTargetFallsBackToComplaintCount() {
        LifezoneAnalysisResultVO body = analyzeOk(fullPeriod());
        assertThat(body.getAnlsTrgtCd()).isEqualTo(LifezoneTarget.CVLCPT_CNT.name());
        assertThat(body.getAnlsTrgtNm()).isEqualTo(LifezoneTarget.CVLCPT_CNT.getAnlsTrgtNm());
        assertThat(body.getSectionTitle()).isEqualTo(LifezoneTarget.CVLCPT_CNT.getSectionTitle());
        assertThat(body.getSectionSub()).isEqualTo(LifezoneTarget.CVLCPT_CNT.getSectionSub());
    }

    @Test
    @DisplayName("6. 지역 조건 빈 문자열(sggNm='' · emdCd='') — 각각 400")
    void blankRegionKeysAreRejected() {
        Map<String, Object> blankSgg = fullPeriod();
        blankSgg.put("sggNm", "");
        assertThat(statusOf(blankSgg))
                .as("빈 sggNm 을 '전 지역'으로 삼키면 오타가 다른 결과를 정상 응답으로 위장한다")
                .isEqualTo(400);

        Map<String, Object> blankEmd = fullPeriod();
        blankEmd.put("emdCd", "");
        assertThat(statusOf(blankEmd)).isEqualTo(400);
    }

    @Test
    @DisplayName("9. limit=1 은 순위 1건 · limit=0 은 400 (상한 30 은 없앴다)")
    void limitBoundsAreEnforced() {
        Map<String, Object> one = fullPeriod();
        one.put("limit", 1);
        LifezoneAnalysisResultVO body = analyzeOk(one);
        assertThat(body.getRanking()).hasSize(1);
        assertThat(body.getRanking().get(0).getRnk()).isEqualTo(1);
        assertThat(body.getCells())
                .as("limit 은 순위 개수만 줄인다 — 셀 목록은 전량 그대로다")
                .hasSize(expectedCellCount(DEFAULT_GRID_SZ, null, null));

        Map<String, Object> zero = fullPeriod();
        zero.put("limit", 0);
        assertThat(statusOf(zero)).as("하한(1) 미만은 400").isEqualTo(400);
    }

    @Test
    @DisplayName("10. 기간 역전(endYmd < bgngYmd) — 400")
    void invertedPeriodIsRejected() {
        String dayBeforeBgng = LocalDate.parse(dataBgngYmd, YMD).minusDays(1).format(YMD);
        assertThat(statusOf(period(dataBgngYmd, dayBeforeBgng))).isEqualTo(400);
    }

    // ─────────────────────────────────────────────────────── 7 · 8. 지역 필터 · 구간 밖

    @Test
    @DisplayName("7. 지역 필터 — 전 지역 셀 수 ≥ 시군구 한정 셀 수 · echo 는 정규화된 시군구명")
    void regionFilterNarrowsCellCount() {
        int all = analyzeOk(fullPeriod()).getCells().size();

        Map<String, Object> params = fullPeriod();
        params.put("sggNm", filterSggNm);
        LifezoneAnalysisResultVO filtered = analyzeOk(params);

        assertThat(filtered.getSggNm()).isEqualTo(filterSggNm);
        assertThat(filtered.getCells())
                .as("%s 한정 셀 수는 DB 재계산값과 같아야 한다", filterSggNm)
                .hasSize(expectedCellCount(DEFAULT_GRID_SZ, null, filterSggCd));
        assertThat(all)
                .as("전 지역(%d)이 %s(%d)보다 좁을 수 없다", all, filterSggNm, filtered.getCells().size())
                .isGreaterThanOrEqualTo(filtered.getCells().size());
    }

    @Test
    @DisplayName("8. 보유 구간 밖 기간 — 200 · cells 비어 있음 · 보유 구간은 채워진다")
    void periodOutsideDataRangeReturnsEmptyButOk() {
        LocalDate afterEnd = LocalDate.parse(dataEndYmd, YMD).plusDays(1);
        LifezoneAnalysisResultVO body =
                analyzeOk(period(afterEnd.format(YMD), afterEnd.plusDays(OUT_OF_RANGE_DAYS).format(YMD)));

        assertThat(body.getCells()).as("0건은 오류가 아니라 200 + 빈 목록이다").isEmpty();
        assertThat(body.getRanking()).isEmpty();
        assertThat(body.getTotGridCnt()).isZero();
        assertThat(body.getDataBgngYmd()).isEqualTo(dataBgngYmd);
        assertThat(body.getDataEndYmd()).isEqualTo(dataEndYmd);
        assertThat(body.getGradeCriteria())
                .as("0건이어도 범례가 비지 않게 등급 기준은 채워 보낸다")
                .hasSize(GRADE_CNT);
    }

    // ─────────────────────────────────────────────────────── 11 · 19 ~ 22. 등급 경계

    @Test
    @DisplayName("11. 등급 경계값 — 응답 gradeCriteria 의 minVl 로 판정한다 (상수 없음)")
    void gradeBoundariesFollowResponseCriteria() {
        LifezoneAnalysisResultVO body = analyzeOk(fullPeriod());
        LifezoneGradeScale scale = scaleOf(body);
        Map<String, Integer> minVls = minVlByGrade(body);

        LifezoneGrade[] grades = LifezoneGrade.values();   // 선언 순서 = 경계 내림차순
        for (int i = 0; i < grades.length; i++) {
            LifezoneGrade grade = grades[i];
            int minVl = minVls.get(grade.name());

            // 경계값 자신은 그 등급이다
            assertThat(scale.gradeOf(minVl))
                    .as("%s 의 하한 %d 는 %s 로 판정돼야 한다", grade.name(), minVl, grade.name())
                    .isEqualTo(grade);

            // 경계값 -1 은 한 단계 아래다 (최하위 등급의 -1 은 '셀 자체가 없음')
            if (i < grades.length - 1) {
                assertThat(scale.gradeOf(minVl - 1))
                        .as("%s 하한 바로 아래(%d)는 한 단계 아래 등급이다", grade.name(), minVl - 1)
                        .isEqualTo(grades[i + 1]);
            } else {
                assertThat(scale.gradeOf(minVl - 1))
                        .as("최하위 등급 하한 아래는 등급이 없다 — 그런 셀은 응답에 실리지 않는다")
                        .isNull();
            }
        }

        // 응답에 실린 모든 셀이 위 규칙과 일치한다 (경계가 실제 데이터에 그대로 적용됐는지)
        for (LifezoneCellVO cell : body.getCells()) {
            assertThat(cell.getAnlsVl())
                    .as("등급을 못 받는 값(최하위 하한 미만)은 응답에 없다")
                    .isGreaterThanOrEqualTo(minVls.get(LifezoneGrade.LOW.name()));
            assertThat(cell.getGrade())
                    .as("격자 %s (값 %d)", cell.getGridCd(), cell.getAnlsVl())
                    .isEqualTo(scale.gradeOf(cell.getAnlsVl()).name());
        }
    }

    @Test
    @DisplayName("19. gradeCriteria 5행 — 등급 선언 순서 · minVl 내림차순 · 값이 서로 다르다")
    void gradeCriteriaAreStrictlyDescending() {
        List<LifezoneGradeCriterionVO> criteria = analyzeOk(fullPeriod()).getGradeCriteria();
        assertThat(criteria).hasSize(GRADE_CNT);

        List<String> expected = new ArrayList<>();
        for (LifezoneGrade grade : LifezoneGrade.values()) {
            expected.add(grade.name());
        }
        assertThat(criteria).extracting(LifezoneGradeCriterionVO::getGrade)
                .containsExactlyElementsOf(expected);

        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < criteria.size(); i++) {
            LifezoneGradeCriterionVO row = criteria.get(i);
            assertThat(row.getGradeNm()).isNotBlank();
            assertThat(row.getLgndTxt()).isNotBlank();
            assertThat(seen.add(row.getMinVl()))
                    .as("경계 %d 가 중복됐다 — 등급 하나가 도달 불가능해진다", row.getMinVl())
                    .isTrue();
            if (i > 0) {
                assertThat(row.getMinVl())
                        .as("경계는 내림차순이어야 한다 (%s)", row.getGrade())
                        .isLessThan(criteria.get(i - 1).getMinVl());
            }
        }
    }

    @Test
    @DisplayName("20. 등급별 셀 수 합 == totGridCnt — 어느 셀도 등급을 못 받지 않는다")
    void gradeCountsSumToTotal() {
        LifezoneAnalysisResultVO body = analyzeOk(fullPeriod());
        int sum = body.getSevereCnt() + body.getWarnCnt() + body.getCautionCnt()
                + body.getNormalCnt() + body.getLowCnt();
        assertThat(sum).isEqualTo(body.getTotGridCnt());
        assertThat(body.getTotGridCnt()).isEqualTo(body.getCells().size());

        // 요약이 실제 셀 분포와도 일치한다
        Map<String, Integer> counted = new LinkedHashMap<>();
        for (LifezoneCellVO cell : body.getCells()) {
            counted.merge(cell.getGrade(), 1, Integer::sum);
        }
        assertThat(counted.getOrDefault(LifezoneGrade.SEVERE.name(), 0)).isEqualTo(body.getSevereCnt());
        assertThat(counted.getOrDefault(LifezoneGrade.WARN.name(), 0)).isEqualTo(body.getWarnCnt());
        assertThat(counted.getOrDefault(LifezoneGrade.CAUTION.name(), 0)).isEqualTo(body.getCautionCnt());
        assertThat(counted.getOrDefault(LifezoneGrade.NORMAL.name(), 0)).isEqualTo(body.getNormalCnt());
        assertThat(counted.getOrDefault(LifezoneGrade.LOW.name(), 0)).isEqualTo(body.getLowCnt());
    }

    @Test
    @DisplayName("21. 경계는 조회마다 산출된다 — 전 지역·시군구·읍면동의 cap 이 DB 재계산값과 같다")
    void gradeCapDependsOnQuery() {
        LifezoneAnalysisResultVO all = analyzeOk(fullPeriod());

        Map<String, Object> params = fullPeriod();
        params.put("sggNm", filterSggNm);
        LifezoneAnalysisResultVO filtered = analyzeOk(params);

        // 두 cap 모두 DB 재계산값과 같다 — '어디서 온 값인지'까지 못박는다
        assertThat(all.getGradeScale().cap()).isEqualTo(expectedCap(DEFAULT_GRID_SZ, null, null));
        assertThat(filtered.getGradeScale().cap())
                .isEqualTo(expectedCap(DEFAULT_GRID_SZ, null, filterSggCd));

        // 🔴 지역을 좁히면 cap 이 달라진다 — 상수라면 무엇으로 좁혀도 같은 값이 나온다.
        // ⚠️ 시군구 한정으로는 부족하다: cap 이 최대값이라 '최대 격자가 든 시군구' 를 고르면
        //    전 지역과 같아진다(실측 500m — 제주시 386 = 전 지역 386). 그래서 읍면동으로 좁힌다.
        Map<String, Object> narrowParams = fullPeriod();
        narrowParams.put("emdCd", narrowEmdCd);
        LifezoneAnalysisResultVO narrow = analyzeOk(narrowParams);

        assertThat(narrow.getGradeScale().cap())
                .isEqualTo(expectedCap(DEFAULT_GRID_SZ, narrowEmdCd, null));
        assertThat(narrow.getGradeScale().cap())
                .as("cap 이 조회 조건과 무관하게 같다면 상수에서 만든 것이다")
                .isNotEqualTo(all.getGradeScale().cap());

        // 🔴 요청이 clsfMthd 를 생략했으므로 유형 기본값(등간격 확정)이 적용된다
        assertThat(all.getGradeScale().method()).isEqualTo(LifezoneClsfMthd.EQUAL_INTERVAL.name());
        assertThat(all.getClsfMthd()).isEqualTo(LifezoneClsfMthd.EQUAL_INTERVAL.name());
        assertThat(all.getClsfMthdNm()).isEqualTo(LifezoneClsfMthd.EQUAL_INTERVAL.getClsfMthdNm());
        // 🔴 등간격이라 폭은 cap 에서만 나온다 — width = ceil(cap / 등급 수)
        assertWidthMatchesCap(all.getGradeScale());
    }

    @Test
    @DisplayName("23. 분류방법 1종 확정: 생략·빈 값 = EQUAL_INTERVAL · 미지원 값·목록 밖 값(JENKS) 400")
    void classificationMethodIsSelectable() {
        LifezoneAnalysisResultVO byDefault = analyzeOk(fullPeriod());

        Map<String, Object> equalParams = fullPeriod();
        equalParams.put("clsfMthd", LifezoneClsfMthd.EQUAL_INTERVAL.name());
        LifezoneAnalysisResultVO equal = analyzeOk(equalParams);

        // ① 생략 = EQUAL_INTERVAL — 기본값을 프론트가 몰라도 되게 서버가 정한다
        assertThat(byDefault.getClsfMthd()).isEqualTo(LifezoneClsfMthd.EQUAL_INTERVAL.name());
        assertThat(minVlByGrade(byDefault)).isEqualTo(minVlByGrade(equal));

        // ② gradeScale.method 가 요청과 일치하고, 루트 echo 두 키도 같은 값을 되비춘다
        assertThat(equal.getGradeScale().method())
                .isEqualTo(LifezoneClsfMthd.EQUAL_INTERVAL.name());
        assertThat(equal.getClsfMthd()).isEqualTo(LifezoneClsfMthd.EQUAL_INTERVAL.name());
        assertThat(equal.getClsfMthdNm())
                .isEqualTo(LifezoneClsfMthd.EQUAL_INTERVAL.getClsfMthdNm());

        // (종전 ③④⑤ 자연분류 vs 등간격 경계·lowCnt 비교는 삭제.
        //  알고리즘 비교는 LifezoneGradeScaleTest.JenksBeatsEqualInterval 이 커버한다)

        // ⑥ 미지원 값은 400 — enum valueOf 예외가 500 으로 새지 않는다
        Map<String, Object> badParams = fullPeriod();
        badParams.put("clsfMthd", "FOO");
        assertThat(statusOf(badParams)).isEqualTo(400);

        // ⑦ 상수는 있지만 민원건수 허용 목록 밖이면 400 (대표 1건: 종전 기본값 JENKS — ①)
        assertThat(LifezoneTarget.CVLCPT_CNT.getClsfMthds()).doesNotContain(LifezoneClsfMthd.JENKS);
        Map<String, Object> outsideParams = fullPeriod();
        outsideParams.put("clsfMthd", LifezoneClsfMthd.JENKS.name());
        assertThat(statusOf(outsideParams))
                .as("JENKS 는 민원건수 목록 밖이라 400 이어야 한다 — 받아들이면 등간격으로 조용히 빠진다")
                .isEqualTo(400);
        // 400 메시지의 "사용 가능" 은 전 상수가 아니라 그 유형 목록이다
        LifezoneAnalysisRequest outsideReq = requestOf(LifezoneTarget.CVLCPT_CNT.name());
        outsideReq.setClsfMthd(LifezoneClsfMthd.JENKS.name());
        // 시큐어코딩 5.1 — null 로 시작하는 변수 없이 예외와 그 내용을 한 번에 단언한다.
        assertThatThrownBy(() -> lifezoneAnalysisService.analyze(outsideReq))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(400);
                    assertThat(e.getReason())
                            .contains("사용 가능: EQUAL_INTERVAL)")
                            .doesNotContain("CNGST_ABSOLUTE")
                            .doesNotContain("EQUAL_INTERVAL_MIN");
                });

        // ⑧ 빈 값은 생략과 같다 — 유형 기본값(민원건수 = EQUAL_INTERVAL)
        Map<String, Object> blankParams = fullPeriod();
        blankParams.put("clsfMthd", "");
        assertThat(analyzeOk(blankParams).getClsfMthd()).isEqualTo(LifezoneClsfMthd.EQUAL_INTERVAL.name());
    }

    @Test
    @DisplayName("22. 좁은 조회(최대값 ≤ 등급수) — 단조 보정으로 minVl 5개가 모두 다르고 오름차순")
    void narrowQueryStillYieldsDistinctBoundaries() {
        // 🔴 등간격을 명시한다 — 폭 1 로 경계가 붙는 상황은 등간격이 만드는 것이다
        //    (기본도 등간격이 됐지만 경로를 못박기 위해 명시를 유지한다).
        //    종전 '같은 조회를 자연분류로' 절은 삭제 — LifezoneGradeScaleTest.JenksEdgeCases 가 커버한다.
        Map<String, Object> params = fullPeriod();
        params.put("emdCd", narrowEmdCd);
        params.put("gridSz", FINEST_GRID_SZ);
        params.put("clsfMthd", LifezoneClsfMthd.EQUAL_INTERVAL.name());
        LifezoneAnalysisResultVO body = analyzeOk(params);

        assertThat(body.getEmdCd()).isEqualTo(narrowEmdCd);
        assertThat(body.getCells()).as("좁은 조회여도 셀은 있어야 케이스가 성립한다").isNotEmpty();

        int cap = body.getGradeScale().cap();
        assertThat(cap)
                .as("최대값이 등급 수(%d) 이하면 올림이 붙어 보정 없이는 경계가 겹친다", GRADE_CNT)
                .isLessThanOrEqualTo(GRADE_CNT);
        assertThat(body.getGradeScale().width())
                .as("최대값이 등급 수 이하면 폭은 하한인 1 로 떨어진다")
                .isEqualTo(1);

        // 오름차순(LOW → SEVERE)으로 뒤집어 확인한다 — 응답은 내림차순(SEVERE → LOW)이다
        List<LifezoneGradeCriterionVO> criteria = body.getGradeCriteria();
        Set<Integer> seen = new HashSet<>();
        for (int i = criteria.size() - 1; i >= 0; i--) {
            int minVl = criteria.get(i).getMinVl();
            assertThat(seen.add(minVl))
                    .as("보정이 빠지면 폭 1 에서 경계가 겹친다 (%s)", criteria.get(i).getGrade())
                    .isTrue();
            if (i < criteria.size() - 1) {
                assertThat(minVl)
                        .as("아래 등급보다 커야 한다 (%s)", criteria.get(i).getGrade())
                        .isGreaterThan(criteria.get(i + 1).getMinVl());
            }
        }
        assertThat(seen).hasSize(GRADE_CNT);
    }

    // ─────────────────────────────────────────────────────── 12 · 13 · 18. 셀 형식

    @Test
    @DisplayName("12. 경계격자 중복 없음 — 같은 gridCd 가 cells 에 두 번 나오지 않는다")
    void cellsHaveNoDuplicateGridCode() {
        for (int gridSz : GRID_SIZES) {
            Map<String, Object> params = fullPeriod();
            params.put("gridSz", gridSz);
            List<LifezoneCellVO> cells = analyzeOk(params).getCells();

            Set<String> seen = new HashSet<>();
            for (LifezoneCellVO cell : cells) {
                assertThat(seen.add(cell.getGridCd()))
                        .as("%dm 격자 %s 가 중복됐다 — 시군구 경계에 걸친 격자의 중복 제거가 빠졌다",
                                gridSz, cell.getGridCd())
                        .isTrue();
            }
            assertThat(seen).hasSize(cells.size());
        }
    }

    @Test
    @DisplayName("13. ring 형식 — 모든 셀이 4점 · 위도 33~34 · 경도 126~127")
    void everyCellRingHasFourPointsInJejuBounds() {
        LifezoneAnalysisResultVO body = analyzeOk(fullPeriod());
        assertThat(body.getCells()).isNotEmpty();

        for (LifezoneCellVO cell : body.getCells()) {
            assertThat(cell.getRing())
                    .as("격자 %s 의 꼭짓점 링은 닫힘점을 뺀 4점이다", cell.getGridCd())
                    .hasSize(4);
            for (List<BigDecimal> point : cell.getRing()) {
                assertThat(point).as("링의 각 점은 [위도, 경도] 두 값이다").hasSize(2);
                assertThat(point.get(0)).isBetween(LAT_MIN, LAT_MAX);
                assertThat(point.get(1)).isBetween(LOT_MIN, LOT_MAX);
            }
            assertThat(cell.getCtrLat()).isBetween(LAT_MIN, LAT_MAX);
            assertThat(cell.getCtrLot()).isBetween(LOT_MIN, LOT_MAX);
        }
    }

    @Test
    @DisplayName("18. cells 의 구역명은 캐시 이름(POI·역지오코딩) 아니면 '… 격자 {코드}' 폴백 — 셀은 외부를 부르지 않는다")
    void poiNeverLeaksIntoCellList() {
        // 셀에도 캐시 이름이 실린다(종전: 전부 폴백). 셀은 매퍼 JOIN 으로 캐시만 읽고
        //    VWorld 를 부르지 않는다는 계약은 그대로다 → "캐시에 이름이 있는 셀 = 그 이름 · 없는 셀 = 폴백" 으로 단언한다.
        //    기준은 호출 전 스냅샷뿐이다 — 셀 SQL 이 순위 resolver 보다 먼저 돌아 같은 요청이 채운 캐시 키는 셀에
        //    반영될 수 없다. 호출 뒤 스냅샷을 허용하면 "셀이 외부 조회로 이름을 얻는" 회귀를 놓친다.
        Map<String, String> cacheBefore = cachedZoneNames();
        LifezoneAnalysisResultVO body = analyzeOk(fullPeriod());
        List<LifezoneCellVO> cells = body.getCells();
        assertThat(cells.size())
                .as("순위 밖 셀(보통·낮음)이 있어야 이 케이스가 의미를 갖는다")
                .isGreaterThan(body.getRanking().size());

        for (int i = 0; i < cells.size(); i++) {
            LifezoneCellVO cell = cells.get(i);
            assertThat(cell.getCntrPoiNm()).as("cells[%d] (%s) 구역명", i, cell.getGridCd()).isNotBlank();
            String key = cacheKey(cell.getCtrLat(), cell.getCtrLot());
            String before = cacheBefore.get(key);
            if (before != null) {
                assertThat(cell.getCntrPoiNm())
                        .as("cells[%d] (%s) — 캐시(%s)에 이름이 있으면 그 이름이 실린다", i, cell.getGridCd(), key)
                        .isEqualTo(before);
            } else {
                assertThat(cell.getCntrPoiNm())
                        .as("cells[%d] (%s) — 캐시가 없는 셀은 폴백이다(셀이 외부를 불렀다면 여기서 걸린다)", i, cell.getGridCd())
                        .endsWith(GRID_NM_MARK + cell.getGridCd());
                if (cell.getEmdNm() != null && !cell.getEmdNm().isBlank()) {
                    assertThat(cell.getCntrPoiNm()).startsWith(cell.getEmdNm());
                }
            }
            assertThat(cell.getCntrPoiNm())
                    .as("resolver 자기 폴백('… 일대' · '위치 미상')은 셀에 실리지 않는다")
                    .doesNotEndWith(" 일대").isNotEqualTo("위치 미상");
        }

        // 순위 행의 구역명은 POI 든 폴백이든 비지 않는다 (어느 쪽인지는 외부 상태에 달려 단언하지 않는다)
        for (LifezoneRankVO rank : body.getRanking()) {
            assertThat(rank.getCntrPoiNm()).isNotBlank();
        }
    }

    // ─────────────────────────────────────────────────────── 14 · 15 · 16 · 17. 계약

    @Test
    @DisplayName("14. LifezoneTarget 상수명 == 공통코드 ANLS_TRGT_CD 의 cd_vl 집합")
    void targetEnumMatchesCommonCodeGroup() {
        Set<String> fromDb = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT cd_vl FROM parking.tb_com_cd WHERE group_cd = 'ANLS_TRGT_CD'", String.class));
        Set<String> fromEnum = new HashSet<>();
        for (LifezoneTarget target : LifezoneTarget.values()) {
            fromEnum.add(target.name());
        }
        assertThat(fromEnum)
                .as("enum 과 공통코드가 갈리면 요청 코드가 조용히 400 이 되거나 화면 세그먼트가 비뚤어진다")
                .isEqualTo(fromDb);

        // 응답의 availableTargets 도 같은 집합을 싣는다 (미구현 유형도 목록에는 들어간다)
        assertThat(analyzeOk(fullPeriod()).getAvailableTargets())
                .extracting(t -> t.getAnlsTrgtCd())
                .containsExactlyInAnyOrderElementsOf(fromDb);
    }

    @Test
    @DisplayName("15. weights 는 항상 빈 배열 — 분석유형이 배타 선택이라 지표를 섞지 않는다")
    void weightsAreAlwaysEmpty() {
        assertThat(analyzeOk(fullPeriod()).getWeights()).isEmpty();

        Map<String, Object> params = fullPeriod();
        params.put("sggNm", filterSggNm);
        assertThat(analyzeOk(params).getWeights()).isEmpty();
    }

    @Test
    @DisplayName("16. 조회는 DB 를 바꾸지 않는다 — TB_ANLS_EXCN · TB_ANLS_RSLT 행수 불변")
    void analysisQueryDoesNotWriteToDatabase() {
        int excnBefore = rowCount("parking.tb_anls_excn");
        int rsltBefore = rowCount("parking.tb_anls_rslt");

        analyzeOk(fullPeriod());

        Map<String, Object> filtered = fullPeriod();
        filtered.put("sggNm", filterSggNm);
        filtered.put("limit", 1);
        analyzeOk(filtered);

        assertThat(rowCount("parking.tb_anls_excn"))
                .as("생활권 분석은 실행 이력을 남기지 않는 순수 조회다")
                .isEqualTo(excnBefore);
        assertThat(rowCount("parking.tb_anls_rslt")).isEqualTo(rsltBefore);
    }

    @Test
    @DisplayName("17. 순위 결정성 — 같은 조건 3회 조회의 ranking gridCd 순서가 동일하다 (심각·경고·주의 한정)")
    void rankingIsDeterministicAcrossRepeatedCalls() {
        List<String> first = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            LifezoneAnalysisResultVO body = analyzeOk(fullPeriod());
            List<String> gridCds = new ArrayList<>();
            for (LifezoneRankVO rank : body.getRanking()) {
                gridCds.add(rank.getGridCd());
            }
            assertThat(gridCds).isNotEmpty();
            assertThat(gridCds)
                    .as("limit 생략 = 심각·경고·주의 전부")
                    .hasSize(body.getSevereCnt() + body.getWarnCnt() + body.getCautionCnt());
            if (first == null) {
                first = gridCds;
            } else {
                assertThat(gridCds)
                        .as("동점 격자의 tie-break 가 흔들리면 화면 순위가 새로고침마다 바뀐다 (%d회차)", attempt + 1)
                        .containsExactlyElementsOf(first);
            }
        }
    }

    // ─────────────────────────────────────────────────────── 24. 구역명 캐시 키

    @Test
    @DisplayName("24. 순위 구역명은 격자 중심(ctrLat/ctrLot) 키로 캐시를 찾는다 (개정 · 종전 민원 무게중심)")
    void rankZoneNameIsLookedUpByGridCenterKey() {
        LifezoneAnalysisResultVO first = analyzeOk(fullPeriod());
        assumeThat(first.getRanking())
                .as("순위(심각·경고·주의)가 0건이다 — 데이터 부재는 실패가 아니다")
                .isNotEmpty();
        LifezoneRankVO top = first.getRanking().get(0);
        assertThat(top.getCtrLat()).as("ranking[0] 격자 중심 위도").isNotNull();
        assertThat(top.getCtrLot()).as("ranking[0] 격자 중심 경도").isNotNull();

        // 🔴 격자 중심 좌표로 캐시를 심는다 — 서비스가 무게중심으로 조회하면 키가 달라 이 이름이 안 나온다
        String planted = plantZoneName(top.getCtrLat(), top.getCtrLot());

        LifezoneAnalysisResultVO second = analyzeOk(fullPeriod());
        LifezoneRankVO again = second.getRanking().stream()
                .filter(rank -> top.getGridCd().equals(rank.getGridCd()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("재조회 ranking 에 " + top.getGridCd() + " 가 없다"));
        assertThat(again.getCntrPoiNm())
                .as("%s — 격자 중심 키(%s, %s)에 심은 캐시 이름이 실려야 한다",
                        top.getGridCd(), plantedLatKey, plantedLotKey)
                .isEqualTo(planted);
    }

    @Test
    @DisplayName("25. 순위에 없는 셀도 격자 중심 키의 캐시(POI) 이름을 싣는다 (전 셀 구역명)")
    void unrankedCellCarriesCachedZoneName() {
        LifezoneAnalysisResultVO first = analyzeOk(fullPeriod());
        List<LifezoneCellVO> unranked = unrankedCells(first);
        assumeThat(unranked).as("순위 밖 셀이 0개다 — 데이터 부재는 실패가 아니다").isNotEmpty();
        LifezoneCellVO target = unranked.get(0);

        String planted = plantZoneName(target.getCtrLat(), target.getCtrLot(), "POI");

        LifezoneCellVO again = cellOf(analyzeOk(fullPeriod()), target.getGridCd());
        assertThat(again.getCntrPoiNm())
                .as("%s — 순위 밖 셀도 격자 중심 키(%s, %s)에 심은 캐시 이름이 실려야 한다",
                        target.getGridCd(), plantedLatKey, plantedLotKey)
                .isEqualTo(planted);
    }

    @Test
    @DisplayName("26. 캐시가 NOT_FOUND(확정 실패)면 셀은 이름을 쓰지 않고 '… 격자 {코드}' 폴백이다")
    void notFoundCacheFallsBackToGridName() {
        LifezoneAnalysisResultVO first = analyzeOk(fullPeriod());
        List<LifezoneCellVO> unranked = unrankedCells(first);
        assumeThat(unranked).as("순위 밖 셀이 0개다 — 데이터 부재는 실패가 아니다").isNotEmpty();
        LifezoneCellVO target = unranked.get(0);

        // zone_nm 을 일부러 채워 둔다 — 출처가 NOT_FOUND 면 이름이 있어도 쓰지 않아야 한다(매퍼 CASE)
        String planted = plantZoneName(target.getCtrLat(), target.getCtrLot(), "NOT_FOUND");

        LifezoneCellVO again = cellOf(analyzeOk(fullPeriod()), target.getGridCd());
        assertThat(again.getCntrPoiNm())
                .as("%s — NOT_FOUND 캐시는 셀 이름이 되지 않는다", target.getGridCd())
                .isNotEqualTo(planted)
                .endsWith(GRID_NM_MARK + target.getGridCd());
    }

    @Test
    @DisplayName("27. 캐시 키: SQL round(numeric, 5) == Java setScale(5, HALF_UP) (TB_GRID 전 격자 · 응답 셀)")
    void sqlRoundMatchesJavaHalfUp() {
        // 🔴 매퍼 JOIN(round(ctr_lat, 5))과 resolver 저장 키(setScale(5, HALF_UP))가 갈리면 셀은 캐시를 영영 못 찾는다
        Map<String, String> sqlKeyByGrid = new LinkedHashMap<>();
        int[] mismatches = { 0 };
        List<String> samples = new ArrayList<>();
        jdbcTemplate.query("SELECT grid_sz || '|' || grid_cd, ctr_lat, ctr_lot, round(ctr_lat, 5), round(ctr_lot, 5)"
                        + " FROM parking.tb_grid",
                rs -> {
                    String java = cacheKey(rs.getBigDecimal(2), rs.getBigDecimal(3));
                    String sql = rs.getBigDecimal(4).toPlainString() + "," + rs.getBigDecimal(5).toPlainString();
                    if (!java.equals(sql)) {
                        mismatches[0]++;
                        if (samples.size() < 5) {
                            samples.add(rs.getString(1) + " java=" + java + " sql=" + sql);
                        }
                    }
                    sqlKeyByGrid.put(rs.getString(1), sql);
                });
        assertThat(sqlKeyByGrid).as("TB_GRID 가 비어 있다").isNotEmpty();
        assertThat(mismatches[0]).as("SQL round ↔ Java HALF_UP 불일치 예: %s", samples).isZero();

        // 응답 셀 좌표(직렬화 왕복 후)로 만든 Java 키도 같은 격자의 SQL 키와 같다
        LifezoneAnalysisResultVO body = analyzeOk(fullPeriod());
        for (LifezoneCellVO cell : body.getCells()) {
            assertThat(cacheKey(cell.getCtrLat(), cell.getCtrLot()))
                    .as("%s 응답 좌표의 Java 키 == TB_GRID SQL 키", cell.getGridCd())
                    .isEqualTo(sqlKeyByGrid.get(body.getGridSz() + "|" + cell.getGridCd()));
        }
    }

    /** 순위({@code ranking})에 오르지 않은 셀 — {@code cells} 순서대로. */
    private static List<LifezoneCellVO> unrankedCells(LifezoneAnalysisResultVO body) {
        Set<String> ranked = new HashSet<>();
        for (LifezoneRankVO rank : body.getRanking()) {
            ranked.add(rank.getGridCd());
        }
        return body.getCells().stream()
                .filter(cell -> !ranked.contains(cell.getGridCd()))
                .filter(cell -> cell.getCtrLat() != null && cell.getCtrLot() != null)
                .toList();
    }

    private static LifezoneCellVO cellOf(LifezoneAnalysisResultVO body, String gridCd) {
        return body.getCells().stream()
                .filter(cell -> gridCd.equals(cell.getGridCd()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("재조회 cells 에 " + gridCd + " 가 없다"));
    }

    /**
     * 구역명 캐시 중 셀 이름으로 쓰이는 행(출처 {@code POI}·{@code REVERSE_GEOCODE} · 이름 있음)을 키 → 이름으로 일괄 조회한다.
     * 키 문자열은 {@link #cacheKey} 와 같은 모양이다.
     */
    private Map<String, String> cachedZoneNames() {
        Map<String, String> names = new HashMap<>();
        jdbcTemplate.query("SELECT lat_key, lot_key, zone_nm FROM parking.tb_zone_nm_cache"
                        + " WHERE zone_nm_src IN ('POI', 'REVERSE_GEOCODE') AND zone_nm IS NOT NULL",
                rs -> {
                    names.put(cacheKey(rs.getBigDecimal(1), rs.getBigDecimal(2)), rs.getString(3));
                });
        return names;
    }

    /** 캐시 키 문자열 — {@code ZoneNameResolver.key()} 와 같은 소수 5자리 HALF_UP. */
    private static String cacheKey(BigDecimal lat, BigDecimal lot) {
        return lat.setScale(5, RoundingMode.HALF_UP).toPlainString() + ","
                + lot.setScale(5, RoundingMode.HALF_UP).toPlainString();
    }

    private String plantZoneName(BigDecimal ctrLat, BigDecimal ctrLot) {
        return plantZoneName(ctrLat, ctrLot, "POI");
    }

    /**
     * 격자 중심 좌표의 캐시 키(소수 5자리 HALF_UP — {@code ZoneNameResolver.key()} 와 같은 규칙, 그 메서드는
     * 패키지 전용이다)에 테스트 이름을 주어진 출처(기본 {@code POI})로 심는다. 원래 행은 먼저 떠 둔다(복원용).
     */
    private String plantZoneName(BigDecimal ctrLat, BigDecimal ctrLot, String zoneNmSrc) {
        BigDecimal latKey = ctrLat.setScale(5, RoundingMode.HALF_UP);
        BigDecimal lotKey = ctrLot.setScale(5, RoundingMode.HALF_UP);
        List<CachedZoneNm> original = jdbcTemplate.query(
                "SELECT zone_nm, zone_nm_src, poi_dist_m, inq_dt FROM parking.tb_zone_nm_cache"
                        + " WHERE lat_key = ? AND lot_key = ?",
                (rs, rowNum) -> new CachedZoneNm(rs.getString(1), rs.getString(2),
                        (Integer) rs.getObject(3), rs.getTimestamp(4)),
                latKey, lotKey);
        originalCachedZoneNm = original.isEmpty() ? null : original.get(0);
        plantedLatKey = latKey;
        plantedLotKey = lotKey;

        String planted = "테스트-격자중심-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO parking.tb_zone_nm_cache (lat_key, lot_key, zone_nm, zone_nm_src)"
                        + " VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT (lat_key, lot_key) DO UPDATE"
                        + " SET zone_nm = EXCLUDED.zone_nm, zone_nm_src = EXCLUDED.zone_nm_src",
                latKey, lotKey, planted, zoneNmSrc);
        return planted;
    }

    /** 구역명 캐시 1행의 복원 대상 4열. */
    private record CachedZoneNm(String zoneNm, String zoneNmSrc, Integer poiDistM, Timestamp inqDt) {
    }

    // ─────────────────────────────────────────────────────── 헬퍼 — 호출

    /** 정상 응답을 기대하는 호출. 상태코드까지 확인하고 바디를 돌려준다. */
    private LifezoneAnalysisResultVO analyzeOk(Map<String, Object> params) {
        ResponseEntity<String> response = call(params);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        try {
            return RESPONSE_MAPPER.readValue(response.getBody(), LifezoneAnalysisResultVO.class);
        } catch (IOException e) {
            throw new IllegalStateException("응답 역직렬화 실패: " + e, e);
        }
    }

    /**
     * 상태코드만 보는 호출. 오류 바디를 VO 로 역직렬화하려다 터지지 않게 {@code String} 으로 받는다
     * ({@code HotspotAnalysisIntegrationTest} 와 같은 이유).
     */
    private int statusOf(Map<String, Object> params) {
        return call(params).getStatusCode().value();
    }

    private ResponseEntity<String> call(Map<String, Object> params) {
        return restTemplate.exchange(uriTemplate(params), HttpMethod.GET, null, String.class, params);
    }

    /**
     * 쿼리스트링을 <b>URI 템플릿 + 변수</b>로 만든다. 값을 이어 붙이면 한글 시군구명이 인코딩되지 않아
     * 환경에 따라 다른 요청이 나간다.
     */
    private static String uriTemplate(Map<String, Object> params) {
        StringBuilder uri = new StringBuilder(LIFEZONE_URL);
        String separator = "?";
        for (String key : params.keySet()) {
            uri.append(separator).append(key).append("={").append(key).append('}');
            separator = "&";
        }
        return uri.toString();
    }

    /** 필수 파라미터 두 개만 담은 가변 맵. 호출부가 격자·지역·limit 을 덧붙인다. */
    private static Map<String, Object> period(String bgngYmd, String endYmd) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bgngYmd", bgngYmd);
        params.put("endYmd", endYmd);
        return params;
    }

    /** 원천 보유 구간 전체. 🔴 결과를 보는 케이스는 전부 이것을 쓴다 (날짜 상수 금지). */
    private Map<String, Object> fullPeriod() {
        return period(queryBgngYmd, dataEndYmd);
    }

    /**
     * 366일 상한({@code DateRanges.MAX_RANGE_DAYS})에 맞춰 시작일을 자른다.
     * 365 를 빼면 양끝 포함 366일이다. 구간이 1년 이내면 {@code bgngYmd} 를 그대로 돌려준다.
     */
    private static String clampToMaxRange(String bgngYmd, String endYmd) {
        LocalDate bgng = LocalDate.parse(bgngYmd, YMD);
        LocalDate floor = LocalDate.parse(endYmd, YMD).minusDays(MAX_RANGE_DAYS - 1L);
        return bgng.isBefore(floor) ? floor.format(YMD) : bgngYmd;
    }

    /** 케이스 4·23 이 서비스를 직접 부를 때 쓰는 요청 객체 (기간은 보유 구간 그대로). */
    private LifezoneAnalysisRequest requestOf(String anlsTrgtCd) {
        LifezoneAnalysisRequest request = new LifezoneAnalysisRequest();
        request.setAnlsTrgtCd(anlsTrgtCd);
        request.setBgngYmd(queryBgngYmd);
        request.setEndYmd(dataEndYmd);
        return request;
    }

    // ─────────────────────────────────────────────────────── 헬퍼 — 등급

    /**
     * 응답 {@code gradeCriteria} 로 등급 판정기를 되살린다.
     *
     * <p>{@code gradeScale.minVls} 는 {@code @JsonIgnore} 라 응답에 없다 — 경계의 단일 출처는
     * {@code gradeCriteria} 다. 테스트도 그 출처만 읽는다(상수를 박지 않는다).
     */
    private static LifezoneGradeScale scaleOf(LifezoneAnalysisResultVO body) {
        Map<String, Integer> minVls = minVlByGrade(body);
        int[] byGrade = new int[GRADE_CNT];
        for (LifezoneGrade grade : LifezoneGrade.values()) {
            byGrade[grade.ordinal()] = minVls.get(grade.name());
        }
        return new LifezoneGradeScale(body.getGradeScale().cap(), body.getGradeScale().width(),
                LifezoneClsfMthd.valueOf(body.getGradeScale().method()), byGrade);
    }

    /**
     * {@code ranking} 에 실리는 셀(심각·경고·주의)만 {@code cells} 순서대로 고른다.
     * ranking 은 이 목록의 앞에서 떼어 만든다(④).
     */
    private static List<LifezoneCellVO> listedCells(List<LifezoneCellVO> cells) {
        List<LifezoneCellVO> listed = new ArrayList<>();
        for (LifezoneCellVO cell : cells) {
            if (LifezoneGrade.valueOf(cell.getGrade()).isListed()) {
                listed.add(cell);
            }
        }
        return listed;
    }

    private static Map<String, Integer> minVlByGrade(LifezoneAnalysisResultVO body) {
        Map<String, Integer> minVls = new LinkedHashMap<>();
        for (LifezoneGradeCriterionVO row : body.getGradeCriteria()) {
            minVls.put(row.getGrade(), row.getMinVl());
        }
        assertThat(minVls.keySet()).as("등급 기준이 전 등급을 덮지 않는다").hasSize(GRADE_CNT);
        return minVls;
    }

    /**
     * 세부 내역 계약 — 상위 2종까지 · 건수 내림차순 · 합은 격자 총건수 이하 ·
     * 유형명은 {@code TB_COM_CD} 와 일치. 유형명을 소스에 박지 않는다.
     */
    private void assertDtlContract(LifezoneRankVO rank) {
        List<LifezoneDtlVO> dtl = rank.getDtl();
        assertThat(dtl).as("세부 내역은 null 이 아니라 빈 배열이다").isNotNull();
        assertThat(dtl.size()).as("상위 2종까지만 싣는다").isLessThanOrEqualTo(2);

        int sum = 0;
        for (int i = 0; i < dtl.size(); i++) {
            LifezoneDtlVO row = dtl.get(i);
            assertThat(row.getCnt()).isPositive();
            assertThat(cvlcptTypeNms)
                    .as("민원유형 %s 가 TB_COM_CD 에 없다", row.getCvlcptTypeCd())
                    .containsKey(row.getCvlcptTypeCd());
            assertThat(row.getCvlcptTypeNm())
                    .as("유형명은 TB_COM_CD 의 cd_nm 이어야 한다 (%s)", row.getCvlcptTypeCd())
                    .isEqualTo(cvlcptTypeNms.get(row.getCvlcptTypeCd()));
            if (i > 0) {
                assertThat(row.getCnt()).isLessThanOrEqualTo(dtl.get(i - 1).getCnt());
            }
            sum += row.getCnt();
        }
        assertThat(sum)
                .as("세부 내역 합(%d)이 격자 총건수(%d)를 넘을 수 없다", sum, rank.getAnlsVl())
                .isLessThanOrEqualTo(rank.getAnlsVl());
    }

    // ─────────────────────────────────────────────────────── 헬퍼 — DB 재계산

    private int rowCount(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    /** 보유 전 구간·주어진 지역 조건에서 매퍼가 낼 격자 수를 DB 에서 다시 센다. */
    private int expectedCellCount(int gridSz, String emdCd, String sggCd) {
        Integer count = jdbcTemplate.queryForObject(
                gridHitSql(emdCd, sggCd) + " SELECT count(*) FROM h",
                Integer.class, gridHitArgs(gridSz, emdCd, sggCd));
        return count == null ? 0 : count;
    }

    /**
     * 같은 조건에서 서비스가 잡을 상한({@code cap})을 DB 에서 다시 계산한다.
     *
     * <p>🔴 등간격은 <b>최대값</b>만 쓴다 — 백분위가 아니므로 정렬도 인덱스 규약도 없다.
     * {@code LifezoneGradeScale.maxOf} 와 같은 값이다.
     */
    private int expectedCap(int gridSz, String emdCd, String sggCd) {
        Integer cap = jdbcTemplate.queryForObject(
                gridHitSql(emdCd, sggCd) + " SELECT coalesce(max(n), 0) FROM h",
                Integer.class, gridHitArgs(gridSz, emdCd, sggCd));
        return cap == null ? 0 : cap;
    }

    /**
     * {@code width} 가 {@code cap} 에서 나왔는지 본다 — <b>등간격</b>이면 {@code width = ceil(cap / 5)} 다.
     * 🔴 자연분류에는 쓰지 않는다(간격이 일정하지 않아 {@code width} 가 0 이다).
     *
     * <p>정수 나눗셈이라 <b>허용 오차가 없다</b> — 반올림된 소수가 아니므로 정확히 일치해야 한다.
     * {@code cap} 이 등급 수 이하로 떨어지면(좁은 조회·0건) 올림이 0·1 을 내므로 폭의 하한 1 로 막는다.
     */
    private static void assertWidthMatchesCap(LifezoneGradeScale scale) {
        int expected = Math.max(1, (scale.cap() + GRADE_CNT - 1) / GRADE_CNT);

        assertThat(scale.width())
                .as("width 가 ceil(cap(%d) / %d) 이어야 한다", scale.cap(), GRADE_CNT)
                .isEqualTo(expected);
    }

    /** {@link #gridHitSql} 의 바인딩 값 — 격자크기 · 기간 · (있으면) 지역 조건 순. */
    private Object[] gridHitArgs(int gridSz, String emdCd, String sggCd) {
        String region = emdCd != null ? emdCd : sggCd;
        if (region == null) {
            return new Object[] { gridSz, queryBgngYmd, dataEndYmd };
        }
        return new Object[] { gridSz, queryBgngYmd, dataEndYmd, region };
    }

    /**
     * 매퍼 {@code selectDataRange} 와 <b>같은 조건</b>으로 보유 구간을 읽는다 (상수 박기 회피).
     * 조건이 갈리면 케이스 1·8 의 echo 단언이 이유 없이 깨지므로 매퍼 XML 과 함께 고친다.
     */
    private static String dataRangeSql() {
        return "SELECT to_char(min(ocrn_dt), 'YYYYMMDD'), to_char(max(ocrn_dt), 'YYYYMMDD')"
                + "  FROM parking.tb_cvlcpt WHERE cvlcpt_type_cd <> '99'";
    }

    /**
     * 매퍼 {@code selectCells} 의 집계와 같은 식(경계격자 중복 제거 → 공간조인 → 격자별 집계).
     * 지역 조건도 매퍼와 같게 건다 — {@code emdCd} 는 등호, {@code sggCd} 는 앞 5자리 LIKE.
     */
    private static String gridHitSql(String emdCd, String sggCd) {
        String regionFilter = "";
        if (emdCd != null) {
            regionFilter = " AND c.emd_cd = ?";
        } else if (sggCd != null) {
            regionFilter = " AND c.emd_cd LIKE ? || '%'";
        }
        return "WITH g AS ("
                + "  SELECT DISTINCT ON (grid_cd) grid_cd, bndry_geom FROM parking.tb_grid"
                + "   WHERE grid_sz = ? ORDER BY grid_cd, sgg_cd"
                + "), h AS ("
                + "  SELECT g.grid_cd, count(*) AS n FROM g"
                + "    JOIN parking.tb_cvlcpt c"
                + "      ON c.geom && g.bndry_geom AND ST_Contains(g.bndry_geom, c.geom)"
                + "   WHERE c.lat IS NOT NULL AND c.cvlcpt_type_cd <> '99'"
                + "     AND c.ocrn_dt >= to_date(?, 'YYYYMMDD')"
                + "     AND c.ocrn_dt < to_date(?, 'YYYYMMDD') + 1"
                + regionFilter
                + "   GROUP BY g.grid_cd"
                + ")";
    }

    /**
     * <b>최대값</b>이 {@code 등급 수} 이하가 되는 읍면동(주어진 격자크기 기준) 중 셀이 가장 많은 곳.
     * 케이스 22 의 "좁은 조회"를 데이터에서 찾는다 — 코드를 박으면 재적재에 깨진다.
     * 동률이면 코드 오름차순으로 골라 실행 간 결과가 흔들리지 않게 한다.
     */
    private static String narrowEmdSql() {
        return "WITH g AS ("
                + "  SELECT DISTINCT ON (grid_cd) grid_cd, bndry_geom FROM parking.tb_grid"
                + "   WHERE grid_sz = ? ORDER BY grid_cd, sgg_cd"
                + "), h AS ("
                + "  SELECT c.emd_cd, g.grid_cd, count(*) AS n FROM g"
                + "    JOIN parking.tb_cvlcpt c"
                + "      ON c.geom && g.bndry_geom AND ST_Contains(g.bndry_geom, c.geom)"
                + "   WHERE c.lat IS NOT NULL AND c.cvlcpt_type_cd <> '99'"
                + "     AND c.ocrn_dt >= to_date(?, 'YYYYMMDD')"
                + "     AND c.ocrn_dt < to_date(?, 'YYYYMMDD') + 1"
                + "   GROUP BY c.emd_cd, g.grid_cd"
                + "), s AS ("
                + "  SELECT emd_cd, count(*) AS cells, max(n) AS cap"
                + "    FROM h GROUP BY emd_cd"
                + ")"
                + " SELECT emd_cd FROM s WHERE cap <= ? ORDER BY cells DESC, emd_cd LIMIT 1";
    }

    // ─────────────────────────────────────────────────────── 헬퍼 — 역직렬화

    /**
     * 응답 전용 {@link ObjectMapper}. {@code LifezoneGradeScale} 만 손으로 읽는다 —
     * 이유는 클래스 주석의 "⚠️ 응답 역직렬화" 참고. {@code minVls} 는 <b>가짜(0)</b>이므로
     * 등급 판정에는 {@link #scaleOf(LifezoneAnalysisResultVO)} 로 되살린 것을 쓴다.
     */
    private static ObjectMapper responseMapper() {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(LifezoneGradeScale.class, new JsonDeserializer<LifezoneGradeScale>() {
            @Override
            public LifezoneGradeScale deserialize(JsonParser parser, DeserializationContext ctx)
                    throws IOException {
                JsonNode node = parser.readValueAsTree();
                int width = node.path("width").isMissingNode() ? 0 : node.path("width").asInt();
                // method 는 실제로 적용된 분류방법이라 그대로 살린다 — 케이스 21·23 이 이 값을 본다.
                LifezoneClsfMthd mthd = node.path("method").isMissingNode()
                        ? LifezoneClsfMthd.DEFAULT
                        : LifezoneClsfMthd.valueOf(node.path("method").asText());
                return new LifezoneGradeScale(node.path("cap").asInt(), width, mthd,
                        new int[GRADE_CNT]);
            }
        });
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .registerModule(module);
    }
}
