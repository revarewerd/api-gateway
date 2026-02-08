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
 *  При добавлении нового микросервиса — добавить поле сюда + в application.conf */
final case class ServicesConfig(
  deviceManager: ServiceEndpoint,  // Device Manager API (порт 8083)
  authService:   ServiceEndpoint   // Auth Service API (порт 8092)
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
          deviceManager = ServiceEndpoint("http://localhost:8083", 30000),
          authService   = ServiceEndpoint("http://localhost:8092", 30000)
        ),
        cors = CorsConfig(
          billingOrigin    = "http://localhost:3001",
          monitoringOrigin = "http://localhost:3002"
        )
      )
    )
