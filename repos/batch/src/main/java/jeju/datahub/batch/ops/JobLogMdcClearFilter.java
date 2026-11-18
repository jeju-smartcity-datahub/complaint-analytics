package jeju.datahub.batch.ops;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

/**
 * HTTP 진입점의 MDC 정리 — 기존 동기 트리거({@code POST /api/batch/*})는 톰캣 요청 스레드에서 Job 을 끝까지 돈다.
 * 리스너 {@code afterJob} 의 해제가 앞 리스너 예외로 빠지면 그 톰캣 스레드가 다음 요청에서
 * 이전 실행 파일로 로그를 쓴다 — 요청이 끝날 때 {@link JobLogMdcListener#clear()} 로 반드시 지운다.
 *
 * <p>등록은 {@code JobLogConfig} 의 {@code FilterRegistrationBean}(전 경로) — {@code @Component} 로 두지 않는다
 * (등록 지점을 한 곳에 모은다).
 */
public class JobLogMdcClearFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        JobLogMdcListener.clear();   // 방어 — 다른 경로가 남긴 키로 이 요청 로그가 엉뚱한 파일에 가지 않게
        try {
            chain.doFilter(request, response);
        } finally {
            JobLogMdcListener.clear();
        }
    }
}
