#!/bin/bash

[ -n "$HADOOP_PREFIX" ] || { echo "HADOOP_PREFIX must be set"; exit 1; }
PATH=$PATH:$HADOOP_PREFIX/bin
PULL_FORMAT=docker
SKOPEO_FORMAT=dir
HDFS_ROOT=/containers
USE_USER_DIR_OPTION=0
REPLICATION=30
local_image_tag_to_manifest_file="local_image_tag_to_manifest"
tmp_sed_file="tmp.sed"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --skopeo-dir=*) SKOPEO_DIR="${1#*=}"; shift 1;;
    --skopeo-format=*) SKOPEO_FORMAT="${1#*=}"; shift 1;;
    --pull-format=*) PULL_FORMAT="${1#*=}"; shift 1;;
    --hdfs-root=*) HDFS_ROOT="${1#*=}"; shift 1;;
    --image-tag-to-manifest-file=*) IMAGE_TAG_TO_MANIFEST_FILE="${1#*=}"; shift 1;;
    --replication=*) REPLICATION="${1#*=}"; shift 1;;
    --skopeo-dir|--skopeo-format|--pull-format|--hdfs-root|--image-tag-to-manifest-file|--replication) echo "$1 requires an argument" >&2; exit 1;;

    *) IMAGES_AND_TAGS="$IMAGES_AND_TAGS${1#*=} "; shift 1;;
  esac
done

[ -n "$SKOPEO_DIR" ] || { USE_USER_DIR_OPTION=1; }
[ -n "$IMAGES_AND_TAGS" ] || { echo "Error: Image required"; exit 1; }
command -v skopeo 2>/dev/null >/dev/null || { echo "Error: skopeo not found"; exit 1; }
command -v jq 2>/dev/null >/dev/null || { echo "Error: jq not found"; exit 1; }

if [ -n "$IMAGE_TAG_TO_MANIFEST_FILE" ]; then
  if [[ $IMAGE_TAG_TO_MANIFEST_FILE != '/'* ]]; then
    IMAGE_TAG_TO_MANIFEST_FILE=${HDFS_ROOT}/${IMAGE_TAG_TO_MANIFEST_FILE}
  fi
else
  IMAGE_TAG_TO_MANIFEST_FILE=${HDFS_ROOT}/image-tag-to-manifest-file
fi

HDFS_LAYER_DIR=${HDFS_ROOT}/layers
HDFS_CONFIG_DIR=${HDFS_ROOT}/config
HDFS_MANIFEST_DIR=${HDFS_ROOT}/manifests
TMP_IMAGE_TAG_TO_MANIFEST_FILE=${IMAGE_TAG_TO_MANIFEST_FILE}.tmp

hadoop fs -ls $HDFS_ROOT 2>/dev/null >/dev/null
if [ $? != 0 ]; then
  hadoop fs -mkdir $HDFS_ROOT
  hadoop fs -chmod 755 $HDFS_ROOT
fi

HDFS_MANIFEST_HASH=0

hadoop fs -ls $IMAGE_TAG_TO_MANIFEST_FILE 2>/dev/null >/dev/null
if [ $? -eq 0 ]; then
  rm $local_image_tag_to_manifest_file 2>/dev/null >/dev/null
  hadoop fs -copyToLocal $IMAGE_TAG_TO_MANIFEST_FILE $local_image_tag_to_manifest_file
  HDFS_MANIFEST_HASH=$(sha256sum $local_image_tag_to_manifest_file | cut -d ' ' -f1)
fi

rm $tmp_sed_file 2>/dev/null >/dev/null

