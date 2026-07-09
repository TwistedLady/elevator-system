# Build the Elm bundle (Vite + vite-plugin-elm), then serve it from nginx. Build context is the REPO
# ROOT (like the api/app Dockerfiles) so the shared repo-root VERSION file is reachable to
# scripts/gen-version.mjs:
#   docker build -f elevator-console-web/Dockerfile -t elevator-console-web:local .
# nginx also reverse-proxies /api and /actuator to the in-cluster elevator-api Service, so the
# app's relative URLs work. The Elm compiler ships as a statically-linked binary, so alpine is fine.
FROM node:22-alpine AS build
WORKDIR /app
# Scripts must run: the elm devDep's postinstall downloads the Elm compiler binary, and our own
# postinstall gen-version.mjs needs scripts/ + the repo-root VERSION — so we copy both up front.
COPY elevator-console-web/package.json elevator-console-web/package-lock.json ./
COPY elevator-console-web/scripts ./scripts
# gen-version.mjs (at /app/scripts) reads ../../VERSION -> /VERSION.
COPY VERSION /VERSION
RUN npm ci
COPY elevator-console-web/ ./
RUN npm run build

FROM nginx:1.27-alpine
# Links the GHCR package to this repository (github.com → Packages).
LABEL org.opencontainers.image.source="https://github.com/TwistedLady/elevator-system"
COPY elevator-console-web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/elevator-console-web/browser /usr/share/nginx/html
EXPOSE 80
