# 🌐 API Gateway — Архитектурные решения (ADR)

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-06-02` | Версия: `1.0`

---

## ADR-001: Custom Gateway на ZIO вместо готового решения

**Статус:** Принято  
**Дата:** 2026-01

### Контекст
Нужен API Gateway для маршрутизации, аутентификации и rate limiting.

### Решение
Реализовать кастомный gateway на Scala 3 + ZIO + zio-http.

### Альтернативы
1. **Kong** — слишком тяжёлый для MVP, требует Lua для кастомизации
2. **Envoy + gRPC** — отклонено, наши сервисы используют REST
3. **Nginx reverse proxy** — недостаточно функциональности (нет JWT, CB)
4. **Spring Cloud Gateway** — отклонено, не Scala стек

### Последствия
- Полный контроль над middleware pipeline
- Единый стек Scala 3 + ZIO
- Больше кода для поддержки, но меньше внешних зависимостей
- Dashboard aggregation — нативно, без дополнительных сервисов

---

## ADR-002: Sliding Window Rate Limiting через Redis

**Статус:** Принято  
**Дата:** 2026-01

### Контекст
Нужен rate limiting с простой реализацией, работающий при горизонтальном масштабировании.

### Решение
Redis `INCR` + `EXPIRE` по ключу `rate_limit:{identifier}:{minute_window}`:
1. `INCR rate_limit:user:uuid:1717318800` (Unix минута = timestamp / 60)
2. `EXPIRE rate_limit:user:uuid:1717318800 60`
3. Если count > limit → 429

### Альтернативы
1. **Token Bucket** — сложнее, требует Lua-скрипт
2. **Leaky Bucket** — сложнее в Redis
3. **In-memory ConcurrentHashMap** — не работает при масштабировании
4. **Fixed Window** — выбрано (проще, допустим для MVP), sliding window для v2.0

### Последствия
- Работает с несколькими инстансами Gateway
- Простая реализация (2 Redis команды)
- Минутное окно — приемлемая точность для MVP
- Возможен «всплеск» на границе окна (допустимо)

---

## ADR-003: Circuit Breaker per Backend Service

**Статус:** Принято  
**Дата:** 2026-02

### Контекст
Если один backend-сервис упал — Gateway не должен отправлять к нему запросы
(cascade failure). Но другие сервисы должны продолжать работать.

### Решение
Circuit Breaker с 3 состояниями: Closed → Open → HalfOpen.
Состояние в Redis HASH для шаринга между инстансами Gateway.

```
circuit:{serviceName} → {state, failures, lastFailure, openedAt, successes}
```

### Параметры
- `failureThreshold = 5` — порог ошибок для перехода в Open
- `timeout = 30s` — через 30 сек в Open → переход в HalfOpen
- `halfOpenMaxProbes = 3` — сколько пробных запросов в HalfOpen

### Последствия
- Быстрый fail для клиента (503 вместо timeout)
- Автоматическое восстановление через HalfOpen
- Заголовок `Retry-After` помогает клиентам планировать повторы

---

## ADR-004: JWT Session Cache в Redis

**Статус:** Принято  
**Дата:** 2026-01

### Контекст
JWT декодирование — CPU-bound операция. При 10K req/sec на Gateway
это создаёт нагрузку.

### Решение
Кэшировать расшифрованный JWT в Redis:
1. Первый запрос: decode JWT → `HSET session:{hash} userId orgId...`
2. Последующие: `HGET session:{hash}` (< 1ms)
3. Logout: `DEL session:{hash}` + `SET token_blacklist:{hash}`

### Альтернативы
1. **Без кэша** — отклонено, decode при каждом запросе
2. **In-memory cache** — отклонено, не синхронизируется между инстансами
3. **Stateless JWT only** — отклонено, невозможен мгновенный logout

### Последствия
- Быстрая авторизация (Redis < 1ms vs JWT decode ~5ms)
- Мгновенный logout (DEL + blacklist)
- Зависимость от Redis (fallback: decode JWT напрямую)

---

## ADR-005: Aggregated Dashboard Endpoint

**Статус:** Принято  
**Дата:** 2026-03

### Контекст
Фронтенд Dashboard требует данные от 4 сервисов. Делать 4 отдельных запроса
от клиента — медленно (sequential) и создаёт лишнюю нагрузку.

### Решение
Endpoint `/api/v1/dashboard` — Gateway параллельно запрашивает 4 backend
сервиса через `ZIO.collectAllPar` и агрегирует результат.

```scala
def dashboard(orgId: UUID): Task[DashboardResponse] =
  ZIO.collectAllPar(List(
    deviceManager.getVehicleSummary(orgId).either,
    ruleChecker.getGeozoneSummary(orgId).either,
    analytics.getAlertsSummary(orgId).either,
    maintenance.getMaintenanceSummary(orgId).either
  )).map { results =>
    DashboardResponse(
      vehicles = results(0).toOption,
      geozones = results(1).toOption,
      alerts = results(2).toOption,
      maintenance = results(3).toOption
    )
  }
```

### Последствия
- 1 запрос от клиента вместо 4
- Параллельное выполнение → latency = max(backends), а не sum
- Partial response: если один backend упал — остальные данные возвращаются
- Кэшируется 30 секунд в Redis
