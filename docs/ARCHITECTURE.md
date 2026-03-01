# 🌐 API Gateway — Архитектура

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-06-02` | Версия: `1.0`

## Обзор

API Gateway — единая точка входа для всех клиентских запросов. Реализует аутентификацию,
rate limiting, маршрутизацию и circuit breaker для отказоустойчивости.

## Общая схема

```mermaid
flowchart LR
    subgraph Clients
        WEB[Web Frontend]
        MOB[Mobile App]
        EXT[External API]
    end

    subgraph "API Gateway :8080"
        CORS[CORS]
        RID[Request ID]
        LOG[Logging]
        RL[Rate Limiter]
        AUTH[Auth Middleware]
        VAL[Validation]
        RTR[Router]
    end

    subgraph Redis
        RL_R[Rate Limit Counters]
        SESS[JWT Session Cache]
        CB_R[Circuit Breaker State]
        CACHE[Response Cache]
    end

    subgraph "Backend Services"
        US[User Service :8091]
        AS[Auth Service :8092]
        DM[Device Manager :8083]
        RC[Rule Checker :8084]
        NS[Notifications :8085]
        AN[Analytics :8086]
        MS[Maintenance :8087]
        SS[Sensors :8098]
    end

    WEB --> CORS
    MOB --> CORS
    EXT --> CORS
    CORS --> RID --> LOG --> RL --> AUTH --> VAL --> RTR

    RL --> RL_R
    AUTH --> SESS
    RTR --> CB_R
    RTR --> CACHE

    RTR --> US
    RTR --> AS
    RTR --> DM
    RTR --> RC
    RTR --> NS
    RTR --> AN
    RTR --> MS
    RTR --> SS
```

## Middleware Pipeline (детальный)

```mermaid
sequenceDiagram
    participant C as Client
    participant GW as API Gateway
    participant R as Redis
    participant B as Backend Service

    C->>GW: POST /api/v1/devices
    Note over GW: 1. CORS — проверка Origin
    Note over GW: 2. RequestId — генерация X-Request-Id
    Note over GW: 3. Logging — запись входящего запроса

    GW->>R: INCR rate_limit:{ip}:{minute}
    R-->>GW: count = 42
    Note over GW: 4. RateLimit — 42/100 OK

    GW->>R: GET session:{jwt_hash}
    R-->>GW: UserContext (cached)
    Note over GW: 5. Auth — JWT валидный, user найден

    Note over GW: 6. Validation — body JSON валидный

    GW->>R: GET circuit:{device-manager}
    R-->>GW: state = CLOSED
    Note over GW: 7. Router — circuit closed, forward

    GW->>B: POST /api/v1/devices (+ X-Organization-Id, X-Request-Id)
    B-->>GW: 201 Created
    GW-->>C: 201 Created
```

## Аутентификация

### JWT Flow

```mermaid
sequenceDiagram
    participant C as Client
    participant GW as API Gateway
    participant AS as Auth Service
    participant R as Redis

    C->>GW: POST /api/v1/auth/login {email, password}
    GW->>AS: POST /login
    AS-->>GW: {accessToken, refreshToken, expiresIn}
    GW->>R: SET session:{hash} {userId, orgId, roles} EX 3600
    GW-->>C: {accessToken, refreshToken}

    C->>GW: GET /api/v1/devices (Authorization: Bearer {token})
    GW->>GW: Decode JWT → claims
    GW->>R: GET session:{hash}
    R-->>GW: UserContext
    GW->>GW: Inject X-Organization-Id, X-User-Id
    GW->>GW: Route to Device Manager
```

### API Key Flow (для интеграций)

```mermaid
sequenceDiagram
    participant E as External System
    participant GW as API Gateway
    participant R as Redis

    E->>GW: GET /api/v1/devices (X-API-Key: key-xxx)
    GW->>R: GET api_key:{hash}
    R-->>GW: {orgId, permissions, rateLimit}
    GW->>GW: Check permissions
    GW->>GW: Apply custom rate limit
    GW->>GW: Route to backend
```

## Rate Limiting

### Стратегия: Sliding Window (Redis)

```
Ключ: rate_limit:{identifier}:{window}
TTL: длительность окна

Идентификатор:
- JWT: user:{userId}
- API Key: apikey:{keyId}
- Без auth: ip:{clientIp}
```

### Лимиты по умолчанию

| Тип | Лимит | Окно |
|-----|-------|------|
| Authenticated User | 100 req/min | 1 мин |
| API Key (Standard) | 200 req/min | 1 мин |
| API Key (Premium) | 1000 req/min | 1 мин |
| Unauthenticated | 20 req/min | 1 мин |
| Login endpoint | 5 req/min | 1 мин |

### Headers в ответе

```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 58
X-RateLimit-Reset: 1717318860
```

## Circuit Breaker

```mermaid
stateDiagram-v2
    [*] --> Closed
    Closed --> Open : failures >= threshold (5)
    Open --> HalfOpen : timeout elapsed (30s)
    HalfOpen --> Closed : probe request success
    HalfOpen --> Open : probe request failed
