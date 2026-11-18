package jeju.datahub.policysim.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 인접 읍면동 조회 통합테스트 ({@link PolicySimMapper#selectAdjacentEmd}).
 *
 * <p>🔴 판정식은 {@code ST_Intersects} 다(2026-10-11 사용자 결정). 경계 폴리곤이 미세하게 중첩돼
 * {@code ST_Touches} 면 도련일동 7곳 중 3곳(봉개동·영평동·회천동)을 놓친다 — 아래 도련일동 단언이 그 회귀를 잡는다.
 *
 * <p>공간 데이터({@code tb_admdst} 74행 경계)만 읽는다 — 민원·단속처럼 재적재로 이동하는 날짜 값이 없어
 * 읍면동 코드를 고정 단언한다. 조회 전용이라 정리할 행이 없다.
 */
@SpringBootTest
class AdjacentEmdTest {

    private static final String EMD_YEONDONG = "50110137";
    private static final String EMD_DORYEON1 = "50110130";
    private static final String EMD_CHUJA = "50110320";
    private static final String EMD_UDO = "50110330";

    @Autowired
    private PolicySimMapper policySimMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PolicySimBaselineCalculator baselineCalculator;

    private List<String> emdCdsOf(String sggNm) {
        return jdbcTemplate.queryForList(
                "SELECT emd_cd FROM parking.tb_admdst WHERE sgg_nm = ? ORDER BY emd_cd", String.class, sggNm);
    }

    @Test
    @DisplayName("연동 → 인접 6곳 (노형동·도두이동·도두일동·오라삼동·오라이동·용담이동)")
    void yeondong() {
        assertThat(policySimMapper.selectAdjacentEmd(List.of(EMD_YEONDONG)))
                .containsExactlyInAnyOrder(
                        "50110122",  // 노형동
                        "50110128",  // 도두이동
                        "50110127",  // 도두일동
                        "50110121",  // 오라삼동
                        "50110120",  // 오라이동
                        "50110109"); // 용담이동
    }

    @Test
    @DisplayName("도련일동 → 인접 7곳 (ST_Intersects — ST_Touches 면 봉개동·영평동·회천동이 빠진다)")
    void doryeon1() {
        assertThat(policySimMapper.selectAdjacentEmd(List.of(EMD_DORYEON1)))
                .containsExactlyInAnyOrder(
                        "50110131",  // 도련이동
                        "50110116",  // 봉개동
                        "50110114",  // 삼양이동
                        "50110136",  // 영평동
                        "50110112",  // 화북이동
                        "50110111",  // 화북일동
                        "50110133"); // 회천동
    }

    @Test
    @DisplayName("추자면·우도면 → 인접 0곳 (섬)")
    void islandsHaveNoNeighbor() {
        assertThat(policySimMapper.selectAdjacentEmd(List.of(EMD_CHUJA))).isEmpty();
        assertThat(policySimMapper.selectAdjacentEmd(List.of(EMD_UDO))).isEmpty();
    }

    @Test
    @DisplayName("제주시 전체 선택 → 결과에 제주시 읍면동이 없고 서귀포시 접경만 남는다")
    void jejuSiExcludesTargets() {
        List<String> jejuSi = emdCdsOf("제주시");
        List<String> seogwipo = emdCdsOf("서귀포시");
        assertThat(jejuSi).isNotEmpty();

        List<String> adj = policySimMapper.selectAdjacentEmd(jejuSi);

        assertThat(adj).isNotEmpty()
                .doesNotContainAnyElementsOf(jejuSi)
                .allMatch(seogwipo::contains)
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("제주 전체(74) 선택 → 인접 공집합")
    void wholeJejuIsEmpty() {
        List<String> all = jdbcTemplate.queryForList("SELECT emd_cd FROM parking.tb_admdst", String.class);
        assertThat(policySimMapper.selectAdjacentEmd(all)).isEmpty();
    }

    @Test
    @DisplayName("빈 입력·null → 빈 결과 (IN () 문법 오류가 나지 않는다)")
    void emptyInput() {
        assertThat(policySimMapper.selectAdjacentEmd(List.of())).isEmpty();
        assertThat(policySimMapper.selectAdjacentEmd(null)).isEmpty();
    }

    @Test
    @DisplayName(" 제주시 전체의 인접 기준선 — 코드 목록 경로가 전체 기준선의 같은 행과 같고 g_sgg 는 서귀포시 값")
    void adjacentBaselineMatchesFullBaseline() {
        List<String> adj = policySimMapper.selectAdjacentEmd(emdCdsOf(PolicySimParams.SGG_JEJU));
        PolicySimBaseline full = baselineCalculator.calculate();
        PolicySimBaseline sub = baselineCalculator.calculate(adj);

        assertThat(sub.emds()).extracting(PolicySimEmdBaseline::emdCd).containsExactlyInAnyOrderElementsOf(adj);
        assertThat(sub.cvlcptBgngYmd()).isEqualTo(full.cvlcptBgngYmd());
        assertThat(sub.cvlcptMonths()).isEqualTo(full.cvlcptMonths());
        double gSeogwipo = sub.params().geoCvrgCrct(PolicySimParams.SGG_SEOGWIPO);
        for (PolicySimEmdBaseline b : sub.emds()) {
            assertThat(b.sggNm()).isEqualTo(PolicySimParams.SGG_SEOGWIPO);
            assertThat(b.gSgg()).isEqualTo(gSeogwipo);
            assertThat(b).isEqualTo(full.emds().stream().filter(f -> f.emdCd().equals(b.emdCd())).findFirst().orElseThrow());
        }
    }
}
