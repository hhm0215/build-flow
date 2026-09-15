# ─── Build Stage ───
# bun.lock(텍스트 lockfile)은 bun 1.2+ 형식 — packageManager/CI/로컬과 정렬
FROM oven/bun:1.3.11-alpine AS builder
WORKDIR /app
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile
COPY . .
RUN bun run build

# ─── Production Stage ───
FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
