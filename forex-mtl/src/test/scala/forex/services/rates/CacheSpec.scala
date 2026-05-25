package forex.services.rates

import cats.effect.IO
import cats.effect.concurrent.Ref
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.duration._

class CacheSpec extends AnyFunSuite {

  test("calls refreshAll on miss and returns the requested key") {
    val refreshAll: IO[Map[String, Int]] = IO.pure(Map("a" -> 1, "b" -> 2))

    val result = (for {
      cache <- Cache.create[IO, String, Int](1.minute, IO.pure(0L))
      v     <- cache.getOrRefresh("a", refreshAll)
    } yield v).unsafeRunSync()

    assert(result == Some(1))
  }

  test("returns cached value on hit without calling refreshAll") {
    val program = for {
      calls      <- Ref.of[IO, Int](0)
      refreshAll  = calls.update(_ + 1).as(Map("a" -> 1))
      cache      <- Cache.create[IO, String, Int](1.minute, IO.pure(0L))
      _          <- cache.getOrRefresh("a", refreshAll)
      _          <- cache.getOrRefresh("a", refreshAll)
      n          <- calls.get
    } yield n

    assert(program.unsafeRunSync() == 1)
  }

  test("calls refreshAll again after ttl expires") {
    val program = for {
      clock      <- Ref.of[IO, Long](0L)
      calls      <- Ref.of[IO, Int](0)
      refreshAll  = calls.update(_ + 1).as(Map("a" -> 1))
      cache      <- Cache.create[IO, String, Int](1.minute, clock.get)
      _          <- cache.getOrRefresh("a", refreshAll)
      _          <- clock.set(61.seconds.toMillis)
      _          <- cache.getOrRefresh("a", refreshAll)
      n          <- calls.get
    } yield n

    assert(program.unsafeRunSync() == 2)
  }

  test("returns None when the requested key is absent from the refreshed map") {
    val program = for {
      cache  <- Cache.create[IO, String, Int](1.minute, IO.pure(0L))
      result <- cache.getOrRefresh("missing", IO.pure(Map("a" -> 1)))
    } yield result

    assert(program.unsafeRunSync().isEmpty)
  }

  test("does not re-refresh for an absent key while the cache is still fresh") {
    val program = for {
      calls      <- Ref.of[IO, Int](0)
      refreshAll  = calls.update(_ + 1).as(Map("a" -> 1))
      cache      <- Cache.create[IO, String, Int](1.minute, IO.pure(0L))
      _          <- cache.getOrRefresh("a", refreshAll)
      _          <- cache.getOrRefresh("missing", refreshAll)
      n          <- calls.get
    } yield n

    assert(program.unsafeRunSync() == 1)
  }

  test("refreshAll populates all keys so subsequent lookups hit the cache") {
    val program = for {
      calls      <- Ref.of[IO, Int](0)
      refreshAll  = calls.update(_ + 1).as(Map("a" -> 1, "b" -> 2))
      cache      <- Cache.create[IO, String, Int](1.minute, IO.pure(0L))
      a          <- cache.getOrRefresh("a", refreshAll)
      b          <- cache.getOrRefresh("b", refreshAll)
      n          <- calls.get
    } yield (a, b, n)

    assert(program.unsafeRunSync() == ((Some(1), Some(2), 1)))
  }

}
