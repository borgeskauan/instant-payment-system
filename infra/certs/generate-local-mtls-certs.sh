#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Generate local development certificates for PSP mTLS connections.

Usage:
  infra/certs/generate-local-mtls-certs.sh [--force] init
  infra/certs/generate-local-mtls-certs.sh [--force] [--psp-root DIR] psp <ispb>

Commands:
  init        Generate the local CA and server certificates.
  psp <ispb> Generate a PSP client certificate with SAN URI urn:pix:ispb:<ispb>.

Options:
  --force         Recreate the target certificate files if they already exist.
                  With init, also remove local PSP certificates signed by the old CA.
  --psp-root DIR  Write PSP certificates under DIR/psp-<ispb> instead of local/.
  -h, --help      Show this help.

Examples:
  infra/certs/generate-local-mtls-certs.sh init
  infra/certs/generate-local-mtls-certs.sh psp 12345678
  infra/certs/generate-local-mtls-certs.sh --force psp 12345678
  infra/certs/generate-local-mtls-certs.sh --psp-root /tmp/load-certs psp 12345678
EOF
}

script_dir() {
  cd "$(dirname "${BASH_SOURCE[0]}")" && pwd
}

fail() {
  echo "error: $*" >&2
  exit 1
}

require_openssl() {
  command -v openssl >/dev/null 2>&1 || fail "openssl is required"
}

validate_ispb() {
  local ispb="$1"
  [[ "$ispb" =~ ^[0-9]{8}$ ]] || fail "ISPB must have exactly 8 digits: $ispb"
}

ensure_complete_or_absent() {
  local label="$1"
  shift

  local existing=0
  local missing=0
  local file

  for file in "$@"; do
    if [[ -e "$file" ]]; then
      existing=$((existing + 1))
    else
      missing=$((missing + 1))
    fi
  done

  if ((existing > 0 && missing > 0)); then
    fail "$label is partially generated. Remove its files or rerun with --force."
  fi
}

all_exist() {
  local file

  for file in "$@"; do
    [[ -e "$file" ]] || return 1
  done

  return 0
}

write_server_extfile() {
  local file="$1"
  local server_name="$2"

  cat >"$file" <<EOF
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:$server_name,DNS:localhost
EOF
}

write_psp_extfile() {
  local file="$1"
  local ispb="$2"

  cat >"$file" <<EOF
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = clientAuth
subjectAltName = URI:urn:pix:ispb:$ispb
EOF
}

generate_ca() {
  local force="$1"
  local ca_dir="$2"
  local ca_crt="$ca_dir/ca.crt"
  local ca_key="$ca_dir/ca.key"

  mkdir -p "$ca_dir"
  if [[ "$force" != true ]]; then
    ensure_complete_or_absent "local CA" "$ca_crt" "$ca_key"
  fi

  if all_exist "$ca_crt" "$ca_key" && [[ "$force" != true ]]; then
    echo "local CA already exists: $ca_dir"
    return
  fi

  rm -f "$ca_crt" "$ca_key"

  openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes \
    -keyout "$ca_key" \
    -out "$ca_crt" \
    -subj "/CN=Pix Local Dev CA" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -addext "subjectKeyIdentifier=hash" \
    >/dev/null 2>&1

  chmod 600 "$ca_key"
  echo "generated local CA: $ca_dir"
}

generate_server() {
  local force="$1"
  local ca_dir="$2"
  local server_dir="$3"
  local server_name="$4"
  local ca_crt="$ca_dir/ca.crt"
  local ca_key="$ca_dir/ca.key"
  local server_crt="$server_dir/server.crt"
  local server_key="$server_dir/server.key"

  [[ -f "$ca_crt" && -f "$ca_key" ]] || fail "local CA is missing. Run init first."

  mkdir -p "$server_dir"
  if [[ "$force" != true ]]; then
    ensure_complete_or_absent "$server_name certificate" "$server_crt" "$server_key"
  fi

  if all_exist "$server_crt" "$server_key" && [[ "$force" != true ]]; then
    echo "$server_name certificate already exists: $server_dir"
    return
  fi

  rm -f "$server_crt" "$server_key"

  local tmp_dir
  tmp_dir="$(mktemp -d)"

  local csr="$tmp_dir/server.csr"
  local extfile="$tmp_dir/server.ext"
  write_server_extfile "$extfile" "$server_name"

  openssl req -newkey rsa:2048 -nodes \
    -keyout "$server_key" \
    -out "$csr" \
    -subj "/CN=$server_name" \
    >/dev/null 2>&1

  openssl x509 -req -sha256 -days 825 \
    -in "$csr" \
    -CA "$ca_crt" \
    -CAkey "$ca_key" \
    -CAserial "$tmp_dir/ca.srl" \
    -CAcreateserial \
    -out "$server_crt" \
    -extfile "$extfile" \
    >/dev/null 2>&1

  chmod 600 "$server_key"
  rm -rf "$tmp_dir"
  echo "generated $server_name certificate: $server_dir"
}

