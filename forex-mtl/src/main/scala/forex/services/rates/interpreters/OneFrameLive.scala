package forex.services.rates.interpreters

import cats.Monad
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import forex.domain.Rate
import forex.services.rates.{ Algebra, RatesStore }
import forex.services.rates.errors._

class OneFrameLive[F[_]: Monad](store: RatesStore[F]) extends Algebra[F] {

  override def get(pair: Rate.Pair): F[Error Either Rate] =
    store.get(pair).flatMap {
      case Some(rate) =>
        store.isFresh.map {
          case true  => rate.asRight[Error]
          case false => (Error.StaleRates: Error).asLeft[Rate]
        }
      case None =>
        (Error.PairNotFound(s"${pair.from}${pair.to}"): Error).asLeft[Rate].pure[F]
    }
}
