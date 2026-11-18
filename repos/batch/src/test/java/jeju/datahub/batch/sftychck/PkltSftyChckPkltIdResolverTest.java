package jeju.datahub.batch.sftychck;

import static jeju.datahub.batch.sftychck.PkltSftyChckResolverFixture.src;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jeju.datahub.batch.sftychck.PkltSftyChckPkltIdResolver.Match;
import jeju.datahub.batch.sftychck.PkltSftyChckPkltIdResolver.Path;
import jeju.datahub.batch.sftychck.PkltSftyChckResolverFixture.Lot;

/**
 * D2 주차장 해석 회귀. DB 없이 픽스처 주차장(가짜 이름·주소)으로 돈다.
 */
class PkltSftyChckPkltIdResolverTest {

    private PkltSftyChckResolverFixture fx;
    private PkltSftyChckPkltIdResolver resolver;

    @BeforeEach
    void setUp() {
        fx = new PkltSftyChckResolverFixture(List.of(
                new Lot(1, "900", "가람 공영주차장", "가람읍 가람리 10"),
                new Lot(2, "901", "나래 주차장(노외)", "나래동 20"),
                // 같은 주소 · 다른 이름 — 1단(주소+이름)으로 가른다
                new Lot(3, "902", "다솜 주차장", "다솜동 30"),
                new Lot(4, "903", "다솜 제2주차장", "다솜동 30"),
                // 이름·주소가 완전히 같은 쌍 — 원리적으로 구분 불가
                new Lot(5, "904", "라온 주차장", "라온동 40"),
                new Lot(6, "905", "라온 주차장", "라온동 40"),
                // 같은 이름 · 다른 주소 — 3단 이름 후보가 여럿
                new Lot(7, "906", "마루 주차장", "마루동 1"),
                new Lot(8, "907", "마루 주차장", "마루동 2"),
                // 소멸 주차장 — 조회 SQL 이 거르지 않으므로 후보다
                new Lot(9, "908", "바다 주차장", "바다동 50"),
                // 숫자가 아닌 origin_pk — 맵 적재가 죽지 않아야 한다
                new Lot(10, "X-1", "사랑 주차장", "사랑동 60")));
        resolver = fx.resolver;
    }

    // ── ① 키 경로 ───────────────────────────────────────────────

    @Test
    @DisplayName("주소가 다르면 원천 ID/이름만으로 KEY 매칭하지 않고 이름 폴백으로 검증")
    void keyPathWithNameVerification() {
        Match m = resolver.resolve(src(1, 901L, "나래 주차장", "엉뚱한 주소 999"));
        assertThat(m.pkltId()).isEqualTo(2L);
        assertThat(m.path()).isEqualTo(Path.NM);
    }

    @Test
    @DisplayName("① 키 경로: 이름이 달라도 정규화 주소가 맞으면 통과(둘 중 하나)")
    void keyPathWithAddrVerification() {
        Match m = resolver.resolve(src(1, 900L, "전혀 다른 이름", "가람읍 가람리 10"));
        assertThat(m.pkltId()).isEqualTo(1L);
        assertThat(m.path()).isEqualTo(Path.KEY);
    }

    @Test
    @DisplayName("🔴 ① 검증 실패 → ② 로 간다 — 원천 pklt_id 가 가리키는 주차장과 이름·주소가 다르면 키를 믿지 않는다")
    void keyVerificationFailureFallsBackToNameAddr() {
        // 원천 pklt_id 901(= 우리 2번) 인데 이름·주소는 1번 것
        Match m = resolver.resolve(src(1, 901L, "가람 공영주차장", "가람리 10"));
        assertThat(m.pkltId()).isEqualTo(1L);
        assertThat(m.path()).isEqualTo(Path.ADDR_NM);
    }

    @Test
    @DisplayName("① origin_pk 에 없는 키 → ② 로 간다")
    void unknownKeyFallsBack() {
        Match m = resolver.resolve(src(1, 12345L, "나래 주차장", "나래동 20"));
        assertThat(m.pkltId()).isEqualTo(2L);
        assertThat(m.path()).isEqualTo(Path.ADDR_NM);
    }

    @Test
    @DisplayName("원천 pklt_id 가 없으면(null — 값 없음 · 컬럼 없음) 곧장 ②")
    void nullKeyGoesToNameAddr() {
        Match m = resolver.resolve(src(1, null, "나래 주차장", "나래동 20"));
        assertThat(m.pkltId()).isEqualTo(2L);
        assertThat(m.path()).isEqualTo(Path.ADDR_NM);
    }

