package com.wayrecall.gateway.middleware

import zio.*
import zio.http.*

import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// LogMiddleware.scala — Структурированное логирование HTTP-запросов
// ─────────────────────────────────────────────────────────────────────────────
//
// Что это:
//   Утилиты для логирования каждого HTTP-запроса: метод, путь, статус,
//   длительность, пользователь, IP-адрес.
//
// Зачем:
//   - Отладка: видно каждый запрос с таймингом
//   - Аудит: кто что делал (userId + IP)
//   - Мониторинг: можно парсить логи для метрик (Grafana/Loki)
//   - Трейсинг: X-Request-Id прокидывается через все сервисы
//
// Как используется:
//   1. ApiRouter.handleProtectedProxy вызывает generateRequestId → UUID
//   2. UUID прокидывается через X-Request-Id к бэкенду
//   3. После получения ответа — собирается RequestLog и логируется
//   4. Уровень лога зависит от статуса: 5xx → ERROR, 4xx → WARN, остальные → INFO
//
// Паттерн:
//   Все функции чистые (UIO/ZIO). Никакого println!
//   ZIO.logInfo/logWarning/logError интегрируются с SLF4J через logback.xml.
// ─────────────────────────────────────────────────────────────────────────────
object LogMiddleware:

  /** Данные для лога одного HTTP-запроса.
   *
   *  Иммутабельный case class — собираем все данные, потом логируем одной строкой.
   *  Это лучше чем несколько отдельных логов — вся информация в одном месте.
   *
   *  @param requestId  — UUID запроса (уникален, прокидывается бэкендам)
   *  @param method     — HTTP метод (GET, POST, PUT, DELETE)
   *  @param path       — URL путь (/api/v1/devices)
   *  @param origin     — Origin заголовок (домен фронтенда)
   *  @param userAgent  — User-Agent (браузер/клиент)
   *  @param clientIp   — IP клиента (через X-Forwarded-For или напрямую)
   *  @param statusCode — HTTP статус ответа (200, 401, 500...)
   *  @param durationMs — время обработки в миллисекундах
   *  @param userId     — UUID пользователя из JWT (если аутентифицирован) */
  final case class RequestLog(
    requestId:  UUID,
    method:     String,
    path:       String,
    origin:     Option[String],
    userAgent:  Option[String],
    clientIp:   Option[String],
    statusCode: Int,
    durationMs: Long,
    userId:     Option[String]
  ):
    /** Форматированная строка для лога.
     *  Формат: [uuid] GET /api/v1/devices → 200 (15ms) user=uuid ip=127.0.0.1
     *  Легко парсится grep/awk для аналитики. */
    def formatted: String =
      val user = userId.getOrElse("anonymous")
      val ip   = clientIp.getOrElse("unknown")
      s"[$requestId] $method $path → $statusCode (${durationMs}ms) user=$user ip=$ip"

  /** Генерация уникального ID запроса.
   *
   *  UUID v4 — рандомный, 128 бит. Вероятность коллизии ~0.
   *  Этот ID прокидывается:
   *    1. В заголовке X-Request-Id к бэкенду
   *    2. В лог gateway
   *    3. В лог бэкенда (если он читает X-Request-Id)
   *  → Можно найти запрос по всем сервисам по одному ID! */
  val generateRequestId: UIO[UUID] = ZIO.succeed(UUID.randomUUID())

  /** Извлечь IP клиента из заголовков.
   *
   *  Порядок приоритета (стандарт reverse proxy):
   *    1. X-Forwarded-For — устанавливает nginx/LB, первый IP = реальный клиент
   *    2. X-Real-IP       — альтернативный заголовок (nginx)
   *    3. Remote-Address   — TCP-адрес (может быть IP прокси)
   *
   *  Чистая функция: Headers → Option[String] */
  def extractClientIp(headers: Headers): Option[String] =
    headers.get("X-Forwarded-For").map(_.toString.split(",").head.trim)
      .orElse(headers.get("X-Real-IP").map(_.toString))
      .orElse(headers.get("Remote-Address").map(_.toString))

  /** Залогировать запрос с правильным уровнем.
   *
   *  Уровень определяется по HTTP статусу:
   *    5xx → ERROR (серверная ошибка, нужно чинить!)
   *    4xx → WARN  (клиентская ошибка, может быть атака)
   *    1xx-3xx → INFO (нормальная работа)
   *
   *  UIO = никогда не падает (логирование не должно ломать запрос). */
  def logRequest(log: RequestLog): UIO[Unit] =
    if log.statusCode >= 500 then
      ZIO.logError(log.formatted)
    else if log.statusCode >= 400 then
      ZIO.logWarning(log.formatted)
    else
      ZIO.logInfo(log.formatted)

  /** Измерить время выполнения ZIO-эффекта.
   *
   *  Использует ZIO.timed — чистый таймер через Clock.
   *  Возвращает (результат, Duration).
   *
   *  Пример:
   *    timed(httpClient.request(req)).map { case (response, dur) =>
   *      println(s"Запрос занял ${dur.toMillis}ms")
   *    }
   */
  def timed[R, E, A](effect: ZIO[R, E, A]): ZIO[R, E, (A, Duration)] =
    effect.timed.map { case (duration, result) => (result, duration) }
