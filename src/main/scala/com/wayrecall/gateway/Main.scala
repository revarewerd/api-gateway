package com.wayrecall.gateway

import com.wayrecall.gateway.config.{GatewayConfig, ServerConfig}
import com.wayrecall.gateway.routing.ApiRouter
import com.wayrecall.gateway.service.*
import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.http.*
import zio.logging.backend.SLF4J

// ─────────────────────────────────────────────────────────────────────────────
// Main.scala — Точка входа API Gateway (Wayrecall Tracker System)
// ─────────────────────────────────────────────────────────────────────────────
//
// Что это:
//   Единственный entry point всего микросервиса API Gateway.
//   Наследует ZIOAppDefault — это ZIO-аналог обычного main().
//   Весь DI (dependency injection) делается через ZLayer — компилятор
//   проверяет что все зависимости подключены. Если забыл слой — не скомпилится.
//
// Архитектура (Вариант Б — два домена):
//   billing.wayrecall.com  → nginx (SPA) → /api/* → Gateway :8080 (role=admin) → бэкенды
//   app.wayrecall.com      → nginx (SPA) → /api/* → Gateway :8080 (role=user)  → бэкенды
//
//   Gateway — чистый API-прокси. Статику не раздаёт, только JSON API.
//   nginx стоит перед ним и раздаёт SPA (React/Vite).
//
// Порядок инициализации:
//   1. bootstrap — настраивает логирование (SLF4J → Logback)
//   2. run       — загружает конфиг из application.conf
//                — создаёт все сервисы (Auth, Proxy, Health)
//                — поднимает HTTP-сервер на указанном порту
//                — блокируется до SIGTERM (Ctrl+C / docker stop)
//
// Запуск:
//   cd services/API-Gateway && sbt run
//   Или в Docker: java -jar api-gateway.jar
//
// Переменные окружения (переопределяют application.conf):
//   GATEWAY_HOST, GATEWAY_PORT   — адрес/порт сервера
//   JWT_SECRET                   — ключ для подписи JWT (обязательно менять в проде!)
//   CORS_BILLING_ORIGIN          — Origin для billing SPA
//   CORS_MONITORING_ORIGIN       — Origin для monitoring SPA
//   CONNECTION_MANAGER_URL       — URL connection-manager (Block 1)
//   DEVICE_MANAGER_URL           — URL device-manager бэкенда (Block 1)
//   HISTORY_WRITER_URL           — URL history-writer (Block 1)
//   RULE_CHECKER_URL             — URL rule-checker (Block 2)
//   NOTIFICATION_SERVICE_URL     — URL notification-service (Block 2)
//   ANALYTICS_SERVICE_URL        — URL analytics-service (Block 2)
//   USER_SERVICE_URL             — URL user-service (Block 2)
//   ADMIN_SERVICE_URL            — URL admin-service (Block 2)
//   INTEGRATION_SERVICE_URL      — URL integration-service (Block 2)
//   MAINTENANCE_SERVICE_URL      — URL maintenance-service (Block 2)
//   SENSORS_SERVICE_URL          — URL sensors-service (Block 2)
//   WEBSOCKET_SERVICE_URL        — URL websocket-service (Block 3)
//   AUTH_SERVICE_URL             — URL auth-service (Block 3)
// ─────────────────────────────────────────────────────────────────────────────
object Main extends ZIOAppDefault:

  // ─── Логирование ────────────────────────────────────────────────────────
  // Подключаем SLF4J-бридж: все ZIO.logInfo/logError/logDebug
  // будут писаться через Logback (конфиг в logback.xml).
  // removeDefaultLoggers — убираем дефолтный ZIO-логгер (пишет в stdout без формата).
  // SLF4J.slf4j — подключаем Logback через SLF4J API.
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  // ─── Главный эффект ─────────────────────────────────────────────────────
  // Это for-comprehension (монадическая цепочка):
  //   - каждый <- это ZIO-эффект, который может упасть
  //   - если любой шаг падает — вся цепочка прерывается
  //   - .provide() — подключает все ZLayer-зависимости
  //
  // Тип: ZIO[ZIOAppArgs & Scope, Any, Any]
  //   - ZIOAppArgs — аргументы командной строки (не используем пока)
  //   - Scope      — управление жизненным циклом ресурсов (автозакрытие)
  //   - Any, Any   — ошибки и результат (ZIOAppDefault сам обработает)
  override val run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    (for
      // 1. Загрузить конфигурацию из application.conf
      //    Если конфиг невалидный — здесь упадём с Config.Error
      config <- ZIO.service[GatewayConfig]

      // 2. Подробный лог при старте — чтобы видеть с какими настройками поднялся
      //    Полезно для отладки: сразу видно какой порт, какие бэкенды, какой CORS
      _      <- ZIO.logInfo("╔═══════════════════════════════════════════════════════════╗")
      _      <- ZIO.logInfo("║         WAYRECALL API GATEWAY — ЗАПУСК                   ║")
      _      <- ZIO.logInfo("╚═══════════════════════════════════════════════════════════╝")
      _      <- ZIO.logInfo(s"  Сервер:           ${config.server.host}:${config.server.port}")
      _      <- ZIO.logInfo(s"  JWT issuer:       ${config.jwt.issuer}")
      _      <- ZIO.logInfo(s"  JWT TTL:          ${config.jwt.expirationHours}h")
      _      <- ZIO.logInfo(s"  CORS billing:     ${config.cors.billingOrigin}")
      _      <- ZIO.logInfo(s"  CORS monitoring:  ${config.cors.monitoringOrigin}")
      _      <- ZIO.logInfo("─────────────────── Block 1: Data Collection ──────────────")
      _      <- ZIO.logInfo(s"  Connection Manager: ${config.services.connectionManager.baseUrl} (timeout ${config.services.connectionManager.timeoutMs}ms)")
      _      <- ZIO.logInfo(s"  Device Manager:     ${config.services.deviceManager.baseUrl} (timeout ${config.services.deviceManager.timeoutMs}ms)")
      _      <- ZIO.logInfo(s"  History Writer:     ${config.services.historyWriter.baseUrl} (timeout ${config.services.historyWriter.timeoutMs}ms)")
      _      <- ZIO.logInfo("─────────────────── Block 2: Business Logic ───────────────")
      _      <- ZIO.logInfo(s"  Rule Checker:       ${config.services.ruleChecker.baseUrl} (timeout ${config.services.ruleChecker.timeoutMs}ms)")
      _      <- ZIO.logInfo(s"  Notifications:      ${config.services.notificationService.baseUrl} (timeout ${config.services.notificationService.timeoutMs}ms)")
      _      <- ZIO.logInfo(s"  Analytics:          ${config.services.analyticsService.baseUrl} (timeout ${config.services.analyticsService.timeoutMs}ms)")
      _      <- ZIO.logInfo(s"  User Service:       ${config.services.userService.baseUrl} (timeout ${config.services.userService.timeoutMs}ms)")
      _      <- ZIO.logInfo(s"  Admin Service:      ${config.services.adminService.baseUrl} (timeout ${config.services.adminService.timeoutMs}ms)")
      _      <- ZIO.logInfo(s"  Integration:        ${config.services.integrationService.baseUrl} (timeout ${config.services.integrationService.timeoutMs}ms)")
      _      <- ZIO.logInfo(s"  Maintenance:        ${config.services.maintenanceService.baseUrl} (timeout ${config.services.maintenanceService.timeoutMs}ms)")
      _      <- ZIO.logInfo(s"  Sensors:            ${config.services.sensorsService.baseUrl} (timeout ${config.services.sensorsService.timeoutMs}ms)")
      _      <- ZIO.logInfo("─────────────────── Block 3: Presentation ─────────────────")
      _      <- ZIO.logInfo(s"  WebSocket:          ${config.services.websocketService.baseUrl} (timeout ${config.services.websocketService.timeoutMs}ms)")
      _      <- ZIO.logInfo(s"  Auth Service:       ${config.services.authService.baseUrl} (timeout ${config.services.authService.timeoutMs}ms)")
      _      <- ZIO.logInfo("─────────────────── Маршруты ──────────────────────────────")
      _      <- ZIO.logInfo("  Открытые:")
      _      <- ZIO.logInfo("    POST /api/v1/auth/login         — логин")
      _      <- ZIO.logInfo("    GET  /health                    — healthcheck")
      _      <- ZIO.logInfo("    OPTIONS /*                      — CORS preflight")
      _      <- ZIO.logInfo("  Block 1 — Data Collection:")
      _      <- ZIO.logInfo("    ANY  /api/v1/devices/**         → device-manager")
      _      <- ZIO.logInfo("    ANY  /api/v1/vehicles/**        → device-manager")
      _      <- ZIO.logInfo("    ANY  /api/v1/history/**         → history-writer")
      _      <- ZIO.logInfo("  Block 2 — Business Logic:")
      _      <- ZIO.logInfo("    ANY  /api/v1/geozones/**        → rule-checker")
      _      <- ZIO.logInfo("    ANY  /api/v1/speed-rules/**     → rule-checker")
      _      <- ZIO.logInfo("    ANY  /api/v1/notifications/**   → notification-service")
      _      <- ZIO.logInfo("    ANY  /api/v1/reports/**         → analytics-service")
      _      <- ZIO.logInfo("    ANY  /api/v1/users/**           → user-service")
      _      <- ZIO.logInfo("    ANY  /api/v1/roles/**           → user-service")
      _      <- ZIO.logInfo("    ANY  /api/v1/groups/**          → user-service")
      _      <- ZIO.logInfo("    ANY  /api/v1/company/**         → user-service")
      _      <- ZIO.logInfo("    ANY  /api/v1/audit/**           → user-service")
      _      <- ZIO.logInfo("    ANY  /api/v1/admin/**           → admin-service (SuperAdmin only!)")
      _      <- ZIO.logInfo("    ANY  /api/v1/integrations/**    → integration-service")
      _      <- ZIO.logInfo("    ANY  /api/v1/maintenance/**     → maintenance-service")
      _      <- ZIO.logInfo("    ANY  /api/v1/templates/**       → maintenance-service")
      _      <- ZIO.logInfo("    ANY  /api/v1/schedules/**       → maintenance-service")
      _      <- ZIO.logInfo("    ANY  /api/v1/sensors/**         → sensors-service")
      _      <- ZIO.logInfo("    ANY  /api/v1/calibrations/**    → sensors-service")
      _      <- ZIO.logInfo("    ANY  /api/v1/events/**          → sensors-service")
      _      <- ZIO.logInfo("  Block 3 — Presentation:")
      _      <- ZIO.logInfo("    ANY  /api/v1/ws/**              → websocket-service")
      _      <- ZIO.logInfo("═══════════════════════════════════════════════════════════")
      _      <- ZIO.logInfo(s"  Всего: 24 маршрута → 13 бэкендов. Ожидаю запросы... (Ctrl+C для остановки)")

      // 3. Запустить HTTP-сервер
      //    Server.serve() — блокирующий вызов, не возвращает управление
      //    пока сервер не остановится (SIGTERM / Ctrl+C / ошибка).
      //    .toHttpApp — конвертирует Routes[Env, Response] в HttpApp[Any],
      //    что требует Server.serve(). Все зависимости уже в .provide().
      _      <- Server.serve(ApiRouter.routes.toHttpApp)
    yield ()).provide(
      // ─── Слои зависимостей (ZLayer) ───────────────────────────────────
      // Порядок не важен — ZIO сам разберёт граф зависимостей.
      // Если что-то не хватает — ошибка компиляции, не runtime!

      // Конфигурация: application.conf → GatewayConfig case class
      GatewayConfig.live,

      // HTTP-сервер: читает host/port из GatewayConfig
      serverLayer,

      // HTTP-клиент: используется ProxyService и HealthService
      // для запросов к бэкендам (device-manager, auth-service)
      Client.default,

      // AuthService: логин/валидация JWT (MVP: хардкод-юзеры)
      AuthService.live,

      // ProxyService: проксирование запросов к бэкендам через HTTP-клиент
      ProxyService.live,

      // HealthService: параллельная проверка здоровья всех бэкендов
      HealthService.live,

      // Scope: управление жизненным циклом ресурсов (HTTP-клиент, соединения)
      Scope.default
    )

  // ─── Слой HTTP-сервера ──────────────────────────────────────────────────
  // Создаёт Server из GatewayConfig:
  //   1. Читаем host/port из конфига
  //   2. Создаём Server.Config с этими параметрами
  //   3. >>> — передаём конфиг в Server.live (который поднимает Netty)
  //
  // Тип: ZLayer[GatewayConfig, Throwable, Server]
  //   - Требует GatewayConfig (откуда берём host/port)
  //   - Может упасть с Throwable (например порт занят)
  //   - Продуцирует Server (готовый к приёму запросов)
  private val serverLayer: ZLayer[GatewayConfig, Throwable, Server] =
    ZLayer.fromZIO(
      ZIO.service[GatewayConfig].map { config =>
        Server.Config.default.binding(config.server.host, config.server.port)
      }
    ) >>> Server.live
