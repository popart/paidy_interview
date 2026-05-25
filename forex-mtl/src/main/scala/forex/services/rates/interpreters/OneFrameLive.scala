package forex.services.rates.interpreters

import forex.services.rates.{ Algebra, Cache }
import cats.effect.{ Clock, Sync }
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import forex.config.OneFrameConfig
import forex.domain.{ Currency, Price, Rate, Timestamp }
import org.http4s.circe.CirceEntityDecoder._
import forex.services.rates.errors._
import org.http4s.client.Client
import org.http4s.Uri
import org.typelevel.ci.CIString
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.Method.GET

import scala.concurrent.duration.MILLISECONDS

object OneFrameLive {
  import java.time.OffsetDateTime
  import io.circe.Decoder
  import io.circe.generic.semiauto.deriveDecoder

  final case class OneFrameResponse(
      from: String,
      to: String,
      bid: BigDecimal,
      ask: BigDecimal,
      price: BigDecimal,
      time_stamp: OffsetDateTime
  )

  implicit val oneFrameResponseDecoder: Decoder[OneFrameResponse] = deriveDecoder

  val allPairs: List[Rate.Pair] =
    for {
      from <- Currency.all
      to   <- Currency.all
      if from != to
    } yield Rate.Pair(from, to)

  def create[F[_]: Sync: Clock](client: Client[F], config: OneFrameConfig): F[Algebra[F]] =
    Cache.create[F, Rate.Pair, Rate](config.ttl, Clock[F].realTime(MILLISECONDS))
      .map(cache => new OneFrameLive[F](client, config, cache))
}

class OneFrameLive[F[_]: Sync] private (
    client: Client[F],
    config: OneFrameConfig,
    cache: Cache[F, Rate.Pair, Rate]
) extends Algebra[F] with Http4sClientDsl[F] {
  import OneFrameLive._

  private def getAll(pairs: List[Rate.Pair]): F[Error Either List[Rate]] = {
    val pairValues = pairs.map(p => s"${p.from}${p.to}")

    val target: Either[Error, Uri] = Uri.fromString(s"http://${config.host}:${config.port}") match {
      case Right(uri) =>
        Right((uri / "rates").withMultiValueQueryParams(Map("pair" -> pairValues)))
      case Left(e) =>
        Left(Error.OneFrameLookupFailed(e.getMessage))
    }

    target match {
      case Right(uri) => client
        .expect[List[OneFrameResponse]](GET(uri, org.http4s.Header.Raw(CIString("token"), config.token)))
        .map { responses =>
          responses.map { r =>
            Rate(Rate.Pair(Currency.fromString(r.from), Currency.fromString(r.to)), Price(r.price), Timestamp(r.time_stamp))
          }.asRight[Error]
        }
      case Left(e) => e.asLeft[List[Rate]].pure[F]
    }
  }

  private val refresh: F[Map[Rate.Pair, Rate]] =
    getAll(allPairs).flatMap {
      case Right(rates) => Sync[F].pure(rates.map(r => r.pair -> r).toMap)
      case Left(e)      => Sync[F].raiseError[Map[Rate.Pair, Rate]](RefreshFailure(e))
    }

  override def get(pair: Rate.Pair): F[Error Either Rate] =
    cache.getOrRefresh(pair, refresh).attempt.map {
      case Right(Some(rate))      => Right(rate)
      case Right(None)            => Left(Error.OneFrameLookupFailed(s"no rate for pair $pair"))
      case Left(RefreshFailure(e)) => Left(e)
      case Left(t)                => Left(Error.OneFrameLookupFailed(t.getMessage))
    }

  private case class RefreshFailure(err: Error) extends RuntimeException(err.toString)
}
