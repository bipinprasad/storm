#!/usr/bin/env python

import argparse
from collections import Iterable
import glob
import hashlib
import json
import logging
import os
from pprint import pprint
import re
import shutil
import subprocess
import sys
import tarfile

def shell_command(command, print_stdout, print_stderr, raise_on_error):
    global log_level
    stdout_val = subprocess.PIPE
    stderr_val = subprocess.PIPE

    logging.debug("command: %s" % (command))

    if print_stdout:
        stdout_val = None

    if print_stderr or log_level == "DEBUG":
        stderr_val = None

    process = subprocess.Popen(command, stdout=stdout_val,
                               stderr=stderr_val)
    process.wait()
    if raise_on_error and process.returncode is not 0:
        out, err = process.communicate()
        raise Exception("Commmand: " + str(command)
                        + " failed with returncode: "
                        + str(process.returncode) + "\nstdout: "
                        + str(out) + "\nstderr: " + str(err))
    return process

def does_hdfs_entry_exist(entry):
    ret = hdfs_ls(entry)
    if ret.returncode is not 0:
        return False
    return True

def setup_hdfs_dir(dir_entry):
    if not does_hdfs_entry_exist(dir_entry):
        hdfs_mkdir(dir_entry)
        hdfs_chmod("755", dir_entry)

def append_or_extend_to_list(src, src_list):
    if type(src) is list:
        src_list.extend(src)
    else:
        src_list.append(src)

def hdfs_get(src, dest, print_stdout=False, print_stderr=False, raise_on_error=True):
    command = ["hadoop", "fs", "-get"]
    append_or_extend_to_list(src, command)
    command.append(dest)
    ret = shell_command(command, print_stdout, print_stderr, raise_on_error)
    return ret

def hdfs_ls(file_path, options="", print_stdout=False, print_stderr=False, raise_on_error=False):
    command = ["hadoop", "fs", "-ls"]
    if options:
        append_or_extend_to_list(options, command)
    append_or_extend_to_list(file_path, command)
    ret = shell_command(command, print_stdout, print_stderr, raise_on_error)
    return ret

def hdfs_cat(file_path, print_stdout=False, print_stderr=True, raise_on_error=True):
    command = ["hadoop", "fs", "-cat"]
    append_or_extend_to_list(file_path, command)
    ret = shell_command(command, print_stdout, print_stderr, raise_on_error)
    return ret

def hdfs_mkdir(file_path, print_stdout=False, print_stderr=True, raise_on_error=True):
    command = ["hadoop", "fs", "-mkdir", "-p"]
    append_or_extend_to_list(file_path, command)
    ret = shell_command(command, print_stdout, print_stderr, raise_on_error)
    return ret

def hdfs_rm(file_path, print_stdout=False, print_stderr=True, raise_on_error=True):
    command = ["hadoop", "fs", "-rm"]
    append_or_extend_to_list(file_path, command)
    ret = shell_command(command, print_stdout, print_stderr, raise_on_error)
    return ret

def hdfs_put(src, dest, force=False, print_stdout=False, print_stderr=True, raise_on_error=True):
    command = ["hadoop", "fs", "-put"]
    if force:
        command.append("-f")
    append_or_extend_to_list(src, command)
    command.append(dest)
    ret = shell_command(command, print_stdout, print_stderr, raise_on_error)
    return ret

def hdfs_chmod(mode, file_path, print_stdout=False, print_stderr=True, raise_on_error=True):
    command = ["hadoop", "fs", "-chmod", mode]
    append_or_extend_to_list(file_path, command)
    ret = shell_command(command, print_stdout, print_stderr, raise_on_error)
    return ret

def hdfs_setrep(replication, file_path, print_stdout=False, print_stderr=True, raise_on_error=True):
    command = ["hadoop", "fs", "-setrep", str(replication)]
    append_or_extend_to_list(file_path, command)
    ret = shell_command(command, print_stdout, print_stderr, raise_on_error)
    return ret

def hdfs_cp(src, dest, force=False, print_stdout=False, print_stderr=True, raise_on_error=False):
    command = ["hadoop", "fs", "-cp"]
    if force:
        command.append("-f")
    append_or_extend_to_list(src, command)
    command.append(dest)
    ret = shell_command(command, print_stdout, print_stderr, raise_on_error)
    return ret

def is_sha256_hash(string):
    if not re.findall(r"^[a-fA-F\d]{64,64}$", string):
        return False
    return True

def calculate_file_hash(filename):
    sha = hashlib.sha256()
    with open(filename, 'rb') as f:
        while True:
            data = f.read(65536)
            if not data:
                break
            sha.update(data)
    return sha.hexdigest()

def calculate_string_hash(string):
    sha = hashlib.sha256()
    sha.update(string)
    return sha.hexdigest()

def get_local_manifest_from_path(manifest_path):
    out = open(manifest_path, "rb").read()
    manifest_hash = calculate_string_hash(str(out))
    manifest = json.loads(out)
    return manifest, manifest_hash

def get_hdfs_manifest_from_path(manifest_path):
    ret = hdfs_cat(manifest_path)
    out, err = ret.communicate()
    manifest_hash = calculate_string_hash(str(out))
    manifest = json.loads(out)
    return manifest, manifest_hash

def get_config_hash_from_manifest(manifest):
    config_hash = manifest['config']['digest'].split(":", 1)[1]
    return config_hash

def get_layer_hashes_from_manifest(manifest):
    layers = []
    layers_dict = manifest['layers']
    for layer in layers_dict:
        layers.append(layer['digest'].split(":", 1)[1])
    return layers

def get_manifest_from_docker_image(pull_format, image):
    ret = shell_command(["skopeo", "inspect", "--raw", pull_format + "://" + image],
                        False, True, True)
    out, err = ret.communicate()
    manifest_hash = calculate_string_hash(str(out))
    manifest = json.loads(out)
    return manifest, manifest_hash

def split_image_and_tag(image_and_tag):
    split = image_and_tag.split(",")
    image = split[0]
    tags = split[1:]
    return image, tags

