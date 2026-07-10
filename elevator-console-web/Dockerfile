# Two stage: build the Elm bundle (Vite + vite-plugin-elm), then serve via nginx (which also
# reverse-proxies /api + /actuator to elevator-api). Build context is the REPO ROOT so the
# shared VERSION file reaches scripts/gen-version.mjs:
#   docker build -f elevator-console-web/Dockerfile -t elevator-console-web:local .
FROM node:22-alpine AS build
WORKDIR /app
COPY elevator-console-web/package.json elevator-console-web/package-lock.json ./
COPY elevator-console-web/scripts ./scripts
COPY VERSION /VERSION
RUN npm ci
COPY elevator-console-web/ ./
RUN npm run build

FROM nginx:1.27-alpine
LABEL org.opencontainers.image.source="https://github.com/TwistedLady/elevator-system"
COPY elevator-console-web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/elevator-console-web/browser /usr/share/nginx/html
EXPOSE 80
