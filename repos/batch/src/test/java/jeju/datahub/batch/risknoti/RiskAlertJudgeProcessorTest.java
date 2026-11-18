package jeju.datahub.batch.risknoti;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import jeju.datahub.batch.risknoti.RiskCriteriaCache.RiskCriteria;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 판정 산식·단계 선택·문구를 <b>경계값까지</b> 고정한다.
 *
 * <p>🔴 <b>기준을 픽스처로 직접 심는다</b> — 실 DB({@code parking.tb_risk_stp_noti_crtr})를 읽지
 * 않는다. 기준은 화면({@code PUT /api/risk-criteria})에서 언제든 바뀌므로 "현재 30/20/10" 을
 * 기대값의 근거로 삼으면 <b>남이 기준을 바꾼 날 테스트가 깨진다.</b> 여기서 지키는 것은 산식과
 * 선택 규칙이지 운영 설정값이 아니다(실 DB 값을 쓰는 검증은 E2E 의 몫이다).
 *
 * <p><b>DB 를 건드리지 않는다</b>(Spring 컨텍스트도 띄우지 않는다) — {@code @AfterEach} 정리가 필요 없다.
 *
 * <p>⚠️ Processor 를 빈으로 주입받지 않고 <b>직접 생성</b>한다. {@code @StepScope} 라 Step 실행
 * 밖에서는 프록시가 풀리지 않고, 여기서 검증하려는 것은 Step 조립이 아니라 순수 판정 로직이다.
 */
class RiskAlertJudgeProcessorTest {

    /** 판정일 — {@code ocrn_dt} 가 '이 날 00:00:00' 이어야 한다(D1·D6). */
    private static final String TARGET_DATE = "2025-12-31";

    /**
     * 기준 픽스처 — 시드와 같은 값(CRIT 30 / WARN 20 / CAUT 10)이지만 <b>여기서 심은 값</b>이다.
     * 일부러 오름차순으로 넣어, 캐시가 "임계가 가장 큰 매치"를 고르는지(D5)가 입력 순서에
     * 좌우되지 않음을 함께 확인한다.
     */
    private static final List<RiskCriteria> CRITERIA = List.of(
            new RiskCriteria("CAUT", new BigDecimal("10.00"), 0),
            new RiskCriteria("WARN", new BigDecimal("20.00"), 0),
            new RiskCriteria("CRIT", new BigDecimal("30.00"), 0));

    private final RiskAlertJudgeProcessor processor =
            new RiskAlertJudgeProcessor(new RiskCriteriaCache(CRITERIA), TARGET_DATE);

    /**
     * 🔴 경계값 8행 표다.
     *
     * <p>핵심은 셋이다.
     * <ul>
     *   <li>{@code 20/0} — <b>분모 하한 1</b>(D2). 스킵했다면 최악의 급증을 통째로 놓친다</li>
     *   <li>{@code 13/10}·{@code 12/10}·{@code 11/10} — 경계는 <b>"기준값 이상"</b> 이면 통과다.
     *       {@code double} 로 계산하면 여기서 판정이 뒤집힌다</li>
     *   <li>{@code 10/10}·{@code 0/0}·{@code 5/10} — 못 넘으면 {@code null}(알림 없음). <b>정상 경로</b>다</li>
     * </ul>
     */
    @ParameterizedTest(name = "당일 {0} / 전일 {1} → {2}% / {3}")
    @CsvSource({
            "20,  0, 1900.00, CRIT",   // 🔴 분모 하한 1 — D2 핵심
            "11,  1, 1000.00, CRIT",
            "13, 10,   30.00, CRIT",   // 경계 = 기준값 이상
            "12, 10,   20.00, WARN",   // 경계
            "11, 10,   10.00, CAUT",   // 경계
            "10, 10,    0.00, ",       // 알림 없음
            " 0,  0, -100.00, ",       // 알림 없음
            " 5, 10,  -50.00, "        // 감소는 알림이 아니다
    })
    @DisplayName("🔴 경계값 8행 — 증가율과 단계가 표대로 나온다")
    void judgesBoundaryValues(long targetCnt, long prevCnt, BigDecimal expectedRate, String expectedLevel) {
        RiskAlertRecord result = processor.process(row(targetCnt, prevCnt));

        if (expectedLevel == null) {
            assertThat(result).as("어떤 기준도 못 넘으면 null 이다(예외가 아니다)").isNull();
            return;
        }
        assertThat(result).isNotNull();
        assertThat(result.incrsRt()).isEqualByComparingTo(expectedRate);
        assertThat(result.incrsRt().scale()).as("NUMERIC(7,2) 와 같은 scale").isEqualTo(2);
        assertThat(result.riskLvlCd()).isEqualTo(expectedLevel);
    }

