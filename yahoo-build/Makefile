BASE_VERSION:=`cat BASE_VERSION`
MVN_BASE_VERSION:=`cat yahoo-build/BASE_VERSION`
FULL_VERSION=`cat RELEASE`
BASE_VERSION_UNDERSCORE=`cat BASE_VERSION_UNDERSCORE`
MVN_FULL_VERSION=`cat yahoo-build/RELEASE`
MVN_MAKE_OPTS=--batch-mode
MVN=LD_LIBRARY_PATH=/home/y/lib64 STORM_TEST_TIMEOUT_MS=20000 MAVEN_OPTS="-Xmx1024M" /home/y/bin/mvn
EXEC_CONF_DIR?=/home/y/conf/storm/
DATE=`date +%s`

all: build

shaded-deps-install:
	cd ../shaded-deps && $(MVN) install -DskipTests

test: shaded-deps-install RELEASE
	cd .. && $(MVN) -fae -Pnative -Pyahoo -Pcoverage -Pall-tests '-P!include-shaded-deps' -Pexternals,examples -Dworker-launcher.conf.dir=${EXEC_CONF_DIR} install ${MVN_MAKE_OPTS} -Dystorm.build.number=$(shell sed 's;.*\.;;' RELEASE)

check: test
	@echo "Errors or Failures:"
	@egrep -il '<fail|<error' ../*/target/test-reports/*.xml | xargs -I '{}' bash -c "echo -n '{}':' '; egrep -ic '<fail|<error' '{}'"
	@! egrep -iq '<fail|<error' ../*/target/test-reports/*.xml 


build: shaded-deps-install RELEASE
	cd .. && $(MVN) -Pnative -Pyahoo '-P!include-shaded-deps' -Pexternals,examples -Dworker-launcher.conf.dir=${EXEC_CONF_DIR} install -DskipTests ${MVN_MAKE_OPTS} -Dystorm.build.number=$(shell sed 's;.*\.;;' RELEASE)

dist_tag: RELEASE
	$(shell echo "ystorm_`cat RELEASE`" | sed 's/\./_/g' > DIST_TAG)

#TODO need a good way to see if these values would have changed and update only then
BASE_VERSION: ../pom.xml
	cd .. && $(MVN) help:evaluate -Dexpression=project.version | grep -v "^\[" | tail -1 > yahoo-build/BASE_VERSION

BASE_VERSION_UNDERSCORE: BASE_VERSION
	echo "$(BASE_VERSION)" | sed -e 's/-/_/g' > BASE_VERSION_UNDERSCORE

RELEASE: BASE_VERSION_UNDERSCORE
	if [ "$(YSTORM_BUILD_NUMBER)" != "" ]; then \
		echo "$(BASE_VERSION_UNDERSCORE).$(YSTORM_BUILD_NUMBER)" > RELEASE; \
	else \
		/home/y/bin/auto_increment_version.pl ystorm $(BASE_VERSION_UNDERSCORE) > RELEASE; \
	fi

define setup-versions
cd .. && ${MVN} -q -Pyahoo -Pexternals,examples -Pinclude-shaded-deps versions:set -DnewVersion=$(MVN_FULL_VERSION) ${MVN_MAKE_OPTS} && \
	${MVN} -q -Pyahoo -Pexternals,examples versions:update-child-modules ${MVN_MAKE_OPTS}
if [ -e ../storm-core/dependency-reduced-pom.xml ]; then sed -e 's/'"$(BASE_VERSION)"'/'"$(FULL_VERSION)"'/g' ../storm-core/dependency-reduced-pom.xml > storm-core-dependency-reduced-pom.xml; fi
endef

define revert-versions
cd .. && ${MVN} -q -Pyahoo -Pexternals,examples -Pinclude-shaded-deps versions:revert ${MVN_MAKE_OPTS} && \
	${MVN} -q -Pyahoo -Pexternals,examples versions:update-child-modules ${MVN_MAKE_OPTS}
endef

define find-tracked-pom-xml
git ls-files 'pom.xml' '**/pom.xml'
endef

DATE:
	echo ${DATE} > DATE

copy_webapp: DATE
	rsync -razv ../storm-webapp/src/main/java/org/apache/storm/daemon/ui/WEB-INF/ ./WEB-INF/
	find ./WEB-INF/ -type f | xargs sed -i.back -e 's/$${packageTimestamp}/'`cat DATE`'/g'

packages/rhel-6.x:
	mkdir -pv "$@"

packages/rhel-7.x:
	mkdir -pv "$@"

testpkg: RELEASE BASE_VERSION build copy_webapp packages/rhel-6.x packages/rhel-7.x
	$(setup-versions)
	yinst_create --buildtype test --platform rhel-6.x ystorm.yicf --target packages/rhel-6.x && \
	yinst_create --buildtype test --platform rhel-7.x ystorm.yicf --target packages/rhel-7.x && \
	$(revert-versions) || ( $(revert-versions) && false )

ignore-pom-changes:
	cd .. && $(find-tracked-pom-xml) | xargs git update-index --assume-unchanged
	
# This target is called from screwdriver build and leaves the poms changed for its
# publish phase
package-sd: RELEASE copy_webapp packages/rhel-6.x packages/rhel-7.x
	$(setup-versions)
	cd ../shaded-deps && $(MVN) install -DskipTests
	cd .. && $(find-tracked-pom-xml)| xargs git update-index --assume-unchanged
	yinst_create --buildtype release --platform rhel-6.x ystorm.yicf --target packages/rhel-6.x && \
	yinst_create --buildtype release --platform rhel-7.x ystorm.yicf --target packages/rhel-7.x
	cd .. && $(find-tracked-pom-xml) | xargs git update-index --no-assume-unchanged

package: package-sd
	$(revert-versions)


rhel6_build.state: rhel6_build.yidf rhel6_build.yml
	rm -f rhel6_build.state rhel6_build.state.tmp rhel6_build.ruleset
	statecc --branch test ./rhel6_build.yml

clean:
	cd .. && $(MVN) -Pyahoo -Pexternals,examples clean ${MVN_MAKE_OPTS}
	$(revert-versions)
	rm -rf *_build.ruleset ystorm*.tgz BASE_VERSION RELEASE BASE_VERSION_UNDERSCORE WEB-INF DATE
