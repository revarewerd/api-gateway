package com.wayrecall.gateway.domain

import zio.json.*
import java.time.Instant
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// Models.scala — Все доменные типы API Gateway
// ─────────────────────────────────────────────────────────────────────────────
//
// Что здесь:
//   Opaque types, enum-ы, case class-ы, sealed error hierarchy.
//   ВСЕ доменные типы в ОДНОМ файле — удобно для навигации.
//
// Принципы:
//   1. Opaque Types — компилятор различает UserId и CompanyId,
//      хотя оба UUID. Невозможно случайно перепутать.
//   2. Sealed ADT — enum Role, AppDomain, DomainError, AuthResult.
//      Компилятор гарантирует exhaustive pattern matching.
//   3. Derives — автогенерация JsonEncoder/JsonDecoder через zio-json.
//   4. Typed Errors — DomainError хранит statusCode + message,
//      никаких throw/catch, всё через ZIO.fail.
//
// Слои использования:
//   - AuthMiddleware: UserContext, AuthResult, Role, AppDomain
//   - AuthService: LoginRequest, LoginResponse, UserInfo
//   - ProxyService: DomainError (BadGateway, GatewayTimeout)
//   - HealthService: HealthStatus, ServiceHealth, GatewayHealthResponse
//   - ApiRouter: ErrorResponse (JSON для клиента)
// ─────────────────────────────────────────────────────────────────────────────

// ═══════════════════════════════════════════════════════════════════════════
// Opaque Types — типобезопасные обёртки над примитивами
// ═══════════════════════════════════════════════════════════════════════════
//
// Зачем:
//   def getDevice(userId: UUID, companyId: UUID) — легко перепутать аргументы!
//   def getDevice(userId: UserId, companyId: CompanyId) — компилятор не даст.
//
// Как работает:
//   opaque type UserId = UUID — в рантайме это обычный UUID (zero-cost!),
//   но компилятор видит UserId как отдельный тип.
//   Создать можно только через companion object.

/** IMEI трекера — 15 цифр (International Mobile Equipment Identity).
 *  Уникальный ID каждого GPS-трекера. Записан в SIM-карту устройства.
 *  Пример: "860719020025346" */
opaque type Imei = String
object Imei:
  /** Безопасное создание: проверяет формат (ровно 15 цифр) */
  def apply(value: String): Either[DomainError, Imei] =
    if value.matches("^\\d{15}$") then Right(value)
    else Left(DomainError.ValidationFailed(s"Невалидный IMEI: $value"))

  /** Небезопасное создание: без проверки (только для тестов!) */
  def unsafe(value: String): Imei = value

  given JsonEncoder[Imei] = JsonEncoder.string
  given JsonDecoder[Imei] = JsonDecoder.string

  extension (imei: Imei) def value: String = imei

/** ID организации — ключ multi-tenant изоляции.
 *  КАЖДЫЙ запрос к бэкенду должен содержать CompanyId!
 *  Без него — утечка данных между организациями. */
opaque type CompanyId = UUID
object CompanyId:
  def apply(value: UUID): CompanyId = value
  def fromString(s: String): Either[DomainError, CompanyId] =
    try Right(UUID.fromString(s))
    catch case _: IllegalArgumentException =>
      Left(DomainError.ValidationFailed(s"Невалидный CompanyId: $s"))

  given JsonEncoder[CompanyId] = JsonEncoder.uuid
  given JsonDecoder[CompanyId] = JsonDecoder.uuid

  extension (id: CompanyId) def value: UUID = id

/** ID пользователя — уникальный UUID из Auth Service */
opaque type UserId = UUID
object UserId:
  def apply(value: UUID): UserId = value

  given JsonEncoder[UserId] = JsonEncoder.uuid
  given JsonDecoder[UserId] = JsonDecoder.uuid

  extension (id: UserId) def value: UUID = id

// ═══════════════════════════════════════════════════════════════════════════
// Роли и домены — sealed ADT (Algebraic Data Type)
// ═══════════════════════════════════════════════════════════════════════════
//
// ADT = конечное множество вариантов. Компилятор проверяет что
// pattern match покрывает ВСЕ варианты (exhaustive check).

/** Роль пользователя в системе.
 *
 *  Admin      — администратор: доступ к биллингу + мониторингу
 *  User       — оператор: доступ к мониторингу
 *  Viewer     — наблюдатель: только чтение (для клиентов)
 *  SuperAdmin — техподдержка Wayrecall: полный доступ ко всему */
