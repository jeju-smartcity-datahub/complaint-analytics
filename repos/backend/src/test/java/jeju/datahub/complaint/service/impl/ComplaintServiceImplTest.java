package jeju.datahub.complaint.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jeju.datahub.complaint.service.ComplaintKpiRow;
import jeju.datahub.complaint.service.ComplaintKpiVO;
import jeju.datahub.complaint.service.ComplaintRequest;
import jeju.datahub.complaint.service.ComplaintUnit;
import jeju.datahub.complaint.service.EmdCompareRow;
import jeju.datahub.complaint.service.EmdCompareVO;
import jeju.datahub.complaint.service.EmdTopTypeRow;
import jeju.datahub.complaint.service.NegEmdCompareRow;
import jeju.datahub.complaint.service.NegEmdCompareVO;

/**
 * {@link ComplaintServiceImpl} 단위 테스트 (TSK-3880 §7·§8 검증).
 *
 * <p>DB 없이 {@link ComplaintMapper} 를 Mockito 로 mock 해 서비스의 rank / deltaPct /
 * deltaPctInferred / topTypeRatio / ratio 계산 로직만 검증한다. 매퍼의 PostgreSQL FILTER·DISTINCT ON
 * 동작은 본 테스트의 대상이 아니다(별도 통합 테스트가 담당).
 *
 * <p>같은 패키지의 다른 통합 테스트({@code ComplaintDashboardIntegrationTest})와 달리
 * {@code @SpringBootTest} 가 없다 — DB 도 매퍼도 안 띄운다.
 */
