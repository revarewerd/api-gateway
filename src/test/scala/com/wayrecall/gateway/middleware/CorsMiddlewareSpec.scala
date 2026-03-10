package com.wayrecall.gateway.middleware

import com.wayrecall.gateway.config.*
import com.wayrecall.gateway.domain.*
import zio.*
import zio.http.*
import zio.test.*
import zio.test.Assertion.*

// ─────────────────────────────────────────────────────────────────────────────
// CorsMiddlewareSpec — тесты CORS middleware
// ─────────────────────────────────────────────────────────────────────────────
// Покрывает: corsHeaders (разрешённый/неразрешённый Origin),
//            handlePreflight (OPTIONS → 204), enrichResponse
// ─────────────────────────────────────────────────────────────────────────────

object CorsMiddlewareSpec extends ZIOSpecDefault:

  /** Тестовый слой — CORS origins: localhost:3001 (billing), localhost:3002 (monitoring) */
  private val testLayer: ULayer[GatewayConfig] = GatewayConfig.test

  def spec = suite("CorsMiddlewareSpec")(
    corsHeadersSuite,
    handlePreflightSuite,
    enrichResponseSuite
  )

  // ─── corsHeaders ───────────────────────────────────────────────────────

  val corsHeadersSuite = suite("corsHeaders")(
    test("разрешённый billing origin — полные CORS-заголовки") {
      for
        headers <- CorsMiddleware.corsHeaders(Some("http://localhost:3001"))
      yield assertTrue(
        headers != Headers.empty,
        headers.get("Access-Control-Allow-Origin").map(_.toString).contains("http://localhost:3001"),
        headers.get("Access-Control-Allow-Methods").isDefined,
        headers.get("Access-Control-Allow-Headers").isDefined,
        headers.get("Access-Control-Allow-Credentials").map(_.toString).contains("true"),
        headers.get("Vary").map(_.toString).contains("Origin")
      )
    }.provide(testLayer),

    test("разрешённый monitoring origin — полные CORS-заголовки") {
      for
        headers <- CorsMiddleware.corsHeaders(Some("http://localhost:3002"))
      yield assertTrue(
        headers != Headers.empty,
        headers.get("Access-Control-Allow-Origin").map(_.toString).contains("http://localhost:3002")
      )
    }.provide(testLayer),

    test("неразрешённый origin — пустые заголовки") {
      for
        headers <- CorsMiddleware.corsHeaders(Some("http://evil.com"))
      yield assertTrue(headers == Headers.empty)
    }.provide(testLayer),

    test("отсутствие Origin — пустые заголовки") {
      for
        headers <- CorsMiddleware.corsHeaders(None)
      yield assertTrue(headers == Headers.empty)
    }.provide(testLayer),

    test("Max-Age = 86400 (24 часа)") {
      for
        headers <- CorsMiddleware.corsHeaders(Some("http://localhost:3001"))
      yield assertTrue(
        headers.get("Access-Control-Max-Age").map(_.toString).contains("86400")
      )
    }.provide(testLayer),

    test("Expose-Headers содержит X-Request-Id") {
      for
        headers <- CorsMiddleware.corsHeaders(Some("http://localhost:3001"))
      yield
        val exposed = headers.get("Access-Control-Expose-Headers").map(_.toString).getOrElse("")
        assertTrue(exposed.contains("X-Request-Id"))
    }.provide(testLayer)
  )

  // ─── handlePreflight ──────────────────────────────────────────────────

  val handlePreflightSuite = suite("handlePreflight")(
    test("OPTIONS с разрешённым origin — 204 No Content + CORS") {
      val request = Request(
        method  = Method.OPTIONS,
        url     = URL.root,
        headers = Headers("Origin" -> "http://localhost:3001")
      )
      for
        response <- CorsMiddleware.handlePreflight(request)
      yield assertTrue(
        response.status == Status.NoContent,
        response.headers.get("Access-Control-Allow-Origin").isDefined
      )
    }.provide(testLayer),

    test("OPTIONS без origin — 204 без CORS заголовков") {
      val request = Request(
        method  = Method.OPTIONS,
        url     = URL.root,
        headers = Headers.empty
      )
      for
        response <- CorsMiddleware.handlePreflight(request)
      yield assertTrue(
        response.status == Status.NoContent,
        response.headers.get("Access-Control-Allow-Origin").isEmpty
      )
    }.provide(testLayer)
  )

  // ─── enrichResponse ───────────────────────────────────────────────────

  val enrichResponseSuite = suite("enrichResponse")(
    test("добавляет CORS-заголовки к ответу") {
      val original = Response.ok
      for
        enriched <- CorsMiddleware.enrichResponse(original, Some("http://localhost:3001"))
      yield assertTrue(
        enriched.headers.get("Access-Control-Allow-Origin").isDefined
      )
    }.provide(testLayer),

    test("неразрешённый origin — заголовки не добавляются") {
      val original = Response.ok
      for
        enriched <- CorsMiddleware.enrichResponse(original, Some("http://unknown.com"))
      yield assertTrue(
        enriched.headers.get("Access-Control-Allow-Origin").isEmpty
      )
    }.provide(testLayer)
  )
