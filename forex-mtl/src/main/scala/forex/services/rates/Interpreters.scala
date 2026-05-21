package forex.services.rates

import cats.Applicative
import forex.config.OneFrameConfig
import interpreters._
import org.http4s.client.Client

object Interpreters {
  def dummy[F[_]: Applicative]: Algebra[F] = new OneFrameDummy[F]()
  def live[F[_]: Applicative](client: Client[F], config: OneFrameConfig): Algebra[F] =
    new OneFrameLive[F](client, config)

}
