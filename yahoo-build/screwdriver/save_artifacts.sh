#!/usr/bin/env bash

set -eux -o pipefail
export PS4='+(${BASH_SOURCE}:${LINENO}): ${FUNCNAME[0]:+${FUNCNAME[0]}(): }'

YSTORM_DIST_OS="${1}"

function save_file {
    local Path="${1}"
    # Saves the file.
    cp -nv "${Path}" "${SD_ARTIFACTS_DIR}/"

    # Updates the list of saved files.
    local OldFiles="$(meta get ${YSTORM_DIST_OS}.files)"
    if [[ 'null' == "${OldFiles}" ]]
    then
        meta set "${YSTORM_DIST_OS}".files "$(basename "${Path}")"
    else
        meta set "${YSTORM_DIST_OS}".files "${OldFiles} $(basename "${Path}")"
    fi
}

# Saves a package as a build artifact.
#
# For a given OS, we resolve a path to the package that was built, copy this
# package to the artifacts directory so that it will be automatically stored,
# and set a metadata value containing the file name of the package so that any
# downstream job can know what file to fetch.
PackagePath="$(ls "${SD_SOURCE_DIR}/yahoo-build/packages/${YSTORM_DIST_OS}"/ystorm-*.tgz)"
save_file "${PackagePath}"

# Saves the RELEASE file
ReleasePath="$(ls "${SD_SOURCE_DIR}/yahoo-build/RELEASE")"
save_file "${ReleasePath}"