def read_image_tag_to_hash(image_tag_to_hash):
    hash_to_tags_dict  = dict()
    tag_to_hash_dict = dict()
    with open(image_tag_to_hash, 'rb') as f:
        while True:
            line = f.readline()
            if not line:
                break
            line = line.rstrip()

            if not line:
                continue

            comment_split_line = line.split("#", 1)
            line = comment_split_line[0]
            comment = comment_split_line[1:]

            split_line = line.rsplit(":", 1)
            manifest_hash = split_line[-1]
            tags_list = ' '.join(split_line[:-1]).split(",")

            if not is_sha256_hash(manifest_hash) or not tags_list:
                logging.warn("image-tag-to-hash file malformed. Skipping entry %s" % (line))
                continue

            tags_and_comments = hash_to_tags_dict.get(manifest_hash, None)
            if tags_and_comments is None:
                known_tags = tags_list
                known_comment = comment
            else:
                known_tags = tags_and_comments[0]
                for tag in tags_list:
                    if tag not in known_tags:
                        known_tags.append(tag)
                known_comment = tags_and_comments[1]
                known_comment.extend(comment)

            hash_to_tags_dict[manifest_hash] = (known_tags, known_comment)

            for tag in tags_list:
                cur_manifest = tag_to_hash_dict.get(tag, None)
                if cur_manifest is not None:
                    logging.warn("tag_to_hash_dict already has manifest %s defined for tag %s. This entry will be overwritten" % (cur_manifest, tag))
                tag_to_hash_dict[tag] = manifest_hash
    return hash_to_tags_dict, tag_to_hash_dict

def remove_tag_from_dicts(hash_to_tags_dict, tag_to_hash_dict, tag):
    if not hash_to_tags_dict:
        logging.debug("hash_to_tags_dict is null. Not removing tag %s" % (tag))
        return

    prev_hash = tag_to_hash_dict.get(tag, None)

    if prev_hash is not None:
        del tag_to_hash_dict[tag]
        prev_tags, prev_comment = hash_to_tags_dict.get(prev_hash, (None, None))
        prev_tags.remove(tag)
        if len(prev_tags) == 0:
            del hash_to_tags_dict[prev_hash]
        else:
            hash_to_tags_dict[prev_hash] = (prev_tags, prev_comment)
    else:
        logging.debug("Tag not found. Not removing tag: %s" % (tag))

def remove_image_hash_from_dicts(hash_to_tags_dict, tag_to_hash_dict, image_hash):
    if not hash_to_tags_dict:
        logging.debug("hash_to_tags_dict is null. Not removing image_hash %s" % (image_hash))
        return
    logging.debug("hash_to_tags_dict: %s" % (str(hash_to_tags_dict)))
    logging.debug("Removing image_hash from dicts: %s" % (image_hash))
    prev_tags, prev_comments = hash_to_tags_dict.get(image_hash, None)

    if prev_tags is not None:
        hash_to_tags_dict.pop(image_hash)
        for tag in prev_tags:
            del tag_to_hash_dict[tag]

def add_tag_to_dicts(hash_to_tags_dict, tag_to_hash_dict, tag, manifest_hash, comment):
    tag_to_hash_dict[tag] = manifest_hash
    new_tags_and_comments = hash_to_tags_dict.get(manifest_hash, None)
    if new_tags_and_comments is None:
        new_tags = [tag]
        new_comment = [comment]
    else:
        new_tags = new_tags_and_comments[0]
        new_comment = new_tags_and_comments[1]
        if tag not in new_tags:
            new_tags.append(tag)
        if comment and comment not in new_comment:
            new_comment.append(comment)
    hash_to_tags_dict[manifest_hash] = (new_tags, new_comment)

def write_local_image_tag_to_hash(image_tag_to_hash, hash_to_tags_dict):
    with open(image_tag_to_hash, 'w') as f:
        for key, value in hash_to_tags_dict.iteritems():
            manifest_hash = key
            tags = ','.join(map(str, value[0]))
            if len(tags) > 0:
                comment = ', '.join(map(str, value[1]))
                if len(comment) > 0:
                    comment = "#" + comment
                f.write(tags + ":" + manifest_hash + comment + "\n")
            else:
                for comment in value[1]:
                    f.write("#" + comment + "\n")

def update_dicts_for_multiple_tags(hash_to_tags_dict, tag_to_hash_dict, tags, manifest_hash, comment):
    for tag in tags:
        update_dicts(hash_to_tags_dict, tag_to_hash_dict, tag, manifest_hash, comment)

def update_dicts(hash_to_tags_dict, tag_to_hash_dict, tag, manifest_hash, comment):
    remove_tag_from_dicts(hash_to_tags_dict, tag_to_hash_dict, tag)
    add_tag_to_dicts(hash_to_tags_dict, tag_to_hash_dict, tag, manifest_hash, comment)

def remove_from_dicts(hash_to_tags_dict, tag_to_hash_dict, tags):
    for tag in tags:
        logging.debug("removing tag: " + tag)
        remove_tag_from_dicts(hash_to_tags_dict, tag_to_hash_dict, tag)

def populate_tag_dicts(hdfs_root, image_tag_to_hash, local_image_tag_to_hash):

    if does_hdfs_entry_exist(hdfs_root + "/" + image_tag_to_hash):
        ret = hdfs_get(hdfs_root + "/" + image_tag_to_hash, local_image_tag_to_hash)
        image_tag_to_hash_file_hash = calculate_file_hash(local_image_tag_to_hash)
    else:
        image_tag_to_hash_file_hash = 0

    if image_tag_to_hash_file_hash != 0:
        hash_to_tags_dict, tag_to_hash_dict = read_image_tag_to_hash(local_image_tag_to_hash)
    else:
        hash_to_tags_dict = {}
        tag_to_hash_dict = {}
    return hash_to_tags_dict, tag_to_hash_dict, image_tag_to_hash_file_hash


