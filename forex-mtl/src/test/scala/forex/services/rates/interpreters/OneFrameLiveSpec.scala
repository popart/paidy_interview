package forex.services.rates.interpreters

import cats.effect.IO
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.services.rates.RatesStore
import forex.services.rates.errors.Error
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.OffsetDateTime

class OneFrameLiveSpec extends AnyFlatSpec with Matchers {

  val pair: Rate.Pair = Rate.Pair(Currency.USD, Currency.JPY)
  val rate: Rate      = Rate(pair, Price(BigDecimal(110.0)), Timestamp(OffsetDateTime.parse("2024-01-01T00:00:00Z")))

  def staleStore(result: Option[Rate]): RatesStore[IO] = new RatesStore[IO] {
    def get(p: Rate.Pair): IO[Option[Rate]] = IO.pure(result)
    def isFresh: IO[Boolean]                = IO.pure(false)
  }

  def freshStore(result: Option[Rate]): RatesStore[IO] = new RatesStore[IO] {
    def get(p: Rate.Pair): IO[Option[Rate]] = IO.pure(result)
    def isFresh: IO[Boolean]                = IO.pure(true)
  }

  "OneFrameLive" should "return a rate when the store is fresh and the pair exists" in {
    val service = new OneFrameLive[IO](freshStore(Some(rate)))
    val result  = service.get(pair).unsafeRunSync()
    result shouldBe Right(rate)
  }

  it should "return StaleRates when the store is not fresh" in {
    val service = new OneFrameLive[IO](staleStore(Some(rate)))
    val result  = service.get(pair).unsafeRunSync()
    result shouldBe Left(Error.StaleRates)
  }

  it should "return PairNotFound when the pair is missing from the store" in {
    val service = new OneFrameLive[IO](freshStore(None))
    val result  = service.get(pair).unsafeRunSync()
    result shouldBe Left(Error.PairNotFound("USDJPY"))
  }
}
