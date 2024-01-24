SHELL := /usr/bin/env bash
DOCKER_OK := $(shell type -P docker)
ARTIFACTORY_HOST := lab03.artefacts.tax.service.gov.uk
ARTEFACT := upscan-upload-proxy

# running locally will give a SNAPSHOT version
# in jenkins MAKE_RELEASE is true
tag = $$(cat ${ARTEFACT}.version | tail -1 | sed s/'\[info\] '//)

build:
	@echo "****** building upscan-upload-proxy ******" 
	docker run --name docker-platops-sbt -d -i -t --rm -v .:/root/build artefacts.tax.service.gov.uk/docker-platops-sbt /bin/bash
	docker exec -w /root docker-platops-sbt cp /root/project/build.properties /root/build/project/build.properties
	docker exec -w /root/build docker-platops-sbt sbt clean test
	docker exec -w /root/build docker-platops-sbt sbt docker:stage
	docker exec -w /root/build docker-platops-sbt sbt version > ${ARTEFACT}.version
	docker build -t ${ARTIFACTORY_HOST}/$(ARTEFACT):$(tag) ./target/docker/stage
	docker stop docker-platops-sbt

authenticate_to_artifactory:
	@docker login --username ${ARTIFACTORY_USERNAME} --password "${ARTIFACTORY_PASSWORD}" ${ARTIFACTORY_HOST}

push:
	docker push ${ARTIFACTORY_HOST}/$(ARTEFACT):$(tag)

environment:
	printenv
