#!/bin/sh
set -eu

psp_a_url="${PSP_A_URL:?PSP_A_URL is required}"
psp_b_url="${PSP_B_URL:?PSP_B_URL is required}"
pix_key="bob@example.com"

open_customer() {
  psp_url="$1"
  name="$2"
  tax_id="$3"
  until curl --fail --silent --show-error \
    --header 'Content-Type: application/json' \
    --data "{\"name\":\"${name}\",\"taxId\":\"${tax_id}\"}" \
    "${psp_url}/customers"; do
    sleep 1
  done
}

open_customer "$psp_a_url" Alice 11111111111 >/dev/null
bob="$(open_customer "$psp_b_url" Bob 22222222222)"
bob_id="$(printf '%s' "$bob" | jq --exit-status --raw-output '.customer.id')"

while true; do
  keys="$(curl --fail --silent --show-error "${psp_b_url}/customers/${bob_id}/pix-keys")"
  if printf '%s' "$keys" | jq --exit-status --arg key "$pix_key" 'any(.[]; .pixKey == $key)' >/dev/null; then
    break
  fi

  if curl --fail --silent --show-error \
    --header 'Content-Type: application/json' \
    --data "{\"pixKey\":\"${pix_key}\"}" \
    "${psp_b_url}/customers/${bob_id}/pix-keys" >/dev/null; then
    break
  fi
  sleep 1
done

echo "Demo actors ready: Alice at PSP A; Bob <${pix_key}> at PSP B"
