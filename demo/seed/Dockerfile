FROM alpine:3.20

RUN apk add --no-cache curl jq

COPY seed.sh /seed.sh

ENTRYPOINT ["/bin/sh", "/seed.sh"]
