SHELL := /usr/bin/env bash
DOCKER_OK := $(shell type -P docker)

# running locally will give a SNAPSHOT version
# in jenkins MAKE_RELEASE is true
tag = $$(sbt version | tail -1 | sed s/'\[info\] '//)

check_docker:
    ifeq ('$(DOCKER_OK)','')
	    $(error package 'docker' not found!)
    endif

build: check_docker 
	@echo '******** building upscan-upload-proxy *********'
	
	sbt clean test docker:stage
	@docker build -t artefacts.tax.service.gov.uk/upscan-upload-proxy:$(tag) ./target/docker/stage/

authenticate_to_artifactory:
	@docker login --username ${ARTIFACTORY_USERNAME} --password "${ARTIFACTORY_PASSWORD}"  artefacts.tax.service.gov.uk

push_image:
	@docker push artefacts.tax.service.gov.uk/upscan-upload-proxy:$(tag)
	@cut-release
