# shellcheck shell=bash
# Signs a call to the planner, checks its answer's proof and asks its proof of the token, as the window does, in the
# forms the planner's REST description gives; sourced by planner-probe.sh and the story tests. The token reaches no
# command line: it passes only through printf, a builtin, into a pipe.
#
# ps_sign METHOD TARGET HOST TYPE BODY-FILE TOKEN   sets PS_HEADER, the Porelo-Signature value, and PS_NONCE (hex)
# ps_answer_holds TOKEN NONCE STATUS BODY-FILE|- HEADER-VALUE
#                                                   0 when the Porelo-Answer value proves the answer; - for a stream
# ps_proves URL TOKEN PORT                          0 when the listener at URL proves it holds TOKEN

# The hex of standard input.
ps_hex() {
    od -An -v -tx1 | tr -d ' \n'
}

# Writes the bytes a hex text stands for; the format holds only \xNN escapes made here.
ps_bytes() {
    local hex=$1 escapes='' at
    for ((at = 0; at < ${#hex}; at += 2)); do
        escapes+="\\x${hex:at:2}"
    done
    # shellcheck disable=SC2059 # the format is the escapes alone
    printf "$escapes"
}

# Writes a field: its length in four bytes, big endian, and its bytes, given as hex.
ps_field() {
    ps_bytes "$(printf '%08x' $((${#1} / 2)))$1"
}

# The HMAC-SHA256 of standard input under the key TOKEN, as hex.
ps_hmac() {
    local key ipad='' opad='' at byte inner
    key=$(printf '%s' "$1" | ps_hex)
    ((${#key} <= 128)) || return 1
    while ((${#key} < 128)); do key+='00'; done
    for ((at = 0; at < 128; at += 2)); do
        byte=$((16#${key:at:2}))
        ipad+=$(printf '%02x' $((byte ^ 0x36)))
        opad+=$(printf '%02x' $((byte ^ 0x5c)))
    done
    inner=$({ ps_bytes "$ipad" && cat; } | sha256sum | cut -c1-64)
    { ps_bytes "$opad" && ps_bytes "$inner"; } | sha256sum | cut -c1-64
}

# Base64url without padding of the bytes a hex text stands for.
ps_b64url() {
    ps_bytes "$1" | base64 -w0 | tr '+/' '-_' | tr -d '='
}

# The hex of a text.
ps_text() {
    printf '%s' "$1" | ps_hex
}

ps_sign() {
    local method=$1 target=$2 host=$3 type=$4 body=$5 token=$6 time mac
    time=$(date -u +%s)
    PS_NONCE=$(od -An -v -N 16 -tx1 /dev/urandom | tr -d ' \n')
    mac=$({
        printf 'porelo-planner-request-1\0'
        ps_field "$(ps_text "$method")"
        ps_field "$(ps_text "$target")"
        ps_field "$(ps_text "$host")"
        ps_field "$(ps_text "$type")"
        ps_field "$(sha256sum <"$body" | cut -c1-64)"
        ps_field "$(ps_text "$time")"
        ps_field "$PS_NONCE"
    } | ps_hmac "$token")
    # shellcheck disable=SC2034 # read by the caller
    PS_HEADER="porelo-planner-request-1;t=$time;n=$(ps_b64url "$PS_NONCE");s=$(ps_b64url "$mac")"
}

ps_answer_holds() {
    local token=$1 nonce=$2 status=$3 body=$4 given=$5 digest='' mac
    [[ $body == - ]] || digest=$(sha256sum <"$body" | cut -c1-64)
    mac=$({
        printf 'porelo-planner-answer-1\0'
        ps_field "$nonce"
        ps_field "$(ps_text "$status")"
        ps_field "$digest"
    } | ps_hmac "$token")
    [[ $given == "porelo-planner-answer-1;s=$(ps_b64url "$mac")" ]]
}

# The value of one header in a file curl wrote with -D, its name in any case.
ps_header() {
    awk -v name="$2" 'tolower($0) ~ "^" tolower(name) ":" { sub(/^[^:]*:[ \t]*/, ""); sub(/\r$/, ""); print; exit }' "$1"
}

ps_proves() {
    local url=$1 token=$2 port=$3 challenge answer mac
    challenge=$(od -An -v -N 32 -tx1 /dev/urandom | tr -d ' \n')
    answer=$(curl -sS --max-time 60 -H 'Content-Type: application/json' \
        --data-binary "{\"challenge\":\"$(ps_b64url "$challenge")\"}" "$url/proof") || return 1
    mac=$({
        printf 'porelo-planner-proof-1\0'
        ps_bytes "$(printf '%04x' "$port")$challenge"
    } | ps_hmac "$token")
    [[ $answer == "{\"proof\":\"$(ps_b64url "$mac")\"}" ]]
}
