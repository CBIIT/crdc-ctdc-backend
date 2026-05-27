# Build stage
ARG ECR_REPO
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /usr/src/app

# Copy only git related files first
COPY .gitmodules .
COPY .git ./.git

# Initialize and update submodules
RUN git submodule update --init --recursive

COPY . .
RUN mvn package -DskipTests

# Production stage
# FROM ${ECR_REPO}/base-images:backend-jdk17

# FROM tomcat:10.1.13-jdk17
# RUN apt-get update && apt-get install unzip
# RUN rm -rf /usr/local/tomcat/webapps.dist
# RUN rm -rf /usr/local/tomcat/webapps/ROOT

FROM tomcat:10.1.55-jdk21-temurin AS fnl_base_image
ENV JAVA_OPTS="-XX:InitialRAMPercentage=25.0 -XX:MaxRAMPercentage=75.0"

RUN apt-get update \
	&& apt-get install -y --no-install-recommends unzip \
	&& rm -rf /var/lib/apt/lists/* \
	&& rm -rf /usr/local/tomcat/webapps.dist \
	&& rm -rf /usr/local/tomcat/webapps/ROOT

# Modify the server.xml file to block error reportiing
RUN sed -i 's|</Host>|  <Valve className="org.apache.catalina.valves.ErrorReportValve"\n               showReport="false"\n               showServerInfo="false" />\n\n      </Host>|' conf/server.xml 

# expose ports
EXPOSE 8080
COPY --from=build /usr/src/app/target/Bento-0.0.1.war /usr/local/tomcat/webapps/ROOT.war
