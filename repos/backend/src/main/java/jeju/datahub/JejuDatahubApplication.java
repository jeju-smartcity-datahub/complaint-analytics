package jeju.datahub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import jeju.datahub.config.tls.InsecureTls;

/**
 * Jeju DataHub 백엔드 진입점.
 *
 * 전자정부표준프레임워크 5.0 실행환경(RTE) 위에서 동작하는 Spring Boot 3 애플리케이션이다.
 */
@SpringBootApplication
public class JejuDatahubApplication {

    public static void main(String[] args) {
        InsecureTls.installFromEnv();   // 🔴 반드시 run 앞 — ClientRegistrations discovery 가 빈 생성 중에 돈다
        SpringApplication.run(JejuDatahubApplication.class, args);
        InsecureTls.logActive();        // 로깅 초기화 뒤 우회 활성 경고를 한 번 더 남긴다(System.err 대체)
    }
}
