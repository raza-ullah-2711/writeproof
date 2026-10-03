# Writeproof frontend

Angular (standalone components, signals, zoneless) clients for Writeproof: two apps in one
workspace. `frontend` (`src/`) is the public app; `admin` (`projects/admin/`) is the admin app,
served on its own host (see `docs/admin.md`). The admin app imports shared code from `src/app`
through the `@app/*` path alias; the public app never imports admin code.

```bash
npm ci
npm start          # public app: http://localhost:4200, proxies /api and /actuator to :8080
npm run start:admin # admin app: http://localhost:4300, its proxy marks requests as the admin app's
npm test           # Vitest unit tests for both apps (single run)
npm run build      # dist/frontend and dist/admin
npm run format:check
```

Requires Node `^22.22.3` or `>=24.15`.
