# Build stage
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /usr/src/app
COPY . .
RUN mvn package -DskipTests

# Production stage
FROM tomcat:10.1.55-jdk21-temurin AS fnl_base_image

ENV JAVA_OPTS="-Xmx4096m"

RUN apt-get update && apt-get -y upgrade \
    && rm -rf /var/lib/apt/lists/*

RUN rm -rf /usr/local/tomcat/webapps.dist \
    && rm -rf /usr/local/tomcat/webapps/ROOT

# Suppress Tomcat version and stack traces in error responses
RUN sed -i 's|</Host>|  <Valve className="org.apache.catalina.valves.ErrorReportValve"\n               showReport="false"\n               showServerInfo="false" />\n\n      </Host>|' conf/server.xml

EXPOSE 8080
COPY --from=build /usr/src/app/target/Bento-0.0.1.war /usr/local/tomcat/webapps/ROOT.war
