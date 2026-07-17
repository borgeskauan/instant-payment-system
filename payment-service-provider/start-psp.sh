#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Start a payment-service-provider container attached to the local infra network.

Usage:
  payment-service-provider/start-psp.sh [BANK_CODE] [options]

Options:
  --bank-code VALUE             PSP bank code. Defaults to positional BANK_CODE or 11111111.
  --name VALUE                  Container name. Defaults to psp-<bank-code>.
  --host-port VALUE             Host port to expose. Defaults to first free port from 8081.
  --container-port VALUE        Container HTTP port. Defaults to 8080.
  --image VALUE                 Docker image. Defaults to payment-service-provider:local.
  --network VALUE               Docker network. Defaults to infra_default.
  --dict-url VALUE              DICT URL inside Docker network. Defaults to http://dict:8003.
  --central-transfer-url VALUE  Central transfer URL. Defaults to http://kafka-producer:8001.
  --notification-host VALUE     Notification gateway host. Defaults to notification-gateway.
  --notification-port VALUE     Notification gateway port. Defaults to 9090.
  --java-tool-options VALUE     Optional JAVA_TOOL_OPTIONS value.
  --build                       Build the PSP image before starting.
  --replace                     Remove an existing container with the same name before starting.
  --dry-run                     Print the docker command instead of running it.
  -h, --help                    Show this help.

Examples:
  payment-service-provider/start-psp.sh
  payment-service-provider/start-psp.sh 22222222
  payment-service-provider/start-psp.sh --bank-code 33333333 --host-port 8083 --replace
EOF
}

repo_root() {
  cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd
}

port_is_free() {
  local port="$1"

  if command -v ss >/dev/null 2>&1; then
    local ss_output
    ss_output="$(ss -ltn "( sport = :$port )" 2>/dev/null || true)"
    ! grep -q ":$port" <<<"$ss_output"
    return
  fi

  if command -v netstat >/dev/null 2>&1; then
    local netstat_output
    netstat_output="$(netstat -ltn 2>/dev/null || true)"
    ! grep -Eq "[.:]$port[[:space:]]" <<<"$netstat_output"
    return
  fi

  # If no port inspection tool exists, keep the default and let Docker report conflicts.
  return 0
}

first_free_port() {
  local port=8081

  while ! port_is_free "$port"; do
    port=$((port + 1))
  done

  printf '%s\n' "$port"
}

quote_cmd() {
  local arg
  printf '%q ' "$@"
  printf '\n'
}

ensure_psp_certificate() {
  local bank_code="$1"
  local cert_script="$2"
  local ca_crt="$3"
  local client_crt="$4"

  if [[ -f "$client_crt" ]] && ! openssl verify -CAfile "$ca_crt" "$client_crt" >/dev/null 2>&1; then
    echo "Existing PSP certificate does not match the local CA; regenerating it." >&2
    "$cert_script" --force psp "$bank_code"
    return
  fi

  "$cert_script" psp "$bank_code"
}

bank_code=""
container_name=""
host_port=""
container_port="8080"
image="payment-service-provider:local"
network="infra_default"
dict_url="http://dict:8003"
central_transfer_url="http://kafka-producer:8001"
notification_host="notification-gateway"
notification_port="9090"
java_tool_options=""
build_image=false
replace_existing=false
dry_run=false

