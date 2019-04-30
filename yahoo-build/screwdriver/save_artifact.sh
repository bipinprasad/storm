#!/usr/bin/env bash

set -eux -o pipefail
export PS4='+(${BASH_SOURCE}:${LINENO}): ${FUNCNAME[0]:+${FUNCNAME[0]}(): }'

# Saves a package as a build artifact.
#
# For a given OS, we resolve a path to the package that was built, copy this
# package to the artifacts directory so that it will be automatically stored,
# and set a metadata value containing the file name of the package so that any
# downstream job can know what file to fetch.
YSTORM_DIST_OS="${1}"
PackagePath="$(ls "${SD_SOURCE_DIR}/yahoo-build/packages/${YSTORM_DIST_OS}"/ystorm-*.tgz)"
cp -v "${PackagePath}" "${SD_ARTIFACTS_DIR}/"
meta set "${YSTORM_DIST_OS}".files "$(basename "${PackagePath}")"
