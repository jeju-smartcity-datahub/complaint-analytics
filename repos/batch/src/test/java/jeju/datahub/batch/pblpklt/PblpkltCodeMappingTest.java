package jeju.datahub.batch.pblpklt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link PblpkltCodeMapping} — 유형명 → {@code PKLT_TYPE_CD} 대응표 검증.
 *
 * <p>🔵 <b>DB 를 쓰지 않는다.</b> cvlcpt 쪽 대응표 테스트는 실 원천 156,883행과 대조하느라
 * {@code @SpringBootTest} 를 쓰지만, 여기서 지키는 것은 "표가 코드와 같은가" 뿐이라
 * 순수 단위 테스트로 충분하다. 공통코드(마이그레이션 64)와의 정합은 E2E 가 본다.
 *
 * <p>🔴 대응표의 요점은 <b>위치(노상/노외)가 소유(공영)보다 우선</b>한다는 것이다 —
 * 이 표 전체가 공영주차장이라 "공영" 은 구분력이 없다. 그래서 {@code 노상공영} 이
 * {@code PBLPKLT} 가 아니라 {@code ONSTR} 이다.
 */
class PblpkltCodeMappingTest {

    @ParameterizedTest(name = "pklt_type_nm={0} → {1}")
    @DisplayName("§3-1 대응표 7종 — 원문 유형명이 코드로 간다")
    @CsvSource({
            "노상,ONSTR",
            "노상(23년 조사),ONSTR",
            "노상공영,ONSTR",          // 🔴 위치가 소유보다 우선 — PBLPKLT 가 아니다
            "노외공영,OFSTR",
            "공영,PBLPKLT",            // 위치 구분 없이 '공영' 만 온 원문
            "공한지,VACLOT",
            "복층화,MULTFLR"})
    void mapsEverySourceTypeName(String typeNm, String expected) {
        assertThat(PblpkltCodeMapping.resolveTypeCd(null, typeNm)).isEqualTo(expected);
    }

    @Test
    @DisplayName("대응표는 7종 그대로다 — 항목이 늘거나 줄면 공통코드(마이그레이션 64)와 함께 봐야 한다")
    void keepsTableInSyncWithCommonCode() {
        assertThat(PblpkltCodeMapping.NAME_TO_CD)
                .hasSize(7)
                .containsOnlyKeys("노상", "노상(23년 조사)", "노상공영", "노외공영", "공영", "공한지", "복층화")
                .containsValues("ONSTR", "OFSTR", "PBLPKLT", "VACLOT", "MULTFLR");
    }

    @ParameterizedTest(name = "pklt_type_nm={0} → ETC")
    @DisplayName("표에 없는 유형명은 ETC — 부분 일치로 억지 배정하지 않는다")
    @ValueSource(strings = {"노상 공영", "노외", "공한지(임시)", "지하", "노상(24년 조사)", "  "})
    void mapsUnknownTypeNameToEtc(String typeNm) {
        assertThat(PblpkltCodeMapping.resolveTypeCd(null, typeNm))
                .isEqualTo(PblpkltCodeMapping.TYPE_CD_ETC);
    }

    @ParameterizedTest
    @DisplayName("유형명이 null/빈값이면 ETC — 코드값을 비우지 않는다")
    @NullAndEmptySource
    void mapsMissingTypeNameToEtc(String typeNm) {
        assertThat(PblpkltCodeMapping.resolveTypeCd(null, typeNm))
                .isEqualTo(PblpkltCodeMapping.TYPE_CD_ETC);
    }

    @Test
    @DisplayName("앞뒤 공백은 다듬어 비교한다 — 그 밖의 정규화(괄호 제거·부분 일치)는 하지 않는다")
    void stripsSurroundingWhitespaceOnly() {
        assertThat(PblpkltCodeMapping.resolveTypeCd(null, "  공한지 ")).isEqualTo("VACLOT");
        // 내부 공백까지 지우지는 않는다 — 지우면 '노상 공영' 이 ONSTR 로 둔갑해 재현성이 사라진다.
        assertThat(PblpkltCodeMapping.resolveTypeCd(null, "노 상"))
                .isEqualTo(PblpkltCodeMapping.TYPE_CD_ETC);
    }

    @Test
    @DisplayName("🔴 원천이 코드를 주면 그 값이 이긴다 — 우리 표를 거치지 않는다")
    void prefersSourceCodeOverNameMapping() {
        // 이름은 '공한지'(→VACLOT) 지만 원천이 코드를 채워 보내면 그 값으로 적재된다.
        assertThat(PblpkltCodeMapping.resolveTypeCd("ONSTR", "공한지")).isEqualTo("ONSTR");
        // 우리 표에 없는 코드라도 그대로 쓴다 — 원천이 코드 체계를 넓혔다는 뜻이다.
        assertThat(PblpkltCodeMapping.resolveTypeCd("NEWCD", null)).isEqualTo("NEWCD");
        assertThat(PblpkltCodeMapping.resolveTypeCd("  OFSTR  ", "노상")).isEqualTo("OFSTR");
    }

    @Test
    @DisplayName("코드가 공백이면 비어 있는 것으로 보고 이름 매핑으로 간다")
    void fallsBackToNameWhenSourceCodeIsBlank() {
        assertThat(PblpkltCodeMapping.resolveTypeCd("   ", "공한지")).isEqualTo("VACLOT");
        assertThat(PblpkltCodeMapping.resolveTypeCd("", "노외공영")).isEqualTo("OFSTR");
    }

    @Test
    @DisplayName("코드도 이름도 없으면 ETC")
    void mapsNothingToEtc() {
        assertThat(PblpkltCodeMapping.resolveTypeCd(null, null))
                .isEqualTo(PblpkltCodeMapping.TYPE_CD_ETC);
    }
}
