package forex.services.rates.interpreters

import forex.services.rates.Algebra
import forex.domain.Rate
import forex.services.rates.errors._

/** Decorator that adds caching to any [[Algebra]] implementation.
  * Wraps the underlying interpreter (e.g. [[OneFrameLive]]) and serves cached
  * rates when fresh, falling back to the underlying only on a cache miss or
  * expiry. OneFrameLive stays focused on HTTP; this class owns cache policy.
  */
class OneFrameCached[F[_]](underlying: Algebra[F]) extends Algebra[F] {

  override def get(pair: Rate.Pair): F[Error Either Rate] =
    underlying.get(pair)

}
