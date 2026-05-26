package forex.services.rates

import cats.{ Applicative, Monad }
import interpreters._

object Interpreters {
  def dummy[F[_]: Applicative]: Algebra[F] = new OneFrameDummy[F]()

  def live[F[_]: Monad](store: RatesStore[F]): Algebra[F] = new OneFrameLive[F](store)
}
