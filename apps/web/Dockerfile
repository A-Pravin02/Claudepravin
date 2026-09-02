# Development image: runs the Vite dev server with hot reload, which is what
# `docker compose up` is for here. A production build would be a separate
# multi-stage target serving static assets from nginx -- not needed yet.
FROM node:22-alpine
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .

# node:alpine ships a non-root `node` user.
USER node

EXPOSE 5173
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]
