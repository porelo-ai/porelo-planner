# shellcheck shell=bash
# shellcheck disable=SC2154 # base and JF_ACCESS_TOKEN are the caller's.
# What the Build's publishing steps share, sourced by each: asking whether a file is published, and uploading one.
# Curl alone, so no downloaded tool runs beside the token; the token reaches curl as config on standard input, so
# neither a file nor a command line holds it. The caller sets 'base', the Artifactory address ending in /artifactory,
# and JF_ACCESS_TOKEN before it calls put.

# A published release is never replaced, as on Central: each file is asked for where it would be stored, so one
# uploaded by a run that failed before its index was written counts too. Anything but a 404 stops the release, since
# whether it is published is then unknown. Anonymous, so the redirect Artifactory answers a stored file with may be
# followed.
absent() { # <path under base>
    local status
    status="$(curl -sSL -o /dev/null -w '%{http_code}' --retry 5 --proto =https --proto-redir =https --max-time 60 \
        "$base/$1")"
    case "$status" in
    404) ;;
    200)
        echo "::error::$1 is published already"
        exit 1
        ;;
    *)
        echo "::error::asking for $1 answered $status, so whether it is published is unknown"
        exit 1
        ;;
    esac
}

# Assigned first, so a checksum that cannot be computed stops here instead of sending an empty header, which curl drops;
# Artifactory checks the bytes against both. Only a 2xx is an upload, and a redirect is not followed, so the token stays
# with this host. No -f: a refusal such as 401 is reported with its status at once, and only a timeout or a 408, 429 or
# 5xx is tried again.
put() { # <file> <directory under base, ending in /> [<matrix parameters>]
    local sha1 sha256 status
    sha1="$(sha1sum "$1" | cut -d' ' -f1)"
    sha256="$(sha256sum "$1" | cut -d' ' -f1)"
    echo "  put   $2$(basename "$1")"
    status="$(printf 'header = "Authorization: Bearer %s"\n' "$JF_ACCESS_TOKEN" |
        curl -sS --retry 5 --proto =https --max-time 120 -K - -o /dev/null -w '%{http_code}' -T "$1" \
            -H "X-Checksum-Sha1: $sha1" -H "X-Checksum-Sha256: $sha256" \
            "$base/$2$(basename "$1")${3:+;$3}")"
    case "$status" in
    2??) ;;
    *)
        echo "::error::Artifactory answered $status to the upload of $(basename "$1"), not a 2xx"
        exit 1
        ;;
    esac
}