def setup_squashfs_hdfs_dirs(layers, config, manifest):
    dirs = [layers, config, manifest]
    logging.debug("Setting up squashfs dirs: " + str(dirs))
    for dir_entry in dirs:
        setup_hdfs_dir(dir_entry)

def skopeo_copy_image(pull_format, image, skopeo_format, skopeo_dir):
    logging.info("Pulling image: " + image)
    if os.path.isdir(skopeo_dir):
        raise Exception("Skopeo output directory already exists. "
                        + "Please delete and try again "
                        + "Directory: " + skopeo_dir)
    shell_command(["skopeo", "copy", pull_format + "://" + image,
                   skopeo_format + ":" + skopeo_dir], False, True, True)

def untar_layer(tmp_dir, layer_path):
    shell_command(["sudo", "tar", "-C", tmp_dir, "--xattrs",
                   "--xattrs-include='*'", "-xzf", layer_path],
                  False, True, True)

def set_fattr(d):
    shell_command(["sudo", "setfattr", "-n","trusted.overlay.opaque",
                   "-v", "y", d], False, True, True)

def make_whiteout_block_device(w, whiteout):
    shell_command(["sudo", "mknod", "-m", "000", w,
                   "c", "0", "0"], False, True, True)

    ret = shell_command(["stat", "-c", "%U:%G", whiteout], False, True, True)
    out, err = ret.communicate()
    perms = str(out).strip()

    shell_command(["sudo", "chown", perms, w], False, True, True)

def convert_oci_whiteouts(tmp_dir):
    ret = shell_command(["sudo", "find", tmp_dir, "-name", ".wh.*"],
                        False, False, True)
    out, err = ret.communicate()
    whiteouts = str(out).splitlines()
    for whiteout in whiteouts:
        if len(whiteout) == 0:
            continue
        b = os.path.basename(whiteout)
        d = os.path.dirname(whiteout)
        if b == ".wh..wh..opq":
            set_fattr(d)
        else:
            whiteout_string = ".wh."
            idx = b.rfind(whiteout_string)
            bname = b[idx+len(whiteout_string):]
            w = os.path.join(d, bname)
            make_whiteout_block_device(w, whiteout)
        shell_command(["sudo", "rm", whiteout], False, True, True)

def dir_to_squashfs(tmp_dir, squash_path):
    shell_command(["sudo", "mksquashfs", tmp_dir, squash_path],
                  False, True, True)

def upload_to_hdfs(file_path, file_name, hdfs_dir, replication, mode, force=False):
    dest = hdfs_dir + "/" + file_name

    if does_hdfs_entry_exist(dest):
        if not force:
            logging.warn("Not uploading to HDFS. File already exists: " + dest)
            return
        logging.info("File already exists, but overwriting due to force option: " + dest)

    hdfs_put(file_path, dest, force)
    hdfs_setrep(replication, dest)
    hdfs_chmod(mode, dest)
    logging.info("Uploaded file %s with replication %d and permissions %s" % (dest, replication, mode))

def atomic_upload_mv_to_hdfs(file_path, file_name, hdfs_dir, replication, hadoop_prefix, image_tag_to_hash_file_hash):
    local_hash = calculate_file_hash(file_path)
    if local_hash == image_tag_to_hash_file_hash:
        logging.info("image_tag_to_hash file unchanged. Not uploading")
        return
    tmp_file_name = file_name + ".tmp"
    hdfs_tmp_path = hdfs_dir + "/" + tmp_file_name
    hdfs_file_path = hdfs_dir + "/" + file_name
    try:
        if does_hdfs_entry_exist(hdfs_tmp_path):
            hdfs_rm(hdfs_tmp_path)
        hdfs_put(file_path, hdfs_tmp_path)

        jar_path = hadoop_prefix + "/share/hadoop/tools/lib/hadoop-extras-*.jar"
        for file in glob.glob(jar_path):
            jar_file = file

        if not jar_file:
            raise Exception("SymlinkTool Jar doesn't exist: %s" % (jar_path))

        logging.debug("jar_file: " + jar_file)

        shell_command(["hadoop", "jar", jar_file, "org.apache.hadoop.tools.SymlinkTool",
                       "mvlink", "-f", hdfs_tmp_path, hdfs_file_path], False, False, True)

        hdfs_setrep(replication, hdfs_file_path)
        hdfs_chmod("444", hdfs_file_path)

    except:
        if does_hdfs_entry_exist(hdfs_tmp_path):
            hdfs_rm(hdfs_tmp_path)
        raise

def docker_to_squash(layer_dir, layer, working_dir):
    tmp_dir = os.path.join(working_dir, "expand_archive_" + layer)
    layer_path = os.path.join(layer_dir, layer)
    squash_path = layer_path + ".sqsh"
    squash_name = os.path.basename(squash_path)

    if os.path.isdir(tmp_dir):
        raise Exception("tmp_dir already exists. Please delete and try again " +
                        "Directory: " + tmp_dir)
    os.makedirs(tmp_dir)

    try:
        untar_layer(tmp_dir, layer_path)
        convert_oci_whiteouts(tmp_dir)
        dir_to_squashfs(tmp_dir, squash_path)
    finally:
        os.remove(layer_path)
        shell_command(["sudo", "rm", "-rf", tmp_dir],
                      False, True, True)

