
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
	cp yahoo-build/*tgz ${SD_ARTIFACTS_DIR}/publish

dist_force_push:
	for packages in ${SD_ARTIFACTS_DIR}/publish/*.tgz; do \
		/home/y/bin/dist_install -branch quarantine -headless -group=hadoopqa -batch -nomail -os rhel-6.x $$packages; \
		/home/y/bin/dist_install -branch quarantine -headless -group=hadoopqa -batch -nomail -os rhel-7.x $$packages; \
	done

git_tag:
	git tag -f -a `cat ${SD_SOURCE_DIR}/yahoo-build/RELEASE` -m "yahoo version `cat ${SD_SOURCE_DIR}/yahoo-build/RELEASE`"
	git push origin `cat ${SD_SOURCE_DIR}/yahoo-build/RELEASE`
	${SD_SOURCE_DIR}/internal/QATools/storm_tag_master_launcher
