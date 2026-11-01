# 基础镜像默认官方源。国内网络可以通过 compose 的 build.args 或 .env 里的 REGISTRY 覆盖。
ARG BUILD_IMAGE=maven:3.9-eclipse-temurin-17
ARG RUNTIME_IMAGE=eclipse-temurin:17-jre

FROM ${BUILD_IMAGE} AS build
WORKDIR /app
COPY pom.xml .
# 依赖层单独缓存：只改 src 时不会重新下载 200MB 依赖
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package

FROM ${RUNTIME_IMAGE}
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
