package jeju.datahub.batch.cvlcpt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link CvlcptCodeMapping} — 실 원천/공통코드와의 정합 검증.
 *
 * <p><b>로컬 DB 가 떠 있어야 한다.</b> 이 매핑의 위험은 "코드가 틀린 것"이 아니라 <b>원천 표기나
 * 공통코드가 바뀌었는데 매핑만 그대로인 것</b>이라, 실제 156,883행·공통코드 테이블과 대조하는 것이
 * 유일하게 의미 있는 검증이다. 조회 전용이라 데이터를 건드리지 않는다.
 *
 * <p>전 행을 자바로 돌리지 않고 {@code (prk_cvlcpt_type, dclr_type)} 조합 34종 × 건수로 집계해
 * 대조한다 — 결과는 전수 대조와 같고 쿼리는 한 번이다.
 */
@SpringBootTest
@ActiveProfiles("local")
class CvlcptCodeMappingTest {

    /**
     * 계획서 사전조사 "좌변 완전성 검증 완료" 절의 실측치(2026-08-03, xlsx 시드 기준).
     *
     * <h2>🔴 이 값들은 이제 <b>하한</b>이다 (Step 11)</h2>
     * {@code complaintFetchJob} 이 API 수집분을 {@code tb_cvlcpt} 에 upsert 하면서
     * <b>원천이 단조 증가</b>한다 — 이 표는 더 이상 고정된 스냅샷이 아니다. 등호로 묶어 두면
     * <b>수집 배치가 한 번 돌 때마다 이 테스트가 깨진다.</b>
     *
     * <p>그렇다고 검증을 없애면 이 테스트의 목적("원천 표기가 바뀌었는데 매핑만 그대로인 것"을
     * 잡는 것)이 사라진다. 그래서 <b>성장에 견디면서 표기 변경은 잡는</b> 두 축으로 바꿨다:
     * <ul>
     *   <li><b>단조성</b> — 유형별 건수가 기준선 아래로 내려가지 않는다(표기가 바뀌면 그 버킷이 줄어든다)</li>
     *   <li><b>미분류 비율</b> — 기준선 47.9% 근처를 유지한다. 원천 표기가 통째로 바뀌면
     *       <b>전량이 조용히 99 로 빠지는데 배치는 정상 완료로 보고</b>하므로, 비율 급등이
     *       그 사고의 유일한 신호다(계획서 "미분류(99) 행 처리" 절)</li>
     * </ul>
     */
    // 🔁 2026-08-24 — 절대 기준선(156,883 / 80,138 / 76,745)을 걷어냈다.
    //    그 숫자는 폐지된 staging 표(tb_cvlcpt_origin)의 행 수였다. 대조 대상이 tb_cvlcpt 로
    //    바뀐 지금 같은 하한을 걸면 **환경마다 실패한다** — 시드는 17,811행이고 배치 수집분은
    //    조회 구간에 따라 달라진다. 남은 검증은 행 수와 무관한 두 축이다(아래 주석).

    /**
     * 미분류(99) 비율 밴드. 원천 표기 변경으로 유형이 통째로 99 로 새면 이 밴드를 벗어난다.
     *
     * <p>🔁 2026-09-01 — 기준선이 <b>47.9%</b> 다(새 원천 78,386행 중 미분류 37,508). 밴드는
     * ±5%p 가 아니라 <b>[20, 75]</b> 로 넓다. 이유는 이 검증의 대상이 "전량 수집분"이 아니기
     * 때문이다 — 로컬에서는 <b>하루치만 수집한 상태</b>로 도는 것이 정상이고(실측 2026-05-01
     * 하루치는 39.1%), 구간에 따라 유형 구성이 흔들린다. 좁은 밴드는 그때마다 빨개져
     * <b>진짜 신호를 묻는다.</b>
     *
     * <p>이 축이 잡으려는 사고는 "비율이 몇 %p 움직였나" 가 아니라 <b>원천 표기가 통째로 바뀌어
     * 전량이 99 로 쏠리는 것</b>(≈100%)과 그 반대(≈0%)다. 넓은 밴드로도 그것은 확실히 잡힌다.
     */
    private static final double UNCLASSIFIED_PCT_MIN = 20.0;
    private static final double UNCLASSIFIED_PCT_MAX = 75.0;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("TYPE_CD_MAP 이 공통코드 CVLCPT_TYPE_CD 와 1:1 이다 (99=기타 제외)")
    void typeCdMapMatchesCommonCode() {
        Map<String, String> commonCode = new HashMap<>();
        jdbc.query("SELECT cd_vl, cd_nm FROM parking.tb_com_cd WHERE group_cd = 'CVLCPT_TYPE_CD'",
                rs -> {
                    commonCode.put(rs.getString("cd_nm"), rs.getString("cd_vl"));
                });

        assertThat(commonCode).containsAllEntriesOf(CvlcptCodeMapping.TYPE_CD_MAP);
        // 미분류가 갈 자리(99)도 공통코드에 실재해야 FK 가 깨지지 않는다.
        assertThat(commonCode).containsValue(CvlcptCodeMapping.TYPE_CD_ETC);
        assertThat(commonCode).hasSize(CvlcptCodeMapping.TYPE_CD_MAP.size() + 1);
    }

