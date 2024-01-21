SHELL := /usr/bin/env bash
DOCKER_OK := $(shell type -P docker)
ARTIFACTORY_URL := "artefacts.tax.service.gov.uk"

# running locally will give a SNAPSHOT version
# in jenkins MAKE_RELEASE is true
tag = $$(sbt version | tail -1 | sed s/'\[info\] '//)
artefact = "upscan-upload-proxy"

build:
	@echo "****** building upscan-upload-proxy" $(tag) "******" 
	docker run --name docker-platops-sbt -d -i -t --rm -v .:/home/root ${ARTIFACTORY_URL}/platops-docker-sbt /bin/bash
	docker exec -it -w /home/root docker-platops-sbt sbt clean docker:stage
	docker build -t ${ARTIFACTORY_URL}/$(artefact):$(tag) ./target/docker/stage
	docker stop docker-platops-sbt

authenticate_to_artifactory:
	@docker login --username ${ARTIFACTORY_USERNAME} --password "${ARTIFACTORY_PASSWORD}" ${ARTIFACTORY_URL}

push:
	docker push ${ARTIFACTORY_URL}/$(artefact):$(tag)


