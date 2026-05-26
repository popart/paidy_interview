package forex.domain

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CurrencySpec extends AnyFlatSpec with Matchers {

  "Currency.fromString" should "parse known currencies case-insensitively" in {
    Currency.fromString("USD") shouldBe Right(Currency.USD)
    Currency.fromString("usd") shouldBe Right(Currency.USD)
    Currency.fromString("Usd") shouldBe Right(Currency.USD)
  }

  it should "parse all supported currencies" in {
    Currency.all.foreach { c =>
      Currency.fromString(c.toString) shouldBe Right(c)
    }
  }

  it should "return Left for an unknown currency" in {
    Currency.fromString("XYZ") shouldBe Left("unknown currency: XYZ")
  }

  it should "return Left for an empty string" in {
    Currency.fromString("") shouldBe a[Left[_, _]]
  }
}
