package forex.services.rates

import cats.Applicative
import cats.effect.{ Clock, Sync }
import forex.config.OneFrameConfig
import interpreters._
import org.http4s.client.Client

object Interpreters {
  def dummy[F[_]: Applicative]: Algebra[F] = new OneFrameDummy[F]()

  def live[F[_]: Sync: Clock](client: Client[F], config: OneFrameConfig): F[Algebra[F]] =
    OneFrameLive.create[F](client, config)
}
