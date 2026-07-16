FROM alpine:3.20

RUN apk add --no-cache bash openssl

WORKDIR /certs
ENTRYPOINT ["/certs/generate-local-mtls-certs.sh"]
