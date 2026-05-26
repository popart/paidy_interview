package forex.services.rates.interpreters

import cats.effect.concurrent.Ref
import cats.effect.{ Clock, Concurrent, Resource, Sync, Timer }
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import fs2.Stream

import scala.annotation.nowarn
import scala.concurrent.duration._
import forex.config.OneFrameConfig
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.services.rates.RatesStore
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import org.http4s.Method.GET
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.{ Header, Uri }
import org.typelevel.ci.CIString

import java.time.OffsetDateTime
import scala.concurrent.duration.MILLISECONDS

object RatesStoreLive {

  final case class Snapshot(entries: Map[Rate.Pair, Rate], refreshedAt: Option[Long])

  private final case class OneFrameResponse(
      from: Currency,
      to: Currency,
      bid: BigDecimal,
      ask: BigDecimal,
      price: BigDecimal,
      time_stamp: OffsetDateTime
  )

  private implicit val currencyDecoder: Decoder[Currency] =
    Decoder[String].emap(Currency.fromString)

  private implicit val oneFrameResponseDecoder: Decoder[OneFrameResponse] = deriveDecoder

  private val allPairs: List[Rate.Pair] =
    for {
      from <- Currency.all
      to   <- Currency.all
      if from != to
    } yield Rate.Pair(from, to)

  def resource[F[_]: Concurrent: Timer](
      client: Client[F],
      config: OneFrameConfig
  ): Resource[F, RatesStore[F]] = {
    val now: F[Long] = Clock[F].realTime(MILLISECONDS)

    // TODO: replace with structured logging + metrics (e.g. log4cats + Prometheus counter);
    //       a persistent outage will silently stale the cache with no alert until clients see StaleRates
    def onRefreshError(@nowarn err: Throwable): F[Unit] = Sync[F].unit

    val acquire: F[(RatesStoreLive[F], cats.effect.Fiber[F, Unit])] =
      for {
        ref   <- Ref.of[F, Snapshot](Snapshot(Map.empty, None))
        store = new RatesStoreLive[F](ref, client, config, now)
        _     <- store.refresh
        fiber <- Concurrent[F].start(
                  Stream
                    .awakeEvery[F](config.refreshInterval)
                    .evalMap(_ => store.refresh.handleErrorWith(onRefreshError))
                    .compile
                    .drain
                )
      } yield (store, fiber)

    Resource.make(acquire) { case (_, fiber) => fiber.cancel }.map(_._1)
  }
}

/**
 * Serves cached exchange rates to stay within One-Frame's 1,000 requests/day limit while
 * supporting ≥10,000 requests/day. On startup it fetches all 72 currency pairs (9 currencies,
 * N×(N-1)) in a single upstream call, then refreshes on a background fiber at `refreshInterval`.
 * At the default 2-minute interval that is 24 * 60 / 2 = 720 upstream calls/day.
 * Requests are rejected with StaleRates if the cache age exceeds `ttl` (default 4 minutes).
 */
class RatesStoreLive[F[_]: Sync: Timer] private[interpreters] (
    ref: Ref[F, RatesStoreLive.Snapshot],
    client: Client[F],
    config: OneFrameConfig,
    now: F[Long]
) extends RatesStore[F]
    with Http4sClientDsl[F] {
  import RatesStoreLive._

  def refresh: F[Unit] =
    for {
      rates <- withRetry(fetchAll, retries = 2, delay = 30.seconds)
      t     <- now
      _     <- ref.set(Snapshot(rates.map(r => r.pair -> r).toMap, Some(t)))
    } yield ()

  private def withRetry[A](fa: F[A], retries: Int, delay: FiniteDuration): F[A] =
    fa.handleErrorWith { err =>
      if (retries > 0)
        Timer[F].sleep(delay) >> withRetry(fa, retries - 1, delay)
      else
        err.raiseError[F, A]
    }

  override def get(pair: Rate.Pair): F[Option[Rate]] =
    ref.get.map(_.entries.get(pair))

  override def isFresh: F[Boolean] =
    for {
      t <- now
      s <- ref.get
    } yield s.refreshedAt.exists(r => t - r < config.ttl.toMillis)

  private val fetchAll: F[List[Rate]] = {
    val pairValues = allPairs.map(p => s"${p.from}${p.to}")
    val uri = (Uri.unsafeFromString(s"http://${config.host}:${config.port}") / "rates")
      .withMultiValueQueryParams(Map("pair" -> pairValues))

    client
      .expect[List[OneFrameResponse]](GET(uri, Header.Raw(CIString("token"), config.token)))
      .map(_.map { r =>
        Rate(Rate.Pair(r.from, r.to), Price(r.price), Timestamp(r.time_stamp))
      })
  }
}
