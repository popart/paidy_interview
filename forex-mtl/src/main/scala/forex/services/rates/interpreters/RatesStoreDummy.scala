package forex.services.rates.interpreters

import cats.Applicative
import cats.syntax.applicative._
import forex.domain.{ Price, Rate, Timestamp }
import forex.services.rates.RatesStore

class RatesStoreDummy[F[_]: Applicative] extends RatesStore[F] {
  override def get(pair: Rate.Pair): F[Option[Rate]] =
    Option(Rate(pair, Price(BigDecimal(100)), Timestamp.now)).pure[F]

  override def isFresh: F[Boolean] = true.pure[F]
}
