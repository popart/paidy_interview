package forex.services.rates.interpreters

import cats.effect.{ ContextShift, IO, Resource, Timer }
import cats.effect.concurrent.Ref
//import forex.Slow
import forex.config.OneFrameConfig
import forex.domain.{ Currency, Rate }
import org.http4s._
import org.http4s.client.Client
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.tagobjects.Slow

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._

class RatesStoreLiveSpec extends AnyFlatSpec with Matchers {
  implicit val cs: ContextShift[IO] = IO.contextShift(global)
  implicit val timer: Timer[IO]     = IO.timer(global)

  val config: OneFrameConfig = OneFrameConfig(
    host = "localhost",
    port = 8080,
    token = "test-token",
    ttl = 5.minutes,
    refreshInterval = 4.minutes,
    requestTimeout = 5.seconds,
  )

  val stubJson: String =
    """[
      |{"from":"USD","to":"JPY","bid":1.0,"ask":1.0,"price":110.0,"time_stamp":"2024-01-01T00:00:00Z"},
      |{"from":"JPY","to":"USD","bid":1.0,"ask":1.0,"price":0.009,"time_stamp":"2024-01-01T00:00:00Z"},
      |{"from":"EUR","to":"USD","bid":1.0,"ask":1.0,"price":1.08,"time_stamp":"2024-01-01T00:00:00Z"}
      |]""".stripMargin

  def stubClient(capturedUri: Ref[IO, Option[Uri]], responseBody: String): Client[IO] =
    Client.fromHttpApp(HttpApp[IO] { req =>
      capturedUri.set(Some(req.uri)).as(
        Response[IO](Status.Ok).withEntity(responseBody)
      )
    })

  "RatesStoreLive" should "call the correct One-Frame URL on resource init" in {
    val result = (for {
      captured <- Resource.eval(Ref.of[IO, Option[Uri]](None))
      _        <- RatesStoreLive.resource[IO](stubClient(captured, stubJson), config)
      uri      <- Resource.eval(captured.get)
    } yield uri).use(IO.pure).unsafeRunSync()

    result shouldBe defined
    val uri = result.get
    uri.host.map(_.value) shouldBe Some("localhost")
    uri.path.renderString shouldBe "/rates"
    val pairs = uri.multiParams.getOrElse("pair", Nil)
    pairs should contain("USDJPY")
    pairs should contain("JPYUSD")
  }

  it should "return a rate for a known pair after startup" in {
    val result = RatesStoreLive.resource[IO](stubClient(Ref.unsafe(None), stubJson), config)
      .use(store => store.get(Rate.Pair(Currency.USD, Currency.JPY)))
      .unsafeRunSync()

    result shouldBe defined
  }

  it should "return None for a pair not in the response" in {
    val result = RatesStoreLive.resource[IO](stubClient(Ref.unsafe(None), stubJson), config)
      .use(store => store.get(Rate.Pair(Currency.AUD, Currency.CAD)))
      .unsafeRunSync()

    result shouldBe None
  }

  it should "hit the upstream client more than once as the polling fiber ticks" in {
    val fastConfig = config.copy(refreshInterval = 100.milliseconds)
    val result = (for {
      count <- Resource.eval(Ref.of[IO, Int](0))
      countingClient = Client.fromHttpApp(HttpApp[IO] { _ =>
        count.update(_ + 1).as(Response[IO](Status.Ok).withEntity(stubJson))
      })
      _ <- RatesStoreLive.resource[IO](countingClient, fastConfig)
      _ <- Resource.eval(IO.sleep(350.milliseconds))
      n <- Resource.eval(count.get)
    } yield n).use(IO.pure).unsafeRunSync()

    result should be >= 3
  }

  it should "report isFresh immediately after startup" in {
    val result = RatesStoreLive.resource[IO](stubClient(Ref.unsafe(None), stubJson), config)
      .use(_.isFresh)
      .unsafeRunSync()

    result shouldBe true
  }

  it should "populate the cache after retrying a transient failure" taggedAs Slow in {
    val attempts = new AtomicInteger(0)
    val retryClient = Client.fromHttpApp(HttpApp[IO] { _ =>
      if (attempts.incrementAndGet() <= 1) IO.raiseError(new Exception("transient"))
      else IO.pure(Response[IO](Status.Ok).withEntity(stubJson))
    })
    val result = RatesStoreLive.resource[IO](retryClient, config)
      .use(store => store.get(Rate.Pair(Currency.USD, Currency.JPY)))
      .unsafeRunSync()

    attempts.get() shouldBe 2
    result shouldBe defined
  }

  it should "fail resource acquisition when configured with an invalid host" taggedAs Slow in {
    val badConfig = config.copy(host = "not a valid host:with:colons")
    val result = RatesStoreLive.resource[IO](stubClient(Ref.unsafe(None), stubJson), badConfig)
      .use(_ => IO.unit)
      .attempt
      .unsafeRunSync()

    result shouldBe a[Left[_, _]]
    result.left.toOption.get.getMessage should include("Invalid")
  }

  it should "fail resource acquisition when upstream returns malformed JSON" taggedAs Slow in {
    val badClient = Client.fromHttpApp(HttpApp[IO] { _ =>
      IO.pure(Response[IO](Status.Ok).withEntity("not json"))
    })
    val result = RatesStoreLive.resource[IO](badClient, config)
      .use(_ => IO.unit)
      .attempt
      .unsafeRunSync()

    result shouldBe a[Left[_, _]]
  }

  it should "retry before failing on malformed JSON" taggedAs Slow in {
    val attempts = new AtomicInteger(0)
    val badClient = Client.fromHttpApp(HttpApp[IO] { _ =>
      IO.pure(Response[IO](Status.Ok).withEntity({ attempts.incrementAndGet(); "not json" }))
    })
    RatesStoreLive.resource[IO](badClient, config)
      .use(_ => IO.unit)
      .attempt
      .unsafeRunSync()

    attempts.get() shouldBe 3 // 1 initial + 2 retries
  }

  it should "report stale after ttl has elapsed" in {
    val fakeNow = Ref.unsafe[IO, Long](0L)
    val noopClient = Client.fromHttpApp(HttpApp[IO] { _ =>
      IO.pure(Response[IO](Status.Ok).withEntity(stubJson))
    })
    val result = (for {
      ref   <- cats.effect.concurrent.Ref.of[IO, RatesStoreLive.Snapshot](RatesStoreLive.Snapshot(Map.empty, None))
      store = new RatesStoreLive[IO](ref, noopClient, config, fakeNow.get)
      _     <- store.refresh
      _     <- fakeNow.set(config.ttl.toMillis + 1)
      stale <- store.isFresh
    } yield stale).unsafeRunSync()

    result shouldBe false
  }
}
