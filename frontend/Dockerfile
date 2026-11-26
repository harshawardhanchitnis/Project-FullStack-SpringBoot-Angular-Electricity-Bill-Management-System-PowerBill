FROM node:22-alpine AS build
WORKDIR /opt/gridiq
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/angular.json frontend/tsconfig*.json frontend/.postcssrc.json ./
COPY frontend/src ./src
RUN npm run build

FROM nginxinc/nginx-unprivileged:stable-alpine@sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03
# Apply Alpine security updates between base image releases; nginx itself comes from the nginx.org package.
USER root
RUN apk upgrade --no-cache
COPY infra/nginx/gridiq.conf /etc/nginx/conf.d/default.conf
COPY --from=build /opt/gridiq/dist/gridiq/browser /usr/share/nginx/html
USER 101
