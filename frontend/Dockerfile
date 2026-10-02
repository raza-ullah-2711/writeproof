# syntax=docker/dockerfile:1
# Writeproof web: the production Angular build served by Caddy, which also terminates TLS
# (automatic HTTPS) and proxies the API. Build: docker build -t writeproof-web frontend/

FROM node:24-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
RUN npm run build

FROM caddy:2-alpine AS runtime
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /src/dist/frontend/browser /srv
