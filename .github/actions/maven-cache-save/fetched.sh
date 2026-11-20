#!/usr/bin/env bash
# Says whether a local Maven repository gained a jar or pom after the marker was touched: 'fetched <path>' for the
# first one found, 'nothing' when none, 'unrestored' without the marker or the repository. By the status-change time,
# since Maven dates a fetched file by the server's Last-Modified, often long before the marker.
set -euo pipefail

repository=$1
marker=$2
if [[ ! -f $marker || ! -d $repository ]]; then
	echo unrestored
	exit 0
fi
first=$(find "$repository" -type f \( -name '*.jar' -o -name '*.pom' \) -cnewer "$marker" -print -quit)
if [[ -n $first ]]; then
	echo "fetched ${first#"$repository"/}"
else
	echo nothing
fi