    @Test
    @DisplayName("🔴 origin_pk 는 문자열로 비교한다 — 원천 숫자 7 은 origin_pk '007' 이 아니다")
    void originPkIsComparedAsString() {
        fx.lots.add(new Lot(11, "007", "아라 주차장", "아라동 70"));
        resolver.beforeStep(null);
        Match m = resolver.resolve(src(1, 7L, "아라 주차장", "엉뚱한 주소 1"));
        assertThat(m.path()).as("키 경로가 아니라 3단 이름으로 붙는다").isEqualTo(Path.NM);
        assertThat(m.pkltId()).isEqualTo(11L);
    }

    @Test
    void repeatedOriginIdDoesNotPickAnArbitraryParkingLot() {
        fx.lots.add(new Lot(20, "900", "가람 공영주차장", "가람읍 가람리 10"));
        resolver.beforeStep(null);
        Match match = resolver.resolve(src(1, 900L, "가람 공영주차장", "가람읍 가람리 10"));
        assertThat(match.resolved()).isFalse();
        assertThat(match.failRsn()).isEqualTo(PkltSftyChckSkippableException.PKLT_AMBIGUOUS);
    }

    // ── ② 이름·주소 3단 ─────────────────────────────────────────

    @Test
    @DisplayName("② 1단: 주소 후보 여럿 중 정규화 이름이 같은 것이 유일하면 채택")
    void step1AddrAndName() {
        Match m = resolver.resolve(src(1, null, "다솜 제2 주차장", "다솜동 30"));
        assertThat(m.pkltId()).isEqualTo(4L);
        assertThat(m.path()).isEqualTo(Path.ADDR_NM);
        assertThat(m.candCnt()).isEqualTo(2);
    }

