#!/bin/sh
set -eu

psp_url="${PSP_URL:?PSP_URL is required}"
customer_name="${CUSTOMER_NAME:?CUSTOMER_NAME is required}"
customer_tax_id="${CUSTOMER_TAX_ID:?CUSTOMER_TAX_ID is required}"
pix_key="${PIX_KEY:?PIX_KEY is required}"

until customer="$(
  curl --fail --silent --show-error \
    --header 'Content-Type: application/json' \
    --data "{\"name\":\"${customer_name}\",\"taxId\":\"${customer_tax_id}\"}" \
    "${psp_url}/customers"
)"; do
  sleep 1
done

customer_id="$(printf '%s' "$customer" | jq --exit-status --raw-output '.customer.id')"

while true; do
  keys="$(curl --fail --silent --show-error "${psp_url}/customers/${customer_id}/pix-keys")"
  if printf '%s' "$keys" | jq --exit-status --arg key "$pix_key" 'any(.[]; .pixKey == $key)' >/dev/null; then
    break
  fi

  if curl --fail --silent --show-error \
    --header 'Content-Type: application/json' \
    --data "{\"pixKey\":\"${pix_key}\"}" \
    "${psp_url}/customers/${customer_id}/pix-keys" >/dev/null; then
    break
  fi
  sleep 1
done

echo "Demo recipient ready: ${customer_name} <${pix_key}>"
