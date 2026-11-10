# SPDX-License-Identifier: GPL-3.0-or-later
# Native ARM64 musl build. On an x86_64 host install binfmt/QEMU first:
#   docker run --privileged --rm \
#     tonistiigi/binfmt@sha256:400a4873b838d1b89194d982c45e5fb3cda4593fbfd7e08a02e76b03b21166f0 \
#     --install arm64
#   docker build --platform linux/arm64 -t quasar-openio-builder .
FROM arm64v8/alpine:3.22@sha256:79857ea9c21b68f601255fc225cfdf04577fa24c05b62d614b364a9a92aa586d AS build
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
