# 前端镜像:用 Node 打包页面,再交给 Nginx 提供页面并把 /api 转给后端。
FROM node:20.18-alpine AS build
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
# 网络不稳时自动重试,避免下载依赖中途断开导致整个安装失败
RUN npm ci --fetch-retries=5 --fetch-retry-mintimeout=20000 --fetch-retry-maxtimeout=120000
COPY frontend/ .
RUN npm run build

FROM nginx:1.27-alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