while (($#)); do
  case "$1" in
    --bank-code)
      bank_code="${2:?Missing value for --bank-code}"
      shift 2
      ;;
    --name)
      container_name="${2:?Missing value for --name}"
      shift 2
      ;;
    --host-port)
      host_port="${2:?Missing value for --host-port}"
      shift 2
      ;;
    --container-port)
      container_port="${2:?Missing value for --container-port}"
      shift 2
      ;;
    --image)
      image="${2:?Missing value for --image}"
      shift 2
      ;;
    --network)
      network="${2:?Missing value for --network}"
      shift 2
      ;;
    --dict-url)
      dict_url="${2:?Missing value for --dict-url}"
      shift 2
      ;;
    --central-transfer-url)
      central_transfer_url="${2:?Missing value for --central-transfer-url}"
      shift 2
      ;;
    --notification-host)
      notification_host="${2:?Missing value for --notification-host}"
      shift 2
      ;;
    --notification-port)
      notification_port="${2:?Missing value for --notification-port}"
      shift 2
      ;;
    --java-tool-options)
      java_tool_options="${2:?Missing value for --java-tool-options}"
      shift 2
      ;;
    --build)
      build_image=true
      shift
      ;;
    --replace)
      replace_existing=true
      shift
      ;;
    --dry-run)
      dry_run=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
    *)
      if [[ -n "$bank_code" ]]; then
        echo "Unexpected argument: $1" >&2
        usage >&2
        exit 2
      fi
      bank_code="$1"
      shift
      ;;
  esac
done

bank_code="${bank_code:-11111111}"
container_name="${container_name:-psp-$bank_code}"
host_port="${host_port:-$(first_free_port)}"

root="$(repo_root)"
cert_script="$root/infra/certs/generate-local-mtls-certs.sh"
certs_local_dir="$root/infra/certs/local"
ca_dir="$certs_local_dir/ca"
psp_cert_dir="$certs_local_dir/psp-$bank_code"
psp_client_crt="$psp_cert_dir/client.crt"

docker_run_cmd=(
  docker run -d
  --name "$container_name"
  --network "$network"
  -p "$host_port:$container_port"
  -e "BANK_CODE=$bank_code"
  -e "EXTERNAL_DICT_URL=$dict_url"
  -e "EXTERNAL_CENTRAL_TRANSFER_URL=$central_transfer_url"
  -e "NOTIFICATION_GATEWAY_HOST=$notification_host"
  -e "NOTIFICATION_GATEWAY_PORT=$notification_port"
  -v "$psp_cert_dir:/certs/psp:ro"
  -v "$ca_dir:/certs/ca:ro"
  -e "NOTIFICATION_GATEWAY_TLS_CERTIFICATE_CHAIN=/certs/psp/client.crt"
  -e "NOTIFICATION_GATEWAY_TLS_PRIVATE_KEY=/certs/psp/client.key"
  -e "NOTIFICATION_GATEWAY_TLS_TRUST_CERT_COLLECTION=/certs/ca/ca.crt"
)

if [[ -n "$java_tool_options" ]]; then
  docker_run_cmd+=(-e "JAVA_TOOL_OPTIONS=$java_tool_options")
fi

docker_run_cmd+=("$image")

if $dry_run; then
  quote_cmd ensure_psp_certificate "$bank_code" "$cert_script" "$ca_dir/ca.crt" "$psp_client_crt"
  if $build_image; then
    quote_cmd docker build -t "$image" "$root/payment-service-provider"
  fi
  if $replace_existing; then
    quote_cmd docker rm -f "$container_name"
  fi
  quote_cmd "${docker_run_cmd[@]}"
  exit 0
fi

if [[ ! -f "$ca_dir/ca.crt" || ! -f "$ca_dir/ca.key" ]]; then
  cat >&2 <<EOF
Notification gateway mTLS is enabled, but the local CA was not found.

Run the certs init service before starting PSP containers:
  LOCAL_UID=\$(id -u) LOCAL_GID=\$(id -g) docker compose -f infra/docker-compose.yml up certs-init
EOF
  exit 1
fi

ensure_psp_certificate "$bank_code" "$cert_script" "$ca_dir/ca.crt" "$psp_client_crt"

if $build_image; then
  docker build -t "$image" "$root/payment-service-provider"
fi

if $replace_existing && docker ps -a --format '{{.Names}}' | grep -Fxq "$container_name"; then
  docker rm -f "$container_name"
fi

container_id="$("${docker_run_cmd[@]}")"

cat <<EOF
Started PSP container.

Container: $container_name
ID:        $container_id
Bank code: $bank_code
URL:       http://localhost:$host_port
EOF
