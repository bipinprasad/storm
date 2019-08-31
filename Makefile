
export GIT_REPO = git@git.ouroath.com:storm/storm_tools.git
export GIT_SCRIPTS_BRANCH = storm_tools
#DIST TAG to allow CI to find the correct versions to test
export STORM_LATEST_RELEASE_TAG = ystorm_master_launcher_latest_2_x
export ADDITIONAL_DIST_TAGS=ystorm_master_launcher_latest_2_x_rhel7
export STORM_MASTER_PKGS = ystorm
export AUTO_CREATE_RELEASE_TAG = 1
export UPDATE_DIST_TAG_WITH_MASTER_PKG = 1

internal:
	git clone --depth=1 --branch ${GIT_SCRIPTS_BRANCH} ${GIT_REPO} internal

copy_test_files:
	mkdir ${SD_SOURCE_DIR}/my_test_results
	for dir in `find ${SD_SOURCE_DIR} -type d \( -name test-reports -or -name surefire-reports \)` ; do \
		if [ -d $$dir ] ;\
		then \
			cp $$dir/*.xml ${SD_SOURCE_DIR}/my_test_results || true;\
		fi ;\
	done

dist_tag:
	$(MAKE) -C yahoo-build dist_tag

screwdriver: internal dist_tag
	$(MAKE) -C yahoo-build clean build test package-sd ; if [ $$? -eq 0 ] ; then $(MAKE) copy_test_files ;  else $(MAKE) copy_test_files; false ; fi

cleanplatforms:
	$(MAKE) -C yahoo-build clean

platforms: internal dist_tag
	$(MAKE) -C yahoo-build build 

testcoverageplatforms:
	$(MAKE) -C yahoo-build test ; if [ $$? -eq 0 ] ; then $(MAKE) copy_test_files ;  else $(MAKE) copy_test_files; false ; fi

package-release:
	$(MAKE) -C yahoo-build package-sd

dist_force_push:
	/home/y/bin/dist_install -branch quarantine -headless -identity=/home/screwdrv/.ssh/id_dsa -group=hadoopqa -batch -nomail -os rhel-6.x ${SD_SOURCE_DIR}/yahoo-build/packages/rhel-6.x/*.tgz && \
	/home/y/bin/dist_install -branch quarantine -headless -identity=/home/screwdrv/.ssh/id_dsa -group=hadoopqa -batch -nomail -os rhel-7.x ${SD_SOURCE_DIR}/yahoo-build/packages/rhel-7.x/*.tgz

# RELEASE files are assumed to exist per-OS.
RELEASE:
	# Make sure the release files have the same content.
	diff ${SD_SOURCE_DIR}/yahoo-build/packages/rhel-?.x/RELEASE
	# Copy one of the files.
	cp -nv `ls ${SD_SOURCE_DIR}/yahoo-build/packages/rhel-?.x/RELEASE | head -n 1` RELEASE

# DIST_TAG files are assumed to exist per-OS.
DIST_TAG:
	# Make sure the release files have the same content.
	diff ${SD_SOURCE_DIR}/yahoo-build/packages/rhel-?.x/DIST_TAG
	# Copy one of the files.
	cp -nv `ls ${SD_SOURCE_DIR}/yahoo-build/packages/rhel-?.x/DIST_TAG | head -n 1` yahoo-build/DIST_TAG

git_tag: internal RELEASE DIST_TAG
	git tag -f -a `cat RELEASE` -m "Pipeline ${SD_PIPELINE_ID} job ${SD_JOB_ID} build ${SD_BUILD_ID}: yahoo version `cat RELEASE`"
	git push origin `cat RELEASE`
	${SD_SOURCE_DIR}/internal/QATools/storm_tag_master_launcher
	./yahoo-build/screwdriver/update_package_info.sh `cat RELEASE`
	
upload_to_artifactory:
	$(MAKE) -C yahoo-build upload_to_artifactory
