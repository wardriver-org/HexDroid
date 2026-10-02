#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_HOME:?Install the Android SDK and NDK first}"
command -v go >/dev/null
mobile_version=8b95e45f8d3e224183cc3d760609cef9896e498c
go install "golang.org/x/mobile/cmd/gomobile@$mobile_version"
go install "golang.org/x/mobile/cmd/gobind@$mobile_version"
export PATH="$(go env GOPATH)/bin:$PATH"
cd "$project_dir/native/agebridge"
go mod tidy
go get "golang.org/x/mobile@$mobile_version"
go test ./...
gomobile init
mkdir -p "$project_dir/app/libs"
gomobile bind -javapkg=go -target=android -androidapi=26 -o "$project_dir/app/libs/agebridge.aar" .
