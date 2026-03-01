# 🌐 API Gateway — Шлюз API

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-06-02` | Версия: `1.0`

## Обзор

**API Gateway** — единая точка входа для всех клиентов (Block 3 — Presentation).
Аутентификация, авторизация, rate limiting, маршрутизация запросов к backend-сервисам,
агрегация ответов.

| Параметр | Значение |
|----------|----------|
| **Блок** | 3 — Presentation |
| **Порт** | 8080 (HTTP), 8443 (HTTPS) |
| **Auth** | JWT (Bearer) + API Key |
| **Rate Limiting** | Redis sliding window |
| **Circuit Breaker** | Per backend service |
| **Кеш** | Redis (сессии, ответы, rate limit counters) |

## Backend сервисы

| Сервис | Внутренний URL | Префикс маршрута |
|--------|---------------|-----------------|
| Auth Service | `http://auth:8092` | `/api/v1/auth/*` |
| User Service | `http://user:8091` | `/api/v1/users/*`, `/api/v1/organizations/*` |
| Device Manager | `http://device-manager:8083` | `/api/v1/devices/*`, `/api/v1/groups/*` |
| Rule Checker | `http://rule-checker:8084` | `/api/v1/geozones/*`, `/api/v1/rules/*` |
| Notification Service | `http://notifications:8085` | `/api/v1/notifications/*` |
| Analytics Service | `http://analytics:8086` | `/api/v1/reports/*`, `/api/v1/analytics/*` |
| Maintenance Service | `http://maintenance:8087` | `/api/v1/maintenance/*` |
| Sensors Service | `http://sensors:8098` | `/api/v1/sensors/*` |

## Middleware Pipeline

```
Request → CORS → RequestId → Logging → RateLimit → Auth → Validation → Router → Backend
```

## Быстрый старт

```bash
# 1. Поднять инфраструктуру и backend
cd ../../test-stand && docker-compose up -d

# 2. Запустить gateway
cd ../services/API-Gateway
sbt run

# 3. Health check
curl http://localhost:8080/health

# 4. Получить JWT
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@company.com","password":"secret"}'

# 5. Запрос с токеном
curl -H "Authorization: Bearer {token}" \
  http://localhost:8080/api/v1/devices
```

## Переменные окружения

| Переменная | По умолчанию | Описание |
|------------|-------------|----------|
| `HTTP_PORT` | `8080` | HTTP порт |
| `HTTPS_PORT` | `8443` | HTTPS порт (если TLS включён) |
| `REDIS_HOST` | `localhost` | Redis хост |
| `REDIS_PORT` | `6379` | Redis порт |
| `JWT_SECRET` | — | Секрет для подписи JWT |
| `JWT_EXPIRY` | `3600` | Время жизни JWT (секунды) |
| `RATE_LIMIT_DEFAULT` | `100` | Запросов в минуту (по умолчанию) |
| `CORS_ORIGINS` | `*` | Разрешённые CORS origins |

## Связанные документы

- [ARCHITECTURE.md](ARCHITECTURE.md) — Middleware, routing, circuit breaker
- [API.md](API.md) — Маршруты, аутентификация, rate limiting
- [DATA_MODEL.md](DATA_MODEL.md) — Redis ключи (сессии, counters, cache)
- [DECISIONS.md](DECISIONS.md) — Архитектурные решения
- [RUNBOOK.md](RUNBOOK.md) — Запуск, дебаг, ошибки
- [INDEX.md](INDEX.md) — Содержание документации
- [docs/services/API_GATEWAY.md](../../../docs/services/API_GATEWAY.md) — Системный дизайн-документ
