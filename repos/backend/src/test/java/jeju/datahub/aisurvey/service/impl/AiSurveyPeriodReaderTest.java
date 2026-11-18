package jeju.datahub.aisurvey.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jeju.datahub.commoncode.service.CommonCodeService;
import jeju.datahub.commoncode.service.CommonCodeVO;

/** 공통코드 → 일수 변환과 폴백. DB 없이 돈다. */
class AiSurveyPeriodReaderTest {

    private static AiSurveyPeriodReader readerReturning(String... cdVls) {
        List<CommonCodeVO> rows = new ArrayList<>();
        for (String v : cdVls) {
            CommonCodeVO vo = new CommonCodeVO();
            vo.setCdVl(v);
            rows.add(vo);
        }
        return new AiSurveyPeriodReader(new CommonCodeService() {
            @Override public List<CommonCodeVO> selectCommonCodeList() { return rows; }
            @Override public List<CommonCodeVO> selectCommonCodesByGroup(String groupCd) { return rows; }
        });
    }

    @Test
    @DisplayName("1행 정수면 그 값")
    void singleRow() {
        assertThat(readerReturning("21").submitDays()).isEqualTo(21);
        assertThat(readerReturning(" 3 ").postponeDays()).isEqualTo(3);
    }

    @Test
    @DisplayName("0행·2행·정수 아님·1 미만 → 기본값 14/7")
    void fallbacks() {
        for (AiSurveyPeriodReader r : List.of(readerReturning(), readerReturning("14", "21"),
                readerReturning("abc"), readerReturning("0"), readerReturning("-5"))) {
            assertThat(r.submitDays()).isEqualTo(AiSurveyPeriodReader.DEFAULT_SBMSN_DAYS);
            assertThat(r.postponeDays()).isEqualTo(AiSurveyPeriodReader.DEFAULT_PSTP_DAYS);
        }
    }

    @Test
    @DisplayName("읽기 실패(DB 오류)도 기본값")
    void readError() {
        AiSurveyPeriodReader r = new AiSurveyPeriodReader(new CommonCodeService() {
            @Override public List<CommonCodeVO> selectCommonCodeList() { throw new IllegalStateException(); }
            @Override public List<CommonCodeVO> selectCommonCodesByGroup(String g) { throw new IllegalStateException(); }
        });
        assertThat(r.submitDays()).isEqualTo(AiSurveyPeriodReader.DEFAULT_SBMSN_DAYS);
    }
}
