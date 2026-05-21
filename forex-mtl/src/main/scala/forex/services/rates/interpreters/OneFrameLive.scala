package forex.services.rates.interpreters

import forex.services.rates.Algebra
import cats.Applicative
import cats.syntax.applicative._
import cats.syntax.either._
import forex.config.OneFrameConfig
import forex.domain.{ Price, Rate, Timestamp }
import forex.services.rates.errors._
import org.http4s.client.Client

class OneFrameLive[F[_]: Applicative](@annotation.unused client: Client[F], @annotation.unused config: OneFrameConfig) extends Algebra[F] {

  override def get(pair: Rate.Pair): F[Error Either Rate] =
    Rate(pair, Price(BigDecimal(1337)), Timestamp.now).asRight[Error].pure[F]

}