    @Test
    @DisplayName("② 1단: 읍면 접두·괄호·공백 정규화가 양쪽에 같이 걸린다")
    void step1Normalizes() {
        Match m = resolver.resolve(src(1, null, "나래주차장", "나래동 20"));
        assertThat(m.pkltId()).isEqualTo(2L);
        assertThat(resolver.resolve(src(2, null, "가람공영 주차장", "가람리 10")).pkltId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("② 2단: 이름이 달라도 주소 후보가 유일하면 채택")
    void step2AddrUnique() {
        Match m = resolver.resolve(src(1, null, "전혀 다른 이름", "나래동 20"));
        assertThat(m.pkltId()).isEqualTo(2L);
        assertThat(m.path()).isEqualTo(Path.ADDR);
    }

    @Test
    @DisplayName("② 3단: 주소 후보 0 이고 정규화 이름 후보가 유일하면 채택")
    void step3NameUnique() {
        Match m = resolver.resolve(src(1, null, "나래 주차장", "없는 주소 1"));
        assertThat(m.pkltId()).isEqualTo(2L);
        assertThat(m.path()).isEqualTo(Path.NM);
    }

    @Test
    @DisplayName("🔴 주소 후보 여럿 + 이름 동일 여럿 → PKLT_AMBIGUOUS — MIN(pklt_id) 로 붙이지 않는다")
    void identicalPairIsAmbiguous() {
        Match m = resolver.resolve(src(1, null, "라온 주차장", "라온동 40"));
        assertThat(m.resolved()).isFalse();
        assertThat(m.pkltId()).as("MIN 배정 금지").isNull();
        assertThat(m.failRsn()).isEqualTo(PkltSftyChckSkippableException.PKLT_AMBIGUOUS);
        assertThat(m.candCnt()).isEqualTo(2);
    }

    @Test
    @DisplayName("🔴 주소 후보 여럿 + 이름 일치 0 → PKLT_AMBIGUOUS(주소 후보 수)")
    void addrMultipleNoNameIsAmbiguous() {
        Match m = resolver.resolve(src(1, null, "모르는 주차장", "다솜동 30"));
        assertThat(m.failRsn()).isEqualTo(PkltSftyChckSkippableException.PKLT_AMBIGUOUS);
        assertThat(m.candCnt()).isEqualTo(2);
    }

    @Test
    @DisplayName("🔴 3단 이름 후보 여럿 → PKLT_AMBIGUOUS")
    void nameMultipleIsAmbiguous() {
        Match m = resolver.resolve(src(1, null, "마루 주차장", "없는 주소 2"));
        assertThat(m.failRsn()).isEqualTo(PkltSftyChckSkippableException.PKLT_AMBIGUOUS);
        assertThat(m.candCnt()).isEqualTo(2);
    }

    @Test
    @DisplayName("후보 0 → PKLT_NOT_FOUND")
    void nothingFound() {
        Match m = resolver.resolve(src(1, 99999L, "없는 주차장", "없는 주소 3"));
        assertThat(m.resolved()).isFalse();
        assertThat(m.failRsn()).isEqualTo(PkltSftyChckSkippableException.PKLT_NOT_FOUND);
        assertThat(m.candCnt()).isZero();
    }

    @Test
    @DisplayName("빈 이름·주소는 후보 키로 쓰지 않는다 — PKLT_NOT_FOUND")
    void blankInputDoesNotMatch() {
        fx.lots.add(new Lot(12, "910", "", ""));
        resolver.beforeStep(null);
        Match m = resolver.resolve(src(1, null, "", ""));
        assertThat(m.failRsn()).isEqualTo(PkltSftyChckSkippableException.PKLT_NOT_FOUND);
    }

    @Test
    @DisplayName("소멸 주차장도 후보다 — 조회 SQL 이 extsh_dt 로 거르지 않는다")
    void extinctLotIsCandidate() {
        assertThat(PkltSftyChckPkltIdResolver.LOAD_SQL).doesNotContainIgnoringCase("WHERE");
        assertThat(resolver.resolve(src(1, null, "바다 주차장", "바다동 50")).pkltId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("행 단위 판정이라 순서에 좌우되지 않는다 — 같은 주차장을 노리는 원천 2행이 모두 붙는다(taken 배타 없음)")
    void noExclusiveAssignment() {
        Match a = resolver.resolve(src(1, null, "나래 주차장", "나래동 20"));
        Match b = resolver.resolve(src(2, null, "나래 주차장", "나래동 20"));
        assertThat(a.pkltId()).isEqualTo(2L);
        assertThat(b.pkltId()).isEqualTo(2L);
    }

    // ── 자연키 · 수명 ───────────────────────────────────────────

    @Test
    @DisplayName("markIfFirst: 처음 본 자연키만 true · 유형이 다르면 다른 키")
    void markIfFirst() {
        assertThat(resolver.markIfFirst(2L, "20260301", "REGULAR")).isTrue();
        assertThat(resolver.markIfFirst(2L, "20260301", "REGULAR")).isFalse();
        assertThat(resolver.markIfFirst(2L, "20260301", "OCCASION")).isTrue();
        assertThat(resolver.markIfFirst(2L, "20260302", "REGULAR")).isTrue();
    }

    @Test
    @DisplayName("🔴 beforeStep 재호출 시 맵·키 집합·경로 건수가 초기화된다 — 재실행 때 전량 DUP_KEY 가 되지 않는다")
    void beforeStepResetsState() {
        assertThat(resolver.markIfFirst(2L, "20260301", "REGULAR")).isTrue();
        resolver.resolve(src(1, null, "나래 주차장", "나래동 20"));
        assertThat(resolver.pathCount(Path.ADDR_NM)).isEqualTo(1);

        // 부모 표가 바뀐 상황 — 2번이 사라졌다
        fx.lots.removeIf(l -> l.id() == 2);
        resolver.beforeStep(null);

        assertThat(resolver.markIfFirst(2L, "20260301", "REGULAR")).as("키 집합 초기화").isTrue();
        assertThat(resolver.pathCount(Path.ADDR_NM)).as("경로 건수 초기화").isZero();
        assertThat(resolver.resolve(src(1, 901L, "나래 주차장", "나래동 20")).failRsn())
                .as("맵도 새로 만든다 — 이전 실행의 주차장이 남지 않는다")
                .isEqualTo(PkltSftyChckSkippableException.PKLT_NOT_FOUND);
    }

    @Test
    @DisplayName("afterStep 은 종료 상태를 바꾸지 않는다")
    void afterStepKeepsExitStatus() {
        assertThat(resolver.afterStep(null)).isNull();
    }
    @Test void undatedObservationPrefersCurrentParkingButHistoricalDatesKeepAllCandidates() {
        fx.lots.add(new Lot(20,"old","가람 공영주차장","가람읍 가람리 10"));
        resolver.beforeStep(null);
        org.springframework.test.util.ReflectionTestUtils.setField(resolver,"activeIds",java.util.Set.of(1L));
        Match observation=resolver.resolve(src(1,null,"가람 공영주차장","가람읍 가람리 10",null,null,null,"D",null));
        assertThat(observation.pkltId()).isEqualTo(1L);
        Match historical=resolver.resolve(src(2,null,"가람 공영주차장","가람읍 가람리 10"));
        assertThat(historical.resolved()).isFalse();
    }

}
