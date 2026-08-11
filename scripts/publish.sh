#!/usr/bin/env bash
#
# Build the JVM image and push it to a container registry.
#
#   ./scripts/publish.sh 1.0            # push pavelmichalec/ora-jdbc-mcp:1.0 (and :latest)
#   ./scripts/publish.sh 1.1-rc1        # push only the :1.1-rc1 tag
#   IMAGE=ghcr.io/me/x ./scripts/publish.sh 1.0
#   PUSH=false ./scripts/publish.sh 1.0 # build and tag locally, push nothing
#
# `docker login` must have been run first - this script deliberately does not handle
# credentials.
#
# Tagging a release in git (`git tag v1.0 && git push --tags`) runs the same build in CI
# via .github/workflows/docker-publish.yml, which is the better route for anything other
# people will pull. This script is for getting a build onto a machine now.

set -euo pipefail

IMAGE="${IMAGE:-pavelmichalec/ora-jdbc-mcp}"
PUSH="${PUSH:-true}"
VERSION="${1:-}"

if [ -z "$VERSION" ]; then
    echo "usage: $0 <version>   e.g. $0 1.0" >&2
    exit 2
fi

cd "$(dirname "$0")/.."

# The build fails deep inside a Quarkus mojo with an unhelpful NoSuchElementException when
# it runs on JDK 11, so check up front and say what is actually wrong. On Windows this is
# the usual cause: the shell's default JAVA_HOME is often an older JDK.
java_exe="java"
[ -n "${JAVA_HOME:-}" ] && java_exe="${JAVA_HOME}/bin/java"
java_version="$("$java_exe" -version 2>&1 | head -1)"
java_major="$(printf '%s' "$java_version" | sed -n 's/.*"\([0-9]*\).*/\1/p')"
if [ -z "$java_major" ] || [ "$java_major" -lt 17 ]; then
    echo "This project needs JDK 17, but '$java_exe' is: $java_version" >&2
    echo "Set JAVA_HOME for this shell only, e.g." >&2
    echo "  JAVA_HOME=/c/Users/you/.jdks/liberica-17.0.5 $0 $VERSION" >&2
    exit 1
fi
echo "==> Using JDK ${java_major} from ${JAVA_HOME:-(PATH)}"

echo "==> Building (this runs the test suite; Oracle container tests stay opt-in)"
./mvnw package

echo "==> Building image ${IMAGE}:${VERSION}"
docker build -f src/main/docker/Dockerfile.jvm -t "${IMAGE}:${VERSION}" .

tags=("${IMAGE}:${VERSION}")

# Only a plain version number moves ':latest', so a release candidate or a dated build
# cannot become what everyone pulls by default. Same rule as the CI workflows.
if [[ "$VERSION" =~ ^[0-9]+(\.[0-9]+)*$ ]]; then
    docker tag "${IMAGE}:${VERSION}" "${IMAGE}:latest"
    tags+=("${IMAGE}:latest")
    echo "==> Also tagged ${IMAGE}:latest"
fi

if [ "$PUSH" != "true" ]; then
    echo "==> PUSH=false, stopping. Built: ${tags[*]}"
    exit 0
fi

for tag in "${tags[@]}"; do
    echo "==> Pushing ${tag}"
    docker push "$tag"
done

echo
echo "Done. Pull with:"
echo "  docker pull ${IMAGE}:${VERSION}"
