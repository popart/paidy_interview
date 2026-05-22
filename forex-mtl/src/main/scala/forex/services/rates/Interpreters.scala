package forex.services.rates

import cats.Applicative
import cats.effect.Sync
import forex.config.OneFrameConfig
import interpreters._
import org.http4s.client.Client

object Interpreters {
  def dummy[F[_]: Applicative]: Algebra[F] = new OneFrameDummy[F]()
  // F[_] => F is a type constructor that takes one *type* argument, e.g. IO, not String
  // : Sync => F "implements" Sync, i.e. F has a Sync instance--Sync[F], e.g. Sync[IO]
  //   (Sync[IO] defined in the cats-effect IO companion object)
  def live[F[_]: Sync](client: Client[F], config: OneFrameConfig): Algebra[F] =
    new OneFrameLive[F](client, config)

}
