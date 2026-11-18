package jeju.datahub.batch.ops;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 실행별 로그 파일 배선 — HTTP 진입점 MDC 정리 필터 등록.
 *
 * <p>전 경로({@code /*})·가장 바깥(최우선 순서)에 건다 — 키 필터가 401 로 끊는 요청도 감싸고, 기존 동기 트리거가
 * 톰캣 스레드에서 Job 을 돈 뒤 남긴 라우팅 키를 요청 끝에서 지운다({@link JobLogMdcClearFilter}).
 */
@Configuration
public class JobLogConfig {

    @Bean
    public FilterRegistrationBean<JobLogMdcClearFilter> jobLogMdcClearFilter() {
        FilterRegistrationBean<JobLogMdcClearFilter> registration =
                new FilterRegistrationBean<>(new JobLogMdcClearFilter());
        registration.addUrlPatterns("/*");
        registration.setName("jobLogMdcClearFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
