#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PREPARER="${ROOT_DIR}/prepare-performance-environment.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

mkdir -p "$tmp_dir/fake-bin" "$tmp_dir/profiles" "$tmp_dir/ca"
export FLOW_LOG="$tmp_dir/flow.log"
export PREPARED_ENVIRONMENT_ROOT="$tmp_dir/prepared"
export LOADTOOL_PROFILES_DIR="$tmp_dir/profiles"
export RUST_LOADTOOL_TARGET_DIR="$tmp_dir/target"
export STACK_READINESS_SCRIPT="$tmp_dir/fake-readiness"
export PROVISION_PROFILE_FUNDS_SCRIPT="$tmp_dir/fake-funding"
export LOADTOOL_CERT_SCRIPT="$tmp_dir/fake-certs"
export LOADTOOL_CA_CERT="$tmp_dir/ca/ca.crt"
touch "$LOADTOOL_CA_CERT"

for profile in uniform-smoke explicit-smoke; do
    printf '{"name":"%s"}\n' "$profile" > "$tmp_dir/profiles/${profile}.json"
done

cat > "$tmp_dir/fake-bin/cargo" <<'SH'
#!/bin/bash
set -euo pipefail
printf 'cargo %s\n' "$*" >> "$FLOW_LOG"
mkdir -p "$RUST_LOADTOOL_TARGET_DIR/release"
cat > "$RUST_LOADTOOL_TARGET_DIR/release/rust-loadtool" <<'BIN'
#!/bin/bash
set -euo pipefail
profile=uniform-smoke
while [[ $# -gt 0 ]]; do
    case "$1" in
        validate-profile) shift ;;
        --profile) profile="$2"; shift 2 ;;
        *) exit 90 ;;
    esac
done
printf 'validate %s\n' "$profile" >> "$FLOW_LOG"
cat <<JSON
{"profile":"$profile","scenarios":[{"participants":{"pairNumberStart":1,"hotPairCount":1,"coldPairCount":1},"provisioning":{"payerBalance":"1.00","receiverBalance":"0.00","resetIfExists":true}}]}
JSON
BIN
chmod +x "$RUST_LOADTOOL_TARGET_DIR/release/rust-loadtool"
SH
chmod +x "$tmp_dir/fake-bin/cargo"

cat > "$tmp_dir/fake-bin/docker" <<'SH'
#!/bin/bash
set -euo pipefail
if [[ "$*" == *" down -v --remove-orphans" ]]; then
    printf '%s\n' down >> "$FLOW_LOG"
    exit "${DOCKER_DOWN_STATUS:-0}"
fi
if [[ "$*" == *" up -d --build" ]]; then
    printf '%s\n' up >> "$FLOW_LOG"
    exit "${DOCKER_UP_STATUS:-0}"
fi
if [[ "$*" == "logs spi" ]]; then
    printf '%s\n' runtime-config >> "$FLOW_LOG"
    printf '%s\n' 'event=spi_runtime_configuration payment_request_listener_concurrency=1 status_report_listener_concurrency=1'
    exit "${DOCKER_LOGS_STATUS:-0}"
fi
exit 91
SH
chmod +x "$tmp_dir/fake-bin/docker"

cat > "$tmp_dir/fake-readiness" <<'SH'
#!/bin/bash
set -euo pipefail
printf '%s\n' readiness >> "$FLOW_LOG"
exit "${READINESS_STATUS:-0}"
SH
chmod +x "$tmp_dir/fake-readiness"

cat > "$tmp_dir/fake-funding" <<'SH'
#!/bin/bash
set -euo pipefail
printf 'funding %s\n' "$*" >> "$FLOW_LOG"
exit "${FUNDING_STATUS:-0}"
SH
chmod +x "$tmp_dir/fake-funding"

cat > "$tmp_dir/fake-certs" <<'SH'
#!/bin/bash
set -euo pipefail
printf 'cert %s\n' "$*" >> "$FLOW_LOG"
root=""
ispb="${!#}"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --psp-root) root="$2"; shift 2 ;;
        *) shift ;;
    esac
