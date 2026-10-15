#
# Copyright © 2015 The Gravitee team (http://gravitee.io)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# ─────────────────────────────────────────────────────────────────────────────
# Singularitee Gateway production image (Debian, linux/amd64)
# ─────────────────────────────────────────────────────────────────────────────
FROM graviteeio/java:25-debian
LABEL maintainer="contact@graviteesource.com"
LABEL org.opencontainers.image.source="https://github.com/gravitee-io/gravitee-singularitee"
LABEL org.opencontainers.image.description="Singularitee Gateway - gRPC inference server"

ENV GRAVITEEIO_HOME=/opt/graviteeio-singularitee

USER root
RUN apt-get update && apt-get install --yes --no-install-recommends libjemalloc2 && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Code is root-owned and read-only to the runtime user, so a compromised process cannot plant a
# plugin, library or script that loads on the next start. Only the directories the server writes
# are owned by graviteeio, group root with group write, for arbitrary runtime uids in group root.
COPY --chown=root:root ./gravitee-singularitee-standalone/gravitee-singularitee-standalone-distribution/target/distribution/ ${GRAVITEEIO_HOME}/
RUN set -eux; \
    chmod -R go-w "${GRAVITEEIO_HOME}"; \
    mkdir -p "${GRAVITEEIO_HOME}/logs" "${GRAVITEEIO_HOME}/models" "${GRAVITEEIO_HOME}/plugins/.work"; \
    chown graviteeio:root "${GRAVITEEIO_HOME}/logs" "${GRAVITEEIO_HOME}/models" "${GRAVITEEIO_HOME}/plugins/.work"; \
    chmod 0770 "${GRAVITEEIO_HOME}/logs" "${GRAVITEEIO_HOME}/models" "${GRAVITEEIO_HOME}/plugins/.work"

WORKDIR ${GRAVITEEIO_HOME}
EXPOSE 9090

USER graviteeio
ENTRYPOINT ["./bin/gravitee.sh"]
