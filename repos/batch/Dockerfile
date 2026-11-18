# =============================================================================
# 제주 데이터허브 배치 앱 (Spring Boot 3 / Spring Batch 5 / Java 17)
#
# 백엔드와 같은 2단 빌드. 차이는 두 가지뿐이다.
#   - EXPOSE 8280 (배치 기본 포트, 백엔드 local 8180 과 충돌 회피)
#   - eGovFrame Maven 저장소 불필요 — 배치는 RTE 를 쓰지 않는다 (build.gradle 은 mavenCentral 만)
#
# ⚠️ 이 컨테이너는 기동만 한다 (TSK-4018 D8). spring.batch.job.enabled=false 라
#    기동만으로는 아무 Job 도 돌지 않고, 실행은 HTTP 수동 트리거 또는 스케줄이다.
# =============================================================================
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

COPY gradle gradle
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies || true

COPY src src
RUN ./gradlew --no-daemon clean bootJar -x test

FROM eclipse-temurin:17-jre
WORKDIR /app
# curl 은 헬스체크용, unzip 은 jar 안 application-dev.yml 확인 검증용 —
# temurin JRE 기본 이미지엔 둘 다 없다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl unzip \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/build/libs/*.jar app.jar
ENV TZ=Asia/Seoul
EXPOSE 8280
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
