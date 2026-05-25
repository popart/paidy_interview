package forex.services.rates.interpreters

import cats.effect.IO
import forex.domain.{ Currency, Rate }
import forex.services.rates.interpreters.{ OneFrameCached, OneFrameDummy }
import org.scalatest.funsuite.AnyFunSuite

class OneFrameCachedSpec extends AnyFunSuite {

  test("returns a rate for a valid pair") {
    val pair = Rate.Pair(Currency.USD, Currency.JPY)
    val cached = new OneFrameCached[IO](new OneFrameDummy[IO])
    val result = cached.get(pair).unsafeRunSync()
    assert(result.isRight)
  }

}
