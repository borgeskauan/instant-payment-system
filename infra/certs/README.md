# Local mTLS certificates

This directory contains local development tooling for PSP `<->` `notification-gateway` mTLS.

Generated files are written under `infra/certs/local/` and are intentionally ignored by Git.

## Commands

Generate the local CA and the `notification-gateway` server certificate:

```bash
infra/certs/generate-local-mtls-certs.sh init
```

In the Docker Compose environment, this is normally done by the one-shot service:

```bash
LOCAL_UID=$(id -u) LOCAL_GID=$(id -g) docker compose -f infra/docker-compose.yml up certs-init
```

Generate a PSP client certificate for an ISPB:

```bash
infra/certs/generate-local-mtls-certs.sh psp 12345678
```

When using `payment-service-provider/start-psp.sh` with mTLS enabled, the PSP certificate is generated automatically. The script does not run `init`; the local CA must already exist.

Recreate existing files with `--force`:

```bash
infra/certs/generate-local-mtls-certs.sh --force psp 12345678
```

## Generated files

```text
infra/certs/local/
  ca/
    ca.crt
    ca.key

  notification-gateway/
    server.crt
    server.key

  psp-12345678/
    client.crt
    client.key
```

`ca.crt` is the local CA certificate. Services use it at runtime to validate peer certificates.

`ca.key` is the local CA private key. It is only used for provisioning new certificates and must not be mounted into application containers.

`server.crt` and `server.key` identify the `notification-gateway` as the gRPC server. The server certificate includes:

```text
SAN DNS = notification-gateway
SAN DNS = localhost
```

`client.crt` and `client.key` identify one PSP as a gRPC client. The client certificate includes the business identity:

```text
SAN URI = urn:pix:ispb:<ISPB>
```

Example:

```text
SAN URI = urn:pix:ispb:12345678
```

## Local model vs production model

This local setup follows the same trust idea as production: the gateway trusts a CA, the PSP presents a client certificate signed by that CA, and the application uses the signed certificate identity instead of trusting an ISPB sent in a payload.

The local setup is intentionally simpler:

- `generate-local-mtls-certs.sh init` creates the local CA and the gateway server certificate.
- `generate-local-mtls-certs.sh psp <ISPB>` creates both the PSP private key and the PSP client certificate.
- The local CA private key is stored on the developer machine under `infra/certs/local/ca/ca.key`.
- There is no CSR flow, revocation check, certificate inventory, or formal rotation policy.

A production-like setup should be stricter:

- The PSP generates and protects its own private key.
- The PSP sends a CSR containing its public key and requested identity to the CA/onboarding process.
- The CA validates the PSP/ISPB association and signs a certificate containing the approved identity, for example `SAN URI = urn:pix:ispb:<ISPB>`.
- The PSP receives only the signed certificate; the CA/SPI does not need the PSP private key.
- Homologation and production should use separate trust roots and separate certificates.
- Certificate revocation, expiration monitoring, renewal, audit, and operational inventory should exist outside this local script.

If a signed certificate is edited after issuance, the CA signature no longer validates. The gateway relies on this property: the ISPB extracted from the SAN URI is trusted only because it is part of the signed certificate validated during mTLS.

## Idempotency and rotation

Without `--force`, the script does not overwrite complete existing certificates.

If only part of a certificate pair exists, the script fails and asks for cleanup or `--force`.

`--force` means "delete and recreate the files for this command".

For a PSP certificate:

```bash
infra/certs/generate-local-mtls-certs.sh --force psp 12345678
```

This recreates only:

```text
infra/certs/local/psp-12345678/client.crt
infra/certs/local/psp-12345678/client.key
```

For the local CA and gateway certificate:

```bash
infra/certs/generate-local-mtls-certs.sh --force init
```

This recreates:

```text
infra/certs/local/ca/ca.crt
infra/certs/local/ca/ca.key
infra/certs/local/notification-gateway/server.crt
infra/certs/local/notification-gateway/server.key
```

Be careful with `--force init`: recreating the CA changes the authority that signs certificates. PSP certificates signed by the old CA will no longer match the new CA, so regenerate the PSP certificates that should keep working:

```bash
infra/certs/generate-local-mtls-certs.sh --force init
infra/certs/generate-local-mtls-certs.sh --force psp 12345678
```

## Inspect certificates

```bash
openssl x509 -in infra/certs/local/notification-gateway/server.crt -noout -text
openssl x509 -in infra/certs/local/psp-12345678/client.crt -noout -text
```