generate_psp() {
  local force="$1"
  local ca_dir="$2"
  local psp_dir="$3"
  local ispb="$4"
  local ca_crt="$ca_dir/ca.crt"
  local ca_key="$ca_dir/ca.key"
  local client_crt="$psp_dir/client.crt"
  local client_key="$psp_dir/client.key"

  [[ -f "$ca_crt" && -f "$ca_key" ]] || fail "local CA is missing. Run init first."

  mkdir -p "$psp_dir"
  if [[ "$force" != true ]]; then
    ensure_complete_or_absent "PSP certificate for $ispb" "$client_crt" "$client_key"
  fi

  if all_exist "$client_crt" "$client_key" && [[ "$force" != true ]]; then
    echo "PSP certificate already exists: $psp_dir"
    return
  fi

  rm -f "$client_crt" "$client_key"

  local tmp_dir
  tmp_dir="$(mktemp -d)"

  local csr="$tmp_dir/client.csr"
  local extfile="$tmp_dir/client.ext"
  write_psp_extfile "$extfile" "$ispb"

  openssl req -newkey rsa:2048 -nodes \
    -keyout "$client_key" \
    -out "$csr" \
    -subj "/CN=psp-$ispb" \
    >/dev/null 2>&1

  openssl x509 -req -sha256 -days 825 \
    -in "$csr" \
    -CA "$ca_crt" \
    -CAkey "$ca_key" \
    -CAserial "$tmp_dir/ca.srl" \
    -CAcreateserial \
    -out "$client_crt" \
    -extfile "$extfile" \
    >/dev/null 2>&1

  chmod 600 "$client_key"
  rm -rf "$tmp_dir"
  echo "generated PSP certificate: $psp_dir"
}

remove_local_psp_certificates() {
  local local_dir="$1"
  local psp_dir
  local removed=false

  for psp_dir in "$local_dir"/psp-*; do
    [[ -d "$psp_dir" ]] || continue
    rm -rf -- "$psp_dir"
    echo "removed local PSP certificate: $psp_dir"
    removed=true
  done

  if [[ "$removed" != true ]]; then
    echo "no local PSP certificates to remove: $local_dir"
  fi
}

main() {
  require_openssl

  local force=false
  local psp_root=""
  local command=""
  local ispb=""

  while (($#)); do
    case "$1" in
      --force)
        force=true
        shift
        ;;
      --psp-root)
        psp_root="${2:-}"
        [[ -n "$psp_root" ]] || fail "--psp-root requires a directory"
        shift 2
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      init|psp)
        [[ -z "$command" ]] || fail "multiple commands provided"
        command="$1"
        shift
        ;;
      *)
        if [[ "$command" == "psp" && -z "$ispb" ]]; then
          ispb="$1"
          shift
        else
          fail "unexpected argument: $1"
        fi
        ;;
    esac
  done

  [[ -n "$command" ]] || {
    usage >&2
    exit 2
  }

  local certs_dir
  certs_dir="$(script_dir)"
  local local_dir="$certs_dir/local"
  local ca_dir="$local_dir/ca"
  local gateway_dir="$local_dir/notification-gateway"
  local kafka_producer_dir="$local_dir/kafka-producer"
  psp_root="${psp_root:-$local_dir}"

  case "$command" in
    init)
      [[ -z "$ispb" ]] || fail "init does not accept an ISPB"
      [[ "$psp_root" == "$local_dir" ]] || fail "--psp-root is only supported for psp"
      if [[ "$force" == true ]]; then
        echo "warning: --force init rotates the local CA and removes local PSP certificates"
        remove_local_psp_certificates "$local_dir"
        echo "PSP certificates must be regenerated and running PSP containers must be restarted"
      fi
      generate_ca "$force" "$ca_dir"
      generate_server "$force" "$ca_dir" "$gateway_dir" "notification-gateway"
      generate_server "$force" "$ca_dir" "$kafka_producer_dir" "kafka-producer"
      ;;
    psp)
      [[ -n "$ispb" ]] || fail "missing ISPB for psp command"
      validate_ispb "$ispb"
      generate_psp "$force" "$ca_dir" "$psp_root/psp-$ispb" "$ispb"
      ;;
  esac
}

main "$@"
