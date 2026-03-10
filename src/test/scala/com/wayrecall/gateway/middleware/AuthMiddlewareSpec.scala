package com.wayrecall.gateway.middleware

import com.wayrecall.gateway.config.*
import com.wayrecall.gateway.domain.*
import zio.*
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// AuthMiddlewareSpec — тесты middleware аутентификации
// ─────────────────────────────────────────────────────────────────────────────
// Покрывает: extractToken (чистая), authorizeForDomain (чистая),
//            enrichHeaders (чистая), verifyToken + createToken (с GatewayConfig)
// ─────────────────────────────────────────────────────────────────────────────

object AuthMiddlewareSpec extends ZIOSpecDefault:

  /** Тестовый слой — используем GatewayConfig.test */
  private val testLayer: ULayer[GatewayConfig] = GatewayConfig.test

  private def makeContext(roles: Set[Role] = Set(Role.Admin, Role.User)): UserContext =
    UserContext(
      userId    = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
      companyId = CompanyId(UUID.fromString("00000000-0000-0000-0000-000000000100")),
      email     = "admin@wayrecall.com",
      roles     = roles,
      sessionId = UUID.fromString("00000000-0000-0000-0000-000000000999"),
      expiresAt = Instant.now().plusSeconds(3600)
    )

  def spec = suite("AuthMiddlewareSpec")(
    extractTokenSuite,
    authorizeForDomainSuite,
    enrichHeadersSuite,
    tokenRoundtripSuite
  )

  // ─── extractToken (чистая функция: Headers → Option[String]) ──────────

  val extractTokenSuite = suite("extractToken")(
    test("Bearer токен — извлекается") {
      val headers = zio.http.Headers("Authorization" -> "Bearer abc123")
      val result = AuthMiddleware.extractToken(headers)
      assertTrue(result == Some("abc123"))
    },
    test("Basic auth — None (не поддерживается)") {
      val headers = zio.http.Headers("Authorization" -> "Basic dXNlcjpwYXNz")
      val result = AuthMiddleware.extractToken(headers)
      assertTrue(result.isEmpty)
    },
    test("без Authorization заголовка — None") {
      val headers = zio.http.Headers("Content-Type" -> "application/json")
      val result = AuthMiddleware.extractToken(headers)
      assertTrue(result.isEmpty)
    },
    test("пустой Authorization — None") {
      val headers = zio.http.Headers("Authorization" -> "")
      val result = AuthMiddleware.extractToken(headers)
      assertTrue(result.isEmpty)
    },
    test("Bearer без пробела — None") {
      val headers = zio.http.Headers("Authorization" -> "Bearerabc123")
      val result = AuthMiddleware.extractToken(headers)
      assertTrue(result.isEmpty)
    }
  )

  // ─── authorizeForDomain (чистая: UserContext × AppDomain → Either) ────

  val authorizeForDomainSuite = suite("authorizeForDomain")(
    test("Admin → Billing — разрешено") {
      val ctx = makeContext(Set(Role.Admin))
      val result = AuthMiddleware.authorizeForDomain(ctx, AppDomain.Billing)
      assertTrue(result.isRight)
    },
    test("SuperAdmin → Billing — разрешено") {
      val ctx = makeContext(Set(Role.SuperAdmin))
      val result = AuthMiddleware.authorizeForDomain(ctx, AppDomain.Billing)
      assertTrue(result.isRight)
    },
    test("User → Billing — запрещено") {
      val ctx = makeContext(Set(Role.User))
      val result = AuthMiddleware.authorizeForDomain(ctx, AppDomain.Billing)
      assertTrue(
        result.isLeft,
        result.left.toOption.get.isInstanceOf[DomainError.Forbidden]
      )
    },
    test("Viewer → Billing — запрещено") {
      val ctx = makeContext(Set(Role.Viewer))
      val result = AuthMiddleware.authorizeForDomain(ctx, AppDomain.Billing)
      assertTrue(result.isLeft)
    },
    test("User → Monitoring — разрешено") {
      val ctx = makeContext(Set(Role.User))
      val result = AuthMiddleware.authorizeForDomain(ctx, AppDomain.Monitoring)
      assertTrue(result.isRight)
    },
    test("Admin → Monitoring — разрешено") {
      val ctx = makeContext(Set(Role.Admin))
      val result = AuthMiddleware.authorizeForDomain(ctx, AppDomain.Monitoring)
      assertTrue(result.isRight)
    },
    test("Viewer → Monitoring — запрещено") {
      val ctx = makeContext(Set(Role.Viewer))
      val result = AuthMiddleware.authorizeForDomain(ctx, AppDomain.Monitoring)
      assertTrue(result.isLeft)
    },
    test("множественные роли — достаточно одной") {
      val ctx = makeContext(Set(Role.User, Role.Viewer))
      val billing = AuthMiddleware.authorizeForDomain(ctx, AppDomain.Billing)
      val monitoring = AuthMiddleware.authorizeForDomain(ctx, AppDomain.Monitoring)
      assertTrue(
        billing.isLeft,       // User не даёт доступ к Billing
        monitoring.isRight    // User даёт доступ к Monitoring
      )
    }
  )

  // ─── enrichHeaders (чистая: UserContext × UUID → Headers) ──────────────

  val enrichHeadersSuite = suite("enrichHeaders")(
    test("добавляет 4 X-заголовка") {
      val ctx = makeContext(Set(Role.Admin, Role.User))
      val requestId = UUID.fromString("11111111-1111-1111-1111-111111111111")
      val headers = AuthMiddleware.enrichHeaders(ctx, requestId)

      val userId    = headers.get("X-User-Id").map(_.toString)
      val companyId = headers.get("X-Company-Id").map(_.toString)
      val roles     = headers.get("X-User-Roles").map(_.toString)
      val reqId     = headers.get("X-Request-Id").map(_.toString)

      assertTrue(
        userId.contains("00000000-0000-0000-0000-000000000001"),
        companyId.contains("00000000-0000-0000-0000-000000000100"),
        roles.isDefined,
        reqId.contains("11111111-1111-1111-1111-111111111111")
      )
    },
    test("X-User-Roles содержит все роли") {
      val ctx = makeContext(Set(Role.Admin, Role.SuperAdmin))
      val requestId = UUID.randomUUID()
      val headers = AuthMiddleware.enrichHeaders(ctx, requestId)
      val roles = headers.get("X-User-Roles").map(_.toString).getOrElse("")
      assertTrue(
        roles.contains("Admin"),
        roles.contains("SuperAdmin")
      )
    }
  )

  // ─── createToken + verifyToken (roundtrip через JWT) ──────────────────

  val tokenRoundtripSuite = suite("createToken + verifyToken")(
    test("создать и проверить токен — roundtrip") {
      val ctx = makeContext(Set(Role.Admin, Role.User))
      for
        token   <- AuthMiddleware.createToken(ctx)
        decoded <- AuthMiddleware.verifyToken(token)
      yield assertTrue(
        decoded.userId == ctx.userId,
        decoded.companyId == ctx.companyId,
        decoded.email == ctx.email,
        decoded.roles == ctx.roles,
        decoded.sessionId == ctx.sessionId
      )
    }.provide(testLayer),

    test("невалидный токен — Unauthorized") {
      for
        exit <- AuthMiddleware.verifyToken("invalid.jwt.token").exit
      yield assertTrue(
        exit.isFailure
      )
    }.provide(testLayer),

    test("пустой токен — Unauthorized") {
      for
        exit <- AuthMiddleware.verifyToken("").exit
      yield assertTrue(exit.isFailure)
    }.provide(testLayer)
  )
