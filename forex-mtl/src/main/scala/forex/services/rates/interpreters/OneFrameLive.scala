package forex.services.rates.interpreters

import forex.services.rates.Algebra
import cats.Applicative
import cats.syntax.applicative._
import cats.syntax.either._

import forex.config.OneFrameConfig
import forex.domain.{ Price, Rate, Timestamp }
import forex.services.rates.errors._
import org.http4s.client.Client
//import org.http4s.headers._
//import org.http4s.{ Method, Uri }
import org.http4s.{ Uri  }

class OneFrameLive[F[_]: Applicative](@annotation.unused client: Client[F], @annotation.unused config: OneFrameConfig) extends Algebra[F] {
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
/*
    val req = Method.GET(
      target,
      Authorization(Credentials.Token(AuthScheme.Bearer, config.token)),
    )
*/

    target match {
      case Right(_) =>
        Rate(pair, Price(BigDecimal(1337)), Timestamp.now).asRight[Error].pure[F]
      case Left(e) =>
        e.asLeft[Rate].pure[F]
    }
  }

}
