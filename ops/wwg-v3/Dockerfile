# syntax=docker/dockerfile:1.7

ARG GO_BASE=golang:1.25.0@sha256:5502b0e56fca23feba76dbc5387ba59c593c02ccc2f0f7355871ea9a0852cebe
ARG ALPINE_BASE=alpine:3.19@sha256:b58899f069c47216f6002a6850143dc6fae0d35eb8b0df9300bbe6327b9c2171

FROM ${GO_BASE} AS build

ARG AWG_GO_COMMIT=9f5d948bc72cc554791cfe0fb91527e4acfb6b79
ARG AWG_GO_ARCHIVE_SHA256=44d5a0889fc43eaa7d4661de7831cba6955651d17e97bb00257f323f6778fc7c

COPY amneziawg-go.tar.gz /tmp/amneziawg-go.tar.gz
RUN echo "${AWG_GO_ARCHIVE_SHA256}  /tmp/amneziawg-go.tar.gz" | sha256sum -c - \
    && mkdir -p /src/amneziawg-go \
    && tar -xzf /tmp/amneziawg-go.tar.gz --strip-components=1 -C /src/amneziawg-go

WORKDIR /src/amneziawg-go
RUN go mod download \
    && go mod verify \
    && CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.Version=${AWG_GO_COMMIT}" \
        -o /out/amneziawg-go .

COPY uapi-config.go /src/uapi-config.go
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" \
        -o /out/awg-uapi-config /src/uapi-config.go

FROM ${ALPINE_BASE}

ARG AWG_GO_COMMIT=9f5d948bc72cc554791cfe0fb91527e4acfb6b79
ARG AWGTOOLS_COMMIT=5e882890fbca2316f8ca40e992789d24f67f0118
ARG AWGTOOLS_RELEASE=1.0.20250901
ARG AWGTOOLS_ARCHIVE_SHA256=5995833c72c93b9541bf5bd807b9d077ec20e20928370619f85c388f55ba10a7

LABEL org.opencontainers.image.title="AmneziaWG v3 server" \
      org.opencontainers.image.description="Pinned AmneziaWG v3 userspace server image for WWG" \
      org.opencontainers.image.source="https://github.com/amnezia-vpn/amneziawg-go" \
      org.opencontainers.image.version="3.0.1" \
      org.opencontainers.image.revision="${AWG_GO_COMMIT}" \
      org.amnezia.awg.tools.revision="${AWGTOOLS_COMMIT}" \
      org.amnezia.awg.tools.release="${AWGTOOLS_RELEASE}"

RUN apk --no-cache add bash ca-certificates iproute2 iptables unzip wget \
    && wget -q -O /tmp/amneziawg-tools.zip \
        "https://github.com/amnezia-vpn/amneziawg-tools/releases/download/v${AWGTOOLS_RELEASE}/alpine-3.19-amneziawg-tools.zip" \
    && echo "${AWGTOOLS_ARCHIVE_SHA256}  /tmp/amneziawg-tools.zip" | sha256sum -c - \
    && unzip -j /tmp/amneziawg-tools.zip -d /usr/bin \
    && chmod 0755 /usr/bin/awg /usr/bin/awg-quick \
    && ln -s /usr/bin/awg /usr/bin/wg \
    && ln -s /usr/bin/awg-quick /usr/bin/wg-quick \
    && rm -f /tmp/amneziawg-tools.zip

COPY --from=build /out/amneziawg-go /usr/bin/amneziawg-go
COPY --from=build /out/awg-uapi-config /usr/bin/awg-uapi-config
COPY entrypoint.sh /usr/local/bin/wwg-v3-entrypoint
RUN chmod 0755 /usr/bin/amneziawg-go /usr/bin/awg-uapi-config /usr/local/bin/wwg-v3-entrypoint

ENTRYPOINT ["/usr/local/bin/wwg-v3-entrypoint"]
