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
//   ANY  /api/v1/devices/**         → device-manager (защищённый)
//   ANY  /api/v1/vehicles/**        → device-manager (защищённый)
//   ANY  /api/v1/billing/**         → device-manager (защищённый, TODO: billing-service)
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

      // ─── Защищённые endpoints (JWT + роль + домен) ──────────────────
      // Все запросы к /api/v1/devices/** проксируются в device-manager
      // Путь трансформируется: /api/v1/devices/123 → /devices/123
      Method.ANY / "api" / "v1" / "devices" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("device-manager", req)
      },

      // /api/v1/vehicles/** → device-manager (vehicles — часть device-manager)
      Method.ANY / "api" / "v1" / "vehicles" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("device-manager", req)
      },

      // /api/v1/billing/** → device-manager (временно! TODO: отдельный billing-service)
      Method.ANY / "api" / "v1" / "billing" / trailing -> handler { (req: Request) =>
        handleProtectedProxy("device-manager", req)
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
  // Маппинг логического имени сервиса на его URL из конфига.
  // Когда добавим billing-service — добавим сюда case "billing" => ...
  private def resolveEndpoint(config: GatewayConfig, serviceName: String) =
    serviceName match
      case "device-manager" => config.services.deviceManager
      case "auth-service"   => config.services.authService
      case _                => config.services.deviceManager  // fallback

  // ─── Трансформация пути для бэкенда ─────────────────────────────────────
  // Gateway путь:  /api/v1/devices/123
  // Бэкенд путь:   /devices/123  (убираем /api/v1 — бэкенд про него не знает)
  private def extractBackendPath(fullPath: String): String =
    val stripped = fullPath.replaceFirst("^/api/v1", "")
    if stripped.startsWith("/") then stripped else s"/$stripped"
