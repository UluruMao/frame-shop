# frame-frontend

React 18 + TypeScript + Vite 5 + Tailwind CSS 3.4 frontend template.
Part of the full-stack template (`frame-backend` + `frame-frontend`). Talks to the
Spring Boot backend purely over HTTP/JSON (前端后端分离); never touches the DB.

## Tech stack (versions pinned)

| Dependency | Version |
|---|---|
| react / react-dom | ^18.3.1 |
| typescript | ^5.5.4 |
| vite | ^5.4.8 |
| tailwindcss | ^3.4.14 |
| react-router-dom | ^6.27.0 |
| zustand | ^5.0.0 |
| axios | ^1.7.7 |
| lucide-react | ^0.451.0 |
| recharts | ^2.13.0 |
| vitest | ^2.1.2 |
| @testing-library/react | ^16.0.1 |

## Prerequisites

- Node.js 18+ (verified on Node 24). npm 9+.

## Install & run

```bash
npm install
npm run dev      # Vite dev server on http://localhost:5173
```

The dev server proxies `/api/*` → `http://localhost:8080` (the backend), so start the
backend too for the login + products demos. Without the backend, the app still loads —
the `/login` page renders and `/products` shows an error message.

## Build & preview

```bash
npm run build    # tsc -b && vite build → dist/
npm run preview  # serve the production build locally
```

## Test

```bash
npm test         # vitest run (one component test: Dashboard)
npm run test:watch
```

## Project structure

```
frame-frontend/
├── index.html
├── package.json
├── vite.config.ts        Vite config + /api → :8080 proxy
├── vitest.config.ts      Vitest config (jsdom + setup file)
├── tsconfig.json
├── tailwind.config.js
├── postcss.config.js
└── src/
    ├── main.tsx          React root
    ├── App.tsx           mounts <RouterProvider>
    ├── index.css         Tailwind directives + base styles
    ├── api/
    │   ├── request.ts    Axios instance: attaches Bearer token, unwraps Result, 401 → /login
    │   ├── auth.ts       login / me
    │   ├── product.ts    product CRUD calls
    │   └── types.ts      Result<T>, Product, PageResult, etc. (mirror backend)
    ├── store/
    │   └── useAuthStore.ts   Zustand: token + user, persisted to localStorage
    ├── router/
    │   ├── index.tsx     route table (login + protected layout)
    │   └── RequireAuth.tsx   redirect-to-login guard
    ├── layouts/
    │   └── MainLayout.tsx    nav bar + <Outlet/>
    └── pages/
        ├── Login.tsx     demo: calls login, stores token, redirects
        ├── Dashboard.tsx demo: lucide icons + recharts bar chart
        └── Products.tsx  demo: paginated list from /products
tests/
├── setup.ts             vitest setup (jest-dom matchers)
└── Dashboard.test.tsx   one component test
```

## How the wiring works

- **Auth flow:** `Login.tsx` POSTs to `/auth/login` → response JWT stored in the Zustand
  auth store (persisted to `localStorage` under `frame-auth`). On every request the Axios
  request interceptor reads the token and attaches `Authorization: Bearer <token>`. The
  backend's `JwtAuthenticationFilter` validates it and sets the security context.
- **Protected routes:** `RequireAuth` checks for a token; no token → redirect to `/login`
  (remembering the intended destination).
- **Error handling:** the Axios response interceptor unwraps the backend `Result.data`
  envelope on success; on a non-200 business code it rejects with the server's `message`;
  on HTTP 401 it clears the token and redirects to `/login`.
- **Tailwind:** directives in `src/index.css`; `tailwind.config.js` scans `index.html` +
  `src/**/*`. A small `brand` palette is defined — swap it for your own.

## Demo usage

1. Start the backend (`frame-backend`: `mvn spring-boot:run`).
2. `npm run dev` here.
3. Open http://localhost:5173 → redirected to `/login`.
4. Sign in with `admin` / `admin123` → lands on the Dashboard (chart + icons).
5. Click **Products** in the nav → table of products fetched from the backend.
