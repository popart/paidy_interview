package forex.http
package rates

import cats.effect.Sync
import cats.syntax.apply._
import cats.syntax.flatMap._
import forex.programs.RatesProgram
import forex.programs.rates.errors.{ Error => ProgramError }
import forex.programs.rates.{ Protocol => RatesProgramProtocol }
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

class RatesHttpRoutes[F[_]: Sync](rates: RatesProgram[F]) extends Http4sDsl[F] {

  import Converters._, QueryParams._, Protocol._

  private[http] val prefixPath = "/rates"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root :? FromQueryParam(fromV) +& ToQueryParam(toV) =>
      (fromV, toV).mapN(RatesProgramProtocol.GetRatesRequest(_, _)).fold(
        failures => BadRequest(ErrorResponse(failures.map(_.sanitized).toList.mkString(", "))),
        req =>
          if (req.from == req.to)
            BadRequest(ErrorResponse("from and to currencies must differ"))
          else
            rates.get(req).flatMap {
              case Right(rate)                        => Ok(rate.asGetApiResponse)
              case Left(ProgramError.StaleRates)      => ServiceUnavailable(ErrorResponse("rates are stale; try again shortly"))
              case Left(ProgramError.PairNotFound(p)) => NotFound(ErrorResponse(s"no rate for pair $p"))
            }
      )
  }

  val routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

}
