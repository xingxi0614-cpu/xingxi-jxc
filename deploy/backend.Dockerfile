# 后端镜像:先用 Maven 编译,再放进只有 Java 运行环境的小镜像里运行。
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY backend/pom.xml .
# 先把依赖下载下来(这一层会被缓存)。网络抖动时偶尔会断,断了不算失败,下一步会补下载。
RUN mvn -q -B dependency:go-offline || true
COPY backend/src ./src
# 打包;下载依赖遇到网络错误时最多重试 3 次
RUN for i in 1 2 3; do mvn -q -B -DskipTests package && exit 0; echo "打包失败,第 $i 次重试…"; sleep 5; done; exit 1

FROM eclipse-temurin:17-jre-jammy
# mysqldump:「数据备份 → 下载整库备份」要用
RUN apt-get update \
 && apt-get install -y --no-install-recommends mysql-client \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home-dir /data/scm scm \
 && mkdir -p /data/scm && chown scm:scm /data/scm
COPY --from=build /src/target/scm-backend.jar /app/scm-backend.jar
USER scm
ENV SPRING_PROFILES_ACTIVE=prod \
    SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080 \
    TZ=Asia/Shanghai \
    SCM_UPLOAD_PATH=/data/scm/upload \
    SCM_TOOLBOX_PATH=/data/scm/toolbox \
    SCM_ATTACHMENT_DIR=/data/scm/attachments \
    LOG_FILE=/data/scm/logs/scm-backend.log
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/scm-backend.jar"]
