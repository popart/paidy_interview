package forex

import cats.effect.{ Concurrent, Resource, Timer }
import forex.config.ApplicationConfig
import forex.http.rates.RatesHttpRoutes
import forex.services._
import forex.services.rates.RatesStore
import forex.services.rates.interpreters.RatesStoreLive
import forex.programs._
import org.http4s._
import org.http4s.client.Client
import org.http4s.implicits._
import org.http4s.server.middleware.{ AutoSlash, Timeout }


class Module[F[_]: Concurrent: Timer] private (
    config: ApplicationConfig,
    store: RatesStore[F]
) {
  private val ratesService: RatesService[F] = RatesServices.live(store)
  private val ratesProgram: RatesProgram[F] = RatesProgram[F](ratesService)

  private val ratesHttpRoutes: HttpRoutes[F] = new RatesHttpRoutes[F](ratesProgram).routes

  type PartialMiddleware = HttpRoutes[F] => HttpRoutes[F]
  type TotalMiddleware   = HttpApp[F] => HttpApp[F]

  private val routesMiddleware: PartialMiddleware = { http: HttpRoutes[F] =>
    AutoSlash(http)
  }

  private val appMiddleware: TotalMiddleware = { http: HttpApp[F] =>
    Timeout(config.http.timeout)(http)
  }

  private val http: HttpRoutes[F] = ratesHttpRoutes

  val httpApp: HttpApp[F] = appMiddleware(routesMiddleware(http).orNotFound)
}

object Module {
  def resource[F[_]: Concurrent: Timer](config: ApplicationConfig, client: Client[F]): Resource[F, Module[F]] =
    RatesStoreLive.resource[F](client, config.oneFrame).map(new Module[F](config, _))
}
