# Local mTLS certificates

This directory contains the script used to create certificates for local development and load testing. Generated files are written under `infra/certs/local/` and are ignored by Git.

These certificates are for local environments only. They do not implement production certificate issuance, rotation, revocation, or private-key custody.

## Generate certificates

Create the local CA and the server certificates used by the Notification Gateway and Payment Ingress:

```bash
infra/certs/generate-local-mtls-certs.sh init
```

Docker Compose normally runs the same operation through its one-shot initialization service:

```bash
LOCAL_UID=$(id -u) LOCAL_GID=$(id -g) \
  docker compose -f infra/docker-compose.yml up certs-init
```

Create a client certificate for one PSP:

```bash
infra/certs/generate-local-mtls-certs.sh psp 12345678
```

The PSP identity is stored in the signed certificate as:

```text
SAN URI = urn:pix:ispb:12345678
```

The load tool can place PSP certificates in a temporary directory instead of the shared local tree:

```bash
infra/certs/generate-local-mtls-certs.sh \
  --psp-root /tmp/load-certs \
  psp 12345678
```

## Generated files

```text
infra/certs/local/
├── ca/{ca.crt,ca.key}
├── notification-gateway/{server.crt,server.key}
├── kafka-producer/{server.crt,server.key}
└── psp-12345678/{client.crt,client.key}
```

Services use `ca.crt` to validate their peers. `ca.key` is only needed to issue certificates and must not be mounted into application containers.

## Regenerate certificates

The script does not overwrite a complete certificate pair unless `--force` is provided:

```bash
infra/certs/generate-local-mtls-certs.sh --force psp 12345678
```

Rotating the local CA also recreates both server certificates and removes PSP certificates under `infra/certs/local/`:

```bash
infra/certs/generate-local-mtls-certs.sh --force init
```

After rotating the CA, regenerate the required PSP certificates and restart services that use them. PSP certificates created under a custom `--psp-root` are not removed automatically.

## Inspect a certificate

```bash
openssl x509 \
  -in infra/certs/local/psp-12345678/client.crt \
  -noout -text
```
