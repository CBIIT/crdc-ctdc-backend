# Build stage
ARG ECR_REPO
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /usr/src/app

# Refresh trust store in build image so Maven can resolve dependencies reliably.
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates \
    && update-ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Copy only git related files first
COPY .gitmodules .
COPY .git ./.git

# Initialize and update submodules
RUN git submodule update --init --recursive

COPY . .
RUN mvn package -DskipTests

# Production stage
FROM tomcat:10.1.56-jdk21-temurin AS fnl_base_image
ENV JAVA_OPTS="-XX:InitialRAMPercentage=25.0 -XX:MaxRAMPercentage=75.0"

# Security updates with deterministic package handling for this base image
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends unzip ca-certificates openssl \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends --only-upgrade \
    openssl libssl3t64 \
    util-linux tar libgcrypt20 libc-bin libc6 locales libexpat1 binutils xz-utils liblzma5 \
    && rm -rf /var/lib/apt/lists/* \
    && rm -rf /usr/local/tomcat/webapps.dist \
    && rm -rf /usr/local/tomcat/webapps/ROOT

# Modify the server.xml file to block error reporting
RUN sed -i 's|</Host>|  <Valve className="org.apache.catalina.valves.ErrorReportValve"\n               showReport="false"\n               showServerInfo="false" />\n\n      </Host>|' conf/server.xml

# expose ports
EXPOSE 8080
COPY --from=build /usr/src/app/target/Bento-0.0.1.war /usr/local/tomcat/webapps/ROOT.war