def pull_build_push_update(args):
    working_dir = args.working_dir
    skopeo_format = args.skopeo_format
    pull_format = args.pull_format
    hdfs_root = args.hdfs_root
    image_tag_to_hash = args.image_tag_to_hash
    local_image_tag_to_hash = os.path.join(working_dir, os.path.basename(image_tag_to_hash))
    replication = args.replication
    force = args.force
    hadoop_prefix = args.hadoop_prefix
    images_and_tags = args.images_and_tags

    hdfs_layers_dir = hdfs_root + "/layers"
    hdfs_config_dir = hdfs_root + "/config"
    hdfs_manifest_dir = hdfs_root + "/manifests"

    try:
        os.makedirs(working_dir)
        setup_hdfs_dir(hdfs_root)
        setup_squashfs_hdfs_dirs(hdfs_layers_dir, hdfs_config_dir, hdfs_manifest_dir)
        hash_to_tags_dict, tag_to_hash_dict, image_tag_to_hash_file_hash = populate_tag_dicts(hdfs_root, image_tag_to_hash, local_image_tag_to_hash)

        for image_and_tag_arg in images_and_tags:
            image, tags = split_image_and_tag(image_and_tag_arg)
            if not image or not tags:
                raise Exception("Positional parameter requires an image and at least 1 tag: " + image_and_tag_arg)

            logging.info("Working on image %s with tags %s" % (image, str(tags)))
            manifest, manifest_hash = get_manifest_from_docker_image(pull_format, image)

            layers = get_layer_hashes_from_manifest(manifest)

            config_hash = get_config_hash_from_manifest(manifest)

            logging.debug("Layers: " + str(layers))
            logging.debug("Config: " + str(config_hash))

            update_dicts_for_multiple_tags(hash_to_tags_dict, tag_to_hash_dict, tags, manifest_hash, image)

            all_layers_exist = True

            if not does_hdfs_entry_exist(hdfs_manifest_dir + "/" + manifest_hash):
                all_layers_exist = False

            if not does_hdfs_entry_exist(hdfs_config_dir + "/" + config_hash):
                all_layers_exist = False

            for layer in layers:
                hdfs_squash_path  = hdfs_layers_dir + "/" + layer + ".sqsh"
                if not does_hdfs_entry_exist(hdfs_squash_path):
                    all_layers_exist = False
                    break

            if all_layers_exist:
                if not force:
                    logging.info("All layers exist in HDFS, skipping this image")
                    continue
                logging.info("All layers exist in HDFS, but force option set, so overwriting image")

            skopeo_dir = os.path.join(working_dir, image.split("/")[-1])
            logging.debug("skopeo_dir: " + skopeo_dir)

            skopeo_copy_image(pull_format, image, skopeo_format, skopeo_dir)

            for layer in layers:
                logging.info("Squashifying and uploading layer: " + layer)
                hdfs_squash_path  = hdfs_layers_dir + "/" + layer + ".sqsh"
                if does_hdfs_entry_exist(hdfs_squash_path):
                    if force:
                        logging.info("Layer already exists, but overwriting due to force option: " + layer)
                    else:
                        logging.info("Layer exists. Skipping and not squashifying or uploading: " + layer)
                        continue

                docker_to_squash(skopeo_dir, layer, working_dir)
                squash_path = os.path.join(skopeo_dir, layer + ".sqsh")
                squash_name = os.path.basename(squash_path)
                upload_to_hdfs(squash_path, squash_name, hdfs_layers_dir, replication, "444", force)


            config_local_path = os.path.join(skopeo_dir, config_hash)
            upload_to_hdfs(config_local_path,
                           os.path.basename(config_local_path),
                           hdfs_config_dir, replication, "444", force)

            manifest_local_path = os.path.join(skopeo_dir ,"manifest.json")
            upload_to_hdfs(manifest_local_path, manifest_hash,
                           hdfs_manifest_dir, replication, "444", force)

        write_local_image_tag_to_hash(local_image_tag_to_hash, hash_to_tags_dict)
        atomic_upload_mv_to_hdfs(local_image_tag_to_hash, image_tag_to_hash,
                                 hdfs_root, replication, hadoop_prefix, image_tag_to_hash_file_hash)
    finally:
        if os.path.isfile(local_image_tag_to_hash):
            os.remove(local_image_tag_to_hash)
        if os.path.isdir(working_dir):
            shell_command(["sudo", "rm", "-rf", working_dir],
                          False, True, True)

def pull_build(args):
    working_dir = args.working_dir
    skopeo_format = args.skopeo_format
    pull_format = args.pull_format
    images_and_tags = args.images_and_tags

    for image_and_tag_arg in images_and_tags:
        image, tags = split_image_and_tag(image_and_tag_arg)
        if not image or not tags:
            raise Exception("Positional parameter requires an image and at least 1 tag: " + image_and_tag_arg)

        logging.info("Working on image %s with tags %s" % (image, str(tags)))
        manifest, manifest_hash = get_manifest_from_docker_image(pull_format, image)

        layers = get_layer_hashes_from_manifest(manifest)

        config_hash = get_config_hash_from_manifest(manifest)

        logging.debug("Layers: " + str(layers))
        logging.debug("Config: " + str(config_hash))

        skopeo_dir = os.path.join(working_dir, image.split("/")[-1])
        logging.debug("skopeo_dir: " + skopeo_dir)

        try:
            os.makedirs(working_dir)
            skopeo_copy_image(pull_format, image, skopeo_format, skopeo_dir)

            for layer in layers:
                logging.info("Squashifying layer: " + layer)
                docker_to_squash(skopeo_dir, layer, working_dir)

        except:
            if os.path.isdir(skopeo_dir):
                shutil.rmtree(skopeo_dir)
            raise

