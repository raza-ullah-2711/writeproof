# Writeproof frontend

Angular (standalone components, signals, zoneless) client for Writeproof.

```bash
npm ci
npm start          # http://localhost:4200, proxies /api and /actuator to :8080
npm test           # Vitest unit tests (single run)
npm run build
npm run format:check
```

Requires Node `^22.22.3` or `>=24.15`.
