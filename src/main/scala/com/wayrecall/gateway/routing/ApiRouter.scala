package com.wayrecall.gateway.routing

import com.wayrecall.gateway.config.GatewayConfig
import com.wayrecall.gateway.domain.*
import com.wayrecall.gateway.middleware.*
import com.wayrecall.gateway.service.*
import zio.*
import zio.http.*
import zio.json.*

import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// ApiRouter.scala — Главный маршрутизатор API Gateway
// ─────────────────────────────────────────────────────────────────────────────
//
// Что это:
//   Центральная точка маршрутизации. Все HTTP-запросы проходят через этот объект.
//   Каждый маршрут — чистая функция Request → ZIO[Env, Nothing, Response].
//   Ошибки обрабатываются внутри (wrapWithCors) — наружу ВСЕГДА выходит Response.
//
// Как работает запрос:
//   1. Клиент шлёт запрос → nginx → Gateway :8080
//   2. ZIO HTTP матчит маршрут по Method + Path
//   3. Открытые endpoints (login, health, OPTIONS) — без JWT
//   4. Защищённые endpoints — JWT → роль → домен → прокси к бэкенду
//   5. Ответ обогащается CORS-заголовками
//   6. Любая ошибка → JSON ErrorResponse с правильным HTTP-статусом
//
// Маршруты:
//   POST /api/v1/auth/login         — логин (открытый)
//   GET  /health                    — healthcheck (открытый)
//   OPTIONS /*                      — CORS preflight (открытый)
//   ─── Block 1: Data Collection ──────────────────────────────────
//   ANY  /api/v1/devices/**         → device-manager     (CRUD устройств)
//   ANY  /api/v1/vehicles/**        → device-manager     (привязка ТС)
//   ANY  /api/v1/history/**         → history-writer     (телеметрия, маршруты)
//   ─── Block 2: Business Logic ───────────────────────────────────
//   ANY  /api/v1/geozones/**        → rule-checker       (CRUD геозон)
//   ANY  /api/v1/speed-rules/**     → rule-checker       (правила скорости)
//   ANY  /api/v1/notifications/**   → notification-service (правила/шаблоны/история уведомлений)
//   ANY  /api/v1/reports/**         → analytics-service  (отчёты, экспорт)
//   ANY  /api/v1/users/**           → user-service       (пользователи, роли)
//   ANY  /api/v1/roles/**           → user-service       (управление ролями)
//   ANY  /api/v1/groups/**          → user-service       (группы ТС)
//   ANY  /api/v1/company/**         → user-service       (компания, аудит)
//   ANY  /api/v1/audit/**           → user-service       (аудит-лог)
//   ANY  /api/v1/admin/**           → admin-service      (только SuperAdmin!)
//   ANY  /api/v1/integrations/**    → integration-service (Wialon, webhooks, API keys)
//   ANY  /api/v1/maintenance/**     → maintenance-service (ТО, расписания)
//   ANY  /api/v1/templates/**       → maintenance-service (шаблоны ТО)
//   ANY  /api/v1/schedules/**       → maintenance-service (расписания ТО)
//   ANY  /api/v1/services/**        → maintenance-service (записи ТО)
//   ANY  /api/v1/sensors/**         → sensors-service    (датчики, калибровки)
//   ANY  /api/v1/calibrations/**    → sensors-service    (калибровки)
//   ANY  /api/v1/events/**          → sensors-service    (события датчиков)
//   ─── Block 3: Presentation ─────────────────────────────────────
//   ANY  /api/v1/ws/**              → websocket-service  (WebSocket upgrade)
//
// Типы ошибок (все обрабатываются в wrapWithCors):
//   401 Unauthorized  — нет/невалидный JWT
//   403 Forbidden     — роль не подходит для домена
//   422 Validation    — невалидный JSON в запросе
//   502 Bad Gateway   — бэкенд не отвечает
//   504 Gateway Timeout — бэкенд не уложился в таймаут
// ─────────────────────────────────────────────────────────────────────────────
object ApiRouter:

  // Тип окружения для всех маршрутов.
  // & — пересечение типов (intersection type, Scala 3).
  // Означает: "для работы нужны ВСЕ эти сервисы одновременно".
  // Если забыть подключить один из слоёв в Main — ошибка компиляции.
  type GatewayEnv = GatewayConfig & AuthService & ProxyService & HealthService & Scope

  // ─── Таблица маршрутов ────────────────────────────────────────────────
  // Routes() — коллекция маршрутов ZIO HTTP.
  // Каждый маршрут: Method / "path" / "segments" -> handler { ... }
  // trailing — wildcard, матчит любой хвост пути (/devices/123/commands и т.д.)
  // Method.ANY — матчит GET, POST, PUT, DELETE, PATCH
  def routes: Routes[GatewayEnv, Response] =
    Routes(
      // ─── Открытые endpoints (без JWT-проверки) ──────────────────────
      // Логин: POST /api/v1/auth/login — принимает email+password, отдаёт JWT
      Method.POST / "api" / "v1" / "auth" / "login" -> handler { (req: Request) =>
        handleLogin(req)
      },

      // Healthcheck: GET /health — проверяет доступность бэкендов
      // Используется Docker healthcheck, load balancer, мониторингом
      Method.GET / "health" -> handler { (req: Request) =>
        handleHealth(req)
      },

      // CORS preflight: OPTIONS /* — браузер шлёт перед каждым cross-origin запросом
      // Без этого фронтенд (React на другом порту) не сможет общаться с API
      Method.OPTIONS / trailing -> handler { (req: Request) =>
        handleOptions(req)
      },

      // ═══════════════════════════════════════════════════════════════════
      // ═══ Block 1: Data Collection (защищённые endpoints) ═══════════════
      // ═══════════════════════════════════════════════════════════════════

      // Device Manager — CRUD устройств, привязка к ТС
      // /api/v1/devices/** → device-manager :10092
      Method.ANY / "api" / "v1" / "devices" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("device-manager", req)
      },
      // /api/v1/vehicles/** → device-manager (vehicles — часть device-manager: assign/unassign)
      // Примеры: POST /devices/:id/assign/:vehicleId, DELETE /devices/:id/vehicle
      Method.ANY / "api" / "v1" / "vehicles" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("device-manager", req)
      },

      // History Writer — телеметрия, маршруты, статистика
      // /api/v1/history/** → history-writer :10091
      // Примеры: GET /history/telemetry/:vehicleId?from=&to=, GET /history/trips/:vehicleId
      Method.ANY / "api" / "v1" / "history" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("history-writer", req)
      },

      // ═══════════════════════════════════════════════════════════════════
      // ═══ Block 2: Business Logic (защищённые endpoints) ════════════════
      // ═══════════════════════════════════════════════════════════════════

      // Rule Checker — геозоны
      // /api/v1/geozones/** → rule-checker :8093
      // Примеры: GET/POST /geozones, GET /geozones/:id, DELETE /geozones/:id
      Method.ANY / "api" / "v1" / "geozones" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("rule-checker", req)
      },
      // Rule Checker — правила скорости
      // /api/v1/speed-rules/** → rule-checker :8093
      Method.ANY / "api" / "v1" / "speed-rules" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("rule-checker", req)
      },

      // Notification Service — правила уведомлений, шаблоны, история
      // /api/v1/notifications/** → notification-service :8094
      // Перенаправляет на /api/v1/rules, /api/v1/templates, /api/v1/history внутри сервиса
      Method.ANY / "api" / "v1" / "notifications" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("notification-service", req)
      },

      // Analytics Service — отчёты и экспорт
      // /api/v1/reports/** → analytics-service :8095
      // Примеры: GET /reports/mileage, GET /reports/fuel, POST /reports/export
      Method.ANY / "api" / "v1" / "reports" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("analytics-service", req)
      },

      // User Service — пользователи
      // /api/v1/users/** → user-service :8091
      // Примеры: GET /users/me, PUT /users/me, GET /users, POST /users
      Method.ANY / "api" / "v1" / "users" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("user-service", req)
      },
      // User Service — роли
      // /api/v1/roles/** → user-service :8091
      Method.ANY / "api" / "v1" / "roles" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("user-service", req)
      },
      // User Service — группы ТС
      // /api/v1/groups/** → user-service :8091
      Method.ANY / "api" / "v1" / "groups" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("user-service", req)
      },
      // User Service — информация о компании
      // /api/v1/company/** → user-service :8091
      Method.ANY / "api" / "v1" / "company" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("user-service", req)
      },
      // User Service — аудит-лог
      // /api/v1/audit/** → user-service :8091
      Method.ANY / "api" / "v1" / "audit" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("user-service", req)
      },

      // Admin Service — системное администрирование (только SuperAdmin!)
      // /api/v1/admin/** → admin-service :8097
      // Примеры: GET /admin/system/health, GET /admin/companies, POST /admin/config/maintenance/enable
      Method.ANY / "api" / "v1" / "admin" / trailing -> handler { (req: Request) =>
        handleAdminProxy(req)
      },

      // Integration Service — Wialon, webhooks, API keys
      // /api/v1/integrations/** → integration-service :8096
      // Примеры: GET /integrations/wialon, POST /integrations/webhooks/:id/test
      Method.ANY / "api" / "v1" / "integrations" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("integration-service", req)
      },

      // Maintenance Service — плановое ТО
      // /api/v1/maintenance/** → maintenance-service :8087
      // Примеры: GET /maintenance/vehicles/:id/overview, POST /maintenance/services
      Method.ANY / "api" / "v1" / "maintenance" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("maintenance-service", req)
      },
      // Maintenance Service — шаблоны ТО
      // /api/v1/templates/** → maintenance-service :8087
      // Примеры: GET/POST /templates, GET/PUT/DELETE /templates/:id
      Method.ANY / "api" / "v1" / "templates" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("maintenance-service", req)
      },
      // Maintenance Service — расписания ТО
      // /api/v1/schedules/** → maintenance-service :8087
      Method.ANY / "api" / "v1" / "schedules" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("maintenance-service", req)
      },

      // Sensors Service — датчики, калибровки, события
      // /api/v1/sensors/** → sensors-service :8098
      // Примеры: GET /sensors?vehicle_id=, POST /sensors, GET /sensors/:id/values
      Method.ANY / "api" / "v1" / "sensors" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("sensors-service", req)
      },
      // Sensors Service — калибровки
      // /api/v1/calibrations/** → sensors-service :8098
      Method.ANY / "api" / "v1" / "calibrations" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("sensors-service", req)
      },
      // Sensors Service — события датчиков (слив/заправка)
      // /api/v1/events/** → sensors-service :8098
      Method.ANY / "api" / "v1" / "events" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("sensors-service", req)
      },

      // ═══════════════════════════════════════════════════════════════════
      // ═══ Block 3: Presentation ═════════════════════════════════════════
      // ═══════════════════════════════════════════════════════════════════

      // WebSocket Service — real-time GPS позиции через WebSocket
      // /api/v1/ws/** → websocket-service :8090
      // Клиент подключается: ws://gateway:8080/api/v1/ws/gps?token=<jwt>
      Method.ANY / "api" / "v1" / "ws" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("websocket-service", req)
      }
    )

  // ─── POST /api/v1/auth/login ────────────────────────────────────────────
  // Открытый endpoint — JWT не требуется.
  // Принимает: { "email": "...", "password": "..." }
  // Возвращает: { "token": "...", "expiresAt": "...", "user": {...} }
  // Ошибки: 422 (невалидный JSON), 401 (неверный пароль)
  private def handleLogin(request: Request): ZIO[GatewayEnv, Nothing, Response] =
    wrapWithCors(request) {
      for
        // Шаг 1: прочитать тело запроса как строку
        _        <- ZIO.logDebug("[LOGIN] Получен запрос на логин")
        body     <- request.body.asString
                      .mapError(_ => DomainError.ValidationFailed("Невалидное тело запроса"))

        // Шаг 2: распарсить JSON → LoginRequest case class
        loginReq <- ZIO.fromEither(body.fromJson[LoginRequest])
                      .mapError(err => DomainError.ValidationFailed(s"Ошибка парсинга JSON: $err"))
        _        <- ZIO.logDebug(s"[LOGIN] Email: ${loginReq.email}")

        // Шаг 3: аутентификация (проверка email+password, создание JWT)
        response <- AuthService.login(loginReq)
        _        <- ZIO.logInfo(s"[LOGIN] Успешный логин: ${loginReq.email}")
      yield Response.json(response.toJson).status(Status.Ok)
    }

  // ─── GET /health ────────────────────────────────────────────────────────
  // Открытый endpoint — проверяет здоровье gateway и всех бэкендов.
  // Возвращает 200 если всё хорошо, 503 если есть проблемы.
  // Docker/K8s используют для автоперезапуска контейнера.
  private def handleHealth(request: Request): ZIO[GatewayEnv, Nothing, Response] =
    for
      _      <- ZIO.logDebug("[HEALTH] Проверка здоровья бэкендов...")
      health <- HealthService.check
      _      <- ZIO.logDebug(s"[HEALTH] Статус: ${health.status}, сервисы: ${health.services.map(s => s"${s.name}=${s.status}").mkString(", ")}")
    yield Response.json(health.toJson).status(
      if health.status == HealthStatus.Healthy then Status.Ok
      else Status.ServiceUnavailable
    )

  // ─── OPTIONS /* ─────────────────────────────────────────────────────────
  // CORS preflight — браузер автоматически шлёт OPTIONS перед любым
  // cross-origin запросом (когда фронтенд на localhost:3001, а API на :8080).
  // Возвращаем 204 с CORS-заголовками, чтобы браузер разрешил основной запрос.
  private def handleOptions(request: Request): ZIO[GatewayEnv, Nothing, Response] =
    for
      _    <- ZIO.logDebug(s"[CORS] Preflight: ${request.headers.get("Origin").map(_.toString).getOrElse("no-origin")} → ${request.path}")
      resp <- CorsMiddleware.handlePreflight(request)
    yield resp

  // ─── Защищённый прокси к бэкенду ───────────────────────────────────────
  // Полный pipeline обработки защищённого запроса:
  //   1. Извлечь JWT из Authorization header
  //   2. Проверить подпись и срок действия токена
  //   3. Определить AppDomain по Origin (billing/monitoring)
  //   4. Проверить что роли пользователя подходят для этого домена
  //   5. Найти URL бэкенда по имени сервиса
  //   6. Обогатить заголовки контекстом (X-User-Id, X-Company-Id, X-User-Roles)
  //   7. Проксировать запрос к бэкенду
  //   8. Вернуть ответ бэкенда клиенту с CORS-заголовками
  //
  // Если любой шаг падает — DomainError превращается в JSON-ответ с кодом ошибки.
  private def handleProtectedProxy(serviceName: String, request: Request): ZIO[GatewayEnv, Nothing, Response] =
    wrapWithCors(request) {
      for
        config    <- ZIO.service[GatewayConfig]
        requestId <- LogMiddleware.generateRequestId
        _         <- ZIO.logDebug(s"[$requestId] ▶ ${request.method} ${request.path} → $serviceName")

        // Шаг 1: Аутентификация — извлечь и проверить JWT
        authResult <- AuthMiddleware.authenticate(request)
        _          <- ZIO.logDebug(s"[$requestId]   Аутентификация: ${authResult.getClass.getSimpleName}")
        context    <- authResult match
                        case AuthResult.Authenticated(ctx) =>
                          ZIO.logDebug(s"[$requestId]   Пользователь: ${ctx.email}, роли: ${ctx.roles.mkString(",")}") *>
                          ZIO.succeed(ctx)
                        case AuthResult.Anonymous =>
                          ZIO.logWarning(s"[$requestId]   ✗ Нет токена — отказ") *>
                          ZIO.fail(DomainError.Unauthorized("Требуется авторизация"))
                        case AuthResult.Failed(err) =>
                          ZIO.logWarning(s"[$requestId]   ✗ Невалидный токен: ${err.message}") *>
                          ZIO.fail(err)

        // Шаг 2: Авторизация — проверить роль для домена
        domain     <- AuthMiddleware.resolveAppDomain(request).map(_.getOrElse(AppDomain.Monitoring))
        _          <- ZIO.logDebug(s"[$requestId]   Домен: $domain, допустимые роли: ${domain.allowedRoles.mkString(",")}")
        _          <- ZIO.fromEither(AuthMiddleware.authorizeForDomain(context, domain))

        // Шаг 3: Определить бэкенд
        endpoint    = resolveEndpoint(config, serviceName)
        _          <- ZIO.logDebug(s"[$requestId]   Бэкенд: ${endpoint.baseUrl}")

        // Шаг 4: Обогатить заголовки контекстом пользователя
        // Бэкенд получает X-User-Id, X-Company-Id, X-User-Roles — не парсит JWT сам
        headers     = AuthMiddleware.enrichHeaders(context, requestId)

        // Шаг 5: Проксировать к бэкенду
        path        = extractBackendPath(request.path.toString)
        _          <- ZIO.logDebug(s"[$requestId]   Прокси: ${request.method} ${endpoint.baseUrl}$path")
        start      <- Clock.instant
        response   <- ProxyService.forward(endpoint, request, path, headers)
        end        <- Clock.instant
        latency     = java.time.Duration.between(start, end).toMillis

        // Шаг 6: Логирование результата
        _          <- ZIO.logInfo(
                        s"[$requestId] ◀ ${request.method} ${request.path} → $serviceName → ${response.status.code} (${latency}ms) " +
                        s"user=${context.email} company=${context.companyId.value}"
                      )
      yield response
    }

  // ─── Прокси к Admin Service (ТОЛЬКО SuperAdmin!) ────────────────────────
  // Отдельный обработчик, т.к. admin endpoints требуют роль SuperAdmin.
  // Проверяет роль ДО проксирования — если не SuperAdmin → 403 Forbidden.
  private def handleAdminProxy(request: Request): ZIO[GatewayEnv, Nothing, Response] =
    wrapWithCors(request) {
      for
        config    <- ZIO.service[GatewayConfig]
        requestId <- LogMiddleware.generateRequestId
        _         <- ZIO.logDebug(s"[$requestId] ▶ [ADMIN] ${request.method} ${request.path}")

        // Шаг 1: Аутентификация
        authResult <- AuthMiddleware.authenticate(request)
        context    <- authResult match
                        case AuthResult.Authenticated(ctx) => ZIO.succeed(ctx)
                        case AuthResult.Anonymous =>
                          ZIO.logWarning(s"[$requestId]   ✗ ADMIN: нет токена") *>
                          ZIO.fail(DomainError.Unauthorized("Требуется авторизация"))
                        case AuthResult.Failed(err) =>
                          ZIO.logWarning(s"[$requestId]   ✗ ADMIN: невалидный токен") *>
                          ZIO.fail(err)

        // Шаг 2: Проверка роли SuperAdmin — строже чем обычный домен
        _          <- {
                        if context.hasRole(Role.SuperAdmin) then
                          ZIO.logDebug(s"[$requestId]   ✓ ADMIN: SuperAdmin подтверждён (${context.email})")
                        else
                          ZIO.logWarning(s"[$requestId]   ✗ ADMIN: роль ${context.roles.mkString(",")} недостаточна (нужен SuperAdmin)") *>
                          ZIO.fail(DomainError.Forbidden("Доступ только для SuperAdmin", Set(Role.SuperAdmin)))
                      }

        // Шаг 3: Прокси к admin-service
        endpoint    = resolveEndpoint(config, "admin-service")
        headers     = AuthMiddleware.enrichHeaders(context, requestId)
        path        = extractBackendPath(request.path.toString)
        _          <- ZIO.logDebug(s"[$requestId]   Прокси: ${request.method} ${endpoint.baseUrl}$path")
        start      <- Clock.instant
        response   <- ProxyService.forward(endpoint, request, path, headers)
        end        <- Clock.instant
        latency     = java.time.Duration.between(start, end).toMillis
        _          <- ZIO.logInfo(
                        s"[$requestId] ◀ [ADMIN] ${request.method} ${request.path} → admin-service → ${response.status.code} (${latency}ms) " +
                        s"user=${context.email}"
                      )
      yield response
    }

  // ─── Обёртка: ошибки → Response + CORS ──────────────────────────────────
  // Любой обработчик оборачивается сюда. Гарантии:
  //   1. DomainError → JSON ErrorResponse с правильным HTTP-статусом
  //   2. CORS-заголовки добавляются к любому ответу (и успех, и ошибка)
  //   3. Наружу ВСЕГДА выходит Response, никогда не ошибка
  private def wrapWithCors(request: Request)(
    effect: ZIO[GatewayEnv, DomainError, Response]
  ): ZIO[GatewayEnv, Nothing, Response] =
    val origin = request.headers.get("Origin").map(_.toString)
    effect
      .catchAll { err =>
        ZIO.logWarning(s"[ERROR] ${err.statusCode} ${err.message}") *>
        ZIO.succeed(domainErrorToResponse(err))
      }
      .flatMap(resp => CorsMiddleware.enrichResponse(resp, origin))

  // ─── DomainError → HTTP Response (JSON) ─────────────────────────────────
  // Маппинг типизированной ошибки в HTTP-ответ.
  // ErrorResponse — стандартный формат ошибки для всех клиентов.
  private def domainErrorToResponse(error: DomainError): Response =
    val body = ErrorResponse.fromDomainError(error)
    Response
      .json(body.toJson)
      .status(Status.fromInt(error.statusCode).getOrElse(Status.InternalServerError))

  // ─── Имя сервиса → конфиг endpoint-а ───────────────────────────────────
  // Маппинг логического имени сервиса на его ServiceEndpoint из конфига.
  // ServiceEndpoint содержит baseUrl + timeoutMs.
  //
  // Block 1: connection-manager, device-manager, history-writer
  // Block 2: rule-checker, notification-service, analytics-service,
  //          user-service, admin-service, integration-service,
  //          maintenance-service, sensors-service
  // Block 3: websocket-service, auth-service
  //
  // При добавлении нового сервиса — добавь case И запись в GatewayConfig.
  private def resolveEndpoint(config: GatewayConfig, serviceName: String) =
    serviceName match
      // Block 1 — Data Collection
      case "connection-manager"  => config.services.connectionManager
      case "device-manager"      => config.services.deviceManager
      case "history-writer"      => config.services.historyWriter
      // Block 2 — Business Logic
      case "rule-checker"        => config.services.ruleChecker
      case "notification-service"=> config.services.notificationService
      case "analytics-service"   => config.services.analyticsService
      case "user-service"        => config.services.userService
      case "admin-service"       => config.services.adminService
      case "integration-service" => config.services.integrationService
      case "maintenance-service" => config.services.maintenanceService
      case "sensors-service"     => config.services.sensorsService
      // Block 3 — Presentation
      case "websocket-service"   => config.services.websocketService
      case "auth-service"        => config.services.authService
      // Fallback — не должно произойти в production (все имена контролируются в routes)
      case unknown               => config.services.deviceManager

  // ─── Трансформация пути для бэкенда ─────────────────────────────────────
  // Gateway путь:  /api/v1/devices/123
  // Бэкенд путь:   /devices/123  (убираем /api/v1 — бэкенд про него не знает)
  private def extractBackendPath(fullPath: String): String =
    val stripped = fullPath.replaceFirst("^/api/v1", "")
    if stripped.startsWith("/") then stripped else s"/$stripped"
