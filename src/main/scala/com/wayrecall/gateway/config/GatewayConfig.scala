package com.wayrecall.gateway.config

import zio.*
import zio.config.*
import zio.config.typesafe.*
import zio.config.magnolia.*

// ─────────────────────────────────────────────────────────────────────────────
// GatewayConfig.scala — Конфигурация API Gateway (иммутабельная)
// ─────────────────────────────────────────────────────────────────────────────
//
// Что это:
//   Все настройки gateway в виде иммутабельных case class.
//   Загружается из application.conf (HOCON) при старте.
//   Каждый параметр можно переопределить через env var.
//
// Иерархия:
//   GatewayConfig
//     ├── ServerConfig    — хост и порт HTTP-сервера
//     ├── JwtConfig       — секрет, issuer, TTL токена
//     ├── ServicesConfig   — URL-ы бэкенд-сервисов
//     │     ├── deviceManager  — ServiceEndpoint (baseUrl + timeout)
//     │     └── authService    — ServiceEndpoint (baseUrl + timeout)
//     └── CorsConfig      — разрешённые origins для фронтендов
//
// Загрузка:
//   application.conf → TypesafeConfigProvider → deriveConfig[GatewayConfig]
//   deriveConfig из zio-config-magnolia автоматически маппит HOCON → case class
//   через рефлексию имён полей (camelCase в конфиге = camelCase в Scala).
//
// Переопределение через env vars:
//   GATEWAY_PORT=9090          → server.port = 9090
//   JWT_SECRET=my-prod-secret  → jwt.secret = "my-prod-secret"
//   (см. application.conf для полного списка)
// ─────────────────────────────────────────────────────────────────────────────

/** Корневой конфиг — содержит все секции */
final case class GatewayConfig(
  server:   ServerConfig,    // HTTP-сервер (хост, порт)
  jwt:      JwtConfig,       // JWT (секрет, issuer, TTL)
  services: ServicesConfig,  // URL-ы бэкендов
  cors:     CorsConfig       // CORS (разрешённые origins)
)

/** HTTP-сервер — на чём слушаем.
 *  host = "0.0.0.0" → слушаем все интерфейсы (нужно для Docker)
 *  port = 8080      → стандартный порт API Gateway */
final case class ServerConfig(
  host: String,  // "0.0.0.0" — все интерфейсы
  port: Int      // 8080 — порт HTTP-сервера
)

/** JWT-верификация — параметры для создания и проверки токенов.
 *
 *  ⚠️ secret ОБЯЗАТЕЛЬНО менять в продакшне через JWT_SECRET env var!
 *  Дефолтный ключ в application.conf — только для разработки. */
final case class JwtConfig(
  secret:          String,  // HMAC-SHA256 ключ для подписи (≥32 символа в проде!)
  issuer:          String,  // "wayrecall-auth" — кто выпустил токен
  expirationHours: Int      // TTL токена в часах (24 = сутки)
)

/** URL-ы бэкенд-сервисов — куда gateway проксирует запросы.
 *  Каждый сервис — ServiceEndpoint(baseUrl, timeoutMs).
 *  При добавлении нового микросервиса — добавить поле сюда + в application.conf.
 *
 *  Порты по умолчанию (devlopment):
 *    Block 1: CM=10090, HW=10091, DM=10092
 *    Block 2: maintenance=8087, user=8091, ruleChecker=8093, notification=8094,
 *             analytics=8095, integration=8096, admin=8097, sensors=8098
 *    Block 3: websocket=8090 */
final case class ServicesConfig(
  // ─── Block 1: Data Collection ──────────────────────────────────
  connectionManager: ServiceEndpoint,   // CM: TCP-сервер GPS (порт 10090, только health/metrics)
  deviceManager:     ServiceEndpoint,   // DM: CRUD устройств (порт 10092)
  historyWriter:     ServiceEndpoint,   // HW: История GPS точек (порт 10091)
  // ─── Block 2: Business Logic ───────────────────────────────────
  ruleChecker:       ServiceEndpoint,   // Геозоны и правила скорости (порт 8093)
  notificationService: ServiceEndpoint, // Уведомления: email/SMS/push/Telegram (порт 8094)
  analyticsService:  ServiceEndpoint,   // Отчёты и экспорт (порт 8095)
  userService:       ServiceEndpoint,   // Пользователи, роли, организации (порт 8091)
  adminService:      ServiceEndpoint,   // Системное администрирование (порт 8097)
  integrationService: ServiceEndpoint,  // Ретрансляция: Wialon, webhooks (порт 8096)
  maintenanceService: ServiceEndpoint,  // Плановое ТО, пробег (порт 8087)
  sensorsService:    ServiceEndpoint,   // Датчики, калибровка (порт 8098)
  // ─── Block 3: Presentation ─────────────────────────────────────
  websocketService:  ServiceEndpoint,   // Real-time GPS через WebSocket (порт 8090)
  authService:       ServiceEndpoint    // Auth Service (порт 8092, TODO: user-service)
)