done
mkdir -p "$root/psp-$ispb"
touch "$root/psp-$ispb/client.crt" "$root/psp-$ispb/client.key"
exit "${CERT_STATUS:-0}"
SH
chmod +x "$tmp_dir/fake-certs"
export PATH="$tmp_dir/fake-bin:$PATH"

run_case() {
    local id="$1"
    shift
    : > "$FLOW_LOG"
    set +e
    "$PREPARER" "$@" >"$tmp_dir/$id.out" 2>"$tmp_dir/$id.err"
    local status=$?
    set -e
    printf '%s\n' "$status"
}

if [[ "$(run_case default)" -ne 0 ]]; then
    cat "$tmp_dir/default.err" >&2
    exit 1
fi
cat > "$tmp_dir/expected-flow" <<EOF
cargo build --locked --release --manifest-path ${ROOT_DIR}/rust-loadtool/Cargo.toml --target-dir ${RUST_LOADTOOL_TARGET_DIR}
validate uniform-smoke
down
up
readiness
runtime-config
funding --execution-plan ${PREPARED_ENVIRONMENT_ROOT}/.staging/inputs/execution-plan.json
cert --psp-root ${PREPARED_ENVIRONMENT_ROOT}/.staging/certs psp 10000001
cert --psp-root ${PREPARED_ENVIRONMENT_ROOT}/.staging/certs psp 20000001
cert --psp-root ${PREPARED_ENVIRONMENT_ROOT}/.staging/certs psp 10000002
cert --psp-root ${PREPARED_ENVIRONMENT_ROOT}/.staging/certs psp 20000002
EOF
diff -u "$tmp_dir/expected-flow" "$FLOW_LOG"
test -f "$PREPARED_ENVIRONMENT_ROOT/current/inputs/profile.json"
test -f "$PREPARED_ENVIRONMENT_ROOT/current/inputs/execution-plan.json"
test -f "$PREPARED_ENVIRONMENT_ROOT/current/inputs/spi-runtime-config.log"
test -d "$PREPARED_ENVIRONMENT_ROOT/current/certs"
cmp "$tmp_dir/profiles/uniform-smoke.json" "$PREPARED_ENVIRONMENT_ROOT/current/inputs/profile.json"

if [[ "$(run_case explicit --profile explicit-smoke)" -ne 0 ]]; then
    cat "$tmp_dir/explicit.err" >&2
    exit 1
fi
test -d "$PREPARED_ENVIRONMENT_ROOT/current"
cmp "$tmp_dir/profiles/explicit-smoke.json" "$PREPARED_ENVIRONMENT_ROOT/current/inputs/profile.json"
if [[ "$(find "$PREPARED_ENVIRONMENT_ROOT" -mindepth 1 -maxdepth 1 -type d | wc -l)" -ne 1 ]]; then
    echo "preparer retained more than one prepared environment" >&2
    exit 1
fi
grep -q '^validate explicit-smoke$' "$FLOW_LOG"

for stage in down up readiness runtime-config funding cert; do
    case "$stage" in
        down) variable=DOCKER_DOWN_STATUS ;;
        up) variable=DOCKER_UP_STATUS ;;
        readiness) variable=READINESS_STATUS ;;
        runtime-config) variable=DOCKER_LOGS_STATUS ;;
        funding) variable=FUNDING_STATUS ;;
        cert) variable=CERT_STATUS ;;
    esac
    : > "$FLOW_LOG"
    set +e
    env "$variable=19" "$PREPARER" >"$tmp_dir/$stage.out" 2>"$tmp_dir/$stage.err"
    status=$?
    set -e
    if [[ "$status" -eq 0 ]]; then
        echo "preparer accepted $stage failure" >&2
        exit 1
    fi
    if [[ -e "$PREPARED_ENVIRONMENT_ROOT/current" ]]; then
        echo "preparer published a partial environment after $stage failure" >&2
        exit 1
    fi
    if find "$PREPARED_ENVIRONMENT_ROOT" -mindepth 1 -maxdepth 1 -print -quit | grep -q .; then
        echo "preparer retained a stale environment after $stage failure" >&2
        exit 1
    fi
done

if [[ "$(run_case invalid --profile ../escape)" -ne 2 ]]; then
    echo "preparer accepted an invalid internal profile name" >&2
    exit 1
fi
