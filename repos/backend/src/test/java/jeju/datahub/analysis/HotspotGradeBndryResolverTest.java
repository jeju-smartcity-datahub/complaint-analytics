package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.AnlsVrblOvrd;
import jeju.datahub.analysis.service.AnlsVrblScope;
import jeju.datahub.analysis.service.HotspotGradeBndry;
import jeju.datahub.analysis.service.impl.AnalysisWeightMapper;
import jeju.datahub.analysis.service.impl.HotspotGradeBndryResolver;

/** {@link HotspotGradeBndryResolver#resolve(AnlsVrblOvrd)} 단위 테스트. 설정 표를 건드리지 않으려 매퍼만 모킹한다. */
@ExtendWith(MockitoExtension.class)
class HotspotGradeBndryResolverTest {

    @Mock
    private AnalysisWeightMapper mapper;

    @Test
    @DisplayName("입력을 설정값 위에 얹는다 — 입력 없는 행은 설정값, 원본 행은 그대로다")
    void inputOverlaysSettings() {
        List<AnalysisWeightVO> rows = List.of(row("GRD_BNDRY1", "2", "5", "Y"), row("GRD_BNDRY2", "8", "10", "Y"),
                row("GRD_BNDRY3", "10", "15", "Y"));
        given(mapper.selectWeightsByType("HOTSPOT")).willReturn(rows);

        HotspotGradeBndry bndry = new HotspotGradeBndryResolver(mapper).resolve(ovrd(rows, Map.of("GRD_BNDRY3", "20")));

        assertThat(bndry).isEqualTo(new HotspotGradeBndry(2, 8, 20));
        assertThat(rows.get(2).getStngVl()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("입력 없음 + 깨진 설정 → 기존처럼 기본값 (예외 없음)")
    void emptyInputFallsBackToDefault() {
        List<AnalysisWeightVO> rows = List.of(row("GRD_BNDRY1", "20", "5", "Y"), row("GRD_BNDRY2", "8", "10", "Y"),
                row("GRD_BNDRY3", "10", "15", "Y"));
        given(mapper.selectWeightsByType("HOTSPOT")).willReturn(rows);

        assertThat(new HotspotGradeBndryResolver(mapper).resolve(ovrd(rows, Map.of())))
                .isEqualTo(HotspotGradeBndry.DEFAULT);
    }

    @Test
    @DisplayName("🔴 입력이 꺼진 행의 기본값과 역전 → 조용히 기본값으로 떨어지지 않고 400")
    void inputConflictingWithDisabledRowIs400() {
        // 꺼진 경고 행 — 입력 검증은 stng_vl(20) 을 보지만 판정은 bsc_stng_vl(10) 을 쓴다
        List<AnalysisWeightVO> rows = List.of(row("GRD_BNDRY1", "2", "5", "Y"), row("GRD_BNDRY2", "20", "10", "N"),
                row("GRD_BNDRY3", "30", "15", "Y"));
        given(mapper.selectWeightsByType("HOTSPOT")).willReturn(rows);

        assertThatThrownBy(() -> new HotspotGradeBndryResolver(mapper).resolve(ovrd(rows, Map.of("GRD_BNDRY1", "15"))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static AnlsVrblOvrd ovrd(List<AnalysisWeightVO> rows, Map<String, String> values) {
        Map<String, BigDecimal> decimals = new java.util.HashMap<>();
        values.forEach((k, v) -> decimals.put(k, new BigDecimal(v)));
        return AnlsVrblOvrd.of(AnlsVrblScope.HOTSPOT, rows, decimals);
    }

    private static AnalysisWeightVO row(String vrblCd, String stngVl, String bscStngVl, String useYn) {
        AnalysisWeightVO row = new AnalysisWeightVO();
        row.setAnlsTypeCd("HOTSPOT");
        row.setVrblCd(vrblCd);
        row.setVrblSeCd("CFC");
        row.setStngVl(new BigDecimal(stngVl));
        row.setBscStngVl(new BigDecimal(bscStngVl));
        row.setUseYn(useYn);
        return row;
    }
}
