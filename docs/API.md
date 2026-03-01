# 🌐 API Gateway — REST API (маршруты)

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-06-02` | Версия: `1.0`

## Аутентификация

### POST /api/v1/auth/login

Получить JWT токен.

```json
// Request
{ "email": "admin@company.com", "password": "secret" }

// Response 200
{
  "accessToken": "eyJhbGci...",
  "refreshToken": "eyJhbGci...",
  "expiresIn": 3600,
  "tokenType": "Bearer"
}
```

### POST /api/v1/auth/refresh

Обновить JWT через refresh token.

```json
// Request
{ "refreshToken": "eyJhbGci..." }

// Response 200
{ "accessToken": "eyJhbGci...", "expiresIn": 3600 }
```

### POST /api/v1/auth/logout

Инвалидировать токен (blacklist в Redis).

---

## Маршруты к backend сервисам

Все маршруты ниже требуют заголовок `Authorization: Bearer {token}` (кроме `/auth/*`).

| Метод | Маршрут | Backend | Описание |
|-------|---------|---------|----------|
| `*` | `/api/v1/auth/*` | Auth Service `:8092` | Логин, регистрация, refresh |
| `*` | `/api/v1/users/*` | User Service `:8091` | CRUD пользователей |
| `*` | `/api/v1/organizations/*` | User Service `:8091` | CRUD организаций |
| `*` | `/api/v1/devices/*` | Device Manager `:8083` | CRUD устройств, команды |
| `*` | `/api/v1/groups/*` | Device Manager `:8083` | Группы устройств |
| `*` | `/api/v1/geozones/*` | Rule Checker `:8084` | CRUD геозон |
| `*` | `/api/v1/rules/*` | Rule Checker `:8084` | Правила и алерты |
| `*` | `/api/v1/notifications/*` | Notifications `:8085` | Уведомления |
| `*` | `/api/v1/reports/*` | Analytics `:8086` | Отчёты |
| `*` | `/api/v1/analytics/*` | Analytics `:8086` | Аналитика |
| `*` | `/api/v1/maintenance/*` | Maintenance `:8087` | Плановое ТО |
| `*` | `/api/v1/sensors/*` | Sensors `:8098` | Датчики |
| `GET` | `/api/v1/dashboard` | **Aggregator** | Сводная панель |

### Заголовки, добавляемые Gateway

При проксировании запросов к backend сервисам Gateway добавляет:

```
X-Organization-Id: {orgId}    — ID организации из JWT
X-User-Id: {userId}            — ID пользователя из JWT
X-Request-Id: {uuid}           — Уникальный ID запроса
X-Forwarded-For: {clientIp}    — IP клиента
X-Forwarded-Proto: {http|https} — Протокол
```

---

## Aggregated Endpoints

### GET /api/v1/dashboard

Параллельно запрашивает 4 backend-сервиса и агрегирует ответ.

**Ответ: 200 OK**
```json
{
  "vehicles": {
    "total": 150,
    "online": 120,
    "offline": 30
  },
  "alerts": {
    "active": 5,
    "todayTotal": 12,
    "critical": 1,
    "warning": 4
  },
  "geozones": {
    "total": 25,
    "violations": 3,
    "lastViolation": "2026-06-02T09:30:00Z"
  },
  "maintenance": {
    "overdueCount": 2,
    "dueThisWeek": 5,
    "completedThisMonth": 8
  }
}
```

**Таймаут:** 5 секунд на каждый backend. Если один сервис не ответил —
поле заполняется null, остальные данные возвращаются.

---

## Rate Limiting

### Заголовки ответа

```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 58
X-RateLimit-Reset: 1717318860
```

### Ответ при превышении лимита

**429 Too Many Requests**
```json
{
  "error": "rate_limit_exceeded",
  "message": "Rate limit exceeded. Try again in 18 seconds.",
  "retryAfter": 18
}
```
Заголовок: `Retry-After: 18`

---

## CORS

### Конфигурация

```
Access-Control-Allow-Origin: {CORS_ORIGINS}
Access-Control-Allow-Methods: GET, POST, PUT, DELETE, PATCH, OPTIONS
Access-Control-Allow-Headers: Authorization, Content-Type, X-API-Key, X-Request-Id
Access-Control-Max-Age: 86400
Access-Control-Allow-Credentials: true
```

---

## Служебные эндпоинты

### GET /health

```json
{
  "status": "ok",
  "service": "api-gateway",
  "version": "1.0.0",
  "backends": {
    "auth-service": "up",
    "user-service": "up",
    "device-manager": "up",
    "rule-checker": "up",
    "notification-service": "up",
    "analytics-service": "up",
    "maintenance-service": "up",
    "sensors-service": "down"
  }
}
```

### GET /metrics

Prometheus-метрики.

---

## Коды ошибок Gateway

| Код | Значение |
|-----|----------|
| 400 | Невалидный запрос (JSON, Content-Type) |
| 401 | Не аутентифицирован (JWT отсутствует или невалиден) |
| 403 | Недостаточно прав (роль не позволяет) |
| 404 | Маршрут не найден |
| 429 | Rate limit превышен |
| 502 | Backend вернул ошибку (Bad Gateway) |
| 503 | Backend недоступен (Circuit Breaker Open) |
| 504 | Backend timeout (Gateway Timeout) |

## Примеры curl

```bash
# Логин
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@company.com","password":"secret"}'

# Список устройств (JWT)
curl -H "Authorization: Bearer eyJhbGci..." \
  http://localhost:8080/api/v1/devices

# Dashboard (агрегация)
curl -H "Authorization: Bearer eyJhbGci..." \
  http://localhost:8080/api/v1/dashboard

# С API Key
curl -H "X-API-Key: key-abc123" \
  http://localhost:8080/api/v1/devices

# Health check
curl http://localhost:8080/health
```
