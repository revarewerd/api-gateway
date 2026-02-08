package com.wayrecall.gateway.service

import com.wayrecall.gateway.config.{GatewayConfig, ServiceEndpoint}
import com.wayrecall.gateway.domain.*
import zio.*
import zio.http.*

import java.time.Instant

// ─────────────────────────────────────────────────────────────────────────────
// HealthService.scala — Health Check: проверка доступности бэкендов
// ─────────────────────────────────────────────────────────────────────────────
//
// Что это:
//   Сервис мониторинга здоровья. Параллельно проверяет все бэкенд-сервисы
//   и возвращает агрегированный статус gateway.
//
// Зачем:
//   - Kubernetes liveness/readiness проба (GET /api/v1/health)
//   - Мониторинг: Grafana/Prometheus дёргает этот endpoint
//   - Оператор видит какой бэкенд лежит и как давно работает gateway
//   - Load Balancer (nginx) может убрать ноду если health = Unhealthy
//
// Как работает:
//   1. ZIO.foreachPar — параллельно шлёт GET /health каждому бэкенду
//   2. Каждый бэкенд проверяется с таймаутом 5 секунд
//   3. Если ответил 2xx → Healthy, иначе → Degraded, не ответил → Unhealthy
//   4. Агрегация: все Healthy → Healthy, хотя бы один не Healthy → Degraded,
//      все Unhealthy → Unhealthy
//   5. Возвращает JSON: { status, version, uptime, services: [{name, status, latencyMs}] }
//
// Чистый параллелизм:
//   ZIO.foreachPar запускает все проверки ОДНОВРЕМЕННО (не последовательно!)
//   Нет мьютексов, нет thread pool management — ZIO Fiber делает всё сам.
//   Проверка 5 бэкендов занимает время САМОГО МЕДЛЕННОГО, а не сумму.
//
// Важно:
//   check возвращает UIO (никогда не падает!) — ошибки превращаются в статус Unhealthy.
//   Это гарантирует что /health endpoint ВСЕГДА вернёт ответ.
// ─────────────────────────────────────────────────────────────────────────────

/** Trait-интерфейс HealthService.
 *  UIO[...] = ZIO[Any, Nothing, ...] — гарантирует что ошибка невозможна.
 *  Это важно: health endpoint не должен падать никогда. */
trait HealthService:

  /** Проверить здоровье всех бэкендов и вернуть агрегированный статус */
  def check: UIO[GatewayHealthResponse]

