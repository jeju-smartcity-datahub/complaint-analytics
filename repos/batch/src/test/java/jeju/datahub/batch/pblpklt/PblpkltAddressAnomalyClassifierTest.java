package jeju.datahub.batch.pblpklt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jeju.datahub.batch.pblpklt.api.PblpkltOriginRow;

/**
 * 주소 특이 패턴 판정의 <b>입력</b> 회귀 테스트 (2026-09-16).
 *
 * <h2>왜 이 테스트가 필요한가</h2>
 * 이 Processor 는 원천에 주소 컬럼이 없던 시절 {@code pklt_nm} 으로 판정했는데, 판정 결과를
 * 적재하는 {@link PblpkltItemWriter} 는 {@code pklt_addr} 을 저장했다 — <b>판정한 값과 기록된
 * 값이 다른</b> 상태가 오래 남아 있었고, 옛 데이터셋에서는 그 컬럼이 통째로 null 이라
 * {@code null value in column "orgnl_addr"} 로 Job 이 죽었다.
 *
 * <p>두 자리가 같은 식({@code anomalyInput})을 쓰는지는 컴파일러가 보장하지 못하므로
 * 여기서 입력 선택 규칙을, {@code PblpkltItemWriterTest} 에서 저장 값을 각각 고정한다.
 */
class PblpkltAddressAnomalyClassifierTest {

    private final PblpkltAddressAnomalyClassifier classifier = new PblpkltAddressAnomalyClassifier();

    @Test
    @DisplayName("🔴 판정 입력은 pklt_addr 이다 — 이름이 아무리 이상해도 주소가 정상이면 정상")
    void judgesOnAddressNotName() {
        // 이름은 지명(종전 규칙이면 NON_ADDRESS_NAME), 주소는 멀쩡한 지번이다.
        PblpkltFetchRow row = classifier.process(row("한림 상두거리 남측", "상모리 3964-1"));

        assertThat(row.anomalyTypes()).isEmpty();
    }

    @Test
    @DisplayName("주소가 지명이면 NON_ADDRESS_NAME 으로 잡힌다")
    void flagsNonAddressName() {
        PblpkltFetchRow row = classifier.process(row("아무 이름", "한림 상두거리"));

        assertThat(row.anomalyTypes()).contains("NON_ADDRESS_NAME");
    }

    @Test
    @DisplayName("방향 접미사는 주소 쪽에서 본다")
    void flagsDirectionSuffixFromAddress() {
        PblpkltFetchRow row = classifier.process(row("아무 이름", "상모리 3964-1 남측"));

        assertThat(row.anomalyTypes()).contains("DIRECTION_SUFFIX");
    }

    @Test
    @DisplayName("pklt_addr 이 없으면 pklt_nm 으로 폴백한다 — 옛 7필드 데이터셋 호환")
    void fallsBackToNameWhenAddressMissing() {
        assertThat(PblpkltAddressAnomalyClassifier.anomalyInput(null, "한림 상두거리"))
                .isEqualTo("한림 상두거리");

        PblpkltFetchRow row = classifier.process(row("한림 상두거리", null));
        assertThat(row.anomalyTypes()).contains("NON_ADDRESS_NAME");
    }

    @Test
    @DisplayName("주소가 있으면 이름은 보지 않는다")
    void prefersAddressOverName() {
        assertThat(PblpkltAddressAnomalyClassifier.anomalyInput("상모리 3964-1", "한림 상두거리"))
                .isEqualTo("상모리 3964-1");
    }

    @Test
    @DisplayName("🔴 주소·이름이 모두 없으면 판정하지 않는다 — orgnl_addr 은 NOT NULL 이다")
    void producesNoAnomalyWhenBothMissing() {
        assertThat(PblpkltAddressAnomalyClassifier.anomalyInput(null, null)).isNull();

        PblpkltFetchRow row = classifier.process(row(null, null));
        assertThat(row.anomalyTypes()).isEmpty();
    }

    private static PblpkltOriginRow row(String pkltNm, String pkltAddr) {
        return PblpkltOriginRow.builder()
                .pkltId("1916")
                .pkltNm(pkltNm)
                .pkltAddr(pkltAddr)
                .build();
    }
}
