FROM node:24-alpine AS build

WORKDIR /workspace/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY openapi /workspace/openapi
COPY web ./
RUN npm run api:generate && npm run build

FROM nginx:1.29-alpine
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/web/dist /usr/share/nginx/html
EXPOSE 80

