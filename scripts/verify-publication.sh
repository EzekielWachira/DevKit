#!/usr/bin/env bash
#
# DevKit publication smoke test.
#
# Publishes every artifact to Maven Local, then resolves and compiles them from
# a standalone consumer build that knows DevKit only by its Maven coordinates.
#
# This exists because `project(":netkit")` dependencies inside the main build
# prove nothing about publication: they bypass the POM, the Gradle module
# metadata, the artifact ids and the versions. Every publication bug this
# restructuring could introduce is invisible until something resolves the
# published coordinates, which is what the consumer build does.
#
# Usage:  ./scripts/verify-publication.sh

set -euo pipefail

cd "$(dirname "$0")/.."
GROUP_PATH="io/github/ezekielwachira/devkit"

echo "==> Publishing every DevKit artifact to Maven Local"
./gradlew publishToMavenLocal

echo
echo "==> Artifacts in ~/.m2/repository/$GROUP_PATH"
if [ ! -d "$HOME/.m2/repository/$GROUP_PATH" ]; then
  echo "    none found — publication did not write where it claimed to" >&2
  exit 1
fi
find "$HOME/.m2/repository/$GROUP_PATH" -name "*.pom" \
  | sed "s|$HOME/.m2/repository/||" | sort

echo
echo "==> Resolving and compiling from a consumer that uses Maven coordinates"
./gradlew --project-dir consumer-test verifyAll

echo
echo "==> Publication verified"
