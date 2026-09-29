#!/usr/bin/env bash
# Decides which CI jobs a change needs, from the paths it touches.
#
#   changes.sh < changed-paths      prints name=true|false lines for $GITHUB_OUTPUT
#   changes.sh --all                every job, for when the change set is unknown
#
# A path this does not recognise runs everything, so a new directory can never
# quietly skip the checks it needs; teaching it the path is how to narrow that.
set -euo pipefail

build=false chart=false e2e=false architecture=false publish=false

all() { build=true chart=true e2e=true architecture=true publish=true; }

if [[ "${1:-}" == "--all" ]]; then
  all
else
  while IFS= read -r path; do
    [[ -n "$path" ]] || continue
    case "$path" in
      # The application. The user guide and its screenshots are built into the
      # image, so they count as the application, not as documentation.
      backend/* | ui/* | docs/USER-GUIDE.md | docs/images/* \
        | build.gradle | settings.gradle | gradle.properties | gradle/* | gradlew | gradlew.bat)
        build=true e2e=true publish=true ;;
      # The chart: checked, installed for real, and published with an image
      # whose tag it can name.
      deploy/helm/*)
        chart=true e2e=true publish=true ;;
      # The local stack and values: the chart checks render with them.
      deploy/local/*)
        chart=true e2e=true ;;
      e2e/*)
        e2e=true ;;
      # Examples are rendered by the chart checks, and are not in the chart.
      deploy/examples/*)
        chart=true ;;
      docs/ARCHITECTURE.md | docs/architecture.calm.json)
        architecture=true ;;
      # Read by people only.
      README.md | LICENSE | .gitignore | docs/*.md)
        ;;
      # The pipeline itself, and anything unrecognised.
      *)
        all ;;
    esac
  done
fi

for job in build chart e2e architecture publish; do
  echo "$job=${!job}"
done