/** Единичный бэкенд-сервис — URL и таймаут.
 *  baseUrl: полный URL без trailing slash, например "http://localhost:8083"
 *  timeoutMs: максимальное время ожидания ответа в миллисекундах */
final case class ServiceEndpoint(
  baseUrl:   String,  // "http://localhost:8083" — без / в конце!
  timeoutMs: Int      // 30000 = 30 секунд
)

/** CORS — какие фронтенд-домены могут делать запросы к gateway.
 *  billingOrigin:    домен биллинг-панели (admin@wayrecall.com)
 *  monitoringOrigin: домен мониторинг-панели (user@wayrecall.com)
 *
 *  Важно: Origin должен ТОЧНО совпадать (включая порт!):
 *    "http://localhost:3001" ≠ "http://localhost:3001/"  */
final case class CorsConfig(
  billingOrigin:    String,  // "https://billing.wayrecall.com" (или "http://localhost:3001" для dev)
  monitoringOrigin: String   // "https://app.wayrecall.com" (или "http://localhost:3002" для dev)
)

object GatewayConfig:

  /** ZLayer — загрузка конфигурации из application.conf.
   *
   *  Цепочка:
   *    1. TypesafeConfigProvider.fromResourcePath() — ищет application.conf в classpath
   *    2. deriveConfig[GatewayConfig] — автоматическая генерация Config descriptor
   *       из имён полей case class (zio-config-magnolia)
   *    3. .nested("gateway") — всё под ключом "gateway" в HOCON
   *
   *  Ошибки: Config.Error — если файл не найден, ключ отсутствует или тип не совпадает.
   *  При ошибке gateway НЕ стартует — fail fast. */
  val live: ZLayer[Any, Config.Error, GatewayConfig] =
    ZLayer.fromZIO {
      val provider = TypesafeConfigProvider.fromResourcePath()
      provider.load(deriveConfig[GatewayConfig].nested("gateway"))
    }

  /** Тестовая конфигурация — для юнит-тестов.
   *
   *  ULayer = ZLayer[Any, Nothing, ...] — никогда не падает.
   *  Все значения захардкожены для localhost:
   *    - JWT secret = тестовый (НЕ использовать в проде!)
   *    - Бэкенды на localhost с дефолтными портами
   *    - CORS origins = localhost:3001/3002 */
  val test: ULayer[GatewayConfig] =
    ZLayer.succeed(
      GatewayConfig(
        server = ServerConfig("0.0.0.0", 8080),
        jwt = JwtConfig(
          secret          = "test-secret-key-for-development-only-change-in-prod",
          issuer          = "wayrecall-auth",
          expirationHours = 24
        ),
        services = ServicesConfig(
          // Block 1: Data Collection
          connectionManager  = ServiceEndpoint("http://localhost:10090", 30000),
          deviceManager      = ServiceEndpoint("http://localhost:10092", 30000),
          historyWriter      = ServiceEndpoint("http://localhost:10091", 30000),
          // Block 2: Business Logic
          ruleChecker        = ServiceEndpoint("http://localhost:8093", 30000),
          notificationService = ServiceEndpoint("http://localhost:8094", 30000),
          analyticsService   = ServiceEndpoint("http://localhost:8095", 30000),
          userService        = ServiceEndpoint("http://localhost:8091", 30000),
          adminService       = ServiceEndpoint("http://localhost:8097", 30000),
          integrationService = ServiceEndpoint("http://localhost:8096", 30000),
          maintenanceService = ServiceEndpoint("http://localhost:8087", 30000),
          sensorsService     = ServiceEndpoint("http://localhost:8098", 30000),
          // Block 3: Presentation
          websocketService   = ServiceEndpoint("http://localhost:8090", 30000),
          authService        = ServiceEndpoint("http://localhost:8092", 30000)
        ),
        cors = CorsConfig(
          billingOrigin    = "http://localhost:3001",
          monitoringOrigin = "http://localhost:3002"
        )
      )
    )