enum Role derives JsonEncoder, JsonDecoder:
  case Admin
  case User
  case Viewer
  case SuperAdmin

object Role:
  /** Парсинг строки → Role. Используется при декодировании JWT payload.
   *  Case-insensitive: "admin", "Admin", "ADMIN" → Role.Admin */
  def fromString(s: String): Either[DomainError, Role] = s.toLowerCase match
    case "admin"       => Right(Role.Admin)
    case "user"        => Right(Role.User)
    case "viewer"      => Right(Role.Viewer)
    case "superadmin"  => Right(Role.SuperAdmin)
    case other         => Left(DomainError.ValidationFailed(s"Неизвестная роль: $other"))

/** Домен приложения — определяет с какого фронтенда пришёл запрос.
 *
 *  Gateway обслуживает ДВА фронтенда:
 *    Billing    — billing.wayrecall.com — управление тарифами, счетами, устройствами
 *    Monitoring — app.wayrecall.com     — карта, трекинг, геозоны, отчёты
 *
 *  Каждый домен требует определённые роли (allowedRoles).
 *  Авторизация: пересечение ролей пользователя и allowedRoles домена. */
enum AppDomain:
  case Billing
  case Monitoring

  /** Роли, которым разрешён доступ к этому домену */
  def allowedRoles: Set[Role] = this match
    case Billing    => Set(Role.Admin, Role.SuperAdmin)
    case Monitoring => Set(Role.User, Role.Admin, Role.SuperAdmin)

// ═══════════════════════════════════════════════════════════════════════════
// Контекст аутентификации — результат проверки JWT
// ═══════════════════════════════════════════════════════════════════════════

/** Контекст пользователя — извлекается из JWT после верификации.
 *
 *  Содержит ВСЮ информацию для авторизации и аудита:
 *    - Кто: userId, email
 *    - Откуда: companyId (multi-tenant изоляция)
 *    - Что может: roles (Admin, User...)
 *    - Сессия: sessionId (для трейсинга)
 *    - Когда истекает: expiresAt
 *
 *  Прокидывается бэкендам через X-заголовки (AuthMiddleware.enrichHeaders). */
final case class UserContext(
  userId:    UserId,
  companyId: CompanyId,
  email:     String,
  roles:     Set[Role],
  sessionId: UUID,
  expiresAt: Instant
) derives JsonEncoder, JsonDecoder:

  /** Проверка: может ли пользователь работать с этим доменом.
   *  Есть хотя бы одна общая роль? */
  def hasAccessTo(domain: AppDomain): Boolean =
    roles.exists(domain.allowedRoles.contains)

  /** Проверка: есть ли конкретная роль */
  def hasRole(role: Role): Boolean = roles.contains(role)

/** Результат аутентификации — сумм-тип (один из трёх вариантов).
 *
 *  Authenticated — JWT валиден, вот контекст
 *  Anonymous     — токена нет (для открытых endpoints: login, health)
 *  Failed        — токен есть но невалиден (истёк, подпись не совпадает) */
enum AuthResult:
  case Authenticated(context: UserContext)
  case Anonymous
  case Failed(error: DomainError)

// ═══════════════════════════════════════════════════════════════════════════
// DTO аутентификации — запросы и ответы
// ═══════════════════════════════════════════════════════════════════════════

/** Запрос на логин (POST /api/v1/auth/login).
 *  derives JsonDecoder — автогенерация парсера из JSON body.
 *  Пример: {"email":"admin@wayrecall.com","password":"admin"} */
final case class LoginRequest(
  email:    String,
  password: String
) derives JsonDecoder

/** Ответ после успешного логина.
 *  Клиент сохраняет token и шлёт его в Authorization: Bearer <token>.
 *  expiresAt — чтобы клиент знал когда запросить новый токен. */
final case class LoginResponse(
  token:     String,
  expiresAt: Instant,
  user:      UserInfo
) derives JsonEncoder

/** Публичная информация о пользователе (без пароля!).
 *  Возвращается клиенту после логина и в /me endpoint. */
final case class UserInfo(
  userId:    UserId,
  email:     String,
  name:      String,
  roles:     Set[Role],
  companyId: CompanyId
) derives JsonEncoder, JsonDecoder

