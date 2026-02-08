package com.wayrecall.gateway.middleware

import com.wayrecall.gateway.config.GatewayConfig
import com.wayrecall.gateway.domain.*
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtZIOJson}
import zio.*
import zio.http.*
import zio.json.*

import java.time.{Clock, Instant}
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// AuthMiddleware.scala — Аутентификация и авторизация через JWT
// ─────────────────────────────────────────────────────────────────────────────
//
// Что это:
//   Middleware который проверяет JWT-токен в каждом защищённом запросе.
//   Извлекает UserContext (кто пользователь, какие роли, какая компания)
//   и прокидывает его бэкендам через X-заголовки.
//
// Как работает JWT:
//   1. Клиент логинится через POST /api/v1/auth/login → получает JWT-токен
//   2. При каждом запросе шлёт: Authorization: Bearer <token>
//   3. Gateway проверяет подпись (HMAC-SHA256) и срок действия
//   4. Извлекает payload: userId, companyId, email, roles, sessionId
//   5. Проверяет что роли позволяют работать с данным доменом
//   6. Прокидывает бэкенду: X-User-Id, X-Company-Id, X-User-Roles
//
// Формат JWT payload:
//   {
//     "sub": "uuid-user-id",      // userId
//     "cid": "uuid-company-id",   // companyId (multi-tenant изоляция!)
//     "email": "admin@wayrecall.com",
//     "roles": ["Admin", "User"], // роли пользователя
//     "sid": "uuid-session-id",   // ID сессии для трейсинга
//     "exp": 1707400000           // Unix timestamp истечения
//   }
//
// Алгоритм подписи: HMAC-SHA256 (симметричный ключ из конфига jwt.secret)
//   В проде ключ ОБЯЗАТЕЛЬНО менять через JWT_SECRET env var!
//
// Типы ошибок:
//   DomainError.Unauthorized — токен отсутствует, невалидный или истёк
//   DomainError.Forbidden    — роли не позволяют доступ к домену
// ─────────────────────────────────────────────────────────────────────────────
object AuthMiddleware:

  // ─── Константы заголовков ──────────────────────────────────────────────

  /** Заголовок с JWT-токеном от клиента */
  private val AuthHeader   = "Authorization"

  /** Префикс Bearer — стандарт RFC 6750 */
  private val BearerPrefix = "Bearer "

  /** Заголовки которые gateway прокидывает бэкендам.
   *  Бэкенд НЕ парсит JWT сам — доверяет этим заголовкам от gateway. */
  val UserIdHeader    = "X-User-Id"       // UUID пользователя
  val CompanyIdHeader = "X-Company-Id"    // UUID организации (multi-tenant!)
  val UserRolesHeader = "X-User-Roles"    // "Admin,User" — через запятую
  val RequestIdHeader = "X-Request-Id"    // UUID запроса для трейсинга

  // ─── Публичный API ────────────────────────────────────────────────────

  /** Извлечь JWT-токен из заголовка Authorization.
   *
   *  Чистая функция (не ZIO!): Headers → Option[String].
   *  Ищет заголовок "Authorization: Bearer <token>" и возвращает <token>.
   *  Если заголовка нет или он без "Bearer " — возвращает None.
   *
   *  Пример:
   *    "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..." → Some("eyJhbGciOiJIUzI1NiJ9...")
   *    "Authorization: Basic dXNlcjpwYXNz"             → None (не Bearer)
   *    (заголовка нет)                                  → None
   */
  def extractToken(headers: Headers): Option[String] =
    headers.get(AuthHeader).map(_.toString).collect {
      case value if value.startsWith(BearerPrefix) =>
        value.drop(BearerPrefix.length).trim
    }

  /** Верифицировать JWT и декодировать UserContext.
   *
   *  Pipeline:
   *    1. decodeJwt    — проверить подпись HMAC-SHA256
   *    2. parseContext  — распарсить JSON payload → UserContext
   *    3. validateExpiry — проверить что токен не истёк
   *
   *  Если любой шаг падает — DomainError.Unauthorized с подробным сообщением.
   */
  def verifyToken(token: String): ZIO[GatewayConfig, DomainError, UserContext] =
    for
      config <- ZIO.service[GatewayConfig]
      _      <- ZIO.logDebug("[AUTH] Верификация JWT-токена...")
      claim  <- decodeJwt(token, config.jwt.secret)
      ctx    <- parseUserContext(claim)
      _      <- validateExpiry(ctx.expiresAt)
      _      <- ZIO.logDebug(s"[AUTH] JWT валиден: user=${ctx.email}, roles=${ctx.roles.mkString(",")}, expires=${ctx.expiresAt}")
    yield ctx

  /** Полный pipeline аутентификации: извлечь токен → проверить → AuthResult.
   *
   *  Никогда не бросает исключений! Все ошибки в типе AuthResult:
   *    - Authenticated(ctx) — токен валиден, вот контекст
   *    - Anonymous          — токена нет (для открытых endpoints это ок)
   *    - Failed(err)        — токен есть но невалиден
   */
  def authenticate(request: Request): ZIO[GatewayConfig, Nothing, AuthResult] =
    extractToken(request.headers) match
      case None =>
        ZIO.logDebug("[AUTH] Токен не найден → Anonymous") *>
        ZIO.succeed(AuthResult.Anonymous)
      case Some(token) =>
        verifyToken(token)
          .map(AuthResult.Authenticated(_))
          .catchAll { err =>
            ZIO.logDebug(s"[AUTH] Токен невалиден: ${err.message}") *>
            ZIO.succeed(AuthResult.Failed(err))
          }

  /** Определить AppDomain по Origin или Host заголовку.
   *
   *  Gateway обслуживает два домена:
   *    billing.wayrecall.com  → AppDomain.Billing    (нужна роль Admin)
   *    app.wayrecall.com      → AppDomain.Monitoring  (нужна роль User)
   *
   *  Браузер шлёт заголовок Origin с URL фронтенда.
   *  Gateway сравнивает с конфигом и определяет домен.
   *  Если Origin не матчит ни один — None (используется дефолт Monitoring).
   */
  def resolveAppDomain(request: Request): ZIO[GatewayConfig, Nothing, Option[AppDomain]] =
    for
      config <- ZIO.service[GatewayConfig]
      origin  = request.headers.get("Origin").map(_.toString).orElse(
                  request.headers.get("Host").map(_.toString)
                )
      domain  = origin.flatMap { o =>
                  if o.contains(extractDomain(config.cors.billingOrigin)) then Some(AppDomain.Billing)
                  else if o.contains(extractDomain(config.cors.monitoringOrigin)) then Some(AppDomain.Monitoring)
                  else None
                }
      _      <- ZIO.logDebug(s"[AUTH] Origin: ${origin.getOrElse("нет")} → домен: ${domain.getOrElse("не определён")}")
    yield domain

  /** Проверка авторизации: пользователь с данными ролями может работать с доменом?
   *
   *  Чистая функция (не ZIO!): UserContext × AppDomain → Either[Error, Unit].
   *  Проверяет пересечение ролей пользователя и допустимых ролей домена.
   *
   *  Пример:
   *    Admin пользователь + Billing домен   → Right(())  ✓
   *    User пользователь + Billing домен    → Left(Forbidden)  ✗
   *    User пользователь + Monitoring домен → Right(())  ✓
   */
  def authorizeForDomain(context: UserContext, domain: AppDomain): Either[DomainError, Unit] =
    if context.hasAccessTo(domain) then Right(())
    else Left(DomainError.Forbidden(
      s"Роли ${context.roles.mkString(",")} не имеют доступа к ${domain}",
      domain.allowedRoles
    ))

  /** Создать заголовки для проксирования к бэкенду.
   *
   *  Бэкенд получает идентификацию пользователя через X-заголовки:
   *    X-User-Id:     UUID пользователя
   *    X-Company-Id:  UUID организации (multi-tenant фильтрация!)
   *    X-User-Roles:  "Admin,User" — через запятую
   *    X-Request-Id:  UUID запроса для трейсинга по всем сервисам
   *
   *  Бэкенду НЕ нужно парсить JWT — он доверяет gateway.
   */
  def enrichHeaders(context: UserContext, requestId: UUID): Headers =
    Headers(
      Header.Custom(UserIdHeader, context.userId.value.toString),
      Header.Custom(CompanyIdHeader, context.companyId.value.toString),
      Header.Custom(UserRolesHeader, context.roles.map(_.toString).mkString(",")),
      Header.Custom(RequestIdHeader, requestId.toString)
    )

  /** Создать JWT-токен из UserContext.
   *
   *  Используется AuthService при логине для выдачи токена.
   *  Также полезен в тестах для создания тестовых токенов.
   *
   *  Формат: JwtPayload → JSON → HMAC-SHA256 подпись → JWT строка
   */
  def createToken(context: UserContext): ZIO[GatewayConfig, Nothing, String] =
    for
      config <- ZIO.service[GatewayConfig]
      payload = JwtPayload(
        sub   = context.userId.value.toString,
        cid   = context.companyId.value.toString,
        email = context.email,
        roles = context.roles.map(_.toString).toList,
        sid   = context.sessionId.toString,
        exp   = context.expiresAt.getEpochSecond
      ).toJson
      token   = JwtZIOJson.encode(payload, config.jwt.secret, JwtAlgorithm.HS256)
      _      <- ZIO.logDebug(s"[AUTH] Создан JWT для ${context.email}, expires=${context.expiresAt}")
    yield token

  // ─── Приватные хелперы ────────────────────────────────────────────────

  /** Декодирование JWT — проверяет подпись HMAC-SHA256.
   *  Если подпись не совпадает или формат невалиден → Unauthorized. */
  private def decodeJwt(token: String, secret: String): IO[DomainError, JwtClaim] =
    ZIO
      .fromTry(JwtZIOJson.decode(token, secret, Seq(JwtAlgorithm.HS256)))
      .mapError(err => DomainError.Unauthorized(s"Невалидный JWT: ${err.getMessage}"))

  /** Парсинг JSON из JWT claim → структурированный JwtPayload → UserContext */
  private def parseUserContext(claim: JwtClaim): IO[DomainError, UserContext] =
    ZIO
      .fromEither(claim.content.fromJson[JwtPayload])
      .mapError(err => DomainError.Unauthorized(s"Невалидная структура JWT: $err"))
      .flatMap(payload => payloadToContext(payload))

  /** Маппинг JwtPayload → UserContext с валидацией каждой роли.
   *  Если в JWT есть неизвестная роль — ошибка (безопасность!) */
  private def payloadToContext(payload: JwtPayload): IO[DomainError, UserContext] =
    for
      roles <- ZIO.foreach(payload.roles)(r =>
                 ZIO.fromEither(Role.fromString(r))
               )
    yield UserContext(
      userId    = UserId(UUID.fromString(payload.sub)),
      companyId = CompanyId(UUID.fromString(payload.cid)),
      email     = payload.email,
      roles     = roles.toSet,
      sessionId = UUID.fromString(payload.sid),
      expiresAt = Instant.ofEpochSecond(payload.exp)
    )

  /** Проверка не протух ли токен. Сравниваем exp с текущим временем. */
  private def validateExpiry(expiresAt: Instant): IO[DomainError, Unit] =
    ZIO.clockWith(_.instant).flatMap { now =>
      if now.isBefore(expiresAt) then ZIO.unit
      else ZIO.fail(DomainError.Unauthorized("Токен истёк"))
    }

  /** Извлечь домен из полного URL: "http://localhost:3001" → "localhost:3001" */
  private def extractDomain(url: String): String =
    url.replaceAll("^https?://", "").stripSuffix("/")

// ─── JWT Payload — внутренний формат токена ──────────────────────────────
// Это НЕ public API! Используется только внутри AuthMiddleware.
// Поля совпадают с тем что записано в JWT claim при создании токена.
// private — не видно снаружи пакета middleware.
private final case class JwtPayload(
  sub:   String,       // userId (UUID строкой)
  cid:   String,       // companyId (UUID строкой)
  email: String,       // email пользователя
  roles: List[String], // ["Admin", "User"] — роли строками
  sid:   String,       // sessionId (UUID строкой)
  exp:   Long          // Unix timestamp истечения токена
) derives JsonEncoder, JsonDecoder
