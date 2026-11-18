package jeju.datahub.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import jeju.datahub.support.IntegrationLoginSupport;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 롤별 메뉴 경로 회귀.
 *
 * <p>롤 3종 × 대표 경로 개수를 표로 못박는다 — 시드({@code TB_AUTHRT_MENU} 40행)가 흔들리면
 * 여기서 먼저 깨진다. 기본 test 체인({@code security-enabled=false})을 쓰므로
 * {@link IntegrationLoginSupport#forTestChain} 으로 만든다(CSRF 토큰이 내려오지 않는다).
 *
 * <p>🔁 <b>개수가 한 열이다.</b> 종전엔 {@code menuIds} 와 {@code menuPathNms} 두 수를 함께
 * 고정했지만(그 둘이 다르다는 것이 근거였다), 경로 분리로 <b>메뉴 수 = 경로 수</b> 가 되고
 * {@code /me} 는 {@code menuPathNms} 만 준다. 두 수가 갈리면 시드가 잘못된 것이며 그것은
 * 검증이 잡는다.
 *
 * <p>🔴 로그인 경로를 타므로 {@code TB_LGN_HSTRY}·{@code TB_USER} 에 행이 남는다
 * ({@code RANDOM_PORT} 는 롤백이 안 먹는다). {@code markStart()}/{@code cleanUp()} 규약을 그대로
 * 따른다 — ⚠️ 이력은 {@code OR user_id IS NULL} 로 넓히지 않는다: 실패 행은
 * {@code user_id} 가 null 이라 테스트가 만들지 않은 실 실패 이력까지 지운다.
 *
 * <p>이 테스트는 매핑을 넣거나 지우지 않는다(롤 승격만 쓴다) — 시드 40행을 건드리지 않으므로
 * 별도 복원이 필요 없다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MenuAccessIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;

    private IntegrationLoginSupport auth;

    @BeforeEach
    void markStart() {
        auth = IntegrationLoginSupport.forTestChain(rest, jdbcTemplate);
        auth.markStart();
    }

    @AfterEach
    void cleanUp() {
        auth.cleanUp();
    }

    @ParameterizedTest(name = "{0} → menuPathNms {1}개")
    @DisplayName("🔴 롤별 /me menuPathNms 개수가 시드와 일치한다")
    @CsvSource({
        "ROLE_SYSTEM_ADMIN, 15",
        "ROLE_SERVICE_ADMIN, 15",   // 🔴 시드 — 개정 전은 12
        "ROLE_USER, 10",
    })
    void menuPathCountsByRole(String authrtCd, int paths) {
        HttpHeaders session = auth.mockSession();
        // 롤의 정본은 IdP 의 type claim 이지만, mock 로그인은 claim 자체가 없어
        // 기존 롤이 유지된다 — 그래서 이 테스트의 DB 직접 UPDATE 승격이 그대로 통한다.
        // /me 는 매 요청 selectLoginUser 를 다시 돌리므로 재로그인이 필요 없다.
        auth.promoteTo(authrtCd);

        ResponseEntity<Map> me = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(session), Map.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("userAuthrtCd")).isEqualTo(authrtCd);
        assertThat((List<?>) me.getBody().get("menuPathNms"))
                .as("%s 의 허용 메뉴 경로", authrtCd)
                .hasSize(paths)
                .doesNotHaveDuplicates();
    }
}
