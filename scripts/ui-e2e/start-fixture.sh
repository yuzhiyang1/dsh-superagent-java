#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
state=$(mktemp -d)
trap 'rm -rf "$state"' EXIT
java -cp target/test-classes:target/dsh-superagent-java-1.0.0-SNAPSHOT.jar io.github.dsh.management.PanelFixture "$state" 3083
