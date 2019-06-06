#!/usr/bin/env bash

set -x

RELEASE=$1
PACKAGE_NAME=ystorm
PACKAGE_VERSION=ystorm-${RELEASE}
GIT_REPO=git@git.ouroath.com:storm/storm-cicd.git
BRANCH=master
REPO_DIR=storm-cicd

rm -rf ${REPO_DIR}
git clone --depth 1 --branch ${BRANCH} ${GIT_REPO} ${REPO_DIR}
cd ./${REPO_DIR}/ystorm-2.x/screwdriver && ./update_package_info.sh $PACKAGE_NAME $PACKAGE_VERSION




