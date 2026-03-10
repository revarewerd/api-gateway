package com.wayrecall.gateway.middleware

import zio.*
import zio.http.*
import zio.test.*
import zio.test.Assertion.*

import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// LogMiddlewareSpec — тесты логирующего middleware
// ─────────────────────────────────────────────────────────────────────────────
// Покрывает: RequestLog.formatted, extractClientIp, generateRequestId,
//            logRequest (уровни), timed
// ─────────────────────────────────────────────────────────────────────────────

object LogMiddlewareSpec extends ZIOSpecDefault:

  private val testRequestId = UUID.fromString("11111111-1111-1111-1111-111111111111")

  def spec = suite("LogMiddlewareSpec")(
    requestLogSuite,
    extractClientIpSuite,
    generateRequestIdSuite,
    timedSuite
  )

  // ─── RequestLog.formatted ──────────────────────────────────────────────

  val requestLogSuite = suite("RequestLog.formatted")(
    test("формат с userId и IP") {
      val log = LogMiddleware.RequestLog(
        requestId  = testRequestId,
        method     = "GET",
        path       = "/api/v1/devices",
        origin     = Some("http://localhost:3001"),
        userAgent  = Some("Mozilla/5.0"),
        clientIp   = Some("192.168.1.1"),
        statusCode = 200,
        durationMs = 15,
        userId     = Some("user-uuid-123")
      )
      val formatted = log.formatted
      assertTrue(
        formatted.contains(testRequestId.toString),
        formatted.contains("GET"),
        formatted.contains("/api/v1/devices"),
        formatted.contains("200"),
        formatted.contains("15ms"),
        formatted.contains("user=user-uuid-123"),
        formatted.contains("ip=192.168.1.1")
      )
    },
    test("формат без userId — anonymous") {
      val log = LogMiddleware.RequestLog(
        requestId  = testRequestId,
        method     = "POST",
        path       = "/api/v1/auth/login",
        origin     = None,
        userAgent  = None,
        clientIp   = None,
        statusCode = 200,
        durationMs = 50,
        userId     = None
      )
      val formatted = log.formatted
      assertTrue(
        formatted.contains("user=anonymous"),
        formatted.contains("ip=unknown")
      )
    },
    test("формат с 500 статусом") {
      val log = LogMiddleware.RequestLog(
        requestId  = testRequestId,
        method     = "GET",
        path       = "/api/v1/health",
        origin     = None,
        userAgent  = None,
        clientIp   = Some("10.0.0.1"),
        statusCode = 500,
        durationMs = 5000,
        userId     = None
      )
      assertTrue(
        log.formatted.contains("500"),
        log.formatted.contains("5000ms")
      )
    }
  )

  // ─── extractClientIp ──────────────────────────────────────────────────

  val extractClientIpSuite = suite("extractClientIp")(
    test("X-Forwarded-For — первый IP") {
      val headers = Headers("X-Forwarded-For" -> "192.168.1.1, 10.0.0.1, 172.16.0.1")
      val ip = LogMiddleware.extractClientIp(headers)
      assertTrue(ip == Some("192.168.1.1"))
    },
    test("X-Real-IP — как fallback") {
      val headers = Headers("X-Real-IP" -> "10.0.0.5")
      val ip = LogMiddleware.extractClientIp(headers)
      assertTrue(ip == Some("10.0.0.5"))
    },
    test("Remote-Address — как последний fallback") {
      val headers = Headers("Remote-Address" -> "127.0.0.1")
      val ip = LogMiddleware.extractClientIp(headers)
      assertTrue(ip == Some("127.0.0.1"))
    },
    test("X-Forwarded-For приоритетнее X-Real-IP") {
      val headers = Headers("X-Forwarded-For", "192.168.1.1") ++
        Headers("X-Real-IP", "10.0.0.5")
      val ip = LogMiddleware.extractClientIp(headers)
      assertTrue(ip == Some("192.168.1.1"))
    },
    test("без заголовков — None") {
      val headers = Headers("Content-Type" -> "application/json")
      val ip = LogMiddleware.extractClientIp(headers)
      assertTrue(ip.isEmpty)
    }
  )

  // ─── generateRequestId ────────────────────────────────────────────────

  val generateRequestIdSuite = suite("generateRequestId")(
    test("генерирует уникальный UUID") {
      for
        id1 <- LogMiddleware.generateRequestId
        id2 <- LogMiddleware.generateRequestId
      yield assertTrue(id1 != id2)
    }
  )

  // ─── timed ────────────────────────────────────────────────────────────

  val timedSuite = suite("timed")(
    test("возвращает результат и длительность") {
      for
        result <- LogMiddleware.timed(ZIO.succeed(42))
      yield
        val value    = result._1
        val duration = result._2
        assertTrue(value == 42) && assertTrue(duration.toMillis >= 0L)
    },
    test("пробрасывает ошибку") {
      for
        exit <- LogMiddleware.timed(ZIO.fail("boom")).exit
      yield assertTrue(exit.isFailure)
    }
  )