    @Test
    @DisplayName("SRC_CD_MAP 값과 ETC 가 모두 공통코드 SRC_CD 에 실재한다")
    void srcCdMapMatchesCommonCode() {
        List<String> codes = jdbc.queryForList(
                "SELECT cd_vl FROM parking.tb_com_cd WHERE group_cd = 'SRC_CD'", String.class);

        assertThat(codes).containsAll(CvlcptCodeMapping.SRC_CD_MAP.values());
        assertThat(codes).contains(CvlcptCodeMapping.SRC_CD_ETC);
    }

    @Test
    @DisplayName("원천 src_path 실측값이 모두 매핑에 있다 — ETC 로 새는 값이 없다")
    void everySourcePathIsMapped() {
        assumeSourceTextLoaded("src_path");
        List<String> paths = jdbc.queryForList(
                "SELECT DISTINCT src_path FROM parking.tb_cvlcpt WHERE src_path <> ''",
                String.class);

        assertThat(paths).isNotEmpty();
        assertThat(paths).allSatisfy(path ->
                assertThat(CvlcptCodeMapping.srcCd(path))
                        .as("매핑 밖 유입경로 '%s' 가 ETC 로 적재된다 — SRC_CD_MAP 보강 필요", path)
                        .isNotEqualTo(CvlcptCodeMapping.SRC_CD_ETC));
    }

    @Test
    @DisplayName("실 원천 분류 결과가 DB 집계와 정합한다 — 코드 집합 + 미분류 비율 밴드")
    void classificationMatchesSourceAggregates() {
        assumeSourceTextLoaded("dclr_type");
        Map<String, Long> byCode = new LinkedHashMap<>();
        long[] total = {0};

        // 🔴 dclr_type 이 있는 행만 본다 — 시드(09-TB_CVLCPT.csv)는 **이미 분류를 마친 서비스
        //    테이블 스냅샷**이라 dclr_type 이 NULL 이다. 함께 집계하면 그 17,545행이 전부 99 로
        //    잡혀 미분류 비율이 99% 가 되고, 배치를 한 번이라도 돌린 DB 에서 이 테스트가 늘
        //    빨개진다(2026-09-01 실제로 그렇게 깨졌다). 검증 대상은 **수집 배치가 넣은 행**이다.
        jdbc.query("""
                SELECT prk_cvlcpt_type, dclr_type, count(*) AS cnt
                  FROM parking.tb_cvlcpt
                 WHERE dclr_type IS NOT NULL
                 GROUP BY prk_cvlcpt_type, dclr_type
                """, rs -> {
            long cnt = rs.getLong("cnt");
            String typeName = CvlcptCodeMapping.typeName(
                    rs.getString("prk_cvlcpt_type"), rs.getString("dclr_type"));
            String cd = CvlcptCodeMapping.typeCd(typeName);
            byCode.merge(cd == null ? CvlcptCodeMapping.TYPE_CD_ETC : cd, cnt, Long::sum);
            total[0] += cnt;
        });

        // ── 축 1. 코드 집합 — 값이 공통코드 밖으로 새면 화면·집계가 조용히 깨진다.
        assertThat(byCode.keySet()).isSubsetOf("01", "02", "03", "04", "05",
                CvlcptCodeMapping.TYPE_CD_ETC);

        // ── 축 2. 🔴 미분류(99) 비율 밴드 — 이 테스트의 핵심이다.
        //    원천 표기가 통째로 바뀌면 전량이 조용히 99 로 빠지는데 **배치는 정상 완료로 보고한다.**
        //    비율 급등이 그 사고를 잡는 유일한 신호다(기준선 47.9%, 2026-09-01 실측).
        //    행 수와 무관한 비율이라 수집 구간이 달라져도 견딘다.
        double unclassifiedPct = 100.0 * byCode.getOrDefault(
                CvlcptCodeMapping.TYPE_CD_ETC, 0L) / total[0];
        assertThat(unclassifiedPct)
                .as("미분류(99) 비율이 기준선(47.9%%)에서 크게 벗어났다 — 원천 표기 변경 의심")
                .isBetween(UNCLASSIFIED_PCT_MIN, UNCLASSIFIED_PCT_MAX);

        // ── 축 3. 주차민원 5종 중 최소 1개는 나타나는지 — 5종 모두 사라졌으면 그 표기가 통째로
        //    바뀌었거나, 시드/수집이 빈 것이다. "전부 존재" 가 아니라 "하나라도 존재" 만 본다 —
        //    수집 구간이 좁으면 5종 모두는 당연히 못 채우고, 그래도 비주차 민원이 다수인 평시엔
        //    5종 중 하나는 거의 반드시 나온다. 그게 사라지면 미분류 99 로 쏠리는 사고를 잡는
        //    유일한 신호다. (건수 하한이 아니라 **존재 여부**만 본다.)
        assertThat(byCode.keySet())
                .as("주차민원 유형 중 하나도 나오지 않았다 — 해당 표기의 원천 변경 의심")
                .containsAnyOf("01", "02", "03", "04", "05");
    }