def push_update(args):
    working_dir = args.working_dir
    hdfs_root = args.hdfs_root
    image_tag_to_hash = args.image_tag_to_hash
    local_image_tag_to_hash = os.path.join(working_dir, os.path.basename(image_tag_to_hash))
    replication = args.replication
    force = args.force
    hadoop_prefix = args.hadoop_prefix
    images_and_tags = args.images_and_tags

    hdfs_layers_dir = hdfs_root + "/layers"
    hdfs_config_dir = hdfs_root + "/config"
    hdfs_manifest_dir = hdfs_root + "/manifests"

    try:
        setup_hdfs_dir(hdfs_root)
        setup_squashfs_hdfs_dirs(hdfs_layers_dir, hdfs_config_dir, hdfs_manifest_dir)
        hash_to_tags_dict, tag_to_hash_dict, image_tag_to_hash_file_hash = populate_tag_dicts(hdfs_root, image_tag_to_hash, local_image_tag_to_hash)

        os.makedirs(working_dir)

        for image_and_tag_arg in images_and_tags:
            image, tags = split_image_and_tag(image_and_tag_arg)
            if not image or not tags:
                raise Exception("Positional parameter requires an image and at least 1 tag: " + image_and_tag_arg)

            logging.info("Working on image %s with tags %s" % (image, str(tags)))
            skopeo_dir = os.path.join(working_dir, image.split("/")[-1])
            if not os.path.exists(skopeo_dir):
                raise Exception("skopeo_dir doesn't exists: %s" % (skopeo_dir))
            manifest, manifest_hash = get_local_manifest_from_path(skopeo_dir + "/manifest.json")

            layers = get_layer_hashes_from_manifest(manifest)

            config_hash = get_config_hash_from_manifest(manifest)

            logging.debug("Layers: " + str(layers))
            logging.debug("Config: " + str(config_hash))

            update_dicts_for_multiple_tags(hash_to_tags_dict, tag_to_hash_dict, tags, manifest_hash, image)

            all_layers_exist = True

            if not does_hdfs_entry_exist(hdfs_manifest_dir + "/" + manifest_hash):
                all_layers_exist = False

            if not does_hdfs_entry_exist(hdfs_config_dir + "/" + config_hash):
                all_layers_exist = False

            for layer in layers:
                hdfs_squash_path  = hdfs_layers_dir + "/" + layer + ".sqsh"
                if not does_hdfs_entry_exist(hdfs_squash_path):
                    all_layers_exist = False
                    break

            if all_layers_exist:
                if not force:
                    logging.info("All layers exist in HDFS, skipping this image")
                    continue
                logging.info("All layers exist in HDFS, but force option set, so overwriting image")

            for layer in layers:
                hdfs_squash_path  = hdfs_layers_dir + "/" + layer + ".sqsh"
                if does_hdfs_entry_exist(hdfs_squash_path):
                    if force:
                        logging.info("Layer already exists, but overwriting due to force option: " + layer)
                    else:
                        logging.info("Layer exists. Skipping and not squashifying or uploading: " + layer)
                        continue

                squash_path = os.path.join(skopeo_dir, layer + ".sqsh")
                squash_name = os.path.basename(squash_path)
                upload_to_hdfs(squash_path, squash_name, hdfs_layers_dir, replication, "444", force)


            config_local_path = os.path.join(skopeo_dir, config_hash)
            upload_to_hdfs(config_local_path,
                           os.path.basename(config_local_path),
                           hdfs_config_dir, replication, "444", force)

            manifest_local_path = os.path.join(skopeo_dir, "manifest.json")
            upload_to_hdfs(manifest_local_path, manifest_hash,
                           hdfs_manifest_dir, replication, "444", force)

        write_local_image_tag_to_hash(local_image_tag_to_hash, hash_to_tags_dict)
        atomic_upload_mv_to_hdfs(local_image_tag_to_hash, image_tag_to_hash,
                                 hdfs_root, replication, hadoop_prefix, image_tag_to_hash_file_hash)
    finally:
        if os.path.isfile(local_image_tag_to_hash):
            os.remove(local_image_tag_to_hash)


def remove_image(args):
    hdfs_root = args.hdfs_root
    working_dir = args.working_dir
    image_tag_to_hash = args.image_tag_to_hash
    local_image_tag_to_hash = os.path.join(working_dir, os.path.basename(image_tag_to_hash))
    replication = args.replication
    hadoop_prefix = args.hadoop_prefix
    images_or_tags = args.images_or_tags

    try:
        os.makedirs(working_dir)

        hash_to_tags_dict, tag_to_hash_dict, image_tag_to_hash_file_hash = populate_tag_dicts(hdfs_root, image_tag_to_hash, local_image_tag_to_hash)

        logging.debug("hash_to_tags_dict: " + str(hash_to_tags_dict))
        logging.debug("tag_to_hash_dict: " + str(tag_to_hash_dict))

        hdfs_layers_dir = hdfs_root + "/layers"
        hdfs_config_dir = hdfs_root + "/config"
        hdfs_manifest_dir = hdfs_root + "/manifests"

        delete_list = []

        ret = hdfs_ls(hdfs_manifest_dir, "-C", False, False, False)
        known_images, err = ret.communicate()
        known_images = known_images.split()

        logging.debug("known_images:\n%s" % (known_images))

        layers_to_keep = []

        images_and_tags_to_remove = []
        images_to_remove = []
        for image_or_tag_arg in images_or_tags:
            images_and_tags_to_remove.extend(image_or_tag_arg.split(","))

        logging.debug("images_and_tags_to_remove:\n%s" % (images_and_tags_to_remove))

        if isinstance(images_and_tags_to_remove, Iterable):
            for image in images_and_tags_to_remove:
                if is_sha256_hash(image):
                    image_hash = image
                else:
                    image_hash = tag_to_hash_dict.get(image, None)
                if image_hash:
                    images_to_remove.append(hdfs_manifest_dir + "/" + image_hash)
        else:
            image = images_and_tags_to_remove[0]
            if is_sha256_hash(image):
                image_hash = image
            else:
                image_hash = tag_to_hash_dict.get(image, None)
            if image_hash:
                images_to_remove.append(hdfs_manifest_dir + "/" + image_hash)

        logging.debug("images_to_remove:\n%s" % (images_to_remove))
        if not images_to_remove:
            logging.warn("No images to remove")
            return

        for image in known_images:
            if image not in images_to_remove:
                manifest, manifest_hash = get_hdfs_manifest_from_path(image)
                layers = get_layer_hashes_from_manifest(manifest)
                layers_to_keep.extend(layers)

        logging.debug("layers_to_keep:\n%s" % (layers_to_keep))

        for image_or_tag_arg in images_or_tags:
            images = image_or_tag_arg.split(",")
            for image in images:
                logging.info("removing image: " + image)
                if is_sha256_hash(image):
                    logging.debug("image is sha256")
                    image_hash = image
                else:
                    image_hash = tag_to_hash_dict.get(image, None)
                    if image_hash:
                        logging.debug("image tag exists for %s" % (image))
                    else:
                        logging.info("Not removing %s. Image tag doesn't exist" % (image))
                        continue
                manifest_path = hdfs_manifest_dir + "/" + image_hash
                if does_hdfs_entry_exist(manifest_path):
                    logging.debug("image manifest for %s exists: %s" % (image, manifest_path))
                else:
                    logging.info("Not removing %s. Image manifest doesn't exist: %s" % (image, manifest_path))
                    continue

                delete_list.append(manifest_path)

                manifest, manifest_hash = get_hdfs_manifest_from_path(manifest_path)

                config_hash = get_config_hash_from_manifest(manifest)
                logging.debug("config_hash: %s" % (config_hash))

                delete_list.append(hdfs_config_dir + "/" + config_hash)

                layers = get_layer_hashes_from_manifest(manifest)
                layers_paths = []
                for layer in layers:
                    if layer not in layers_to_keep:
                        layers_paths.append(hdfs_layers_dir + "/" + layer + ".sqsh")
                delete_list.extend(layers_paths)

                logging.debug("delete_list: %s" % (delete_list))

                remove_image_hash_from_dicts(hash_to_tags_dict, tag_to_hash_dict, image_hash)

        write_local_image_tag_to_hash(local_image_tag_to_hash, hash_to_tags_dict)
        atomic_upload_mv_to_hdfs(local_image_tag_to_hash, image_tag_to_hash,
                                 hdfs_root, replication, hadoop_prefix, image_tag_to_hash_file_hash)

        hdfs_rm(delete_list)

    finally:
        if os.path.isfile(local_image_tag_to_hash):
            os.remove(local_image_tag_to_hash)
        if os.path.isdir(working_dir):
            shutil.rmtree(working_dir)

