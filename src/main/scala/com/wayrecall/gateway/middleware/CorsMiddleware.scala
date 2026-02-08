package com.wayrecall.gateway.middleware

import com.wayrecall.gateway.config.GatewayConfig
import zio.*
import zio.http.*

// ─────────────────────────────────────────────────────────────────────────────
// CorsMiddleware.scala — CORS: контроль доступа между доменами
// ─────────────────────────────────────────────────────────────────────────────
//
// Что это:
//   Cross-Origin Resource Sharing — механизм безопасности браузера.
//   Браузер блокирует JS-запросы с одного домена на другой, если сервер
//   не разрешил это явно через CORS-заголовки.
//
// Зачем здесь:
//   Фронтенд на billing.wayrecall.com (порт 3001) шлёт AJAX на gateway (порт 8080).
//   Это РАЗНЫЕ origins → браузер требует CORS-заголовки в ответе.
//
// Как работает:
//   1. Браузер перед POST/PUT/DELETE шлёт OPTIONS preflight-запрос
//   2. Gateway отвечает 204 + Access-Control-Allow-Origin: <origin фронтенда>
//   3. Браузер проверяет что origin разрешён и выполняет настоящий запрос
//   4. Gateway добавляет те же CORS-заголовки к каждому ответу
//
// Разрешённые origins (из конфига):
//   - billing.wayrecall.com   (config.cors.billingOrigin)
//   - app.wayrecall.com       (config.cors.monitoringOrigin)
//
// Безопасность:
//   - Access-Control-Allow-Credentials: true → разрешает куки
//   - Vary: Origin → запрещает кэширование ответа для разных origins
//   - Max-Age: 86400 → кэш preflight на 24 часа (меньше OPTIONS запросов)
//   - Если Origin НЕ в белом списке → заголовки НЕ добавляются → браузер блокирует
// ─────────────────────────────────────────────────────────────────────────────
object CorsMiddleware:

  // ─── Константы CORS-заголовков ────────────────────────────────────────

  /** Разрешённые HTTP методы для API.
   *  OPTIONS не в списке — он обрабатывается отдельно (preflight). */
  private val AllowedMethods = "GET, POST, PUT, DELETE, PATCH, OPTIONS"

  /** Заголовки которые клиент может отправлять.
   *  Content-Type   — для JSON body
   *  Authorization  — для JWT Bearer token
   *  X-Request-Id   — для трейсинга запросов */
  private val AllowedHeaders = "Content-Type, Authorization, X-Request-Id"

  /** Заголовки которые клиент может ЧИТАТЬ из ответа.
   *  По умолчанию браузер скрывает все кастомные заголовки ответа от JS. */
  private val ExposedHeaders = "X-Request-Id, X-RateLimit-Remaining"

  /** Время кэширования preflight в секундах.
   *  86400 = 24 часа — браузер не будет слать OPTIONS повторно целые сутки. */
  private val MaxAge = "86400"

  // ─── Публичный API ────────────────────────────────────────────────────

  /** Создать CORS-заголовки для ответа на основе Origin запроса.
   *
   *  Логика:
   *    1. Берём Origin из запроса (заголовок "Origin")
   *    2. Проверяем что он в белом списке (billingOrigin или monitoringOrigin)
   *    3. Если да → полный набор CORS-заголовков
   *    4. Если нет → Headers.empty (браузер заблокирует запрос)
   *
   *  Чистая функция: Option[String] → ZIO → Headers
   */
  def corsHeaders(requestOrigin: Option[String]): ZIO[GatewayConfig, Nothing, Headers] =
    for
      config        <- ZIO.service[GatewayConfig]
      allowedOrigins = Set(config.cors.billingOrigin, config.cors.monitoringOrigin)
      headers        = requestOrigin
                         .filter(allowedOrigins.contains)
                         .fold(Headers.empty)(origin => buildCorsHeaders(origin))
      _             <- ZIO.logDebug(s"[CORS] Origin: ${requestOrigin.getOrElse("нет")} → " +
                         s"${if headers == Headers.empty then "ЗАБЛОКИРОВАН" else "разрешён"}")
    yield headers

  /** Обработать preflight (OPTIONS) запрос.
   *
   *  Браузер шлёт OPTIONS перед "сложными" запросами (POST с JSON, PUT, DELETE).
   *  Gateway отвечает 204 No Content + CORS-заголовки, тело пустое.
   *  После этого браузер выполняет настоящий запрос.
   */
  def handlePreflight(request: Request): ZIO[GatewayConfig, Nothing, Response] =
    val origin = request.headers.get("Origin").map(_.toString)
    ZIO.logDebug(s"[CORS] Preflight OPTIONS от origin: ${origin.getOrElse("нет")}") *>
    corsHeaders(origin).map { headers =>
      Response(
        status  = Status.NoContent,
        headers = headers
      )
    }

  /** Обогатить ответ CORS-заголовками.
   *
   *  Вызывается для КАЖДОГО ответа (не только preflight).
   *  Добавляет Access-Control-Allow-Origin к ответу бэкенда.
   *  Чистая функция: (Response, Origin) → Response с CORS.
   */
  def enrichResponse(response: Response, requestOrigin: Option[String]): ZIO[GatewayConfig, Nothing, Response] =
    corsHeaders(requestOrigin).map(headers => response.addHeaders(headers))

  // ─── Приватные хелперы ────────────────────────────────────────────────

  /** Полный набор CORS-заголовков для разрешённого origin.
   *
   *  Vary: Origin — ОБЯЗАТЕЛЬНО! Без этого CDN/прокси могут закэшировать
   *  ответ с одним Origin и отдать другому → CORS-ошибка в браузере. */
  private def buildCorsHeaders(origin: String): Headers =
    Headers(
      Header.Custom("Access-Control-Allow-Origin", origin),
      Header.Custom("Access-Control-Allow-Methods", AllowedMethods),
      Header.Custom("Access-Control-Allow-Headers", AllowedHeaders),
      Header.Custom("Access-Control-Expose-Headers", ExposedHeaders),
      Header.Custom("Access-Control-Allow-Credentials", "true"),
      Header.Custom("Access-Control-Max-Age", MaxAge),
      Header.Custom("Vary", "Origin")
    )
