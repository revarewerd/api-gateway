package com.wayrecall.gateway.service

import com.wayrecall.gateway.config.{GatewayConfig, ServiceEndpoint}
import com.wayrecall.gateway.domain.*
import zio.*
import zio.http.*

import java.time.Instant

// ─────────────────────────────────────────────────────────────────────────────
// ProxyService.scala — HTTP-проксирование запросов к бэкенд-сервисам
// ─────────────────────────────────────────────────────────────────────────────
//
// Что это:
//   Сервис-прокси. Получает уже аутентифицированный запрос от ApiRouter
//   и пересылает его нужному бэкенд-сервису (Device Manager, History Writer и т.д.)
//
// Как работает:
//   1. ApiRouter определяет какой бэкенд обслуживает URL (по первому сегменту пути)
//   2. ProxyService получает ServiceEndpoint (baseUrl + timeout) + оригинальный Request
//   3. Собирает новый Request:
//      - Меняет URL на бэкендовский (например http://localhost:8092/api/v1/devices)
//      - Убирает Host (иначе бэкенд получит gateway хост) и Authorization (JWT уже проверен)
//      - Добавляет X-заголовки (X-User-Id, X-Company-Id, X-User-Roles, X-Request-Id)
//      - Прокидывает body как есть (JSON, binary — неважно)
//   4. Шлёт запрос через zio-http Client с таймаутом из конфига
//   5. Возвращает Response бэкенда как есть
//
// Обработка ошибок:
//   - Невалидный URL бэкенда → DomainError.BadGateway
//   - Таймаут (бэкенд не ответил) → DomainError.GatewayTimeout
//   - Сетевая ошибка → DomainError.BadGateway
//
// ZIO паттерн: trait (интерфейс) + companion object (accessor) + Live (реализация)
//   Это позволяет мокать ProxyService в тестах.
// ─────────────────────────────────────────────────────────────────────────────

/** Trait-интерфейс ProxyService.
 *  Единственный метод forward() — проксировать запрос.
 *  Scope в R нужен для управления жизненным циклом HTTP-соединения (zio-http Client). */
trait ProxyService:

  /** Проксировать запрос к указанному бэкенд-сервису.
   *
   *  @param target  — куда проксировать (baseUrl + timeout из конфига)
   *  @param request — оригинальный HTTP-запрос от клиента
   *  @param path    — путь для бэкенда (без /api/v1 префикса шлюза)
   *  @param headers — обогащённые заголовки (X-User-Id и т.д. от AuthMiddleware)
   *  @return Response от бэкенда или DomainError */
  def forward(
    target:  ServiceEndpoint,
    request: Request,
    path:    String,
    headers: Headers
  ): ZIO[Scope, DomainError, Response]

object ProxyService:

  /** Accessor-метод для удобного вызова из for-comprehension.
   *  Вместо ZIO.serviceWithZIO[ProxyService](_.forward(...)) пишем ProxyService.forward(...) */
  def forward(
    target:  ServiceEndpoint,
    request: Request,
    path:    String,
    headers: Headers
  ): ZIO[ProxyService & Scope, DomainError, Response] =
    ZIO.serviceWithZIO[ProxyService](_.forward(target, request, path, headers))

  // ─── Live реализация ──────────────────────────────────────────────────
  // Получает zio-http Client через DI (ZLayer).
  // Client создаётся один раз при старте и переиспользует TCP-соединения (connection pool).

  final case class Live(client: Client) extends ProxyService:

    override def forward(
      target:  ServiceEndpoint,
      request: Request,
      path:    String,
      headers: Headers
    ): ZIO[Scope, DomainError, Response] =
      // Формируем полный URL бэкенда: baseUrl + path
      // Например: "http://localhost:8092" + "/api/v1/devices" = "http://localhost:8092/api/v1/devices"
      val targetUrl = s"${target.baseUrl}$path"

      for
        _   <- ZIO.logDebug(s"[PROXY] Проксирование ${request.method} → $targetUrl (timeout=${target.timeoutMs}ms)")

        // Шаг 1: Парсим URL бэкенда (может быть невалидным в конфиге)
        url <- ZIO.fromEither(URL.decode(targetUrl))
                 .mapError(err => DomainError.BadGateway("proxy", s"Невалидный URL: $targetUrl"))

        // Шаг 2: Собираем новый Request — иммутабельная трансформация
        // Метод и body берём от оригинала, URL и headers — новые
        proxyRequest = Request(
          method  = request.method,
          url     = url,
          headers = mergeHeaders(request.headers, headers),
          body    = request.body
        )

        _        <- ZIO.logDebug(s"[PROXY] Отправка запроса: method=${proxyRequest.method}, headers=${proxyRequest.headers.toList.size} шт")

        // Шаг 3: Выполняем HTTP-запрос с таймаутом
        // timeoutFail — если за timeoutMs не пришёл ответ, возвращаем GatewayTimeout
        start    <- Clock.instant
        response <- client.request(proxyRequest)
                      .timeoutFail(DomainError.GatewayTimeout(target.baseUrl))(
                        Duration.fromMillis(target.timeoutMs.toLong)
                      )
                      .mapError {
                        case e: DomainError => e  // Пробрасываем наши ошибки как есть
                        case other          => DomainError.BadGateway("proxy", other.getMessage)
                      }
        end      <- Clock.instant
        latency   = java.time.Duration.between(start, end).toMillis

        _        <- ZIO.logInfo(s"[PROXY] Ответ от $targetUrl: status=${response.status.code}, latency=${latency}ms")
      yield response

    /** Объединить оригинальные заголовки с обогащёнными (X-User-Id и т.д.).
     *
     *  Фильтруем опасные заголовки:
     *    - Host: бэкенд должен видеть свой хост, не gateway
     *    - Authorization: JWT уже проверен gateway, бэкенду он не нужен
     *    - Content-Length: будет пересчитан (body может измениться при проксировании)
     *
     *  Чистая функция: Headers × Headers → Headers (без побочных эффектов).
     */
    private def mergeHeaders(original: Headers, enriched: Headers): Headers =
      val filtered = original.toList.filterNot { header =>
        val name = header.headerName.toLowerCase
        name == "host" || name == "authorization" || name == "content-length"
      }
      Headers(filtered*) ++ enriched

  // ─── ZLayer ───────────────────────────────────────────────────────────
  // ProxyService.Live зависит от Client.
  // ZLayer.fromFunction автоматически создаёт слой из конструктора Live(client).
  // Client.default (в Main.scala) создаёт HTTP-клиент с connection pool.

  val live: ZLayer[Client, Nothing, ProxyService] =
    ZLayer.fromFunction(Live(_))
