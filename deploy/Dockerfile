# Build from the repository root:
#   docker build -f deploy/Dockerfile -t recording-helper-hub:2.0.0 .
FROM node:24-alpine

ENV NODE_ENV=production \
    HOST=0.0.0.0 \
    PORT=8788 \
    RH_DATA_DIR=/data

WORKDIR /app
# Only the runtime parts of server/ (no tests, no local data directory).
COPY server/package.json server/index.js ./server/
COPY server/lib ./server/lib
COPY server/web ./server/web

RUN mkdir -p /data && chown node:node /data
VOLUME ["/data"]
USER node
EXPOSE 8788

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:8788/api/v2/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "server/index.js"]
