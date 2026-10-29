FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json tsconfig.json tsconfig.node.json vite.config.ts index.html ./
COPY src ./src
RUN npm ci --no-audit --no-fund && npm run build

FROM nginx:1.27-alpine
ENV NGINX_ENVSUBST_FILTER="^PORT$"
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 10000
