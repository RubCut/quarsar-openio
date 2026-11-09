# SPDX-License-Identifier: GPL-3.0-or-later
# Native ARM64 musl build. The base image MUST be supplied as a digest-pinned
# reference resolved from a trusted registry; there is no unpinned default.
# Do NOT use an unpinned privileged binfmt container. Prefer distro QEMU/binfmt
# or the non-Docker `make aarch64` cross-build.
#   docker build --platform linux/arm64 \
#     --build-arg ALPINE_IMAGE=arm64v8/alpine:3.22@sha256:<verified-digest> \
#     -t quasar-openio-builder .
ARG ALPINE_IMAGE
FROM ${ALPINE_IMAGE} AS build
RUN apk add --no-cache build-base linux-headers
WORKDIR /src
COPY Makefile ./
COPY include/ include/
COPY src/ src/
COPY pack/ pack/
COPY plugins/ plugins/
RUN make static && strip build/static/quasar_openio build/static/quasar_pack

# No shell, package manager, Python, GStreamer or dynamic loader at runtime.
FROM scratch
COPY --from=build /src/build/static/quasar_openio /quasar_openio
COPY --from=build /src/build/static/quasar_pack /plugins/standard/quasar_pack
COPY --from=build /src/plugins/standard/plugin.conf /plugins/standard/plugin.conf
ENTRYPOINT ["/quasar_openio"]
