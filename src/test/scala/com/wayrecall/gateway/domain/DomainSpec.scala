package com.wayrecall.gateway.domain

import zio.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// DomainSpec — тесты доменных типов API Gateway
// ─────────────────────────────────────────────────────────────────────────────
// Покрывает: Opaque Types (Imei, CompanyId, UserId), Role, AppDomain,
//            UserContext, AuthResult, DomainError, ErrorResponse, HealthStatus,
//            LoginRequest/LoginResponse, UserInfo, ServiceHealth, GatewayHealthResponse
// ─────────────────────────────────────────────────────────────────────────────

object DomainSpec extends ZIOSpecDefault:

  def spec = suite("DomainSpec")(
    imeiSuite,
    companyIdSuite,
    userIdSuite,
    roleSuite,
    appDomainSuite,
    userContextSuite,
    authResultSuite,
    domainErrorSuite,
    errorResponseSuite,
    healthStatusSuite,
    dtoSuite
  )

  // ─── Opaque Types ──────────────────────────────────────────────────────

  val imeiSuite = suite("Imei")(
    test("валидный IMEI — 15 цифр") {
      val result = Imei("860719020025346")
      assertTrue(result.isRight)
    },
    test("невалидный IMEI — короткая строка") {
      val result = Imei("12345")
      assertTrue(
        result.isLeft,
        result.left.toOption.get.isInstanceOf[DomainError.ValidationFailed]
      )
    },
    test("невалидный IMEI — с буквами") {
      val result = Imei("86071902002534A")
      assertTrue(result.isLeft)
    },
    test("невалидный IMEI — пустая строка") {
      val result = Imei("")
      assertTrue(result.isLeft)
    },
    test("unsafe создаёт без проверки") {
      val imei = Imei.unsafe("any-value")
      assertTrue(imei.value == "any-value")
    },
    test("value возвращает строку") {
      val imei = Imei("860719020025346").toOption.get
      assertTrue(imei.value == "860719020025346")
    },
    test("JSON roundtrip") {
      val imei = Imei.unsafe("860719020025346")
      val json = imei.toJson
      val decoded = json.fromJson[Imei]
      assertTrue(
        json == "\"860719020025346\"",
        decoded == Right(imei)
      )
    }
  )

  val companyIdSuite = suite("CompanyId")(
    test("создание из UUID") {
      val uuid = UUID.randomUUID()
      val cid = CompanyId(uuid)
      assertTrue(cid.value == uuid)
    },
    test("fromString — валидный UUID") {
      val uuid = UUID.randomUUID()
      val result = CompanyId.fromString(uuid.toString)
      assertTrue(result == Right(CompanyId(uuid)))
    },
    test("fromString — невалидная строка") {
      val result = CompanyId.fromString("not-a-uuid")
      assertTrue(
        result.isLeft,
        result.left.toOption.get.isInstanceOf[DomainError.ValidationFailed]
      )
    },
    test("JSON roundtrip") {
      val cid = CompanyId(UUID.fromString("00000000-0000-0000-0000-000000000100"))
      val json = cid.toJson
      val decoded = json.fromJson[CompanyId]
      assertTrue(decoded == Right(cid))
    }
  )

  val userIdSuite = suite("UserId")(
    test("создание из UUID") {
      val uuid = UUID.randomUUID()
      val uid = UserId(uuid)
      assertTrue(uid.value == uuid)
    },
    test("JSON roundtrip") {
      val uid = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
      val json = uid.toJson
      val decoded = json.fromJson[UserId]
      assertTrue(decoded == Right(uid))
    }
  )

  // ─── Role ──────────────────────────────────────────────────────────────

  val roleSuite = suite("Role")(
    test("4 значения") {
      val roles = List(Role.Admin, Role.User, Role.Viewer, Role.SuperAdmin)
      assertTrue(roles.size == 4)
    },
    test("fromString — case insensitive") {
      assertTrue(
        Role.fromString("admin") == Right(Role.Admin),
        Role.fromString("Admin") == Right(Role.Admin),
        Role.fromString("ADMIN") == Right(Role.Admin)
      )
    },
    test("fromString — все значения") {
      assertTrue(
        Role.fromString("admin") == Right(Role.Admin),
        Role.fromString("user") == Right(Role.User),
        Role.fromString("viewer") == Right(Role.Viewer),
        Role.fromString("superadmin") == Right(Role.SuperAdmin)
      )
    },
    test("fromString — неизвестная роль") {
      val result = Role.fromString("unknown")
      assertTrue(
        result.isLeft,
        result.left.toOption.get.isInstanceOf[DomainError.ValidationFailed]
      )
    },
    test("JSON roundtrip") {
      val role = Role.Admin
      val json = role.toJson
      val decoded = json.fromJson[Role]
      assertTrue(decoded == Right(role))
    }
  )

  // ─── AppDomain ─────────────────────────────────────────────────────────

  val appDomainSuite = suite("AppDomain")(
    test("Billing — допустимые роли Admin и SuperAdmin") {
      val roles = AppDomain.Billing.allowedRoles
      assertTrue(
        roles.contains(Role.Admin),
        roles.contains(Role.SuperAdmin),
        !roles.contains(Role.User),
        !roles.contains(Role.Viewer)
      )
    },
    test("Monitoring — допустимые роли User, Admin и SuperAdmin") {
      val roles = AppDomain.Monitoring.allowedRoles
      assertTrue(
        roles.contains(Role.User),
        roles.contains(Role.Admin),
        roles.contains(Role.SuperAdmin),
        !roles.contains(Role.Viewer)
      )
    }
  )

  // ─── UserContext ───────────────────────────────────────────────────────

  private def makeContext(roles: Set[Role] = Set(Role.Admin, Role.User)): UserContext =
    UserContext(
      userId    = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
      companyId = CompanyId(UUID.fromString("00000000-0000-0000-0000-000000000100")),
      email     = "admin@wayrecall.com",
      roles     = roles,
      sessionId = UUID.fromString("00000000-0000-0000-0000-000000000999"),
      expiresAt = Instant.parse("2026-12-31T23:59:59Z")
    )

  val userContextSuite = suite("UserContext")(
    test("hasAccessTo — Admin к Billing") {
      val ctx = makeContext(Set(Role.Admin))
      assertTrue(ctx.hasAccessTo(AppDomain.Billing))
    },
    test("hasAccessTo — User к Monitoring") {
      val ctx = makeContext(Set(Role.User))
      assertTrue(ctx.hasAccessTo(AppDomain.Monitoring))
    },
    test("hasAccessTo — User НЕ имеет доступа к Billing") {
      val ctx = makeContext(Set(Role.User))
      assertTrue(!ctx.hasAccessTo(AppDomain.Billing))
    },
    test("hasAccessTo — Viewer не имеет доступа ни к чему") {
      val ctx = makeContext(Set(Role.Viewer))
      assertTrue(
        !ctx.hasAccessTo(AppDomain.Billing),
        !ctx.hasAccessTo(AppDomain.Monitoring)
      )
    },
    test("hasAccessTo — SuperAdmin имеет доступ ко всему") {
      val ctx = makeContext(Set(Role.SuperAdmin))
      assertTrue(
        ctx.hasAccessTo(AppDomain.Billing),
        ctx.hasAccessTo(AppDomain.Monitoring)
      )
    },
    test("hasRole — проверка наличия роли") {
      val ctx = makeContext(Set(Role.Admin, Role.User))
      assertTrue(
        ctx.hasRole(Role.Admin),
        ctx.hasRole(Role.User),
        !ctx.hasRole(Role.SuperAdmin),
        !ctx.hasRole(Role.Viewer)
      )
    },
    test("JSON roundtrip") {
      val ctx = makeContext()
      val json = ctx.toJson
      val decoded = json.fromJson[UserContext]
      assertTrue(decoded == Right(ctx))
    }
  )

  // ─── AuthResult ────────────────────────────────────────────────────────

  val authResultSuite = suite("AuthResult")(
    test("Authenticated содержит контекст") {
      val ctx = makeContext()
      val result = AuthResult.Authenticated(ctx)
      result match
        case AuthResult.Authenticated(c) => assertTrue(c.email == "admin@wayrecall.com")
        case _                           => assertTrue(false)
    },
    test("Anonymous") {
      val result: AuthResult = AuthResult.Anonymous
      result match
        case AuthResult.Anonymous => assertTrue(true)
        case _                    => assertTrue(false)
    },
    test("Failed содержит ошибку") {
      val err = DomainError.Unauthorized("Токен истёк")
      val result = AuthResult.Failed(err)
      result match
        case AuthResult.Failed(e) => assertTrue(e.message.contains("истёк"))
        case _                    => assertTrue(false)
    },
    test("exhaustive match") {
      val results: List[AuthResult] = List(
        AuthResult.Authenticated(makeContext()),
        AuthResult.Anonymous,
        AuthResult.Failed(DomainError.Unauthorized("test"))
      )
      val matched = results.map {
        case AuthResult.Authenticated(_) => "auth"
        case AuthResult.Anonymous        => "anon"
        case AuthResult.Failed(_)        => "fail"
      }
      assertTrue(matched == List("auth", "anon", "fail"))
    }
  )

  // ─── DomainError ───────────────────────────────────────────────────────

  val domainErrorSuite = suite("DomainError")(
    test("Unauthorized — statusCode 401") {
      val err = DomainError.Unauthorized("Токен истёк")
      assertTrue(
        err.statusCode == 401,
        err.message.contains("Токен истёк"),
        err.isInstanceOf[Throwable]
      )
    },
    test("Forbidden — statusCode 403, requiredRoles") {
      val err = DomainError.Forbidden("Нет доступа", Set(Role.Admin))
      assertTrue(
        err.statusCode == 403,
        err match
          case f: DomainError.Forbidden => f.requiredRoles == Set(Role.Admin)
          case _                        => false
      )
    },
    test("Forbidden — default empty requiredRoles") {
      val err = DomainError.Forbidden("Нет прав")
      assertTrue(
        err match
          case f: DomainError.Forbidden => f.requiredRoles.isEmpty
          case _                        => false
      )
    },
    test("RouteNotFound — statusCode 404") {
      val err = DomainError.RouteNotFound("/api/v1/unknown")
      assertTrue(
        err.statusCode == 404,
        err.message.contains("/api/v1/unknown")
      )
    },
    test("ValidationFailed — statusCode 422") {
      val err = DomainError.ValidationFailed("Невалидный IMEI")
      assertTrue(
        err.statusCode == 422,
        err.message.contains("Невалидный IMEI")
      )
    },
    test("RateLimitExceeded — statusCode 429, retryAfterSeconds") {
      val err = DomainError.RateLimitExceeded(60)
      assertTrue(
        err.statusCode == 429,
        err match
          case r: DomainError.RateLimitExceeded => r.retryAfterSeconds == 60
          case _                                => false,
        err.message.contains("60")
      )
    },
    test("BadGateway — statusCode 502") {
      val err = DomainError.BadGateway("device-manager", "Connection refused")
      assertTrue(
        err.statusCode == 502,
        err.message.contains("device-manager"),
        err match
          case bg: DomainError.BadGateway => bg.serviceName == "device-manager" && bg.cause == "Connection refused"
          case _                          => false
      )
    },
    test("GatewayTimeout — statusCode 504") {
      val err = DomainError.GatewayTimeout("history-writer")
      assertTrue(
        err.statusCode == 504,
        err match
          case gt: DomainError.GatewayTimeout => gt.serviceName == "history-writer"
          case _                              => false
      )
    },
    test("все 7 подтипов — exhaustive match") {
      val errors: List[DomainError] = List(
        DomainError.Unauthorized("test"),
        DomainError.Forbidden("test"),
        DomainError.RouteNotFound("/test"),
        DomainError.ValidationFailed("test"),
        DomainError.RateLimitExceeded(30),
        DomainError.BadGateway("svc", "err"),
        DomainError.GatewayTimeout("svc")
      )
      val statuses = errors.map(_.statusCode)
      assertTrue(statuses == List(401, 403, 404, 422, 429, 502, 504))
    },
    test("extends Throwable — getMessage") {
      val err: Throwable = DomainError.Unauthorized("bad token")
      assertTrue(err.getMessage.contains("bad token"))
    }
  )

  // ─── ErrorResponse ─────────────────────────────────────────────────────

  val errorResponseSuite = suite("ErrorResponse")(
    test("создание из DomainError") {
      val err = DomainError.Unauthorized("Токен истёк")
      val resp = ErrorResponse.fromDomainError(err, Some("req-123"))
      assertTrue(
        resp.message == err.message,
        resp.statusCode == 401,
        resp.requestId == Some("req-123")
      )
    },
    test("requestId по умолчанию None") {
      val err = DomainError.RouteNotFound("/test")
      val resp = ErrorResponse.fromDomainError(err)
      assertTrue(resp.requestId.isEmpty)
    },
    test("JSON encoding") {
      val resp = ErrorResponse(
        error      = "Unauthorized",
        message    = "Не авторизован",
        statusCode = 401,
        requestId  = Some("abc-123")
      )
      val json = resp.toJson
      assertTrue(
        json.contains("\"error\""),
        json.contains("\"message\""),
        json.contains("\"statusCode\""),
        json.contains("401")
      )
    }
  )

  // ─── HealthStatus ──────────────────────────────────────────────────────

  val healthStatusSuite = suite("HealthStatus")(
    test("3 значения") {
      val statuses = List(HealthStatus.Healthy, HealthStatus.Degraded, HealthStatus.Unhealthy)
      assertTrue(statuses.size == 3)
    },
    test("JSON encoding") {
      val json = HealthStatus.Healthy.toJson
      assertTrue(json.contains("Healthy"))
    }
  )

  // ─── DTO (LoginRequest, LoginResponse, UserInfo, ServiceHealth, GatewayHealthResponse) ─

  val dtoSuite = suite("DTO")(
    test("LoginRequest JSON decode") {
      val json = """{"email":"admin@wayrecall.com","password":"admin"}"""
      val decoded = json.fromJson[LoginRequest]
      assertTrue(
        decoded.isRight,
        decoded.toOption.get.email == "admin@wayrecall.com",
        decoded.toOption.get.password == "admin"
      )
    },
    test("UserInfo JSON roundtrip") {
      val info = UserInfo(
        userId    = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
        email     = "admin@wayrecall.com",
        name      = "Администратор",
        roles     = Set(Role.Admin),
        companyId = CompanyId(UUID.fromString("00000000-0000-0000-0000-000000000100"))
      )
      val json = info.toJson
      val decoded = json.fromJson[UserInfo]
      assertTrue(decoded == Right(info))
    },
    test("LoginResponse JSON encode") {
      val resp = LoginResponse(
        token     = "jwt-token-here",
        expiresAt = Instant.parse("2026-12-31T23:59:59Z"),
        user      = UserInfo(
          userId    = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
          email     = "admin@wayrecall.com",
          name      = "Администратор",
          roles     = Set(Role.Admin),
          companyId = CompanyId(UUID.fromString("00000000-0000-0000-0000-000000000100"))
        )
      )
      val json = resp.toJson
      assertTrue(
        json.contains("jwt-token-here"),
        json.contains("admin@wayrecall.com")
      )
    },
    test("ServiceHealth JSON encode") {
      val sh = ServiceHealth(
        name      = "device-manager",
        status    = HealthStatus.Healthy,
        latencyMs = 15,
        checkedAt = Instant.parse("2026-01-01T12:00:00Z")
      )
      val json = sh.toJson
      assertTrue(
        json.contains("device-manager"),
        json.contains("Healthy"),
        json.contains("15")
      )
    },
    test("GatewayHealthResponse JSON encode") {
      val resp = GatewayHealthResponse(
        status   = HealthStatus.Degraded,
        version  = "0.1.0-SNAPSHOT",
        uptime   = "2h 15m 30s",
        services = List(
          ServiceHealth("svc-1", HealthStatus.Healthy, 10, Instant.now()),
          ServiceHealth("svc-2", HealthStatus.Unhealthy, -1, Instant.now())
        )
      )
      val json = resp.toJson
      assertTrue(
        json.contains("Degraded"),
        json.contains("0.1.0-SNAPSHOT"),
        json.contains("svc-1"),
        json.contains("svc-2")
      )
    }
  )