for image_and_tag in $IMAGES_AND_TAGS; do
  ifs=$IFS
  IFS=','
  image_and_tag_array=( $image_and_tag )
  IFS=$ifs

  image="${image_and_tag_array[0]}"
  tag_array=( "${image_and_tag_array[@]:1}" )

  if [ ${#tag_array[@]} -eq 0 ]; then
    tag_array=( $image )
  fi

  [ "$USE_USER_DIR_OPTION" -eq "0" ] || { SKOPEO_DIR=${image##*/}; }
  ALL_DIGESTS_EXIST=1
  MANIFEST=$(skopeo inspect --raw $PULL_FORMAT://$image)
  MANIFEST_SHA=$(echo -n "$MANIFEST" | sha256sum | cut -d " " -f1)
  LAYERS=$(echo $MANIFEST | jq '.layers[].digest' | cut -d ':' -f2 | cut -d '"' -f1)
  CONFIG_SHA=$(echo $MANIFEST | jq '.config.digest' | cut -d ':' -f2 | cut -d '"' -f1)
  
  DIGESTS="$HDFS_MANIFEST_DIR/$MANIFEST_SHA"
  DIGESTS="$DIGESTS $HDFS_CONFIG_DIR/$CONFIG_SHA"

  for tag in "${tag_array[@]}"; do
    search="^$tag:.*$"
    new_entry=${tag}:${MANIFEST_SHA}
    grep $search $local_image_tag_to_manifest_file 2>/dev/null >/dev/null
    if [ $? -eq 0 ]; then
      echo 's,'"$search"','"$new_entry"',g' >> $tmp_sed_file
    else
      echo "$new_entry" >> $local_image_tag_to_manifest_file
    fi
  done
  if [ -f $tmp_sed_file ]; then
    sed -i -f $tmp_sed_file $local_image_tag_to_manifest_file
    rm $tmp_sed_file
  fi

  for layer in $LAYERS; do 
    SQUASH_NAME="${layer}.sqsh"
    DIGESTS="$DIGESTS $HDFS_LAYER_DIR/$SQUASH_NAME"
  done

  hadoop fs -ls $DIGESTS 2>/dev/null >/dev/null

  if [ $? != 0 ]; then
    skopeo copy $PULL_FORMAT://$image $SKOPEO_FORMAT:$SKOPEO_DIR

    cd $SKOPEO_DIR || { echo "cd to skopeo dir failed"; exit 1; }

    hadoop fs -ls $HDFS_LAYER_DIR $HDFS_CONFIG_DIR $HDFS_MANIFEST_DIR 2>/dev/null >/dev/null
    if [ $? != 0 ]; then
      hadoop fs -mkdir $HDFS_LAYER_DIR $HDFS_CONFIG_DIR $HDFS_MANIFEST_DIR
      hadoop fs -chmod 755  $HDFS_LAYER_DIR $HDFS_CONFIG_DIR $HDFS_MANIFEST_DIR
    fi

    for layer in $LAYERS; do
      SQUASH_NAME="${layer}.sqsh"
      echo "Working on layer: $layer"

      hadoop fs -ls $HDFS_LAYER_DIR/$SQUASH_NAME 2>/dev/null >/dev/null
      if [ $? -eq 0 ]; then
        continue;
      fi

      TMP_DIR="expand_archive${layer}"
      sudo mkdir $TMP_DIR
      sudo tar -C $TMP_DIR --xattrs --xattrs-include="*" -xzf $layer

      # convert OCI whiteouts to overlay whiteouts
      for i in $(sudo find $TMP_DIR -name '\.wh\.*'); do
        b="$(basename $i)"
        d="$(dirname $i)"
        if [[ "$b" == ".wh..wh..opq" ]]; then
          sudo setfattr -n trusted.overlay.opaque -v y "$d"
        else
          w="$d/${b#.wh.}"
          sudo mknod -m 000 "$w" c 0 0
          sudo chown "$(stat -c '%U:%G' $i)" "$w"
        fi
        sudo rm -f "$i"
      done
      sudo mksquashfs $TMP_DIR $SQUASH_NAME
      sudo rm -rf $TMP_DIR
      hadoop fs -put $SQUASH_NAME $HDFS_LAYER_DIR
      hadoop fs -setrep $REPLICATION $HDFS_LAYER_DIR/$SQUASH_NAME
      hadoop fs -chmod 444 $HDFS_LAYER_DIR/$SQUASH_NAME
    done

    hadoop fs -ls $HDFS_CONFIG_DIR/$CONFIG_SHA 2>/dev/null >/dev/null
    if [ $? != 0 ]; then
      hadoop fs -put $CONFIG_SHA $HDFS_CONFIG_DIR
      hadoop fs -setrep $REPLICATION $HDFS_CONFIG_DIR/$CONFIG_SHA
      hadoop fs -chmod 444 $HDFS_CONFIG_DIR/$CONFIG_SHA
    fi

    hadoop fs -ls $HDFS_MANIFEST_DIR/$MANIFEST_SHA 2>/dev/null >/dev/null
    if [ $? != 0 ]; then
      hadoop fs -put manifest.json $HDFS_MANIFEST_DIR/$MANIFEST_SHA
      hadoop fs -setrep $REPLICATION $HDFS_MANIFEST_DIR/$MANIFEST_SHA
      hadoop fs -chmod 444 $HDFS_MANIFEST_DIR/$MANIFEST_SHA
    fi

    cd .. || { echo "cd .. failed"; exit 1; }
    sudo rm -rf $SKOPEO_DIR
  else
    echo "All layers already exist for $image"
  fi
done

set -e
LOCAL_MANIFEST_HASH=$(sha256sum $local_image_tag_to_manifest_file | cut -d ' ' -f1)
if [ $HDFS_MANIFEST_HASH != $LOCAL_MANIFEST_HASH ]; then
  hadoop fs -copyFromLocal -f $local_image_tag_to_manifest_file $TMP_IMAGE_TAG_TO_MANIFEST_FILE 2>/dev/null >/dev/null
  hadoop fs -chmod 444 $TMP_IMAGE_TAG_TO_MANIFEST_FILE 2>/dev/null >/dev/null
  hadoop jar $HADOOP_PREFIX/share/hadoop/tools/lib/hadoop-extras-*.jar org.apache.hadoop.tools.SymlinkTool mvlink -f \
      $TMP_IMAGE_TAG_TO_MANIFEST_FILE $IMAGE_TAG_TO_MANIFEST_FILE
  hadoop fs -setrep $REPLICATION $IMAGE_TAG_TO_MANIFEST_FILE
else
  echo "image-tag-to-manifest-file unchanged; Not uploading"
fi
rm $local_image_tag_to_manifest_file