// ═══════════════════════════════════════════════════════════════════════════
// DomainError — типизированная иерархия ошибок
// ═══════════════════════════════════════════════════════════════════════════
//
// Зачем:
//   - Каждая ошибка имеет HTTP statusCode → автоматический маппинг в Response
//   - Sealed enum → компилятор проверяет что ВСЕ ошибки обработаны
//   - extends Throwable → совместимость с ZIO.fail
//   - message → человекочитаемое описание для клиента
//
// Принцип: НИКОГДА не бросаем исключения через throw!
//   Всегда: ZIO.fail(DomainError.Unauthorized("причина"))

/** Все ошибки gateway — типизированные, exhaustive matching */
enum DomainError(val message: String, val statusCode: Int) extends Throwable(message):

  /** 401 — токен невалидный, отсутствует или истёк */
  case Unauthorized(reason: String)
    extends DomainError(s"Не аутентифицирован: $reason", 401)

  /** 403 — роли пользователя не позволяют доступ к ресурсу/домену */
  case Forbidden(reason: String, requiredRoles: Set[Role] = Set.empty)
    extends DomainError(s"Доступ запрещён: $reason", 403)

  /** 404 — запрошенный маршрут не найден в routing table */
  case RouteNotFound(path: String)
    extends DomainError(s"Маршрут не найден: $path", 404)

  /** 422 — ошибка валидации входных данных (IMEI формат, обязательные поля) */
  case ValidationFailed(reason: String)
    extends DomainError(s"Ошибка валидации: $reason", 422)

  /** 429 — rate limit превышен (защита от DDoS/перегрузки) */
  case RateLimitExceeded(retryAfterSeconds: Int)
    extends DomainError(s"Превышен лимит запросов, повторите через ${retryAfterSeconds}с", 429)

  /** 502 — бэкенд-сервис не доступен или вернул ошибку */
  case BadGateway(serviceName: String, cause: String)
    extends DomainError(s"Сервис $serviceName недоступен: $cause", 502)

  /** 504 — бэкенд-сервис не ответил в пределах таймаута */
  case GatewayTimeout(serviceName: String)
    extends DomainError(s"Таймаут ожидания ответа от $serviceName", 504)

/** JSON-представление ошибки для клиента.
 *
 *  Пример ответа:
 *    HTTP/1.1 401 Unauthorized
 *    {"error":"Unauthorized","message":"Не аутентифицирован: Токен истёк","statusCode":401,"requestId":"uuid"} */
final case class ErrorResponse(
  error:      String,           // Тип ошибки (имя case class)
  message:    String,           // Человекочитаемое описание
  statusCode: Int,              // HTTP status code
  requestId:  Option[String] = None  // UUID запроса (для трейсинга)
) derives JsonEncoder

object ErrorResponse:
  /** Конвертация DomainError → ErrorResponse для HTTP-ответа */
  def fromDomainError(err: DomainError, requestId: Option[String] = None): ErrorResponse =
    ErrorResponse(
      error      = err.getClass.getSimpleName.stripSuffix("$"),
      message    = err.message,
      statusCode = err.statusCode,
      requestId  = requestId
    )

// ═══════════════════════════════════════════════════════════════════════════
// Health Check — типы для /api/v1/health endpoint
// ═══════════════════════════════════════════════════════════════════════════

/** Статус здоровья сервиса.
 *    Healthy   — всё работает
 *    Degraded  — частично работает (некоторые бэкенды лежат)
 *    Unhealthy — полностью неработоспособен */
enum HealthStatus derives JsonEncoder:
  case Healthy
  case Degraded
  case Unhealthy

/** Здоровье одного бэкенд-сервиса */
final case class ServiceHealth(
  name:      String,        // "device-manager"
  status:    HealthStatus,  // Healthy/Degraded/Unhealthy
  latencyMs: Long,          // Время ответа в ms (-1 = не ответил)
  checkedAt: Instant        // Когда проверяли
) derives JsonEncoder

/** Общий ответ health check — агрегация всех бэкендов.
 *
 *  Пример:
 *    {
 *      "status": "Degraded",
 *      "version": "0.1.0-SNAPSHOT",
 *      "uptime": "2h 15m 30s",
 *      "services": [
 *        {"name":"device-manager","status":"Healthy","latencyMs":12},
 *        {"name":"auth-service","status":"Unhealthy","latencyMs":-1}
 *      ]
 *    } */
final case class GatewayHealthResponse(
  status:   HealthStatus,       // Агрегированный статус
  version:  String,              // Версия gateway
  uptime:   String,              // "2h 15m 30s"
  services: List[ServiceHealth]  // Статус каждого бэкенда
) derives JsonEncoder
