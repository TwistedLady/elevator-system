# Build the Angular bundle, then serve it from nginx. Build context is the REPO ROOT (like the
# api/app Dockerfiles) so the shared repo-root VERSION file is reachable to scripts/gen-version.mjs:
#   docker build -f elevator-console-web/Dockerfile -t elevator-console-web:local .
# nginx also reverse-proxies /api and /actuator to the in-cluster elevator-api Service, so the
# app's relative URLs work.
FROM node:22-alpine AS build
WORKDIR /app
COPY elevator-console-web/package.json elevator-console-web/package-lock.json ./
# --ignore-scripts: the postinstall version-gen needs scripts/ + VERSION, not yet copied here; the
# prebuild hook regenerates src/app/version.ts once everything is in place (below).
RUN npm ci --ignore-scripts
COPY elevator-console-web/ ./
# gen-version.mjs (at /app/scripts) reads ../../VERSION -> /VERSION.
COPY VERSION /VERSION
RUN npm run build

FROM nginx:1.27-alpine
# Links the GHCR package to this repository (github.com → Packages).
LABEL org.opencontainers.image.source="https://github.com/TwistedLady/elevator-system"
COPY elevator-console-web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/elevator-console-web/browser /usr/share/nginx/html
EXPOSE 80