def add_remove_tag(args):
    pull_format = args.pull_format
    hdfs_root = args.hdfs_root
    working_dir = args.working_dir
    image_tag_to_hash = args.image_tag_to_hash
    local_image_tag_to_hash = os.path.join(working_dir, os.path.basename(image_tag_to_hash))
    replication = args.replication
    hadoop_prefix = args.hadoop_prefix
    sub_command = args.sub_command
    images_and_tags = args.images_and_tags

    hdfs_layers_dir = hdfs_root + "/layers"
    hdfs_config_dir = hdfs_root + "/config"
    hdfs_manifest_dir = hdfs_root + "/manifests"

    try:
        os.makedirs(working_dir)

        hash_to_tags_dict, tag_to_hash_dict, image_tag_to_hash_file_hash = populate_tag_dicts(hdfs_root, image_tag_to_hash, local_image_tag_to_hash)

        for image_and_tag_arg in images_and_tags:
            if sub_command == "add-tag":
                image, tags = split_image_and_tag(image_and_tag_arg)
                if is_sha256_hash(image):
                    manifest_hash = image
                else:
                    manifest_hash = tag_to_hash_dict.get(image, None)

                if manifest_hash:
                    manifest_path = hdfs_manifest_dir + "/" + manifest_hash
                    ret = hdfs_cat(manifest_path)
                    out, err = ret.communicate()
                    manifest = json.loads(out)
                    logging.debug("image tag exists for %s" % (image))
                else:
                    manifest, manifest_hash = get_manifest_from_docker_image(pull_format, image)

                update_dicts_for_multiple_tags(hash_to_tags_dict, tag_to_hash_dict, tags, manifest_hash, image)

            elif sub_command == "remove-tag":
                tags = image_and_tag_arg.split(",")
                image = None
                manifest = None
                manifest_hash = 0
                remove_from_dicts(hash_to_tags_dict, tag_to_hash_dict, tags)
            else:
                raise Exception("Invalid sub_command: %s" % (sub_command))

        write_local_image_tag_to_hash(local_image_tag_to_hash, hash_to_tags_dict)
        atomic_upload_mv_to_hdfs(local_image_tag_to_hash, image_tag_to_hash,
                                 hdfs_root, replication, hadoop_prefix, image_tag_to_hash_file_hash)
    finally:
        if os.path.isfile(local_image_tag_to_hash):
            os.remove(local_image_tag_to_hash)
        if os.path.isdir(working_dir):
            shutil.rmtree(working_dir)

