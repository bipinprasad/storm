"""
Update Athenz key and certificate files. Download software if necessary.
"""

import sys
import os
import time
import platform
import yaml

ATHENZ_CERT_GETTER_SCRIPT_NAME = 'get-athenz-cert.sh'
ATHENZ_DOMAIN_GETTER_SCRIPT_NAME = 'get-athenz-domain.sh'

ATHENZ_KEY_FILE = os.path.expanduser('~/.athenz/key')
ATHENZ_CERT_FILE = os.path.expanduser('~/.athenz/cert')


def is_mac():
    return platform.platform().startswith('macOS')


def install_athenz_user_cert_and_zms_cli():
    """
    Download athenz-user-cert binary package for the specified operating system
    :return: no return value
    """
    install_athenz_software('athenz-user-cert', '1.6.5')
    install_athenz_software('zms-cli', '2.5.9')


def install_athenz_software(package, version, release_url='https://artifactory.ouroath.com/artifactory/simple/core-tech/releases'):
    """
    Download athenz-user-cert binary package for the specified operating system
    :param package_os: Operating system, typically Darwin (for Mac) or Linux.
    :return: no return value
    """

    package_os = get_package_os()
    mydir = os.path.dirname(os.path.abspath(__file__))
    cached_binary_dir = f'{mydir}/cache/{package_os}'
    cached_binary_path = f'{cached_binary_dir}/{package}'
    if os.path.exists(cached_binary_path) and os.path.getsize(cached_binary_path) > 4000000:
        print(f'INFO: Skip software install, binary exists at path: {cached_binary_path}')
        return
    if not os.path.exists(os.path.dirname(cached_binary_dir)):
        os.mkdir(os.path.dirname(cached_binary_dir))
    if not os.path.exists(cached_binary_dir):
        os.mkdir(cached_binary_dir)
    package_url = f'{release_url}/{package}/{version}/{package_os}/{package}'
    cmd = f'curl {package_url} -o {cached_binary_path}'
    print(f'Executing command: {cmd}')
    ret = os.system(cmd)
    if ret != 0:
        raise RuntimeError(f'ERROR: failed to get {package} from url {package_url} using command "{cmd}"')
    if (not os.path.exists(cached_binary_path)) or os.path.getsize(cached_binary_path) < 4000000:
        raise RuntimeError(f'ERROR: failed to get {cached_binary_path} from url {package_url} using command "{cmd}"')
    os.chmod(cached_binary_path, mode=0o755)


def create_athenz_cert_getter_binary_script():
    """
    Create a shell script to run the athenz-user-cert package.
    :return: no return value
    """
    package_os = get_package_os()
    script_content = (
        "#!/bin/bash -x\n"
        "# Get Athenz User Certificate and Key.\n"
        "# While making URL call to Storm UI API,\n"
        "#       - Athenz certificate must be provided in Cookie as okta_at value\n"
        "#       - Alternatively (preferred), CURL can be used with key_file, cert_file, okta_proxy_url\n"
        "#\n"
        "# Prerequisites:\n"
        "#    athenz-user-cert  - must be installed in the same directory\n"
        "export MYDIR=\"$(dirname ${BASH_SOURCE[0]})\"\n"
        "export PATH=${MYDIR}:$PATH\n"
        "        # required by zts-rolecert - created by athenz-user-cert command\n"
        "export SVC_IDENTITY_PRIVATE_KEY_FILE=~/.athenz/key\n"
        "export SVC_IDENTITY_CERT_FILE=~/.athenz/cert\n"
        "echo \"WARNING: Deleting ${SVC_IDENTITY_PRIVATE_KEY_FILE} ${SVC_IDENTITY_CERT_FILE} before running athenz-user-cert\"\n"
        "rm -f ${SVC_IDENTITY_PRIVATE_KEY_FILE} ${SVC_IDENTITY_CERT_FILE}\n"
        "\n"
        "${MYDIR}/athenz-user-cert -s fed.athens.yahoo.com\n"
    )
    cached_binary_path = get_athenz_cert_getter_script_path()
    if os.path.exists(cached_binary_path):
        print(f'INFO: Skip creating script, binary exists at path: {cached_binary_path}')
    else:
        with open(cached_binary_path, "w") as f:
            f.write(script_content)
    os.chmod(cached_binary_path, mode=0o755)
    return cached_binary_path