@ExtendWith(MockitoExtension.class)
class ComplaintServiceImplTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Mock
    private ComplaintMapper complaintMapper;

    @InjectMocks
    private ComplaintServiceImpl sut;

    /**
     * §3 KPI 평면 row fixture — totalSntmntCount=20, negative=8 → negativityRatio=0.4
     */
    private static ComplaintKpiRow kpiRow() {
        return new ComplaintKpiRow(
                120L,    // totalCount
                6L,      // positiveCount
                6L,      // neutralCount
                8L,      // negativeCount
                20L      // totalSntmntCount
        );
    }

    /**
     * §3 KPI 의 selectKpi 만 stub 한다. 기본은 {@link #stubKpiWithCounts} 를 쓰고, enforcement/
     * shortageCount 값 자체가 검증 대상이 아닌 케이스에서만 이 헬퍼를 쓴다 — 그 두 매퍼는
     * {@code getKpi} 에서 항상 호출되지만 Mockito strict 모드는 <b>stub 되지 않은 호출</b>에는
     * 반응하지 않으므로 0L 반환으로 안전하게 통과한다.
     * <ul>
     *   <li>{@code selectEmdCompare} 가 1~N 행 결과를 돌려주는 케이스 — enforcement/shortageCount
     *       가 검증 대상이 아니므로 이 헬퍼로 충분. §7 emdCompare_* 테스트들.</li>
     *   <li>값 검증을 해야 하면 {@link #stubKpiWithCounts} 를 쓰거나 enforcement/shortageCount 를
     *       직접 stub 한다 — {@code kpi_enforcementCount_passed_through} / {@code kpi_shortageCount_passed_through}.</li>
     *   <li>{@code selectEmdCompare} 결과가 비어 있으면 {@code selectTopTypeByEmd} 는 호출되지 않는다
     *       ({@link ComplaintServiceImpl#buildEmdCompare} 의 빈 리스트 단락 반환) — 그 케이스에서는
     *       {@code selectTopTypeByEmd} 를 stub 하지 않는다.</li>
     * </ul>
     */
    private void stubKpi(ComplaintKpiRow row) {
        when(complaintMapper.selectKpi(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(row);
    }

    /**
     * §3 KPI 의 enforcementCount/shortageCount placeholder 0 으로 stub (selectKpi + 두 카운트).
     * Mockito strict 모드가 "stub 했지만 호출되지 않은 메서드" 를 잡아내므로, 빈 리스트를 기대하는
     * emdCompare/negEmdCompare 케이스처럼 이 두 매퍼 호출이 명시적으로 일어나는 케이스에 디폴트로
     * 쓴다. 값 자체를 검증하는 테스트는 직접 stub 한다.
     */
    private void stubKpiWithCounts(ComplaintKpiRow row) {
        when(complaintMapper.selectKpi(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(row);
        when(complaintMapper.selectEnforcementCount(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(0L);
        when(complaintMapper.selectShortageCount(any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(0L);
    }

    private static ComplaintRequest request() {
        ComplaintRequest req = new ComplaintRequest();
        req.setUnit(ComplaintUnit.YESTERDAY); // unit 은 있지만 resolveRange 가 startDt/endDt 우선
        return req;
    }

    /**
     * §7 row fixture. 매퍼가 POJO + setter 로 변경되어 positional 생성자 대신 빌더처럼 사용.
     * SQL alias 명과 setter 명이 1:1 — record 컴포넌트명 → setter 프로퍼티명.
     */
    private static EmdCompareRow emRow(String emdCd, String emdNm, long cvlcptCnt, long prevCnt) {
        EmdCompareRow r = new EmdCompareRow();
        r.setEmdCd(emdCd);
        r.setEmdNm(emdNm);
        r.setCvlcptCnt(cvlcptCnt);
        r.setPrevCnt(prevCnt);
        return r;
    }

    /**
     * §8 row fixture. POJO + setter. (하위 호환 — sentimentDist/totalCount 는 0 기본값)
     */
    private static NegEmdCompareRow negRow(String emdCd, String emdNm, long negativeCount, long prevNegativeCount, Double negativeRatio) {
        NegEmdCompareRow r = new NegEmdCompareRow();
        r.setEmdCd(emdCd);
        r.setEmdNm(emdNm);
        r.setNegativeCount(negativeCount);
        r.setPrevNegativeCount(prevNegativeCount);
        r.setNegativeRatio(negativeRatio);
        return r;
    }

    /**
     * §8 row fixture — sentiment 분포 포함 (가이드 §8 envelope 의 totalCount +
     * sentimentDist.{positive,neutral,negative} 검증용).
     *
     * <p>2026-09-04 갱신 — 부정 분포는 별도 필드 없이 {@code negativeCount} 가
     * 그대로 재사용되므로 fixture 시그니처에서 {@code negativeBySntmnt} 인자를
     * 제거. 호출부에서 {@code negativeCount} 와 동일한 값을 전달하면 됨.
     */
    private static NegEmdCompareRow negRowWithSentiment(
            String emdCd, String emdNm,
            long negativeCount, long prevNegativeCount, Double negativeRatio,
            long totalCount, long positiveCount, long neutralCount
    ) {
        NegEmdCompareRow r = new NegEmdCompareRow();
        r.setEmdCd(emdCd);
        r.setEmdNm(emdNm);
        r.setNegativeCount(negativeCount);
        r.setPrevNegativeCount(prevNegativeCount);
        r.setNegativeRatio(negativeRatio);
        r.setTotalCount(totalCount);
        r.setPositiveCount(positiveCount);
        r.setNeutralCount(neutralCount);
        return r;
    }

    // ------------------------------------------------------------------------
    // §3 KPI
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("§3. 감성 분모=0 이면 negativityRatio=0.0")
    void kpi_negativityRatio_zero_when_totalSntmntCount_is_zero() {
        stubKpiWithCounts(new ComplaintKpiRow(0L, 0L, 0L, 0L, 0L));
        when(complaintMapper.selectEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());
        when(complaintMapper.selectNegEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());

        ComplaintKpiVO vo = sut.getKpi(request());

        assertThat(vo.negativityRatio()).isEqualTo(0.0);
        assertThat(vo.emdCompare()).isEmpty();
        assertThat(vo.negEmdCompare()).isEmpty();
    }

    @Test
    @DisplayName("§3. 감성 분포 분해 — POS/NEU/NEG 가 응답 sentimentDist 로 전달된다")
    void kpi_sentimentDist_passed_through() {
        stubKpiWithCounts(kpiRow());
        when(complaintMapper.selectEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());
        when(complaintMapper.selectNegEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());

        ComplaintKpiVO vo = sut.getKpi(request());

        assertThat(vo.sentimentDist().positive()).isEqualTo(6L);
        assertThat(vo.sentimentDist().neutral()).isEqualTo(6L);
        assertThat(vo.sentimentDist().negative()).isEqualTo(8L);
    }

    @Test
    @DisplayName("§3. enforcementCount 가 양수면 응답에 그대로 전달 (2026-08-31 SQL 교체 후)")
    void kpi_enforcementCount_passed_through() {
        // selectEnforcementCount 가 6440 으로 stub 되어야 하므로 stubKpiWithCounts 를 쓰지 않는다 —
        // enforcementCount 값을 직접 검증하므로 stubKpiWithCounts(=enforcement=0) 대신
        // selectKpi 만 stub 하고 enforcement/shortageCount 를 직접 stub 한다 — 그 자리에서 0 으로
        // stub 한 뒤 6440 으로 덮어쓰면 첫 stub 이 "불필요" 가 돼 strict 모드가 잡아낸다.
        when(complaintMapper.selectKpi(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(kpiRow());
        // I 라운드에서 placeholder SELECT 0 → 원천 기반 SQL 로 교체됨. 6440 같은 양수가
        // 응답에 정확히 반영되는지 검증 (2026-08-31 실측 12월 제주시 단속 zone 수).
        when(complaintMapper.selectEnforcementCount(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(6440L);
        when(complaintMapper.selectShortageCount(any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(0L);
        when(complaintMapper.selectEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());
        when(complaintMapper.selectNegEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());

        ComplaintKpiVO vo = sut.getKpi(request());

        assertThat(vo.enforcementCount()).isEqualTo(6440L);
        assertThat(vo.shortageCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("§3. shortageCount 가 양수면 응답에 그대로 전달 (2026-08-31 SQL 교체 후)")
    void kpi_shortageCount_passed_through() {
        // shortageCount 값을 직접 검증하므로 stubKpiWithCounts(=shortage=0) 대신
        // selectKpi 만 stub 하고 enforcement/shortageCount 를 직접 stub 한다.
        when(complaintMapper.selectKpi(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(kpiRow());
        when(complaintMapper.selectEnforcementCount(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(0L);
        // J 라운드에서 1차 대체 SQL 로 교체됨. 30 같은 양수가 응답에 반영되는지 검증.
        when(complaintMapper.selectShortageCount(any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(30L);
        when(complaintMapper.selectEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());
        when(complaintMapper.selectNegEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());

        ComplaintKpiVO vo = sut.getKpi(request());

        assertThat(vo.shortageCount()).isEqualTo(30L);
        assertThat(vo.enforcementCount()).isEqualTo(0L);
    }

    // ------------------------------------------------------------------------
    // §7 emdCompare
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("§7. 매퍼 결과 빈 경우 emdCompare=빈 리스트")
    void emdCompare_empty_when_no_rows() {
        stubKpiWithCounts(kpiRow());
        when(complaintMapper.selectEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());
        when(complaintMapper.selectNegEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());

        ComplaintKpiVO vo = sut.getKpi(request());

        assertThat(vo.emdCompare()).isEmpty();
    }

    @Test
    @DisplayName("§7. prevCount=0 이면 deltaPct=count*100, deltaPctInferred=true")
    void emdCompare_prevCount_zero_inferred() {
        stubKpi(kpiRow());
        when(complaintMapper.selectEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of(
                emRow("50110137", "연동", 5L, 0L)
        ));
        when(complaintMapper.selectTopTypeByEmd(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());
        when(complaintMapper.selectNegEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());

        ComplaintKpiVO vo = sut.getKpi(request());

        EmdCompareVO row = vo.emdCompare().get(0);
        assertThat(row.rank()).isEqualTo(1);
        assertThat(row.deltaPct()).isEqualTo(500);   // (5-0)/1*100 = 500
        assertThat(row.deltaPctInferred()).isTrue();
    }

    @Test
    @DisplayName("§7. prevCount>0 이면 deltaPct=(count-prev)/prev*100, deltaPctInferred=false")
    void emdCompare_normal_delta_pct() {
        stubKpi(kpiRow());
        when(complaintMapper.selectEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of(
                emRow("50110137", "연동", 13L, 10L)
        ));
        when(complaintMapper.selectTopTypeByEmd(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());
        when(complaintMapper.selectNegEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());

        ComplaintKpiVO vo = sut.getKpi(request());

        EmdCompareVO row = vo.emdCompare().get(0);
        assertThat(row.deltaPct()).isEqualTo(30);    // (13-10)/10*100 = 30
        assertThat(row.deltaPctInferred()).isFalse();
    }

    @Test
    @DisplayName("§7. 매퍼 정렬 순서대로 1-base rank 결정성 부여")
    void emdCompare_rank_assigned_by_mapper_order() {
        stubKpi(kpiRow());
        when(complaintMapper.selectEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of(
                emRow("50110137", "연동",  52L, 40L),
                emRow("50110138", "노형동", 50L, 30L),
                emRow("50110139", "이도동", 30L, 20L)
        ));
        when(complaintMapper.selectTopTypeByEmd(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());
        when(complaintMapper.selectNegEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());

        ComplaintKpiVO vo = sut.getKpi(request());

        assertThat(vo.emdCompare()).hasSize(3);
        assertThat(vo.emdCompare().get(0).rank()).isEqualTo(1);
        assertThat(vo.emdCompare().get(0).emdCd()).isEqualTo("50110137");
        assertThat(vo.emdCompare().get(1).rank()).isEqualTo(2);
        assertThat(vo.emdCompare().get(2).rank()).isEqualTo(3);
    }

    @Test
    @DisplayName("§7. topByEmd 매칭 시 topTypeCd/topTypeNm/topTypeRatio 채움")
    void emdCompare_topType_matched() {
        stubKpi(kpiRow());
        when(complaintMapper.selectEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of(
                emRow("50110137", "연동", 50L, 40L)
        ));
        when(complaintMapper.selectTopTypeByEmd(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of(
                new EmdTopTypeRow("50110137", "02", 21L, "인도·횡단보도 점유")
        ));
        when(complaintMapper.selectNegEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());

        ComplaintKpiVO vo = sut.getKpi(request());

        EmdCompareVO row = vo.emdCompare().get(0);
        assertThat(row.topTypeCd()).isEqualTo("02");
        assertThat(row.topTypeNm()).isEqualTo("인도·횡단보도 점유");
        assertThat(row.topTypeRatio()).isEqualTo(0.42);   // 21/50
    }

    @Test
    @DisplayName("§7. topByEmd 매칭 실패 (zone 에 최다 유형 없음) 면 topType*=null, ratio=0.0")
    void emdCompare_topType_unmatched() {
        stubKpi(kpiRow());
        when(complaintMapper.selectEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of(
                emRow("50110137", "연동", 50L, 40L)
        ));
        when(complaintMapper.selectTopTypeByEmd(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of(
                new EmdTopTypeRow("50110999", "01", 30L, "다른 zone")
        ));
        when(complaintMapper.selectNegEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());

        ComplaintKpiVO vo = sut.getKpi(request());

        EmdCompareVO row = vo.emdCompare().get(0);
        assertThat(row.topTypeCd()).isNull();
        assertThat(row.topTypeNm()).isNull();
        assertThat(row.topTypeRatio()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("§7. count=0 이면 topTypeRatio=0.0 (분모 보호)")
    void emdCompare_zero_count_zero_topTypeRatio() {
        stubKpi(kpiRow());
        // 매퍼가 HAVING 으로 count=0 행을 거르므로 도달하지 않지만, count 만 0 인 row 가정
        when(complaintMapper.selectEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of(
                emRow("50110137", "연동", 0L, 0L)
        ));
        when(complaintMapper.selectTopTypeByEmd(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of(
                new EmdTopTypeRow("50110137", "02", 0L, "유형")
        ));
        when(complaintMapper.selectNegEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());

        ComplaintKpiVO vo = sut.getKpi(request());

        assertThat(vo.emdCompare().get(0).topTypeRatio()).isEqualTo(0.0);
    }

    // ------------------------------------------------------------------------
    // §8 negEmdCompare
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("§8. 매퍼 결과 빈 경우 negEmdCompare=빈 리스트")
    void negEmdCompare_empty_when_no_rows() {
        stubKpiWithCounts(kpiRow());
        when(complaintMapper.selectEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());
        when(complaintMapper.selectNegEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());

        ComplaintKpiVO vo = sut.getKpi(request());

        assertThat(vo.negEmdCompare()).isEmpty();
    }

    @Test
    @DisplayName("§8. prevCount=0 이면 deltaPct=count*100, deltaPctInferred=true")
    void negEmdCompare_prevCount_zero_inferred() {
        stubKpiWithCounts(kpiRow());
        when(complaintMapper.selectEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());
        when(complaintMapper.selectNegEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of(
                negRow("50110137", "연동", 4L, 0L, 0.42)
        ));

        ComplaintKpiVO vo = sut.getKpi(request());

        NegEmdCompareVO row = vo.negEmdCompare().get(0);
        assertThat(row.rank()).isEqualTo(1);
        assertThat(row.deltaPct()).isEqualTo(400);
        assertThat(row.deltaPctInferred()).isTrue();
        assertThat(row.negativeRatio()).isEqualTo(0.42);
    }

    @Test
    @DisplayName("§8. prevCount>0 이면 deltaPct=(count-prev)/prev*100, deltaPctInferred=false")
    void negEmdCompare_normal_delta_pct() {
        stubKpiWithCounts(kpiRow());
        when(complaintMapper.selectEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());
        when(complaintMapper.selectNegEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of(
                negRow("50110137", "연동", 18L, 15L, 0.40)
        ));

        ComplaintKpiVO vo = sut.getKpi(request());

        NegEmdCompareVO row = vo.negEmdCompare().get(0);
        assertThat(row.deltaPct()).isEqualTo(20);   // (18-15)/15*100 = 20
        assertThat(row.deltaPctInferred()).isFalse();
    }

    @Test
    @DisplayName("§8. ratio=null 인 row 는 0.0 으로 치환 (SQL NULLIF 분모=0 결과)")
    void negEmdCompare_null_ratio_to_zero() {
        stubKpiWithCounts(kpiRow());
        when(complaintMapper.selectEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());
        when(complaintMapper.selectNegEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of(
                negRow("50110137", "연동", 1L, 1L, null)
        ));

        ComplaintKpiVO vo = sut.getKpi(request());

        assertThat(vo.negEmdCompare().get(0).negativeRatio()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("§8. 매퍼 정렬 순서대로 1-base rank 결정성 부여")
    void negEmdCompare_rank_assigned_by_mapper_order() {
        stubKpiWithCounts(kpiRow());
        when(complaintMapper.selectEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());
        when(complaintMapper.selectNegEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of(
                negRow("50110137", "연동",  18L, 15L, 0.40),
                negRow("50110138", "노형동", 12L, 10L, 0.30),
                negRow("50110139", "이도동",  6L,  5L, 0.20)
        ));

        ComplaintKpiVO vo = sut.getKpi(request());

        assertThat(vo.negEmdCompare()).hasSize(3);
        assertThat(vo.negEmdCompare().get(0).rank()).isEqualTo(1);
        assertThat(vo.negEmdCompare().get(0).emdCd()).isEqualTo("50110137");
        assertThat(vo.negEmdCompare().get(1).rank()).isEqualTo(2);
        assertThat(vo.negEmdCompare().get(2).rank()).isEqualTo(3);
    }

    @Test
    @DisplayName("§8. totalCount + sentimentDist 가 VO 로 정확히 매핑 (envelope 동봉 검증)")
    void negEmdCompare_sentiment_dist_mapped_to_vo() {
        stubKpiWithCounts(kpiRow());
        when(complaintMapper.selectEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());
        when(complaintMapper.selectNegEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of(
                // 연동: pos=3, neu=5, neg=2 → totalCount=10, negativeCount=2, ratio=0.20
                negRowWithSentiment("50110137", "연동",  2L, 1L, 0.20, 10L, 3L, 5L),
                // 노형동: pos=0, neu=8, neg=2 → totalCount=10, negativeCount=2, ratio=0.20
                negRowWithSentiment("50110138", "노형동", 2L, 3L, 0.20, 10L, 0L, 8L)
        ));

        ComplaintKpiVO vo = sut.getKpi(request());

        assertThat(vo.negEmdCompare()).hasSize(2);

        // 연동 — totalCount / sentimentDist 가 VO 로 전달되는지 검증
        NegEmdCompareVO yundong = vo.negEmdCompare().get(0);
        assertThat(yundong.negativeCount()).isEqualTo(2L);
        assertThat(yundong.totalCount()).isEqualTo(10L);
        assertThat(yundong.sentimentDist().positive()).isEqualTo(3L);
        assertThat(yundong.sentimentDist().neutral()).isEqualTo(5L);
        assertThat(yundong.sentimentDist().negative()).isEqualTo(2L);
        assertThat(yundong.negativeRatio()).isEqualTo(0.20);

        // 노형동 — prev 변경분만 다른 케이스, sentimentDist 매핑 동일 검증
        NegEmdCompareVO nohyeong = vo.negEmdCompare().get(1);
        assertThat(nohyeong.negativeCount()).isEqualTo(2L);
        assertThat(nohyeong.totalCount()).isEqualTo(10L);
        assertThat(nohyeong.sentimentDist().positive()).isEqualTo(0L);
        assertThat(nohyeong.sentimentDist().neutral()).isEqualTo(8L);
        assertThat(nohyeong.sentimentDist().negative()).isEqualTo(2L);
        // prev 3 → 현재 2 → deltaPct = (2-3)/3*100 = -33 (반올림), inferred=false
        assertThat(nohyeong.deltaPct()).isEqualTo(-33);
        assertThat(nohyeong.deltaPctInferred()).isFalse();
    }

    // ------------------------------------------------------------------------
    // resolveRange (§7 의 직전 구간 계산)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("resolveRange. YESTERDAY 단위면 prevStart=어제-1일=그저께, curStart=어제, curEnd=어제")
    void resolveRange_yesterday_unit() {
        stubKpiWithCounts(kpiRow());
        when(complaintMapper.selectEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());
        when(complaintMapper.selectNegEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());

        LocalDate fixed = LocalDate.of(2026, 8, 31);

        // YESTERDAY → [그저께, 어제, 어제] for period [어제 00:00, 어제 23:59:59...]
        // 단순화: unit=YESTERDAY 면 resolveRange 가 호출되지 않을 수도 있는데 ComplaintRequest 의
        // unit 을 직접 쓰므로 verify 로 매개변수만 확인.
        ComplaintRequest req = new ComplaintRequest();
        req.setUnit(ComplaintUnit.YESTERDAY);
        req.setStartDt(fixed);
        req.setEndDt(fixed);   // 명시 구간 우선

        sut.getKpi(req);

        org.mockito.ArgumentCaptor<java.time.LocalDate> captorCurStart =
                org.mockito.ArgumentCaptor.forClass(java.time.LocalDate.class);
        org.mockito.ArgumentCaptor<java.time.LocalDate> captorCurEnd =
                org.mockito.ArgumentCaptor.forClass(java.time.LocalDate.class);
        org.mockito.ArgumentCaptor<java.time.LocalDate> captorPrevStart =
                org.mockito.ArgumentCaptor.forClass(java.time.LocalDate.class);
        org.mockito.Mockito.verify(complaintMapper).selectKpi(
                captorCurStart.capture(), captorCurEnd.capture(), captorPrevStart.capture(),
                any(), any(), any());
        assertThat(captorCurStart.getValue()).isEqualTo(fixed);
        assertThat(captorCurEnd.getValue()).isEqualTo(fixed);
        // prevStart = curStart - (curEnd - curStart + 1) = fixed - 1
        assertThat(captorPrevStart.getValue()).isEqualTo(fixed.minusDays(1));
    }

    @Test
    @DisplayName("resolveRange. 1주 명시 구간이면 prevStart 는 그저께 (7일 전) 가 아니라 동일 길이 1주 전")
    void resolveRange_explicit_one_week_range() {
        stubKpiWithCounts(kpiRow());
        when(complaintMapper.selectEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());
        when(complaintMapper.selectNegEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());

        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end   = LocalDate.of(2026, 8, 7);  // 7 일

        ComplaintRequest req = new ComplaintRequest();
        req.setUnit(ComplaintUnit.WEEK);  // 명시 구간이 있어도 unit 무시
        req.setStartDt(start);
        req.setEndDt(end);

        sut.getKpi(req);

        org.mockito.ArgumentCaptor<java.time.LocalDate> captorCurStart =
                org.mockito.ArgumentCaptor.forClass(java.time.LocalDate.class);
        org.mockito.ArgumentCaptor<java.time.LocalDate> captorCurEnd =
                org.mockito.ArgumentCaptor.forClass(java.time.LocalDate.class);
        org.mockito.ArgumentCaptor<java.time.LocalDate> captorPrevStart =
                org.mockito.ArgumentCaptor.forClass(java.time.LocalDate.class);
        org.mockito.Mockito.verify(complaintMapper).selectKpi(
                captorCurStart.capture(), captorCurEnd.capture(), captorPrevStart.capture(),
                any(), any(), any());
        assertThat(captorCurStart.getValue()).isEqualTo(start);
        assertThat(captorCurEnd.getValue()).isEqualTo(end);
        // 동일 길이 = 7 일, 직전 시작일 = start - 7 일 = 7/25
        assertThat(captorPrevStart.getValue()).isEqualTo(LocalDate.of(2026, 7, 25));
    }

    // ------------------------------------------------------------------------
    // TSK-4164 D3 — 응답이 되비추는 '적용 기간'
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("D3. unit 조회면 bgngYmd/endYmd 가 서버 실제 날짜(Asia/Seoul) 기준 YYYYMMDD 로 채워진다")
    void kpiRange_reflected_as_ymd_from_today() {
        stubKpiWithCounts(kpiRow());
        when(complaintMapper.selectEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());
        when(complaintMapper.selectNegEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());

        ComplaintRequest req = new ComplaintRequest();
        req.setUnit(ComplaintUnit.MONTH);   // MONTH(30, 1) → [today-30, today-1] (TSK-4311)

        // 자정 경계 대비 — 호출 전후 날짜를 둘 다 허용한다.
        LocalDate before = LocalDate.now(SEOUL);
        ComplaintKpiVO vo = sut.getKpi(req);
        LocalDate after = LocalDate.now(SEOUL);

        // 🔴 하이픈 없는 8자리(BASIC_ISO_DATE) — LocalDate.toString() 이면 "2026-08-02" 가 된다.
        DateTimeFormatter ymd = DateTimeFormatter.BASIC_ISO_DATE;
        assertThat(vo.endYmd()).isIn(before.minusDays(1).format(ymd), after.minusDays(1).format(ymd));
        assertThat(vo.bgngYmd()).isIn(before.minusDays(30).format(ymd), after.minusDays(30).format(ymd));
    }

    @Test
    @DisplayName("TSK-4311. 조회 단위는 어제까지의 구간이고 전기는 그 바로 앞의 같은 일수 구간이다")
    void unitRangesEndYesterdayAndPrevIsSameLength() {
        LocalDate today = LocalDate.of(2026, 11, 4);

        LocalDate[] yesterday = ComplaintUnit.YESTERDAY.resolveRange(today);
        assertThat(yesterday).containsExactly(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 3));
        assertThat(ComplaintUnit.prevStart(yesterday[0], yesterday[1])).isEqualTo(LocalDate.of(2026, 11, 2));

        LocalDate[] week = ComplaintUnit.WEEK.resolveRange(today);
        assertThat(week).containsExactly(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 3));
        assertThat(ComplaintUnit.prevStart(week[0], week[1])).isEqualTo(LocalDate.of(2026, 10, 21));

        LocalDate[] month = ComplaintUnit.MONTH.resolveRange(today);
        assertThat(month).containsExactly(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 11, 3));
        assertThat(ComplaintUnit.prevStart(month[0], month[1])).isEqualTo(LocalDate.of(2026, 9, 5));
    }

    @Test
    @DisplayName("D3. 명시 구간이면 보낸 값을 그대로 되비춘다 (unit 무시)")
    void kpiRange_reflects_explicit_range() {
        stubKpiWithCounts(kpiRow());
        when(complaintMapper.selectEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());
        when(complaintMapper.selectNegEmdCompare(any(LocalDate.class), any(LocalDate.class), any(LocalDate.class), any(), any(), any())).thenReturn(List.of());

        ComplaintRequest req = new ComplaintRequest();
        req.setUnit(ComplaintUnit.WEEK);
        req.setStartDt(LocalDate.of(2026, 9, 1));
        req.setEndDt(LocalDate.of(2026, 9, 17));

        ComplaintKpiVO vo = sut.getKpi(req);

        assertThat(vo.bgngYmd()).isEqualTo("20260901");
        assertThat(vo.endYmd()).isEqualTo("20260917");
    }
}
