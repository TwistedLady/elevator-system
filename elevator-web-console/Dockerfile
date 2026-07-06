# Build the Angular bundle, then serve it from nginx. Self-contained (like the api/app
# Dockerfiles) — build context is this module dir. nginx also reverse-proxies /api and
# /actuator to the in-cluster elevator-api Service, so the app's relative URLs work.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/elevator-web-console/browser /usr/share/nginx/html
EXPOSE 80
