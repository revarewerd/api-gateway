package com.wayrecall.gateway.service

import com.wayrecall.gateway.config.GatewayConfig
import com.wayrecall.gateway.domain.*
import com.wayrecall.gateway.middleware.AuthMiddleware
import zio.*
import zio.json.*

import java.time.Instant
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// AuthService.scala — Сервис аутентификации (логин + JWT выдача)
// ─────────────────────────────────────────────────────────────────────────────
//
// Что это:
//   Сервис который обрабатывает POST /api/v1/auth/login.
//   Принимает email + password, возвращает JWT-токен для дальнейших запросов.
//
// MVP-режим (текущий):
//   Два захардкоженных пользователя для разработки фронтенда:
//     admin@wayrecall.com / admin → роли Admin+User → доступ к billing и monitoring
//     user@wayrecall.com  / user  → роль User       → доступ только к monitoring
//
// Продакшн-режим (TODO):
//   AuthService.Live будет проксировать login-запрос к отдельному Auth Service
//   микросервису (services/auth-service), который проверяет credentials в PostgreSQL.
//   Gateway НЕ будет знать пароли — он будет только проксировать.
//
// ZIO Service Pattern:
//   trait AuthService                — интерфейс (что можно делать)
//   object AuthService              — accessors (удобный вызов)
//   case class Live(config)         — реализация (как делать)
//   val live: ZLayer[Config, ...]   — DI через ZLayer
//
// Связь с AuthMiddleware:
//   AuthService СОЗДАЁТ токены (login → createToken)
//   AuthMiddleware ПРОВЕРЯЕТ токены (каждый запрос → verifyToken)
// ─────────────────────────────────────────────────────────────────────────────

/** Trait-интерфейс AuthService.
 *  IO[DomainError, T] = ZIO[Any, DomainError, T] — не требует окружения,
 *  но может упасть с типизированной ошибкой. */
trait AuthService:

  /** Логин: email + password → JWT токен + информация о юзере.
   *  Ошибка: Unauthorized если credentials неверные. */
  def login(request: LoginRequest): IO[DomainError, LoginResponse]

  /** Проверка существующего токена → UserContext.
   *  Ошибка: Unauthorized если токен невалиден или истёк. */
  def validateToken(token: String): IO[DomainError, UserContext]

object AuthService:

  // ─── Accessors (для удобства вызова из for-comprehension) ─────────────
  // Вместо: ZIO.serviceWithZIO[AuthService](_.login(req))
  // Пишем:  AuthService.login(req)

  def login(request: LoginRequest): ZIO[AuthService, DomainError, LoginResponse] =
    ZIO.serviceWithZIO(_.login(request))

  def validateToken(token: String): ZIO[AuthService, DomainError, UserContext] =
    ZIO.serviceWithZIO(_.validateToken(token))

  // ─── Live реализация ──────────────────────────────────────────────────

  /** MVP-реализация с захардкоженными пользователями.
   *
   *  Зависимость: GatewayConfig (нужен jwt.secret для подписи токена
   *  и jwt.expirationHours для срока действия).
   *
   *  В продакшне заменить на: Live(config, httpClient) — проксирование
   *  к Auth Service микросервису через HTTP. */
  final case class Live(config: GatewayConfig) extends AuthService:

    override def login(request: LoginRequest): IO[DomainError, LoginResponse] =
      for
        _      <- ZIO.logInfo(s"[AUTH-SVC] Попытка логина: email=${request.email}")

        // Шаг 1: Проверить credentials (MVP: хардкод, прод: HTTP к Auth Service)
        user   <- authenticateUser(request.email, request.password)
        _      <- ZIO.logDebug(s"[AUTH-SVC] Пользователь найден: ${user.name}, roles=${user.roles.mkString(",")}")

        // Шаг 2: Рассчитать время жизни токена
        now    <- Clock.instant
        expiry  = now.plusSeconds(config.jwt.expirationHours.toLong * 3600)

        // Шаг 3: Собрать UserContext (вся информация для JWT payload)
        ctx     = UserContext(
                    userId    = user.userId,
                    companyId = user.companyId,
                    email     = user.email,
                    roles     = user.roles,
                    sessionId = UUID.randomUUID(),  // Уникальный ID сессии для трейсинга
                    expiresAt = expiry
                  )

        // Шаг 4: Создать JWT-токен через AuthMiddleware
        // provide(ZLayer.succeed(config)) — передаём конфиг для HMAC-подписи
        token  <- AuthMiddleware.createToken(ctx).provide(ZLayer.succeed(config))
        _      <- ZIO.logInfo(s"[AUTH-SVC] Токен создан для ${user.email}, истекает: $expiry (через ${config.jwt.expirationHours}ч)")
      yield LoginResponse(
        token     = token,
        expiresAt = expiry,
        user      = user
      )

    override def validateToken(token: String): IO[DomainError, UserContext] =
      ZIO.logDebug("[AUTH-SVC] Валидация токена...") *>
      AuthMiddleware
        .verifyToken(token)
        .provide(ZLayer.succeed(config))

    /** MVP: захардкоженные пользователи для разработки.
     *
     *  Два тестовых аккаунта:
     *    1. admin@wayrecall.com / admin → Admin+User → полный доступ
     *    2. user@wayrecall.com  / user  → User       → только мониторинг
     *
     *  Оба в одной companyId (multi-tenant: в проде у каждой компании свой UUID).
     *
     *  TODO: Заменить на HTTP-вызов к Auth Service:
     *    client.request(Request.post("/api/v1/internal/auth/verify", body))
     */
    private def authenticateUser(email: String, password: String): IO[DomainError, UserInfo] =
      val users = Map(
        // Админ биллинга — полный доступ к billing.wayrecall.com и app.wayrecall.com
        ("admin@wayrecall.com", "admin") -> UserInfo(
          userId    = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
          email     = "admin@wayrecall.com",
          name      = "Администратор",
          roles     = Set(Role.Admin, Role.User),
          companyId = CompanyId(UUID.fromString("00000000-0000-0000-0000-000000000100"))
        ),
        // Оператор мониторинга — доступ только к app.wayrecall.com
        ("user@wayrecall.com", "user") -> UserInfo(
          userId    = UserId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
          email     = "user@wayrecall.com",
          name      = "Оператор",
          roles     = Set(Role.User),
          companyId = CompanyId(UUID.fromString("00000000-0000-0000-0000-000000000100"))
        )
      )

      ZIO.fromOption(users.get((email, password)))
        .mapError(_ => DomainError.Unauthorized("Неверный email или пароль"))
        .tapError(_ => ZIO.logDebug(s"[AUTH-SVC] Неудачная попытка логина: email=$email"))

  // ─── ZLayer ───────────────────────────────────────────────────────────
  // AuthService.Live зависит только от GatewayConfig (для JWT secret).
  // ZLayer.fromFunction автоматически оборачивает конструктор Live(config).

  val live: ZLayer[GatewayConfig, Nothing, AuthService] =
    ZLayer.fromFunction(Live(_))
