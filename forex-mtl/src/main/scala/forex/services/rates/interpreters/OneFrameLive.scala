package forex.services.rates.interpreters

import forex.services.rates.Algebra
import cats.effect.Sync
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.functor._

import forex.config.OneFrameConfig
import forex.domain.{ Price, Rate, Timestamp }
import forex.services.rates.errors._
import org.http4s.client.Client
import org.http4s.Uri

/*
 NOTES: http4s
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
class OneFrameLive[F[_]: Sync](client: Client[F], @annotation.unused config: OneFrameConfig) extends Algebra[F] {
  override def get(pair: Rate.Pair): F[Error Either Rate] = {
    // there is cats "either" syntax to make this prettier
    val target: Either[Error, Uri] = Uri.fromString(s"http://${config.host}:${config.port}") match {
      case Right(uri) =>
        Right((uri / "rates")
        .withQueryParam("pair", s"${pair.from}${pair.to}"))
      case Left(e) =>
        Left(Error.OneFrameLookupFailed(e.getMessage))
    }

    println(target)

    // .map is from cats.syntax.functor (more generic than scala map)
    // `*>` is from cats.syntax.apply (a.k.a. "andThen")
    target match {
      case Right(uri) =>
        client.expect[String](uri).map(println) *>
          Rate(pair, Price(BigDecimal(1337)), Timestamp.now).asRight[Error].pure[F]
      case Left(e) =>
        e.asLeft[Rate].pure[F]
    }
  }
}
