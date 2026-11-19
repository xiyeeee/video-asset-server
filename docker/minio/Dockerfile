FROM alpine:3.23.4 AS download
ARG TARGETARCH
ARG MINIO_RELEASE=RELEASE.2025-09-07T16-13-09Z
ARG MC_RELEASE=RELEASE.2025-08-13T08-35-41Z
WORKDIR /bin-download
RUN set -eu; \
    case "$TARGETARCH" in \
        amd64) \
            minio_sha=7c5bd8512c6e966455b1d198209358b2d191c77a83ab377c4073281065fb855f; \
            mc_sha=01f866e9c5f9b87c2b09116fa5d7c06695b106242d829a8bb32990c00312e891 ;; \
        arm64) \
            minio_sha=5c83cd2cf151717ba0243f73e1c7802ff36e272b67144bdd7f1f7d684fd6f03d; \
            mc_sha=14c8c9616cfce4636add161304353244e8de383b2e2752c0e9dad01d4c27c12c ;; \
        *) exit 1 ;; \
    esac; \
    wget -q -O minio "https://github.com/minio/minio/releases/download/$MINIO_RELEASE/minio.linux-$TARGETARCH.$MINIO_RELEASE"; \
    wget -q -O mc "https://github.com/minio/mc/releases/download/$MC_RELEASE/mc.linux-$TARGETARCH.$MC_RELEASE"; \
    printf '%s  minio\n%s  mc\n' "$minio_sha" "$mc_sha" | sha256sum -c -; \
    chmod 0755 minio mc

FROM alpine:3.23.4
COPY --from=download /bin-download/ /usr/local/bin/
RUN mkdir /data && chown 65534:65534 /data
ENV HOME=/tmp
USER 65534:65534
