# SPDX-License-Identifier: GPL-3.0-or-later
# Native ARM64 musl build. On an x86_64 host install binfmt/QEMU first:
#   docker run --privileged --rm tonistiigi/binfmt --install arm64
#   docker build --platform linux/arm64 -t quasar-openio-builder .
FROM arm64v8/alpine:3.22 AS build
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
