package jeju.datahub.config;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis 매퍼 인터페이스 스캔 설정.
 *
 * base 패키지 하위에서 {@link Mapper} 애노테이션이 붙은 인터페이스만 매퍼 빈으로 등록한다.
 * (SampleService 같은 일반 서비스 인터페이스가 매퍼로 잘못 등록되는 것을 방지)
 *
 * SqlSessionFactory / mapper-locations 등 세부 설정은 application.yml 의 mybatis.* 로 관리한다.
 */
@Configuration
@MapperScan(basePackages = "jeju.datahub", annotationClass = Mapper.class)
public class MyBatisConfig {
}