```

### Параметры per service

| Параметр | Значение | Описание |
|----------|----------|----------|
| `failureThreshold` | 5 | Ошибок до Open |
| `timeout` | 30s | Время в Open до HalfOpen |
| `halfOpenMaxProbes` | 3 | Пробных запросов в HalfOpen |
| `resetTimeout` | 60s | Reset counters после Closed |

### Поведение при Open Circuit

При Open → ответ клиенту:
```json
{
  "error": "service_unavailable",
  "message": "Device Manager temporarily unavailable",
  "retryAfter": 30
}
```
HTTP 503 с заголовком `Retry-After: 30`.

## Router — Маршруты

```mermaid
flowchart TD
    REQ[Incoming Request] --> R{Path prefix}

    R -->|/api/v1/auth/*| AS[Auth Service :8092]
    R -->|/api/v1/users/*| US[User Service :8091]
    R -->|/api/v1/organizations/*| US
    R -->|/api/v1/devices/*| DM[Device Manager :8083]
    R -->|/api/v1/groups/*| DM
    R -->|/api/v1/geozones/*| RC[Rule Checker :8084]
    R -->|/api/v1/rules/*| RC
    R -->|/api/v1/notifications/*| NS[Notifications :8085]
    R -->|/api/v1/reports/*| AN[Analytics :8086]
    R -->|/api/v1/analytics/*| AN
    R -->|/api/v1/maintenance/*| MS[Maintenance :8087]
    R -->|/api/v1/sensors/*| SS[Sensors :8098]
    R -->|/api/v1/dashboard| AGG[Aggregator]

    AGG -->|parallel| DM
    AGG -->|parallel| RC
    AGG -->|parallel| AN
    AGG -->|parallel| MS
```

### Aggregated Endpoint: `/api/v1/dashboard`

Параллельно запрашивает 4 сервиса и агрегирует ответ:

```json
{
  "vehicles": { "total": 150, "online": 120, "offline": 30 },
  "alerts": { "active": 5, "today": 12 },
  "geozones": { "violations": 3 },
  "maintenance": { "overdue": 2, "dueThisWeek": 5 }
}
```

## Структура пакетов

```
com.wayrecall.tracker.gateway/
├── Main.scala
├── config/
│   ├── AppConfig.scala             # Порты, JWT secret, rate limits
│   ├── RouteConfig.scala           # Маршруты к backend сервисам
│   └── ServiceConfig.scala         # Per-service: URL, timeout, circuit breaker
├── domain/
│   ├── UserContext.scala            # userId, orgId, roles
│   ├── ApiKeyContext.scala          # orgId, permissions, rateLimit
│   ├── AuthResult.scala             # sealed trait: Authenticated, ApiKey, Anonymous
│   ├── GatewayRequest.scala         # Обёртка запроса с context
│   ├── GatewayError.scala           # sealed trait: ошибки
│   └── DashboardResponse.scala      # Агрегированный ответ
├── middleware/
│   ├── CorsMiddleware.scala         # CORS headers
│   ├── RequestIdMiddleware.scala    # Генерация X-Request-Id (UUID)
│   ├── LoggingMiddleware.scala      # Логирование входящих/исходящих
│   ├── RateLimitMiddleware.scala    # Sliding window через Redis
│   ├── AuthMiddleware.scala         # JWT decode + session cache
│   └── ValidationMiddleware.scala   # Content-Type, body size
├── service/
│   ├── Router.scala                 # Маршрутизация по prefix
│   ├── ProxyService.scala           # Проксирование к backend
│   ├── CircuitBreaker.scala         # CB per service
│   ├── DashboardAggregator.scala    # Parallel aggregation
│   └── HealthService.scala          # Health check всех backends
├── api/
│   ├── GatewayRoutes.scala          # Все HTTP routes
│   └── HealthRoutes.scala           # GET /health, /metrics
└── redis/
    ├── SessionStore.scala           # session:{jwt_hash}
    ├── RateLimitStore.scala         # rate_limit:{id}:{window}
    ├── ApiKeyStore.scala            # api_key:{hash}
    ├── CircuitBreakerStore.scala    # circuit:{service}
    └── ResponseCache.scala          # cache:{route}:{hash}
```

## ZIO Layer

```scala
val appLayer = ZLayer.make[AppDependencies](
  AppConfig.live,
  RouteConfig.live,
  // Инфраструктура
  RedisClient.live,
  HttpClient.live,
  // Redis stores
  SessionStore.live,
  RateLimitStore.live,
  ApiKeyStore.live,
  CircuitBreakerStore.live,
  ResponseCache.live,
  // Middleware
  CorsMiddleware.live,
  RequestIdMiddleware.live,
  LoggingMiddleware.live,
  RateLimitMiddleware.live,
  AuthMiddleware.live,
  ValidationMiddleware.live,
  // Services
  Router.live,
  ProxyService.live,
  CircuitBreaker.live,
  DashboardAggregator.live,
  HealthService.live,
  // API
  GatewayRoutes.live,
  HealthRoutes.live,
)
```