object HealthService:

  /** Accessor — вызываем HealthService.check вместо ZIO.serviceWithZIO[HealthService](_.check) */
  def check: ZIO[HealthService, Nothing, GatewayHealthResponse] =
    ZIO.serviceWithZIO(_.check)

  // ─── Live реализация ──────────────────────────────────────────────────
  // Получает конфиг (список бэкендов), HTTP-клиент и время старта через DI.
  // startTime запоминается при создании ZLayer — используется для подсчёта uptime.

  final case class Live(
    config:    GatewayConfig,
    client:    Client,
    startTime: Instant       // Время старта gateway — для расчёта uptime
  ) extends HealthService:

    /** Список бэкендов для проверки.
     *  Каждый элемент: (человекочитаемое имя, ServiceEndpoint из конфига).
     *  При добавлении нового микросервиса — добавить сюда. */
    private val backends: List[(String, ServiceEndpoint)] = List(
      "device-manager" -> config.services.deviceManager,
      "auth-service"   -> config.services.authService
    )

    override def check: UIO[GatewayHealthResponse] =
      for
        _        <- ZIO.logDebug(s"[HEALTH] Запуск health check для ${backends.size} бэкендов: ${backends.map(_._1).mkString(", ")}")
        now      <- Clock.instant

        // foreachPar — параллельная проверка ВСЕХ бэкендов одновременно!
        // Каждый checkBackend запускается как ZIO Fiber, результаты собираются в List
        services <- ZIO.foreachPar(backends) { case (name, endpoint) =>
                      checkBackend(name, endpoint)
                    }

        // Агрегация: худший статус определяет общий
        overall   = aggregateStatus(services)
        uptime    = java.time.Duration.between(startTime, now)

        _        <- ZIO.logInfo(s"[HEALTH] Результат: status=$overall, uptime=${formatDuration(uptime)}, " +
                      s"сервисы: ${services.map(s => s"${s.name}=${s.status}(${s.latencyMs}ms)").mkString(", ")}")
      yield GatewayHealthResponse(
        status   = overall,
        version  = "0.1.0-SNAPSHOT",
        uptime   = formatDuration(uptime),
        services = services
      )

    /** Проверить один бэкенд — GET /health с таймаутом.
     *
     *  Алгоритм:
     *    1. Формируем URL: baseUrl + "/health"
     *    2. Шлём GET с таймаутом 5 секунд
     *    3. Если 2xx → Healthy, иначе → Degraded
     *    4. Если таймаут или ошибка → Unhealthy (latency = -1)
     *
     *  Возвращает UIO — ошибки НЕВОЗМОЖНЫ, они превращаются в статус.
     *  ZIO.scoped нужен для управления жизненным циклом HTTP-соединения.
     */
    private def checkBackend(name: String, endpoint: ServiceEndpoint): UIO[ServiceHealth] =
      val healthUrl = s"${endpoint.baseUrl}/health"

      ZIO.scoped {
        (for
          _        <- ZIO.logDebug(s"[HEALTH] Проверяю $name → GET $healthUrl")
          url      <- ZIO.fromEither(URL.decode(healthUrl))
          start    <- Clock.instant
          response <- client.request(Request.get(url))
                        .timeoutFail(())(5.seconds)  // 5 сек — если не ответил, считаем мёртвым
          end      <- Clock.instant
          latency   = java.time.Duration.between(start, end).toMillis
          status    = if response.status.isSuccess then HealthStatus.Healthy
                      else HealthStatus.Degraded
          _        <- ZIO.logDebug(s"[HEALTH] $name ответил: status=${response.status.code}, latency=${latency}ms → $status")
        yield ServiceHealth(name, status, latency, end))
      }.catchAll { err =>
        // Бэкенд не ответил: таймаут, connection refused, DNS failure...
        // Превращаем в Unhealthy (latency = -1 означает "нет ответа")
        ZIO.logDebug(s"[HEALTH] $name НЕДОСТУПЕН: $err") *>
        Clock.instant.map(now =>
          ServiceHealth(name, HealthStatus.Unhealthy, -1, now)
        )
      }

    /** Агрегация статусов по принципу "худший определяет общий":
     *    - Все Healthy → Healthy (всё работает)
     *    - Все Unhealthy → Unhealthy (всё лежит)
     *    - Микс → Degraded (частично работает) */
    private def aggregateStatus(services: List[ServiceHealth]): HealthStatus =
      val statuses = services.map(_.status)
      if statuses.forall(_ == HealthStatus.Healthy) then HealthStatus.Healthy
      else if statuses.forall(_ == HealthStatus.Unhealthy) then HealthStatus.Unhealthy
      else HealthStatus.Degraded

    /** Форматирование java.time.Duration → "2h 15m 30s" для человека */
    private def formatDuration(d: java.time.Duration): String =
      val hours   = d.toHours
      val minutes = d.toMinutesPart
      val seconds = d.toSecondsPart
      s"${hours}h ${minutes}m ${seconds}s"

  // ─── ZLayer ───────────────────────────────────────────────────────────
  // Создаём Live при старте приложения:
  //   - Запоминаем текущее время (startTime) для расчёта uptime
  //   - Получаем конфиг и HTTP-клиент через DI
  // ZLayer.fromZIO — потому что нам нужен эффект (Clock.instant) при создании

  val live: ZLayer[GatewayConfig & Client, Nothing, HealthService] =
    ZLayer.fromZIO(
      for
        config <- ZIO.service[GatewayConfig]
        client <- ZIO.service[Client]
        now    <- Clock.instant
        _      <- ZIO.logDebug(s"[HEALTH] HealthService создан, startTime=$now, бэкенды: ${List("device-manager", "auth-service").mkString(", ")}")
      yield Live(config, client, now)
    )
