package jeju.datahub.batch.rptp;

import java.time.LocalDate;
import jeju.datahub.batch.rptp.ReportDueDateResolver.TargetPeriod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 설정 한 행을 <b>"오늘 만들 보고서 요청"</b> 으로 바꾼다 — 아니면 걸러 낸다
 * (TSK-4027 Step 7-3).
 *
 * <h2>🔴 {@code null} 반환이 정상 경로다 — 스킵이 아니다</h2>
 * 도래하지 않은 설정은 {@code null} 을 돌려주고 Spring Batch 가 <b>조용히 걸러 낸다</b>
 * ({@code filterCount} 로 집계된다 — {@code skipCount} 가 아니다).
 * 🔴 <b>대부분의 설정이 매일 여기서 {@code null} 이다</b> — 월간 설정도 한 달에 하루만 통과하고,
 * 분기 설정은 석 달에 하루다. 그래서 <b>{@code written=0} 이 정상</b>이며 장애가 아니다.
 * 그 사실이 로그에서 드러나도록 걸러 낸 건수·통과한 건수를 DEBUG/INFO 로 남긴다
 * (종료 요약은 Step 9 의 이력 리스너가 맡는다).
 *
 * <h2>⚠️ 기준일은 Job 파라미터 {@code baseYmd} 하나가 정본이다</h2>
 * 이 클래스는 "오늘" 을 직접 읽는 호출({@code LocalDate.now} 계열)을 <b>두지 않는다.</b> Reader·Processor·Writer 가 각자
 * "오늘" 을 읽으면 자정을 걸칠 때 하루가 갈린다({@code RiskAlertProperties} 와 같은 이유).
 * "오늘" 을 정하는 곳은 트리거·스케줄러 한 곳뿐이다(Step 9).
 *
 * <p>🔴 <b>판정 규칙을 여기에 복사하지 않는다</b> — {@link ReportDueDateResolver} 한 곳이
 * 정본이고 여기는 그것을 부르기만 한다(백엔드와의 케이스 대조가 그 한 클래스로 끝나야 한다).
 */
@Component
@StepScope
public class ReportAutoCrtProcessor implements ItemProcessor<RptpStngRow, ReportAutoCrtCommand> {

    private static final Logger log = LoggerFactory.getLogger(ReportAutoCrtProcessor.class);

    private final ReportDueDateResolver resolver;

    /** 이번 실행의 기준일. 생성자에서 한 번 확정해 행마다 다시 계산하지 않는다. */
    private final LocalDate baseDate;

    /**
     * @param resolver    도래 판정·기간 산출의 유일한 출처
     * @param baseYmdParam Job 파라미터 {@code baseYmd}({@code yyyy-MM-dd}).
     *                     🔴 <b>필수다</b> — 비면 예외로 끊는다({@link ReportDueDateResolver#parseBaseYmd}).
     *                     ⚠️ {@code String} 으로 받는다 — 트리거·스케줄러가
     *                     {@code addString("baseYmd", ...)} 로 넣기 때문이다
     */
    public ReportAutoCrtProcessor(
            ReportDueDateResolver resolver,
            @Value("#{jobParameters['baseYmd']}") String baseYmdParam) {
        this.resolver = resolver;
        this.baseDate = ReportDueDateResolver.parseBaseYmd(baseYmdParam);
    }

    /**
     * @return 오늘 만들 보고서 요청. <b>도래하지 않았으면 {@code null}</b> — Spring Batch 가
     *         조용히 걸러 내는 정상 경로이며, 대부분의 행이 여기다
     */
    @Override
    public ReportAutoCrtCommand process(RptpStngRow stng) {
        if (!resolver.isDue(stng, baseDate)) {
            // 대부분의 행이 매일 여기로 떨어진다 — 정상이다. 그래서 DEBUG 다(INFO 면 로그가 묻힌다).
            log.debug("기준일 {} — 설정 stngId={}({}·기준월 {}·기준일 {})은 배포일이 아니라 건너뜁니다.",
                    baseDate, stng.stngId(), stng.crltnCyclCd(), stng.crltnMm(), stng.crltnDd());
            return null;
        }

        TargetPeriod period = resolver.resolve(stng, baseDate);
        log.info("기준일 {} — 설정 stngId={}({}) 배포일 도래. 대상기간 {} ({}~{})",
                baseDate, stng.stngId(), stng.crltnCyclCd(),
                period.trgtPrdCd(), period.bgngYmd(), period.endYmd());

        return new ReportAutoCrtCommand(
                stng.stngId(),
                stng.rptpTypeCd(),
                period.trgtPrdCd(),
                period.bgngYmd(),
                period.endYmd(),
                stng.sggNm(),
                stng.emdCd(),
                stng.crltnDeptCd(),
                stng.crltnCyclCd());
    }
}
