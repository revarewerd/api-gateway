package com.wayrecall.gateway.service

import com.wayrecall.gateway.config.*
import com.wayrecall.gateway.domain.*
import zio.*
import zio.test.*
import zio.test.Assertion.*

import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// AuthServiceSpec — тесты сервиса аутентификации (MVP логин)
// ─────────────────────────────────────────────────────────────────────────────
// Покрывает: login (MVP: hardcoded users), validateToken
// ─────────────────────────────────────────────────────────────────────────────

object AuthServiceSpec extends ZIOSpecDefault:

  /** Тестовый слой: GatewayConfig.test → AuthService.Live */
  private val testLayer: ZLayer[Any, Nothing, AuthService & GatewayConfig] =
    GatewayConfig.test >+> AuthService.live

  def spec = suite("AuthServiceSpec")(
    loginSuite,
    validateTokenSuite
  )

  // ─── login ─────────────────────────────────────────────────────────────

  val loginSuite = suite("login")(
    test("admin@wayrecall.com / admin — успешный логин") {
      for
        response <- AuthService.login(LoginRequest("admin@wayrecall.com", "admin"))
      yield assertTrue(
        response.token.nonEmpty,
        response.user.email == "admin@wayrecall.com",
        response.user.name == "Администратор",
        response.user.roles.contains(Role.Admin),
        response.user.roles.contains(Role.User)
      )
    }.provide(testLayer),

    test("user@wayrecall.com / user — успешный логин") {
      for
        response <- AuthService.login(LoginRequest("user@wayrecall.com", "user"))
      yield assertTrue(
        response.token.nonEmpty,
        response.user.email == "user@wayrecall.com",
        response.user.name == "Оператор",
        response.user.roles.contains(Role.User),
        !response.user.roles.contains(Role.Admin)
      )
    }.provide(testLayer),

    test("неверный email — Unauthorized") {
      for
        exit <- AuthService.login(LoginRequest("unknown@example.com", "pass")).exit
      yield assertTrue(
        exit match
          case Exit.Failure(cause) =>
            cause.failureOption.exists(_.isInstanceOf[DomainError.Unauthorized])
          case _ => false
      )
    }.provide(testLayer),

    test("неверный пароль — Unauthorized") {
      for
        exit <- AuthService.login(LoginRequest("admin@wayrecall.com", "wrong")).exit
      yield assertTrue(
        exit match
          case Exit.Failure(cause) =>
            cause.failureOption.exists(_.isInstanceOf[DomainError.Unauthorized])
          case _ => false
      )
    }.provide(testLayer),

    test("expiresAt в будущем") {
      for
        response <- AuthService.login(LoginRequest("admin@wayrecall.com", "admin"))
        now      <- Clock.instant
      yield assertTrue(response.expiresAt.isAfter(now))
    }.provide(testLayer),

    test("companyId одинаковый для обоих MVP-пользователей") {
      for
        admin <- AuthService.login(LoginRequest("admin@wayrecall.com", "admin"))
        user  <- AuthService.login(LoginRequest("user@wayrecall.com", "user"))
      yield assertTrue(admin.user.companyId == user.user.companyId)
    }.provide(testLayer)
  )

  // ─── validateToken ────────────────────────────────────────────────────

  val validateTokenSuite = suite("validateToken")(
    test("валидный токен — возвращает UserContext") {
      for
        response <- AuthService.login(LoginRequest("admin@wayrecall.com", "admin"))
        ctx      <- AuthService.validateToken(response.token)
      yield assertTrue(
        ctx.email == "admin@wayrecall.com",
        ctx.roles.contains(Role.Admin)
      )
    }.provide(testLayer),

    test("невалидный токен — Unauthorized") {
      for
        exit <- AuthService.validateToken("garbage-token").exit
      yield assertTrue(exit.isFailure)
    }.provide(testLayer),

    test("roundtrip: login → validateToken → тот же userId") {
      for
        response <- AuthService.login(LoginRequest("user@wayrecall.com", "user"))
        ctx      <- AuthService.validateToken(response.token)
      yield assertTrue(
        ctx.userId == response.user.userId,
        ctx.companyId == response.user.companyId,
        ctx.email == response.user.email
      )
    }.provide(testLayer)
  )
