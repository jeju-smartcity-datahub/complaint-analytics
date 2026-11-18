package jeju.datahub.lifezone;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jeju.datahub.lifezone.service.LifezoneClsfMthd;
import jeju.datahub.lifezone.service.LifezoneQuadrant;
import jeju.datahub.lifezone.service.LifezoneSupply;
import jeju.datahub.lifezone.service.LifezoneTarget;

/**
 * {@link LifezoneTarget} 계약 단위 테스트.
 *
 * <p>DB·컨텍스트가 필요 없는 enum 값만 본다 — {@code @SpringBootTest} 를 쓰지 않는다.
 *
 * <p>🔴 유형별 분류방법 목록은 <b>서버가 400 을 판정하는 근거이자 프론트가 선택 UI 를 그리는 근거</b>다.
 * 목록이 바뀌면 화면 선택지와 서버 허용 범위가 함께 바뀌므로 여기서 값을 못박는다.
 */
class LifezoneTargetTest {

    @Test
    @DisplayName("민원건수 분류방법 1종 확정 [EQUAL_INTERVAL] · 기본 EQUAL_INTERVAL · 기본 핸들러")
    void complaintCount() {
        LifezoneTarget t = LifezoneTarget.CVLCPT_CNT;
        assertThat(t.getClsfMthds()).containsExactly(LifezoneClsfMthd.EQUAL_INTERVAL);
        assertThat(t.getDfltClsfMthd()).isEqualTo(LifezoneClsfMthd.EQUAL_INTERVAL);
        assertThat(t.isDefaultHandled()).isTrue();
    }

    @Test
    @DisplayName("수급 — 부족률 등간격 고정이라 분류방법 없음(빈 목록 · 기본 null) · 단위 % · 제목·부제 · 전용 경로 /supply")
    void supply() {
        LifezoneTarget t = LifezoneTarget.SPLY_DMND;
        assertThat(t.getAnlsTrgtNm()).isEqualTo("주차 수급");
        assertThat(t.getUnit()).as("부족률(%) · 종전 건").isEqualTo("%");
        assertThat(t.getSectionTitle()).isEqualTo("주차 수급 부족 구역");
        assertThat(t.getSectionSub()).as("시안 부제").isEqualTo("격자 기반 수요·공급 격차 (심각, 경고, 주의)");
        assertThat(t.getClsfMthds()).isEmpty();
        assertThat(t.getDfltClsfMthd()).isNull();
        assertThat(t.isDefaultHandled()).isFalse();
        assertThat(t.getAnlsPathSfx()).isEqualTo("/supply");
    }

    @Test
    @DisplayName("혼잡도 분류방법 1종 확정 [CNGST_ABSOLUTE] · 기본 CNGST_ABSOLUTE · 계산 격자 · 전용 경로 /congestion")
    void congestion() {
        LifezoneTarget t = LifezoneTarget.CNGST;
        assertThat(t.getClsfMthds()).containsExactly(LifezoneClsfMthd.CNGST_ABSOLUTE);
        assertThat(t.getDfltClsfMthd()).isEqualTo(LifezoneClsfMthd.CNGST_ABSOLUTE);
        assertThat(t.isDefaultHandled())
                .as("기본 핸들러가 처리하면 민원건수 결과가 '혼잡도' 이름표를 달고 200 으로 나간다")
                .isFalse();
        assertThat(t.getAnlsPathSfx()).isEqualTo("/congestion");
        assertThat(t.getAnlsTrgtNm()).isEqualTo("혼잡도");
        assertThat(t.getUnit()).as("2026-10-08 지수 → 점수 환산").isEqualTo("점");
        assertThat(t.getGridSzs()).containsExactlyElementsOf(LifezoneSupply.GRID_SZS);
        assertThat(t.getDfltGridSz()).isEqualTo(LifezoneSupply.DEFAULT_GRID_SZ);
    }

    @Test
    @DisplayName("기본 분류방법이 있으면 반드시 그 유형의 허용 목록 안에 있다")
    void defaultIsWithinAllowedList() {
        for (LifezoneTarget t : LifezoneTarget.values()) {
            if (t.getDfltClsfMthd() == null) {
                assertThat(t.getClsfMthds()).as("%s — 기본이 없으면 목록도 비어야 한다", t).isEmpty();
            } else {
                assertThat(t.getClsfMthds()).as("%s", t).contains(t.getDfltClsfMthd());
            }
        }
    }

    @Test
    @DisplayName("현재 전 유형이 available — 미구현 사유 없음 (혼잡도까지 켜졌다)")
    void allTargetsAvailable() {
        for (LifezoneTarget t : LifezoneTarget.values()) {
            assertThat(t.isAvailable()).as("%s", t).isTrue();
            assertThat(t.getUnavailableRsn()).as("%s", t).isNull();
        }
    }

    @Test
    @DisplayName("LifezoneQuadrant.ofCode: 대소문자·공백 관대 · 미지원·빈 값은 null(호출부가 400)")
    void quadrantOfCode() {
        assertThat(LifezoneQuadrant.ofCode("Q2")).isEqualTo(LifezoneQuadrant.Q2);
        assertThat(LifezoneQuadrant.ofCode(" q1 ")).isEqualTo(LifezoneQuadrant.Q1);
        assertThat(LifezoneQuadrant.ofCode("Q9"))
                .as("valueOf 처럼 예외를 던지면 전역 핸들러가 없어 500 이 된다")
                .isNull();
        assertThat(LifezoneQuadrant.ofCode("")).isNull();
        assertThat(LifezoneQuadrant.ofCode(null)).isNull();
    }
}
