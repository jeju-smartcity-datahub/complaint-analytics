# =============================================================================
# 제주 데이터허브 백엔드 (eGovFrame 5.0 / Spring Boot 3 / Java 17)
#
# 2단 빌드: gradle 빌드 산출 jar 만 JRE 이미지로 옮긴다.
# 빌드 진입점은 워크스페이스 루트 docker-compose.yml.
# =============================================================================
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# 의존성 레이어 캐시: 소스보다 먼저 복사해 build.gradle 이 안 바뀌면 재다운로드하지 않는다.
COPY gradle gradle
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies || true

COPY src src
# RTE 아티팩트는 maven.egovframe.go.kr 에서 받는다 — 빌드 시 아웃바운드 필요
RUN ./gradlew --no-daemon clean bootJar -x test

FROM eclipse-temurin:17-jre
WORKDIR /app
# compose healthcheck 가 /actuator/health 를 때리므로 curl 을 넣는다 (temurin 기본 이미지엔 없음)
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/build/libs/*.jar app.jar
ENV TZ=Asia/Seoul
EXPOSE 8070
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
