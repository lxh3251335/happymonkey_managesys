FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B dependency:resolve
COPY src src
RUN mvn -B verify

FROM eclipse-temurin:17-jre-noble
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --no-create-home app
COPY --from=build --chown=app:app /src/target/project-commerce-0.1.0.jar app.jar
RUN mkdir -p /var/lib/commerce/attachments && chown app:app /var/lib/commerce/attachments
USER app
ENV SPRING_PROFILES_ACTIVE=prod JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Shanghai
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