    @Test
    @DisplayName("cvlcpt_cnt 는 당일 건수(보정 전 실제 값)다 — 분모 보정은 증가율에만 쓴다")
    void keepsRawTargetCount() {
        RiskAlertRecord result = processor.process(row(20, 0));

        assertThat(result.cvlcptCnt()).isEqualTo(20L);
        assertThat(result.emdCd()).isEqualTo("50110137");
    }

    @Test
    @DisplayName("🔴 ocrn_dt 는 '판정일 00:00:00' 이다 — 실행 시각이면 upsert 키(emd_cd, ocrn_dt)가 매번 어긋난다")
    void stampsJudgeDateMidnight() {
        RiskAlertRecord result = processor.process(row(20, 0));

        assertThat(result.ocrnDt()).isEqualTo(LocalDateTime.of(2025, 12, 31, 0, 0, 0));
    }

    @Test
    @DisplayName("targetDate 가 없으면 '어제' 로 떨어진다 — Reader 와 같은 규칙을 쓴다")
    void fallsBackToYesterday() {
        RiskAlertJudgeProcessor noParam =
                new RiskAlertJudgeProcessor(new RiskCriteriaCache(CRITERIA), null);

        assertThat(noParam.process(row(20, 0)).ocrnDt())
                .isEqualTo(LocalDate.now(RiskAlertProperties.SEOUL).minusDays(1).atStartOfDay());
    }

    @Test
    @DisplayName("🔴 문구는 26.5% · 45% 다 — '45.% 증가' 가 나오면 검증이 깨진다")
    void formatsNotiCtt() {
        // 253/200 = 26.50% → 소수 한 자리만 남는다
        assertThat(processor.process(row(253, 200)).notiCtt()).isEqualTo("연동 민원 26.5% 증가");
        // 29/20 = 45.00% → 소수점째 사라진다
        assertThat(processor.process(row(29, 20)).notiCtt()).isEqualTo("연동 민원 45% 증가");
        // 20/0 = 1900.00% → 정수 표기
        assertThat(processor.process(row(20, 0)).notiCtt()).isEqualTo("연동 민원 1900% 증가");

        // rtrim(to_char(...,'FM990.99'),'.') 과 같은 결과여야 한다(D8).
        assertThat(RiskAlertJudgeProcessor.trimZeros(new BigDecimal("26.50"))).isEqualTo("26.5");
        assertThat(RiskAlertJudgeProcessor.trimZeros(new BigDecimal("45.00"))).isEqualTo("45");
        assertThat(RiskAlertJudgeProcessor.trimZeros(new BigDecimal("0.00"))).isEqualTo("0");
        assertThat(RiskAlertJudgeProcessor.trimZeros(new BigDecimal("10.05"))).isEqualTo("10.05");
    }

    @Test
    @DisplayName("문구에 '.%' 가 남지 않는다 — noti_ctt LIKE '%.\\%%' 가 0이어야 한다")
    void neverLeavesDanglingDecimalPoint() {
        for (long targetCnt = 11; targetCnt <= 400; targetCnt++) {
            RiskAlertRecord result = processor.process(row(targetCnt, 10));
            assertThat(result).isNotNull();
            assertThat(result.notiCtt()).doesNotContain(".%");
        }
    }

