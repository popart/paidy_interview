package forex.http.rates

import cats.effect.IO
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.programs.rates.errors.{ Error => ProgramError }
import forex.programs.rates.Algebra
import io.circe.parser
import org.http4s._
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.OffsetDateTime

class RatesHttpRoutesSpec extends AnyFlatSpec with Matchers {

  def routesFor(program: Algebra[IO]): HttpRoutes[IO] =
    new RatesHttpRoutes[IO](program).routes

  def run(routes: HttpRoutes[IO], req: Request[IO]): Response[IO] =
    routes.run(req).value.unsafeRunSync().getOrElse(Response.notFound)

  val stubRate: Rate = Rate(
    Rate.Pair(Currency.USD, Currency.JPY),
    Price(BigDecimal(110.0)),
    Timestamp(OffsetDateTime.parse("2024-01-01T00:00:00Z"))
  )

  val successProgram: Algebra[IO] = _ => IO.pure(Right(stubRate))
  val notFoundProgram: Algebra[IO] = _ => IO.pure(Left(ProgramError.PairNotFound("AUDCAD")))
  val staleProgram: Algebra[IO] = _ => IO.pure(Left(ProgramError.StaleRates))

  "GET /rates" should "return 200 with rate for a valid pair" in {
    val resp = run(routesFor(successProgram), Request[IO](uri = uri"/rates?from=USD&to=JPY"))
    resp.status shouldBe Status.Ok
    val body = resp.as[String].unsafeRunSync()
    val json = parser.parse(body).toOption.get
    json.hcursor.get[String]("from").toOption shouldBe Some("USD")
    json.hcursor.get[String]("to").toOption shouldBe Some("JPY")
  }

  it should "return 400 with error message for an unknown currency" in {
    val resp = run(routesFor(successProgram), Request[IO](uri = uri"/rates?from=XYZ&to=USD"))
    resp.status shouldBe Status.BadRequest
    val body = resp.as[String].unsafeRunSync()
    parser.parse(body).toOption.get.hcursor.get[String]("error").toOption.get should include("unknown currency")
  }

  it should "return 400 when both params are invalid" in {
    val resp = run(routesFor(successProgram), Request[IO](uri = uri"/rates?from=FOO&to=BAR"))
    resp.status shouldBe Status.BadRequest
  }

  it should "return 404 when the pair is not in the cache" in {
    val resp = run(routesFor(notFoundProgram), Request[IO](uri = uri"/rates?from=AUD&to=CAD"))
    resp.status shouldBe Status.NotFound
    val body = resp.as[String].unsafeRunSync()
    parser.parse(body).toOption.get.hcursor.get[String]("error").toOption.get should include("no rate")
  }

  it should "return 503 when rates are stale" in {
    val resp = run(routesFor(staleProgram), Request[IO](uri = uri"/rates?from=USD&to=JPY"))
    resp.status shouldBe Status.ServiceUnavailable
    val body = resp.as[String].unsafeRunSync()
    parser.parse(body).toOption.get.hcursor.get[String]("error").toOption.get should include("stale")
  }

  it should "return 404 when query params are missing" in {
    val resp = run(routesFor(successProgram), Request[IO](uri = uri"/rates?from=USD"))
    resp.status shouldBe Status.NotFound
  }
}
