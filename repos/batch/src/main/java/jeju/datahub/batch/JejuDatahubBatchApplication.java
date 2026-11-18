package jeju.datahub.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import jeju.datahub.batch.tls.InsecureTls;

/**
 * 제주 데이터허브 배치 애플리케이션 진입점.
 *
 * <p>Spring Boot 3.5.6 + Spring Batch 5.2.3 기반. 부팅 시 Job 자동 실행은
 * {@code spring.batch.job.enabled=false} 로 막혀 있으며, 실행은 수동 트리거(HTTP)
 * 또는 스케줄러로만 이뤄진다.
 */
@SpringBootApplication
public class JejuDatahubBatchApplication {

    public static void main(String[] args) {
        InsecureTls.installFromEnv();   // 🔴 반드시 run 앞 — 대상은 discovery 가 아니라 Job 의 DHub 호출이지만, 설치 시점은 백엔드(D5)와 같게 둔다
        SpringApplication.run(JejuDatahubBatchApplication.class, args);
        InsecureTls.logActive();        // 🔴 log4j2-spring.xml 이라 run 앞 WARN 은 버려진다 — 로깅 초기화 뒤 다시 남긴다(TSK-4336 — System.err 대체)
    }
}