def copy_update(args):
    image_tag_to_hash = args.image_tag_to_hash
    working_dir = args.working_dir
    local_src_image_tag_to_hash = os.path.join(working_dir, "src-" + os.path.basename(image_tag_to_hash))
    local_dest_image_tag_to_hash = os.path.join(working_dir, "dest-" + os.path.basename(image_tag_to_hash))
    replication = args.replication
    force = args.force
    hadoop_prefix = args.hadoop_prefix
    src_root = args.src_root
    dest_root = args.dest_root
    images_and_tags = args.images_and_tags

    src_layers_dir = src_root + "/layers"
    src_config_dir = src_root + "/config"
    src_manifest_dir = src_root  + "/manifests"
    dest_layers_dir = dest_root + "/layers"
    dest_config_dir = dest_root + "/config"
    dest_manifest_dir = dest_root  + "/manifests"

    setup_hdfs_dir(dest_root)
    setup_squashfs_hdfs_dirs(dest_layers_dir, dest_config_dir, dest_manifest_dir)

    try:
        os.makedirs(working_dir)
        src_hash_to_tags_dict, src_tag_to_hash_dict, src_image_tag_to_hash_file_hash = populate_tag_dicts(src_root, image_tag_to_hash, local_src_image_tag_to_hash)
        dest_hash_to_tags_dict, dest_tag_to_hash_dict, dest_image_tag_to_hash_file_hash = populate_tag_dicts(dest_root, image_tag_to_hash, local_dest_image_tag_to_hash)

        for image_and_tag_arg in images_and_tags:
            image, tags = split_image_and_tag(image_and_tag_arg)
            if not image:
                raise Exception("Positional parameter requires an image: " + image_and_tag_arg)
            if not tags:
                logging.debug("Tag not given. Using image tag instead: %s" % (image))
                tags = [image]

            src_manifest_hash = src_tag_to_hash_dict.get(image, None)
            if not src_manifest_hash:
                logging.info("Manifest not found for image %s. Skipping" % (image))
                continue

            src_manifest_path = src_manifest_dir + "/" + src_manifest_hash
            dest_manifest_path = dest_manifest_dir + "/" + src_manifest_hash
            src_manifest, src_manifest_hash = get_hdfs_manifest_from_path(src_manifest_path)

            src_tags, src_comments = src_hash_to_tags_dict.get(src_manifest_hash, (None, None))
            src_comment = ', '.join(map(str, src_comments))

            src_config_hash = get_config_hash_from_manifest(src_manifest)
            src_config_path = src_config_dir + "/" + src_config_hash
            dest_config_path = dest_config_dir + "/" + src_config_hash

            src_layers = get_layer_hashes_from_manifest(src_manifest)
            src_layers_paths = [src_layers_dir + "/" + layer + ".sqsh" for layer in src_layers]
            dest_layers_paths = [dest_layers_dir + "/" + layer + ".sqsh" for layer in src_layers]

            logging.debug("Copying Manifest: " + str(src_manifest_path))
            logging.debug("Copying Layers: " + str(src_layers_paths))
            logging.debug("Copying Config: " + str(src_config_hash))

            hdfs_cp(src_layers_paths, dest_layers_dir, force)
            hdfs_cp(src_config_path, dest_config_dir, force)
            hdfs_cp(src_manifest_path, dest_manifest_dir, force)

            hdfs_setrep(replication, dest_layers_paths)
            hdfs_setrep(replication, dest_config_path)
            hdfs_setrep(replication, dest_manifest_path)

            hdfs_chmod("444", dest_layers_paths)
            hdfs_chmod("444", dest_config_path)
            hdfs_chmod("444", dest_manifest_path)

            for tag in tags:
                new_tags_and_comments = src_hash_to_tags_dict.get(src_manifest_hash, None)
                if new_tags_and_comments:
                    comment = ', '.join(map(str, new_tags_and_comments[1]))
                if comment is None:
                    comment = image

                update_dicts(dest_hash_to_tags_dict, dest_tag_to_hash_dict, tag, src_manifest_hash, comment)

            write_local_image_tag_to_hash(local_dest_image_tag_to_hash, dest_hash_to_tags_dict)
            atomic_upload_mv_to_hdfs(local_dest_image_tag_to_hash, image_tag_to_hash,
                                     dest_root, replication, hadoop_prefix, dest_image_tag_to_hash_file_hash)

    finally:
        if os.path.isfile(local_src_image_tag_to_hash):
            os.remove(local_src_image_tag_to_hash)
        if os.path.isfile(local_dest_image_tag_to_hash):
            os.remove(local_dest_image_tag_to_hash)
        if os.path.isdir(working_dir):
            shutil.rmtree(working_dir)

def query_tag(args):
    hdfs_root = args.hdfs_root
    working_dir = args.working_dir
    image_tag_to_hash = args.image_tag_to_hash
    local_image_tag_to_hash = os.path.join(working_dir, os.path.basename(image_tag_to_hash))
    hadoop_prefix = args.hadoop_prefix
    tags = args.tags

    try:
        os.makedirs(working_dir)

        hash_to_tags_dict, tag_to_hash_dict, image_tag_to_hash_file_hash = populate_tag_dicts(hdfs_root, image_tag_to_hash, local_image_tag_to_hash)

        logging.debug("hash_to_tags_dict: " + str(hash_to_tags_dict))
        logging.debug("tag_to_hash_dict: " + str(tag_to_hash_dict))

        hdfs_layers_dir = hdfs_root + "/layers"
        hdfs_config_dir = hdfs_root + "/config"
        hdfs_manifest_dir = hdfs_root + "/manifests"

        for tag in tags:
            image_hash = tag_to_hash_dict.get(tag, None)
            if not image_hash:
                logging.info("image hash mapping doesn't exist for tag %s" % (tag))
                continue

            manifest_path = hdfs_manifest_dir + "/" + image_hash
            if does_hdfs_entry_exist(manifest_path):
                logging.debug("image manifest for %s exists: %s" % (tag, manifest_path))
            else:
                logging.info("Image manifest for %s doesn't exist: %s" % (tag, manifest_path))
                continue

            manifest, manifest_hash = get_hdfs_manifest_from_path(manifest_path)
            layers = get_layer_hashes_from_manifest(manifest)
            config_hash = get_config_hash_from_manifest(manifest)
            config_path = hdfs_config_dir + "/" + config_hash

            layers_paths = [hdfs_layers_dir + "/" + layer + ".sqsh" for layer in layers]

            logging.info("Image info for '%s'" % (tag))
            logging.info(manifest_path)
            logging.info(config_path)
            for layer in layers_paths:
                logging.info(layer)

    finally:
        if os.path.isfile(local_image_tag_to_hash):
            os.remove(local_image_tag_to_hash)
        if os.path.isdir(working_dir):
            shutil.rmtree(working_dir)

def list_tags(args):
    hdfs_root = args.hdfs_root
    image_tag_to_hash = args.image_tag_to_hash

    hdfs_image_tag_to_hash = hdfs_root + "/" + image_tag_to_hash
    if does_hdfs_entry_exist(hdfs_image_tag_to_hash):
        ret = hdfs_cat(hdfs_image_tag_to_hash, True, True, False)
    else:
        logging.error("image-tag-to-hash file doesn't exist: %s" % (hdfs_image_tag_to_hash))

