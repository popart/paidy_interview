package forex.services.rates

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.concurrent.duration.FiniteDuration

class Cache[F[_]: Sync, K, V](
    ref: Ref[F, Cache.State[K, V]],
    ttl: FiniteDuration,
    now: F[Long]
) {

  def getOrRefresh(key: K, refreshAll: F[Map[K, V]]): F[Option[V]] =
    for {
      currentTime <- now
      state       <- ref.get
      fresh = state.refreshedAt.exists(t => currentTime - t < ttl.toMillis)
      result <- if (fresh) Sync[F].pure(state.entries.get(key))
                else refreshAll.flatMap(m => ref.set(Cache.State(m, Some(currentTime))).as(m.get(key)))
    } yield result
}

object Cache {
  final case class State[K, V](entries: Map[K, V], refreshedAt: Option[Long])

  def create[F[_]: Sync, K, V](ttl: FiniteDuration, now: F[Long]): F[Cache[F, K, V]] =
    Ref.of[F, State[K, V]](State(Map.empty, None)).map(new Cache(_, ttl, now))
}