def create_athenz_domain_getter_binary_script():
    """
    Create a shell script to run zms-cli package.
    Running zms-cli -d $domain show-domain
    :return: no return value
    """
    package_os = get_package_os()
    athenz_conf_dir = os.path.expanduser("~/.athenz")
    script_lines = (
        "#!/bin/bash -x",
        "# Get Athenz Domain info (with the goal of getting AWS id associated with the specified Athenz domain.",
        "#",
        "# Prerequisites:",
        "#    zms-cli  - must be installed in the same directory",
        "#             - ~/.athenz/config should have the variables set",
        f"#                svc-key-file: {athenz_conf_dir}/key",
        f"#                svc-cert-file: {athenz_conf_dir}/cert",
        "#                zts: https://zts.athens.yahoo.com:4443/zts/v1",
        "#",
        'if [ -z "$1" ]',
        'then',
        '    echo "No athenz-domain argument supplied"',
        '    exit 1',
        'fi',
        'export MYDIR="$(dirname ${BASH_SOURCE[0]})"',
        'export PATH=${MYDIR}:$PATH',
        '',
        '${MYDIR}/zms-cli -d ${1} show-domain > ${MYDIR}/domain.${1}.yaml'
    )
    script_content = "\n".join(script_lines) + "\n"
    cached_binary_path = get_athenz_domain_getter_script_path()
    if os.path.exists(cached_binary_path):
        print(f'INFO: Skip creating script, binary exists at path: {cached_binary_path}')
    else:
        with open(cached_binary_path, "w") as f:
            f.write(script_content)
    os.chmod(cached_binary_path, mode=0o755)
    return cached_binary_path


def get_athenz_cert_getter_script_path():
    return get_cached_script_path(ATHENZ_CERT_GETTER_SCRIPT_NAME)


def get_athenz_domain_getter_script_path():
    return get_cached_script_path(ATHENZ_DOMAIN_GETTER_SCRIPT_NAME)


def get_athenz_domain_info_path(athenz_domain_name):
    domain_getter_script_path = get_cached_script_path(ATHENZ_DOMAIN_GETTER_SCRIPT_NAME)
    script_dirpath = os.path.dirname(domain_getter_script_path)
    return os.path.join(script_dirpath, f'domain.{athenz_domain_name}.yaml')


def get_cached_script_path(script_name):
    package_os = get_package_os()
    mydir = os.path.dirname(os.path.abspath(__file__))
    cached_binary_dir = f'{mydir}/cache/{package_os}'
    cached_binary_path = f'{cached_binary_dir}/{script_name}'
    return cached_binary_path


def is_file_newer(path, seconds=60*30, debug=False):
    """
    Check if file is newer than specified time in seconds
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


def get_athenz_key_and_cert():
    """
    Refresh older Athenz key and certificate files by running the script get-athenz-cert.sh
    in the current directory.

    :return: exist status from running os.system(script)
    """
    if os.path.exists(ATHENZ_KEY_FILE) and os.path.exists(ATHENZ_CERT_FILE) and is_file_newer(ATHENZ_KEY_FILE) and is_file_newer(ATHENZ_CERT_FILE):
        print(f'INFO: Skip updating Athenz key and certificate, since they are new enough in {ATHENZ_KEY_FILE} and {ATHENZ_CERT_FILE}')
        return 0

    install_athenz_user_cert_and_zms_cli()
    exec_script = create_athenz_cert_getter_binary_script()
    print(f'Getting Athenz Cert and Key: "{exec_script}"')
    ret = os.system(exec_script)
    if ret == 0:
        return 0
    sys.exit(f'errcode={ret} from command "{exec_script}",\n\t *** did you run yinit first? ***')


def get_athenz_domain_info(athenz_domain):
    exec_script = create_athenz_domain_getter_binary_script()
    exec_cmd = f'{exec_script} {athenz_domain}'
    print(f'Getting Athenz domain info: "{exec_cmd}"')
    ret = os.system(exec_cmd)
    if ret:
        sys.exit(f'errcode={ret} from command "{exec_cmd}",\n\t *** did you run yinit first? ***')
    with open(get_athenz_domain_info_path(athenz_domain)) as f:
        domain_info_yaml = yaml.safe_load(f)
    return domain_info_yaml


def get_package_os():
    """
    :return: Operating system, typically Darwin (for Mac) or Linux.
    """
    package_os = 'Darwin' if is_mac() else 'Linux'
    return package_os


if __name__ == "__main__":
    get_athenz_key_and_cert()
