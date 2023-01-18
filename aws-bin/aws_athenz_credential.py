#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import os.path
import time
from update_athenz_key import get_athenz_key_and_cert


def is_file_newer(path, seconds=60*30, debug=False):
    """
    Check if file is newer than specified time in seconds
    :param seconds:
    :param debug:
    :param path: path to check
    :return: True if file is newer, False otherwise
    """

    if not path:
        if debug:
            print(f'DEBUG: File not provided, therefore it is NOT newer than {seconds} seconds')
        return False

    if not os.path.exists(path):
        print(f'INFO: File {path} does not exist, therefore it is NOT newer than {seconds} seconds')
        return False

    ctime = os.stat(path).st_ctime
    age = int(time.time() - ctime)
    is_newer = age < seconds
    if debug:
        print(f'DEBUG: File {path} exists, its age is {age} seconds and {age}<{seconds}, therefore newer={is_newer}')
    return is_newer


class AwsAthenzCredential:
    verbose = False
    athenz_conf_dir = os.path.expanduser("~/.athenz")
    aws_conf_dir = os.path.expanduser("~/.aws")
    athenz_conf_file = os.path.join(athenz_conf_dir, "config")
    athenz_configs = {}

    def __init__(self, verbose=False):
        self.verbose = verbose
        for conf_dir in [self.athenz_conf_dir, self.aws_conf_dir]:
            if not os.path.exists(conf_dir):
                if self.verbose:
                    print(f"Creating missing directory {conf_dir}")
                os.mkdir(conf_dir)
        self._init_athenz_conf_file()

    def _init_athenz_conf_file(self):
        """
        Make sure that the config file exists and has entries for
            svc-key-file: /Users/hga/.athenz/key
            svc-cert-file: /Users/hga/.athenz/cert
            zts: https://zts.athens.yahoo.com:4443/zts/v1

        The load the final configs into self.athenz_configs.
        This method is called from constructor only.

        :return:
        """
        if self.verbose:
            print(f"Setting {self.athenz_conf_file} for key and certificate defaults")
        with open(self.athenz_conf_file, "a+") as f:
            f.close()
        default_kv = {'svc-key-file' : os.path.join(self.athenz_conf_dir, 'key'),
                      'svc-cert-file': os.path.join(self.athenz_conf_dir, 'cert'),
                      'zts': 'https://zts.athens.yahoo.com:4443/zts/v1'}
        self.athenz_configs = dict(**default_kv)
        seen_keys = set()
        changed = False
        with open(self.athenz_conf_file, "r") as f:
            orig_lines = [x for x in f.readlines()]
            for line_no, orig_line in enumerate(orig_lines):
                line = orig_line.lstrip()
                if not line:
                    continue
                if line[0] == '#':
                    continue
                if ":" not in line:
                    print(f"Found line not in key:value format - \"{line}\"")
                    continue
                line_parts = line.split(":", 2)
                key = line_parts[0].strip()
                val = None
                # value is not set, remove the line
                if len(line_parts) == 1 or not len(line_parts[1].strip()):
                    orig_lines[line_no] = ""
                    changed = True
                    continue
                if key not in default_kv:
                    continue
                self.athenz_configs[key] = line_parts[1].strip()
                seen_keys.add(key)
        # add all the keys not seen
        missing_key_lines = [f'{x}: {y}\n' for x,y in default_kv.items() if x not in seen_keys]
        if missing_key_lines:
            changed = True
            orig_lines.extend(missing_key_lines)

        if changed:
            print(f"Changing content of file {self.athenz_conf_file}")
            with open(self.athenz_conf_file, "w+") as f:
                f.writelines(orig_lines)

    def get_key_file(self):
        return self.athenz_configs['svc-key-file']

    def get_cert_file(self):
        return self.athenz_configs['svc-cert-file']

    def get_zts_url(self):
        return self.athenz_configs['zts']

    def refresh_key_and_cert_files(self, force=False):
        # do not refresh if the key and cert files are less than 30 minutes old
        if not force:
            if is_file_newer(self.get_key_file()) and is_file_newer(self.get_cert_file()):
                return
        get_athenz_key_and_cert()


if __name__ == '__main__':
    aws_athenz_credential = AwsAthenzCredential()
    aws_athenz_credential.refresh_key_and_cert_files()




