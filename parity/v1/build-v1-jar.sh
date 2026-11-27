#!/usr/bin/env bash
# v1 서버의 bootJar를 고정 커밋에서 빌드해 <out>에 둔다. <out>이 이미 있으면 아무것도 하지 않는다.
# v1 빌드는 GitHub Packages(wafflestudio/spring-waffle)를 읽으므로 토큰이 필요하다.
# v1 build.gradle.kts가 gpr.key 프로퍼티 → GITHUB_TOKEN 환경변수 → `gh auth token` 순서로 찾는다.
# 사용법: build-v1-jar.sh <repo> <sha> <gradle-task> <jar-path-in-repo> <out>
set -euo pipefail
repo="$1" sha="$2" task="$3" jar="$4" out="$5"
if [ -f "$out" ]; then exit 0; fi

work="$(mktemp -d "${TMPDIR:-/tmp}/snutt-parity-v1.XXXXXX")"
trap 'rm -rf "$work"' EXIT
git clone -q --filter=blob:none --no-checkout "$repo" "$work/src"
git -C "$work/src" checkout -q "$sha"
(cd "$work/src" && ./gradlew --no-daemon -q -Dorg.gradle.jvmargs=-Xmx1g "$task" -x test)
mkdir -p "$(dirname "$out")"
cp "$work/src/$jar" "$out.tmp"
mv "$out.tmp" "$out"