    @Test
    @DisplayName("🔴 기준 스냅샷(incrs_rt_thrsh)은 '판정된 그 단계' 의 임계다 — 조회가 다시 만들지 않는다(D8)")
    void snapshotsMatchedThreshold() {
        assertThat(processor.process(row(13, 10)).incrsRtThrsh()).isEqualByComparingTo("30.00");
        assertThat(processor.process(row(12, 10)).incrsRtThrsh()).isEqualByComparingTo("20.00");
        assertThat(processor.process(row(11, 10)).incrsRtThrsh()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("🔴 증가율이 NUMERIC(7,2) 상한을 넘으면 그 행만 스킵한다 — 판정일을 같이 실어 보낸다(D9)")
    void skipsRateOverflow() {
        // 전일 1건 → 당일 1,000,002건 = 100000000% (상한 99999.99 초과)
        RiskAlertTargetRow overflow = row(1_000_002, 1);

        assertThatThrownBy(() -> processor.process(overflow))
                .isInstanceOf(RiskAlertSkippableException.class)
                .satisfies(thrown -> {
                    RiskAlertSkippableException skip = (RiskAlertSkippableException) thrown;
                    assertThat(skip.reason()).isEqualTo("RATE_OVERFLOW");
                    assertThat(skip.emdCd()).isEqualTo("50110137");
                    // 🔴 스킵 표 PK 가 (job_se, trgt_id, trgt_de) 라 판정일이 없으면 기록 자체가 불가능하다.
                    assertThat(skip.judgeDate()).isEqualTo(LocalDate.of(2025, 12, 31));
                    assertThat(skip.note()).contains("target_cnt=1000002", "prev_cnt=1");
                });
    }

    @Test
    @DisplayName("상한 경계(99999.99)는 스킵이 아니다 — 초과일 때만 건너뛴다")
    void keepsRateAtUpperBound() {
        // 전일 1건 → 당일 1001건 = 100000.00% 는 초과(스킵), 1000건 = 99900.00% 는 저장 가능
        assertThat(processor.process(row(1000, 1)).incrsRt()).isEqualByComparingTo("99900.00");
        assertThatThrownBy(() -> processor.process(row(1001, 1)))
                .isInstanceOf(RiskAlertSkippableException.class);
    }

    @Test
    @DisplayName("🔴 기준 로드 SQL 은 세 컬럼뿐이다 — sort_seq 를 넣으면 첫 실행에 SQL 오류로 죽는다(D5)")
    void criteriaSqlHasNoSortSeq() {
        String sql = RiskCriteriaCache.LOAD_SQL.toLowerCase(java.util.Locale.ROOT);

        assertThat(sql).doesNotContain("sort_seq");     // tb_risk_stp_noti_crtr 에 없는 컬럼이다
        assertThat(sql).doesNotContain("tb_com_cd");    // 조인하지 않는다 — 판정에 필요한 값은 incrs_rt 하나다
        assertThat(sql).contains("order by incrs_rt desc");   // 첫 매치 = 최고 임계의 근거
    }

    @Test
    @DisplayName("기준값이 비어 있는(NULL) 단계는 판정에서 빠진다 — DESC 정렬에서 NULL 이 맨 앞이라 그냥 두면 NPE 다")
    void ignoresCriteriaWithoutThreshold() {
        RiskCriteriaCache cache = new RiskCriteriaCache(List.of(
                new RiskCriteria("CRIT", null, 0),
                new RiskCriteria("WARN", new BigDecimal("20.00"), 0)));

        assertThat(cache.criteria()).extracting(RiskCriteria::riskLvlCd).containsExactly("WARN");
        assertThat(cache.highestMatched(new BigDecimal("99.00"), 10L, 10L).riskLvlCd()).isEqualTo("WARN");
    }

    @Test
    @DisplayName("⚠️ 비단조 기준(WARN > CRIT)에서도 '넘긴 것 중 최고 임계' 를 고른다 — 단조성은 DB·API 가 강제하지 않는다")
    void picksHighestThresholdEvenWhenNotMonotonic() {
        RiskAlertJudgeProcessor odd = new RiskAlertJudgeProcessor(new RiskCriteriaCache(List.of(
                new RiskCriteria("CRIT", new BigDecimal("30.00"), 0),
                new RiskCriteria("WARN", new BigDecimal("50.00"), 0),   // 설정 오류지만 막을 장치가 없다
                new RiskCriteria("CAUT", new BigDecimal("10.00"), 0))), TARGET_DATE);

        // 16/10 = 60% → 50 을 넘었으니 임계가 가장 큰 WARN 이다(sort_seq 를 봤다면 CRIT 이 나온다)
        assertThat(odd.process(row(16, 10)).riskLvlCd()).isEqualTo("WARN");
        assertThat(odd.process(row(16, 10)).incrsRtThrsh()).isEqualByComparingTo("50.00");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 🔴 최소 민원수 하한(min_cvlcpt_cnt). 표본이 작으면 증가율이 무의미해서
    //    "전일 1건 → 당일 3건 = 200%" 가 "전일 20건 → 당일 25건 = 25%" 보다 심각하게 뜨던 문제를 막는다.
    //    하한은 단계마다 다를 수 있으므로(D3) 판정 순회 안에서 단계별로 본다.
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 하한 10 · 1 → 3건(200%)은 알림이 없다 — 표본이 작으면 증가율이 커도 판정하지 않는다")
    void suppressesHighRateOnTinySample() {
        assertThat(processorWithMin(10).process(row(3, 1))).isNull();
    }

    @Test
    @DisplayName("🔴 하한 10 · 0 → 2건(100%)도 알림이 없다 — 분모 보정(D2)이 만든 큰 증가율도 하한에 걸린다")
    void suppressesZeroPrevOnTinySample() {
        assertThat(processorWithMin(10).process(row(2, 0))).isNull();
    }

    @Test
    @DisplayName("하한 10 · 10 → 15건(50%)은 CRIT — 당일·전일이 하한을 넘으면 최고 임계가 잡힌다")
    void judgesCritWhenAboveMinimum() {
        RiskAlertRecord result = processorWithMin(10).process(row(15, 10));

        assertThat(result).isNotNull();
        assertThat(result.incrsRt()).isEqualByComparingTo("50.00");
        assertThat(result.riskLvlCd()).isEqualTo("CRIT");
    }

    @Test
    @DisplayName("하한 10 · 20 → 25건(25%)은 WARN — 표본이 큰 실제 급증이 하한에 걸리지 않는다")
    void judgesWarnOnLargeSample() {
        RiskAlertRecord result = processorWithMin(10).process(row(25, 20));

        assertThat(result).isNotNull();
        assertThat(result.incrsRt()).isEqualByComparingTo("25.00");
        assertThat(result.riskLvlCd()).isEqualTo("WARN");
    }

    @Test
    @DisplayName("🔴 하위호환 — 하한 0 이면 1 → 3건(200%)이 종전과 똑같이 CRIT 이다")
    void keepsLegacyBehaviourWhenMinimumIsZero() {
        RiskAlertRecord result = processorWithMin(0).process(row(3, 1));

        assertThat(result).isNotNull();
        assertThat(result.riskLvlCd()).isEqualTo("CRIT");
    }

    @Test
    @DisplayName("당일이 하한과 같으면 전일도 하한 이상이라 증가가 없어 알림이 없다(10 → 10건, 0%)")
    void noAlertWhenTargetEqualsMinimumAndPrevPasses() {
        // 증가율 기준은 0 초과라, 전일 >= 하한 이면 알림이 나는 행의 당일은 항상 하한보다 크다.
        assertThat(processorWithMin(10).process(row(10, 10))).isNull();
    }

    @Test
    @DisplayName("🔴 경계 — 전일 건수가 하한과 같으면 통과다(10 → 13건 → CRIT)")
    void passesWhenPrevCountEqualsMinimum() {
        RiskAlertRecord result = processorWithMin(10).process(row(13, 10));

        assertThat(result).isNotNull();
        assertThat(result.riskLvlCd()).isEqualTo("CRIT");
    }

    @ParameterizedTest(name = "하한 10 · 전일 {1} → 당일 {0} 은 알림 없음")
    @CsvSource({"40, 9", "43, 3", "47, 0"})
    @DisplayName("🔴 전일이 하한 미만이면 증가율이 커도 알림이 없다. 분모 보정값이 아니라 실제 전일 건수로 본다")
    void suppressesWhenPrevCountBelowMinimum(long targetCnt, long prevCnt) {
        assertThat(processorWithMin(10).process(row(targetCnt, prevCnt))).isNull();
    }

    @Test
    @DisplayName("🔴 하한 1 · 전일 0건은 탈락한다. 분모 보정값(1)을 넘기면 통과해 버린다")
    void comparesRawPrevCountNotCorrectedDenominator() {
        assertThat(processorWithMin(1).process(row(20, 0))).isNull();
    }

    @Test
    @DisplayName("하한을 넘어도 증가율이 미달이면 알림이 없다(10 → 9건, −10%) — 두 조건은 AND 다")
    void stillRequiresRateEvenAboveMinimum() {
        assertThat(processorWithMin(10).process(row(9, 10))).isNull();
    }

    @Test
    @DisplayName("🔴 하한은 단계마다 따로 본다 — 8 → 12건(50%)은 CRIT 하한 10 미달(전일)이라 WARN(하한 7)으로 내려간다(D3)")
    void fallsBackToLowerLevelWhenItsMinimumIsMet() {
        RiskAlertJudgeProcessor perLevel = new RiskAlertJudgeProcessor(new RiskCriteriaCache(List.of(
                new RiskCriteria("CAUT", new BigDecimal("10.00"), 5),
                new RiskCriteria("WARN", new BigDecimal("20.00"), 7),
                new RiskCriteria("CRIT", new BigDecimal("30.00"), 10))), TARGET_DATE);

        RiskAlertRecord result = perLevel.process(row(12, 8));

        assertThat(result).isNotNull();
        assertThat(result.incrsRt()).isEqualByComparingTo("50.00");
        assertThat(result.riskLvlCd()).as("CRIT 은 하한 10 미달, WARN 은 하한 7 통과").isEqualTo("WARN");
        assertThat(result.incrsRtThrsh()).isEqualByComparingTo("20.00");
    }

    /** 세 단계에 <b>같은 하한</b>을 심은 Processor. 기준값은 픽스처와 같은 CRIT 30 / WARN 20 / CAUT 10 이다. */
    private static RiskAlertJudgeProcessor processorWithMin(int minCvlcptCnt) {
        return new RiskAlertJudgeProcessor(new RiskCriteriaCache(List.of(
                new RiskCriteria("CAUT", new BigDecimal("10.00"), minCvlcptCnt),
                new RiskCriteria("WARN", new BigDecimal("20.00"), minCvlcptCnt),
                new RiskCriteria("CRIT", new BigDecimal("30.00"), minCvlcptCnt))), TARGET_DATE);
    }

    /** 읍면동 하나의 집계 행. 코드·이름은 실데이터의 '연동'(2025-12-31 기준 당일 25 / 전일 27)을 쓴다. */
    private static RiskAlertTargetRow row(long targetCnt, long prevCnt) {
        return new RiskAlertTargetRow("50110137", "연동", targetCnt, prevCnt);
    }
}