def create_parsers():
    parser = argparse.ArgumentParser()
    add_parser_default_arguments(parser)

    subparsers = parser.add_subparsers(help='sub help', dest='sub_command')

    parse_pull_build_push_update = subparsers.add_parser('pull-build-push-update',
                                                         help='Pull an image, build its squashfs layers, push it to hdfs, and atomically update the image-tag-to-hash file')
    parse_pull_build_push_update.set_defaults(func=pull_build_push_update)
    add_parser_default_arguments(parse_pull_build_push_update)
    parse_pull_build_push_update.add_argument("images_and_tags", nargs= "+",
                                              help="Image and tag argument (can specify multiple)")

    parse_pull_build = subparsers.add_parser('pull-build',
                                             help='Pull an image and build its  squashfs layers')
    parse_pull_build .set_defaults(func=pull_build)
    add_parser_default_arguments(parse_pull_build)
    parse_pull_build.add_argument("images_and_tags", nargs= "+",
                                  help="Image and tag argument (can specify multiple)")

    parse_push_update = subparsers.add_parser('push-update',
                                              help='Push the squashfs layers to hdfs and update the image-tag-to-hash file')
    parse_push_update.set_defaults(func=push_update)
    add_parser_default_arguments(parse_push_update)
    parse_push_update.add_argument("images_and_tags", nargs= "+",
                                   help="Image and tag argument (can specify multiple)")

    parse_remove_image = subparsers.add_parser('remove-image',
                                               help='Remove an image (manifest, config, layers) from hdfs based on its tag or manifest hash')
    parse_remove_image.set_defaults(func=remove_image)
    add_parser_default_arguments(parse_remove_image)
    parse_remove_image.add_argument("images_or_tags", nargs= "+",
                                    help="Image or tag argument (can specify multiple)")

    parse_remove_tag = subparsers.add_parser('remove-tag',
                                             help='Remove an image to tag mapping in the image-tag-to-hash file')
    parse_remove_tag.set_defaults(func=add_remove_tag)
    add_parser_default_arguments(parse_remove_tag)
    parse_remove_tag.add_argument("images_and_tags", nargs= "+",
                                  help="Image and tag argument (can specify multiple)")

    parse_add_tag = subparsers.add_parser('add-tag',
                                          help='Add an image to tag mapping in the image-tag-to-hash file')
    parse_add_tag.set_defaults(func=add_remove_tag)
    add_parser_default_arguments(parse_add_tag)
    parse_add_tag.add_argument("images_and_tags", nargs= "+",
                               help="Image and tag argument (can specify multiple)")

    parse_copy_update = subparsers.add_parser('copy-update',
                                              help='Copy an image from hdfs in one cluster to another and then update the image-tag-to-hash file')
    parse_copy_update.set_defaults(func=copy_update)
    add_parser_default_arguments(parse_copy_update)
    parse_copy_update.add_argument("src_root",
                                   help="HDFS path for source root directory")
    parse_copy_update.add_argument("dest_root",
                                   help="HDFS path for destination root directory")
    parse_copy_update.add_argument("images_and_tags", nargs= "+",
                                   help="Image and tag argument (can specify multiple)")

    parse_query_tag = subparsers.add_parser('query-tag',
                                            help='Get the manifest, config, and layers associated with a tag')
    parse_query_tag.set_defaults(func=query_tag)
    add_parser_default_arguments(parse_query_tag)
    parse_query_tag.add_argument("tags", nargs= "+",
                                 help="Image or tag argument (can specify multiple)")

    parse_list_tags = subparsers.add_parser('list-tags',
                                            help='List all tags in image-tag-to-hash file')
    parse_list_tags.set_defaults(func=list_tags)
    add_parser_default_arguments(parse_list_tags)

    return parser

def add_parser_default_arguments(parser):
    parser.add_argument("--working-dir", type=str, dest='working_dir', default="dts-work-dir",
                        help="Name of working directory")
    parser.add_argument("--skopeo-format", type=str, dest='skopeo_format',
                        default='dir', help="Output format for skopeo copy")
    parser.add_argument("--pull-format", type=str, dest='pull_format',
                        default='docker', help="Pull format for skopeo")
    parser.add_argument("-l", "--log", type=str , dest='log_level',
                        default="INFO", help="Logging level (DEBUG, INFO, WARNING, ERROR, CRITICAL)")
    parser.add_argument("--hdfs-root", type=str, dest='hdfs_root',
                        default='/mapred/docker', help="The root directory in HDFS for all of the squashfs images")
    parser.add_argument("--image-tag-to-hash", type=str,
                        dest='image_tag_to_hash', default='image-tag-to-hash',
                        help="image-tag-to-hash filepath or filename in hdfs")
    parser.add_argument("-r", "--replication", type=int, dest='replication',
                        default=1, help="Replication factor for all files uploaded to HDFS")
    parser.add_argument("--hadoop-prefix", type=str, dest='hadoop_prefix', default=os.environ.get('HADOOP_PREFIX'),
                        help="hadoop_prefix value for environment")
    parser.add_argument("-f", "--force", dest='force',
                        action="store_true", default=False, help="Force overwrites in HDFS")
    return parser

def main():
    parser = create_parsers()
    args, extra = parser.parse_known_args()

    if extra:
        raise Exception("Extra unknown arguments given: %s" % (extra))

    global log_level
    log_level = args.log_level.upper()
    hadoop_prefix = args.hadoop_prefix
    image_tag_to_hash = args.image_tag_to_hash
    working_dir = args.working_dir

    numeric_level = getattr(logging, log_level, None)
    if not isinstance(numeric_level, int):
        logging.error('Invalid log level: ' + log_level)
        return
    logging.basicConfig(format="%(levelname)s: %(message)s", level=numeric_level)

    if hadoop_prefix is None:
        logging.info("hadoop_prefix is not set")

    logging.debug("args: %s" % (str(args)))
    logging.debug("extra: %s" % (str(extra)))
    logging.debug("log_level: " + log_level)
    logging.debug("image_tag_to_hash: " + image_tag_to_hash)
    logging.debug("hadoop_prefix: " + str(hadoop_prefix))

    sys.path.append(hadoop_prefix)

    if "/" in image_tag_to_hash:
        logging.error("image-tag-to-hash cannot contain a /")
        return

    if os.path.exists(working_dir):
        logging.error("working_dir already exists. Delete it and try again: %s" % (working_dir))
        return

    args.func(args)

if __name__ == "__main__":
    main()