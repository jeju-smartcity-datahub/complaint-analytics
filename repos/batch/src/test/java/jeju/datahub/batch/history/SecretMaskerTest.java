package jeju.datahub.batch.history;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 공용 마스킹 규칙 (TSK-4337 Step 5) — 이력 기록기·로그 파일·로그 API 가 같은 규칙을 쓴다. */
class SecretMaskerTest {

    @Test
    @DisplayName("key·token·password·Bearer 의 값만 *** — 이름과 나머지 문장은 남긴다")
    void masksValues() {
        assertThat(SecretMasker.mask("GET https://api.vworld.kr/req/address?key=TOP-SECRET-123 실패"))
                .isEqualTo("GET https://api.vworld.kr/req/address?key=*** 실패");
        assertThat(SecretMasker.mask("Authorization: Bearer eyJhbGciOiJIUzI1 끝")).isEqualTo("Authorization: Bearer *** 끝");
        assertThat(SecretMasker.mask("access_token=abc.def refresh")).isEqualTo("access_token=*** refresh");
        assertThat(SecretMasker.mask("PASSWORD: hunter2")).isEqualTo("PASSWORD: ***");
        assertThat(SecretMasker.mask("줄1 token=a\n줄2 api-key=b\n")).isEqualTo("줄1 token=***\n줄2 api-key=***\n");
    }

    @Test
    @DisplayName("비밀 형태가 아니면 그대로 · null/빈 문자열 안전")
    void leavesOthers() {
        assertThat(SecretMasker.mask("VWorld 호출 한도 초과(OVER_REQUEST_LIMIT)")).isEqualTo("VWorld 호출 한도 초과(OVER_REQUEST_LIMIT)");
        assertThat(SecretMasker.mask("monkey=1 tokens=2")).isEqualTo("monkey=1 tokens=2");
        assertThat(SecretMasker.mask(null)).isNull();
        assertThat(SecretMasker.mask("")).isEmpty();
    }

    @Test
    @DisplayName("기록기 redact 는 공용 유틸에 위임 — 결과가 같다")
    void recorderDelegates() {
        String s = "url=https://x?key=K1 Bearer T2 password=P3";
        assertThat(BatchRunHistoryRecorder.redact(s)).isEqualTo(SecretMasker.mask(s));
    }
}
