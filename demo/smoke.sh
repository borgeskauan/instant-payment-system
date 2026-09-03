#!/usr/bin/env bash
set -euo pipefail

sender_url="${SENDER_PSP_URL:-http://localhost:8081}"
receiver_url="${RECEIVER_PSP_URL:-http://localhost:8082}"
pix_key="bob@example.com"

command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "jq is required" >&2; exit 1; }

post_json() {
  local url="$1"
  local body="$2"
  curl --fail --silent --show-error -H 'Content-Type: application/json' -d "$body" "$url"
}

wait_for_psp() {
  local url="$1"
  local deadline=$((SECONDS + 60))
  while ((SECONDS < deadline)); do
    if curl --silent --output /dev/null "$url/"; then
      return
    fi
    sleep 1
  done
  echo "PSP did not become ready: $url" >&2
  exit 1
}

echo "Waiting for the demo PSPs"
wait_for_psp "$sender_url"
wait_for_psp "$receiver_url"

echo "Creating the sender and receiver customers"
sender="$(post_json "$sender_url/customers" '{"name":"Alice","taxId":"11111111111"}')"
receiver="$(post_json "$receiver_url/customers" '{"name":"Bob","taxId":"22222222222"}')"
sender_id="$(jq -er '.customer.id' <<<"$sender")"
receiver_id="$(jq -er '.customer.id' <<<"$receiver")"
sender_balance="$(jq -er '.bankAccount.balance' <<<"$sender")"
receiver_balance="$(jq -er '.bankAccount.balance' <<<"$receiver")"

echo "Resolving the pre-provisioned receiver PIX key"
preview="$(post_json "$sender_url/transfer/preview" "{\"receiverPixKey\":\"$pix_key\"}")"
jq -e --arg key "$pix_key" '.receiver.pixKey == $key' <<<"$preview" >/dev/null

echo "Submitting the payment through the core"
transfer="$(post_json "$sender_url/transfer/execute" "{\"senderCustomerId\":\"$sender_id\",\"receiverPixKey\":\"$pix_key\",\"amount\":25.50,\"description\":\"reference demo smoke\"}")"
transfer_id="$(jq -er '.transferId' <<<"$transfer")"

echo "Waiting for both PSPs to observe the final outcome"
deadline=$((SECONDS + 30))
while ((SECONDS < deadline)); do
  current_sender="$(post_json "$sender_url/customers" '{"name":"Alice","taxId":"11111111111"}')"
  current_receiver="$(post_json "$receiver_url/customers" '{"name":"Bob","taxId":"22222222222"}')"
  current_sender_balance="$(jq -er '.bankAccount.balance' <<<"$current_sender")"
  current_receiver_balance="$(jq -er '.bankAccount.balance' <<<"$current_receiver")"
  sender_payments="$(curl --fail --silent --show-error "$sender_url/customers/$sender_id/payments")"
  receiver_payments="$(curl --fail --silent --show-error "$receiver_url/customers/$receiver_id/payments")"

  if jq -en --arg before "$sender_balance" --arg after "$current_sender_balance" --arg amount "25.50" '($before | tonumber) - ($after | tonumber) == ($amount | tonumber)' >/dev/null \
      && jq -en --arg before "$receiver_balance" --arg after "$current_receiver_balance" --arg amount "25.50" '($after | tonumber) - ($before | tonumber) == ($amount | tonumber)' >/dev/null \
      && jq -e --arg id "$transfer_id" 'any(.[]; .paymentId == $id and .direction == "OUTGOING" and .status == "SETTLED")' <<<"$sender_payments" >/dev/null \
      && jq -e --arg id "$transfer_id" 'any(.[]; .paymentId == $id and .direction == "INCOMING" and .status == "SETTLED")' <<<"$receiver_payments" >/dev/null; then
    echo "Reference demo smoke passed: transfer=$transfer_id key=$pix_key"
    exit 0
  fi
  sleep 1
done

echo "Reference demo smoke failed: the PSP balances did not reach the expected final state" >&2
exit 1
