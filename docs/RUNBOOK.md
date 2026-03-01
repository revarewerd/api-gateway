# 🌐 API Gateway — Runbook

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-06-02` | Версия: `1.0`

## Запуск

### SBT (разработка)
```bash
cd services/API-Gateway
sbt run
```

### Docker
```bash
docker build -t wayrecall/api-gateway .
docker run -p 8080:8080 \
  -e REDIS_HOST=redis \
  -e JWT_SECRET=your-secret-key \
  -e CORS_ORIGINS=http://localhost:3001 \
  wayrecall/api-gateway
```

### Docker Compose
```bash
docker-compose up api-gateway
```

## Health Check

```bash
# Gateway health
curl http://localhost:8080/health

# Backend services status
curl http://localhost:8080/health | jq '.backends'
```

---

## Типичные ошибки

### 1. 401 Unauthorized на все запросы

**Симптом:** Любой запрос с JWT возвращает 401.

**Диагностика:**
```bash
# 1. Проверить JWT_SECRET (одинаковый для Auth Service и Gateway)
echo $JWT_SECRET

# 2. Проверить Redis (session cache)
redis-cli KEYS "session:*" | head -5

# 3. Проверить token blacklist
redis-cli KEYS "token_blacklist:*"

# 4. Декодировать JWT вручную
echo "eyJhbGci..." | cut -d. -f2 | base64 -d | jq .
```

**Причины:**
- `JWT_SECRET` не совпадает с Auth Service → синхронизировать
- Redis недоступен → fallback на decode JWT не работает
- Токен в blacklist → перелогиниться

### 2. 429 Rate Limit при нормальной нагрузке

**Симптом:** Пользователи получают 429 слишком рано.

**Диагностика:**
```bash
# Проверить счётчики
redis-cli KEYS "rate_limit:*" | wc -l

# Конкретный пользователь
redis-cli GET "rate_limit:user:uuid:$(( $(date +%s) / 60 ))"
```

**Причины:**
- Лимит слишком низкий → увеличить `RATE_LIMIT_DEFAULT`
- Фронтенд делает лишние запросы → оптимизировать клиент
- Несколько вкладок одного пользователя → expected behavior

### 3. 503 Service Unavailable (Circuit Breaker)

**Симптом:** Возврат 503 при работающем backend.

**Диагностика:**
```bash
# Проверить состояние CB
redis-cli HGETALL "circuit:device-manager"

# Проверить доступность backend напрямую
curl http://device-manager:8083/health

# Сбросить Circuit Breaker вручную
redis-cli HSET circuit:device-manager state closed failures 0
```

**Причины:**
- Backend временно был недоступен → CB в Open, подождать timeout
- Сеть между Gateway и backend нестабильна → проверить DNS/firewall
- Backend медленно отвечает → timeout = failure для CB

### 4. 502 Bad Gateway

**Симптом:** Прокси-запрос к backend возвращает 502.

**Диагностика:**
```bash
# Логи Gateway
docker logs api-gateway 2>&1 | grep "502" | tail -20

# Проверить backend
curl -v http://device-manager:8083/api/v1/devices
```

**Причины:**
- Backend вернул non-JSON ответ → parsing error
- Backend закрыл connection (keep-alive timeout) → retry
- Неправильный URL backend в конфигурации → проверить RouteConfig

### 5. CORS ошибки в браузере

**Симптом:** Браузер блокирует запрос с ошибкой CORS.

**Диагностика:**
```bash
# Проверить headers
curl -v -X OPTIONS http://localhost:8080/api/v1/devices \
  -H "Origin: http://localhost:3001"
```

**Причины:**
- `CORS_ORIGINS` не включает origin фронтенда → добавить
- OPTIONS preflight не обрабатывается → проверить CorsMiddleware
- Credentials mode: `Access-Control-Allow-Credentials: true` отсутствует

---

## Мониторинг

### Prometheus метрики

| Метрика | Тип | Описание |
|---------|-----|----------|
| `gateway_requests_total` | counter | Всего запросов (по method, path, status) |
| `gateway_request_duration_seconds` | histogram | Время обработки запроса |
| `gateway_auth_success_total` | counter | Успешных аутентификаций |
| `gateway_auth_failure_total` | counter | Неуспешных аутентификаций (по причине) |
| `gateway_rate_limit_exceeded_total` | counter | Превышений rate limit |
| `gateway_circuit_breaker_state` | gauge | Состояние CB: 0=closed, 1=open, 2=half_open |
| `gateway_circuit_breaker_trips_total` | counter | Сколько раз CB открывался |
| `gateway_backend_request_duration_seconds` | histogram | Время ответа backend (по сервису) |
| `gateway_cache_hits_total` | counter | Cache hits |
| `gateway_cache_misses_total` | counter | Cache misses |
| `gateway_active_connections` | gauge | Текущих активных соединений |

### Alert Rules

```yaml
groups:
  - name: api-gateway
    rules:
      - alert: GatewayLatencyHigh
        expr: histogram_quantile(0.99, rate(gateway_request_duration_seconds_bucket[5m])) > 0.5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "API Gateway p99 latency > 500ms"

      - alert: GatewayCircuitBreakerOpen
        expr: gateway_circuit_breaker_state > 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Circuit Breaker OPEN для {{ $labels.service }}"

      - alert: GatewayRateLimitSpike
        expr: rate(gateway_rate_limit_exceeded_total[5m]) > 10
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Более 10 rate limit violations в секунду"

      - alert: GatewayAuthFailureSpike
        expr: rate(gateway_auth_failure_total[5m]) > 5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Много неуспешных попыток аутентификации"

      - alert: GatewayBackendSlow
        expr: histogram_quantile(0.95, rate(gateway_backend_request_duration_seconds_bucket[5m])) > 1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Backend {{ $labels.service }} отвечает медленно (p95 > 1s)"
```

### Grafana Dashboard

**Рекомендуемые панели:**
1. **Request Rate** — req/sec по status code (200, 4xx, 5xx)
2. **Latency** — p50, p95, p99 всего Gateway + per backend
3. **Circuit Breaker** — состояние каждого backend (gauge)
4. **Rate Limiting** — exceeded/sec, по типу (user/apikey/ip)
5. **Auth** — success/failures, по типу (jwt/apikey)
6. **Cache** — hit rate (hits / (hits + misses))
7. **Active Connections** — текущие соединения

---

## Логи

### Ключевые маркеры

| Маркер | Уровень | Описание |
|--------|---------|----------|
| `[CorsMiddleware]` | DEBUG | CORS preflight |
| `[AuthMiddleware]` | INFO | Auth success/failure |
| `[RateLimitMiddleware]` | WARN | Rate limit exceeded |
| `[Router]` | INFO | Route matched → backend |
| `[CircuitBreaker]` | WARN | State change (open/close) |
| `[ProxyService]` | ERROR | Backend error/timeout |
| `[DashboardAggregator]` | INFO | Aggregation timing |

### Примеры логов

```
INFO  [AuthMiddleware] requestId=abc user=uuid org=org-1 auth=jwt
WARN  [RateLimitMiddleware] requestId=xyz user=uuid limit=100 current=101 → 429
INFO  [Router] requestId=abc path=/api/v1/devices → device-manager
WARN  [CircuitBreaker] service=device-manager state=closed→open failures=5
ERROR [ProxyService] requestId=def service=analytics-service timeout=5000ms
INFO  [DashboardAggregator] requestId=ghi org=org-1 backends=4 success=3 failed=1 duration=230ms
```
