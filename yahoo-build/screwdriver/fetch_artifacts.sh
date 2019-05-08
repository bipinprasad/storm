#!/usr/bin/env bash

set -eux -o pipefail
export PS4='+(${BASH_SOURCE}:${LINENO}): ${FUNCNAME[0]:+${FUNCNAME[0]}(): }'

# Fetches all SDv4 artifacts from our parent jobs.
#
# Jobs that build ystorm package files are run as Parallel jobs.
# Each Parallel job builds a package for only one OS.
#
# The Join job must first fetch these files before it can operate on them.
# This script is to help the Join job fetch artifacts so that it can do its
# work.

function fetch_file {
    build_id="$1"
    file_name="$2"
    output_file="$3"

    mkdir -pv "$(dirname "$output_file")"
    url="https://api.screwdriver.ouroath.com/v4/builds/${build_id}/artifacts/$file_name"
    http_code="$(\
        curl -Lsvo "$output_file" \
        -w '%{http_code}' \
        -H "Authorization: Bearer $SD_TOKEN" \
        "$url")"

    if [[ '200' != "$http_code" ]]
    then
        echo "Failed to download '$url'"
        exit 1
    fi

    ls -l "$output_file"
}


function fetch_files {
    build_id="$(meta get ${1}.build_id)"
    files="$(meta get ${1}.files)"
    for f in $files
    do
        # Places files under directories for the respective cluster.
        output_file="${SD_ARTIFACTS_DIR}/yahoo-build/packages/$1/$f"
        fetch_file "$build_id" "$f" "$output_file"
    done

    echo "Done fetching."
}

for os in 'rhel-6.x' 'rhel-7.x'
do
    fetch_files "$os"
done
