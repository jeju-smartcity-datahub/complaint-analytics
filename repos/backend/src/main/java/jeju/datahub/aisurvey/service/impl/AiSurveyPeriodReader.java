package jeju.datahub.aisurvey.service.impl;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jeju.datahub.commoncode.service.CommonCodeService;
import jeju.datahub.commoncode.service.CommonCodeVO;
import lombok.RequiredArgsConstructor;

/**
 * 설문 기간 2종을 공통코드에서 읽는다(공통코드 A안).
 *
 * <p>설정마다 그룹 1개 · 행 1개 · <b>{@code cd_vl} = 일수</b>다. 값 칸이 없는 공통코드 표에서 이름 칸
 * ({@code cd_nm})에 숫자를 넣지 않으려고 코드값을 값으로 쓴다.
 *
 * <ul>
 *   <li>🔴 <b>요청마다 읽는다(캐시 없음)</b> — 운영이 {@code UPDATE} 하면 재기동 없이 바로 반영된다.</li>
 *   <li>🔴 그룹 이름은 이 클래스에만 둔다. {@code .env}·{@code application.yml} 키는 두지 않는다
 *       (정본이 둘이 되면 어느 쪽이 이기는지 갈린다).</li>
 *   <li>0행·2행 이상·정수 아님·1 미만이면 <b>기본값 + WARN 1줄</b>이다 — 설문 때문에 화면을 깨지 않는다.
 *       읽기 자체가 실패해도(DB 오류) 같은 폴백이다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AiSurveyPeriodReader {

    private static final Logger log = LoggerFactory.getLogger(AiSurveyPeriodReader.class);

    /** 제출 후 다시 묻지 않는 일수 */
    public static final String GROUP_SBMSN = "AI_SRVY_SBMSN_PRD_CD";
    /** "나중에" 선택 시 다시 묻지 않는 일수 */
    public static final String GROUP_PSTP = "AI_SRVY_PSTP_PRD_CD";

    /** 마이그레이션 115 시드와 같은 값을 유지한다(폴백). */
    public static final int DEFAULT_SBMSN_DAYS = 14;
    public static final int DEFAULT_PSTP_DAYS = 7;

    private final CommonCodeService commonCodeService;

    public int submitDays() {
        return read(GROUP_SBMSN, DEFAULT_SBMSN_DAYS);
    }

    public int postponeDays() {
        return read(GROUP_PSTP, DEFAULT_PSTP_DAYS);
    }

    private int read(String groupCd, int fallback) {
        List<CommonCodeVO> rows;
        try {
            rows = commonCodeService.selectCommonCodesByGroup(groupCd);   // use_yn='Y' 만
        } catch (RuntimeException e) {
            return fallback(groupCd, fallback, "read-error " + e.getClass().getSimpleName());
        }
        if (rows == null || rows.isEmpty()) {
            return fallback(groupCd, fallback, "no-row");
        }
        if (rows.size() > 1) {
            return fallback(groupCd, fallback, "multiple-rows=" + rows.size());
        }
        String raw = rows.get(0).getCdVl();
        int days;
        try {
            days = Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            return fallback(groupCd, fallback, "not-integer");
        }
        if (days < 1) {
            return fallback(groupCd, fallback, "less-than-1");
        }
        return days;
    }

    private static int fallback(String groupCd, int fallback, String reason) {
        log.warn("[ai-survey] period fallback group={} reason={} days={}", groupCd, reason, fallback);
        return fallback;
    }
}
