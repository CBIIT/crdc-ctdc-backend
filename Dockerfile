# Build stage
ARG ECR_REPO
# Version snapshot captured: 2026-07-08 (UTC)
# Base image digests are pinned for deterministic builds.
FROM maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e as build
WORKDIR /usr/src/app

# Manual package version control for build stage dependencies.
# Pinned from maven:3.9.9-eclipse-temurin-21 apt candidate versions on 2026-07-08.
ARG CA_CERTIFICATES_VERSION=20260601~24.04.1
ARG GIT_VERSION=1:2.43.0-1ubuntu7.3

# Ensure git is available for the existing submodule update step.
RUN git submodule update --init --recursive
RUN apt-get update \
	## list upgradable packages in build log, useful for patching
	&& apt list --upgradable \
	## library specific patches (manually controlled versions)
	&& DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates=${CA_CERTIFICATES_VERSION} \
	&& DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends git=${GIT_VERSION} \
	&& update-ca-certificates \
	&& rm -rf /var/lib/apt/lists/*
COPY . .
RUN mvn package -DskipTests

# Production stage
# FROM ${ECR_REPO}/base-images:backend-jdk21

# FROM tomcat:10.1.13-jdk21
# RUN apt-get update && apt-get install unzip
# RUN rm -rf /usr/local/tomcat/webapps.dist
# RUN rm -rf /usr/local/tomcat/webapps/ROOT

FROM tomcat:10.1.56-jdk21@sha256:933252904a9ec9da9eeb0d43d053cdf7c9ebe0279f3f839f5376f04075f19dfc AS fnl_base_image

# Manual package version control for runtime dependencies.
# Pinned from tomcat:10.1.56-jdk21 apt candidate versions on 2026-07-08.
ARG UNZIP_VERSION=6.0-28ubuntu4.1

# Install only explicit dependencies (no blanket OS upgrade for deterministic builds)
RUN apt-get update \
	## list upgradable packages in build log, useful for patching
	&& apt list --upgradable \
	## library specific patches (manually controlled versions)
	&& DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends unzip=${UNZIP_VERSION} \
	&& rm -rf /var/lib/apt/lists/*

# remove default Tomcat webapps
RUN rm -rf /usr/local/tomcat/webapps.dist
RUN rm -rf /usr/local/tomcat/webapps/ROOT

# Modify the server.xml file to block error reportiing
RUN sed -i 's|</Host>|  <Valve className="org.apache.catalina.valves.ErrorReportValve"\n               showReport="false"\n               showServerInfo="false" />\n\n      </Host>|' conf/server.xml 

# expose ports
EXPOSE 8080
COPY --from=build /usr/src/app/target/Bento-0.0.1.war /usr/local/tomcat/webapps/ROOT.war