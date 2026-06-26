# Build stage
ARG ECR_REPO
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /usr/src/app
COPY . .
RUN mvn package -DskipTests

# Production stage
# FROM ${ECR_REPO}/base-images:backend-jdk21

# FROM tomcat:10.1.13-jdk21
# RUN apt-get update && apt-get install unzip
# RUN rm -rf /usr/local/tomcat/webapps.dist
# RUN rm -rf /usr/local/tomcat/webapps/ROOT

FROM tomcat:10.1.17-jdk21 AS fnl_base_image

RUN apt-get update && apt-get -y upgrade

# install dependencies and clean up unused files
RUN apt-get update && apt-get install -y unzip && rm -rf /var/lib/apt/lists/*
RUN rm -rf /usr/local/tomcat/webapps.dist
RUN rm -rf /usr/local/tomcat/webapps/ROOT

# Modify the server.xml file to block error reportiing
RUN sed -i 's|</Host>|  <Valve className="org.apache.catalina.valves.ErrorReportValve"\n               showReport="false"\n               showServerInfo="false" />\n\n      </Host>|' conf/server.xml 

# Allow deployment platforms to inject a runtime port (for example via PORT env var).
RUN sed -i 's/Connector port="8080"/Connector port="${port.http}"/' conf/server.xml
ENV PORT=8080

# expose ports
EXPOSE 8080

# IMPORTANT: For ECS deployment, configure the target group health check to use /health
# Health check path: /health
# Expected status: 200 OK
# Interval: 30 seconds
# Timeout: 5 seconds
# Healthy threshold: 2
# Unhealthy threshold: 3

COPY --from=build /usr/src/app/target/Bento-0.0.1.war /usr/local/tomcat/webapps/ROOT.war

CMD ["sh", "-c", "export CATALINA_OPTS=\"$CATALINA_OPTS -Dport.http=${PORT:-8080}\"; catalina.sh run"]
