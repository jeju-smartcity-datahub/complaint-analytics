package jeju.datahub.config;

import org.springframework.transaction.TransactionException;
import org.springframework.dao.DataAccessException;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import jeju.datahub.common.SesnIdHasher;
import jeju.datahub.lgnhstry.service.LgnHstryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 세션 소멸 시 로그인 이력의 접속 종료 시각({@code cntn_end_dt})을 보정하는 리스너 (TSK-4104).
 *
 * <p>명시적 로그아웃 없이 세션이 만료되는 경로를 채운다(D6).
 *
 * <p>🔴 <b>로그아웃으로 {@code invalidate()} 된 경우에도 이 콜백이 온다.</b> 로그아웃 처리가 이미
 * {@code cntn_end_dt} 를 채웠다면 {@link LgnHstryService#closeSession(String)} 의
 * {@code cntn_end_dt IS NULL} 조건이 두 번째 UPDATE 를 막는다 — 즉 <b>멱등</b>이며 로그아웃 시각을
 * 리스너가 덮어쓰지 않는다.
 *
 * <p>⚠️ <b>채워지지 않는 경로가 남는다</b> — 브라우저 닫기(세션은 타임아웃까지 살아 있다)·서버 재시작
 * (메모리 세션이 통째로 사라져 콜백이 오지 않는다). <b>D6 대로 정상이다.</b>
 *
 * <p>⚠️ 리스너 안에서는 요청 컨텍스트가 없어 IP·사용자를 새로 얻을 수 없다 — <b>세션 해시로만</b> 찾는다.
 *
 * <p>ℹ️ {@code @Component} 만으로 서블릿 컨테이너에 등록된다 — Spring Boot 의
 * {@code ServletContextInitializerBeans} 가 {@code EventListener} 계열 빈을
 * {@code ServletListenerRegistrationBean} 으로 감싸 자동 등록한다.
 * {@code @WebListener}·{@code ServletListenerRegistrationBean} 을 직접 쓰지 않는다.
 */
@Component
public class SessionEndListener implements HttpSessionListener {

    // 🔴 레포 관례 — `@Slf4j` 가 아니라 `LoggerFactory` 로 선언한다
    private static final Logger log = LoggerFactory.getLogger(SessionEndListener.class);

    private final LgnHstryService lgnHstryService;

    public SessionEndListener(LgnHstryService lgnHstryService) {
        this.lgnHstryService = lgnHstryService;
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        // 세션 소멸 콜백이 예외로 죽으면 컨테이너 로그가 오염되므로 반드시 삼킨다.
        try {
            lgnHstryService.closeSession(SesnIdHasher.shortHash(se.getSession().getId()));
        } catch (DataAccessException | TransactionException | IllegalStateException e) { // 4.3: 이력 갱신(REQUIRES_NEW JPA) 실패 · 이미 무효화된 세션
            log.warn("[lgn-hstry] 세션 종료 기록 실패", e);
        }
    }
}