    /**
     * 🔁 2026-09-01 (TSK-4109) — <b>비활성을 되돌렸다.</b>
     *
     * <p>2026-08-24 에 이 테스트를 껐던 이유는 대조 오라클의 소멸이었다: 원본(staging) 폐지로
     * {@code prk_cvlcpt_type} 을 채우는 경로가 없어져 비교 대상이 영구히 0행이 됐다. 새 원천
     * {@code tb_cvlcpt} 가 이 컬럼을 다시 주고(78,386행 중 40,878행) 수집 배치가 그것을 적재하므로
     * 오라클이 되살아났다.
     *
     * <p>지키는 명제는 그대로다 — <b>{@code dclr_type} CASE 가 원천 분류를 행 단위로 재현한다.</b>
     * 이 명제가 깨지면 두 분류 경로(원천값 우선 / CASE)가 서로 다른 답을 내기 시작했다는 뜻이고,
     * 그것은 원천 표기가 바뀌었다는 가장 이른 신호다.
     */
    @Test
    @DisplayName("CASE 규칙이 prk_cvlcpt_type 을 행 단위로 재현한다 — 두 분류 경로가 갈라지지 않는다")
    void caseRuleReproducesPrkCvlcptType() {
        // 🔴 오라클 자기잠식 방지 — 비교 대상이 0 이면 mismatch 도 0 이라 조용히 "통과"한다.
        //    그때는 통과가 아니라 "검증 불능"(skip)으로 드러나야 한다.
        assumeSourceTextLoaded("prk_cvlcpt_type");

        long[] mismatch = {0};
        long[] compared = {0};

        jdbc.query("""
                SELECT prk_cvlcpt_type, dclr_type, count(*) AS cnt
                  FROM parking.tb_cvlcpt
                 WHERE prk_cvlcpt_type IS NOT NULL AND prk_cvlcpt_type <> ''
                 GROUP BY prk_cvlcpt_type, dclr_type
                """, rs -> {
            // prk_cvlcpt_type 을 무시하고 CASE 만 태웠을 때 같은 결과가 나와야 한다.
            String caseOnly = CvlcptCodeMapping.typeName(null, rs.getString("dclr_type"));
            compared[0] += rs.getLong("cnt");
            if (!rs.getString("prk_cvlcpt_type").equals(caseOnly)) {
                mismatch[0] += rs.getLong("cnt");
            }
        });

        assertThat(compared[0]).isPositive();
        assertThat(mismatch[0]).isZero();
    }

    /**
     * 시드가 적재된 DB 에서만 의미가 있는 검증이다.
     *
     * <p>실측 원천을 오라클로 삼는 테스트라 빈 DB 에서는 "틀렸다" 가 아니라 "확인할 수 없다" 가
     * 맞다. 실패로 두면 시드 CSV 를 아직 받지 못한 환경(git-lfs 미인출)에서 빌드가 늘 빨개져
     * 진짜 회귀가 묻힌다.
     */
    /**
     * 원천 원문 컬럼이 채워진 행이 있을 때만 의미가 있는 검증이다.
     *
     * <p>🔁 2026-08-24 — 게이트 조건이 "표에 행이 있는가" 에서 <b>"그 컬럼이 채워져 있는가"</b> 로
     * 바뀌었다. 원본(staging) 폐지로 대조 대상이 {@code tb_cvlcpt_origin} → {@code tb_cvlcpt} 가
     * 됐는데, <b>시드({@code seeding/09-TB_CVLCPT.csv})는 이미 분류를 마친 서비스 테이블 스냅샷</b>이라
     * {@code cvlcpt_type_cd} 는 있지만 {@code dclr_type}·{@code src_path} 는 NULL 이다(실측 0/17,811).
     * 그 컬럼을 채우는 것은 수집 배치뿐이다.
     *
     * <p>즉 시드만 적재된 DB 에서는 "틀렸다" 가 아니라 <b>"확인할 수 없다"</b> 가 맞다. 실패로 두면
     * 배치를 한 번도 돌리지 않은 환경에서 빌드가 늘 빨개져 진짜 회귀가 묻힌다.
     *
     * @param column 검증에 필요한 원천 원문 컬럼 이름
     */
    private void assumeSourceTextLoaded(String column) {
        Long rows = jdbc.queryForObject(
                "SELECT count(%s) FROM parking.tb_cvlcpt".formatted(column), Long.class);
        Assumptions.assumeTrue(rows != null && rows > 0,
                ("parking.tb_cvlcpt.%s 가 전부 NULL 이라 원천 대조를 건너뜁니다 — "
                        + "이 컬럼은 수집 배치만 채운다(시드는 분류를 마친 스냅샷이다). "
                        + "complaintFetchJob 을 한 번 돌린 뒤 다시 실행할 것").formatted(column));
    }
}
