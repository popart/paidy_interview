package forex.services.rates

import forex.domain.Rate

trait RatesStore[F[_]] {
  def get(pair: Rate.Pair): F[Option[Rate]]
  def isFresh: F[Boolean]
}
