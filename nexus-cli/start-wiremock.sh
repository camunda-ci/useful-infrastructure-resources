#!/bin/bash
WIREMOCK_VERSION=1.58
WIREMOCK_ARTIFACT=wiremock-$WIREMOCK_VERSION-standalone.jar
WIREMOCK_ARTIFACT_DOWNLOAD_URL=https://search.maven.org/remotecontent?filepath=com/github/tomakehurst/wiremock/$WIREMOCK_VERSION/$WIREMOCK_ARTIFACT
PROXY_URL=${1:-https://app.camunda.com/}
ROOT_WIREMOCK_DIR=wiremock

if [ ! -e "$WIREMOCK_ARTIFACT" ]; then
    curl $WIREMOCK_ARTIFACT_DOWNLOAD_URL > $WIREMOCK_ARTIFACT
fi

java -jar wiremock-1.58-standalone.jar --proxy-all="https://app.camunda.com" --record-mappings --verbose --https-port=8443 --root-dir=$ROOT_WIREMOCK_DIR --match-headers="Accept,Content-Type,Authorization"
