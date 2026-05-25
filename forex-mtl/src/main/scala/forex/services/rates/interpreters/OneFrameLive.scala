package forex.services.rates.interpreters

import forex.services.rates.Algebra
import cats.effect.Sync
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.functor._

import forex.config.OneFrameConfig
import forex.domain.{ Price, Rate, Timestamp }
import org.http4s.circe.CirceEntityDecoder._
import forex.services.rates.errors._
import org.http4s.client.Client
import org.http4s.Uri
import org.typelevel.ci.CIString
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.Method.GET

/*
 NOTE: http4s
 def expect[A](uri: Uri)(implicit d: EntityDecoder[F, A]): F[A]
 - implicit means compiler searches for a matching type
 - implicit String decoder exists in EntityDecoder companion object

 http4s's EntityDecoder[F, String] (the text decoder) requires Sync
 (decoding the body involves draining the response stream,
 which is a side effect that must be suspended, which only Sync can do)
 ("suspending a side effect" means wrapping it,
 so it doesn't execute until outside pure code, i.e. IOApp.stream)

 btw, cats is separate from cats-effect
 */
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
}

class OneFrameLive[F[_]: Sync](client: Client[F], config: OneFrameConfig) extends Algebra[F] with Http4sClientDsl[F] {
  import OneFrameLive._

  @scala.annotation.nowarn("cat=unused")
  private def getAll(pairs: List[Rate.Pair]): F[Error Either List[Rate]] = {
    import cats.syntax.applicative._
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
            Rate(Rate.Pair(forex.domain.Currency.fromString(r.from), forex.domain.Currency.fromString(r.to)), Price(r.price), Timestamp(r.time_stamp))
          }.asRight[Error]
        }
      case Left(e) => e.asLeft[List[Rate]].pure[F]
    }
  }

  override def get(pair: Rate.Pair): F[Error Either Rate] = {
    val target: Either[Error, Uri] = Uri.fromString(s"http://${config.host}:${config.port}") match {
      case Right(uri) =>
        Right((uri / "rates").withQueryParam("pair", s"${pair.from}${pair.to}"))
      case Left(e) =>
        Left(Error.OneFrameLookupFailed(e.getMessage))
    }

    target match {
      case Right(uri) => client
        .expect[List[OneFrameResponse]](GET(uri, org.http4s.Header.Raw(CIString("token"), config.token)))
        .map {
          case r :: _ => Rate(pair, Price(r.price), Timestamp(r.time_stamp)).asRight[Error]
          case Nil    => Error.OneFrameLookupFailed("empty response").asLeft[Rate]
        }
      case Left(e) => e.asLeft[Rate].pure[F]
    }
  }
}
