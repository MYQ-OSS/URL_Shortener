# ══════════════════════════════════════════════════════════════════════════════
# 高并发短链接系统 (URL_Shortener) - 多阶段 Docker 构建镜像
# ══════════════════════════════════════════════════════════════════════════════

# ── 阶段 1: 构建 Vue 工作台 ────────────────────────────────────────────────
FROM node:24-alpine AS frontend-builder
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend ./
RUN npm run build

# ── 阶段 2: 编译打包 ─────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /app

# 拷贝 pom.xml 并下载依赖缓存
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 拷贝源码并打包 (跳过单元测试加快镜像构建速度)
COPY src ./src
COPY sql ./sql
COPY --from=frontend-builder /frontend/dist ./src/main/resources/static/app
RUN mvn package -DskipTests -B

# ── 阶段 3: 运行时轻量镜像 ──────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 创建非 root 用户
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# 从构建阶段拷贝 JAR 包
COPY --from=builder /app/target/URL_Shortener-0.0.1-SNAPSHOT.jar app.jar

# JVM 优化参数 (适用于高并发容器环境)
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:+UseStringDeduplication -Dfile.encoding=UTF-8"
ENV SPRING_PROFILES_ACTIVE="prod"

EXPOSE 8080

USER appuser

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
