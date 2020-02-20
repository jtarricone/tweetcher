package com.tweetcher.api

import java.util.concurrent.{Executors, TimeUnit, ExecutorService}

import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContext

import cats.effect._
import cats.implicits._

import doobie._
import doobie.implicits._

import io.circe.syntax._

import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.syntax.kleisli._

import com.tweetcher.datamodel._
import com.tweetcher.database._

object TweetcherService extends IOApp {
  private val config = ConfigFactory.load()
  private val parallelismConfig = config.getConfig("parallelism")

  // doobie transactor
  val xaConfig = CustomTransactor.TransactorConfig(
    contextShift = IO.contextShift(
      ExecutionContext.fromExecutor(
        Executors.newFixedThreadPool(
          parallelismConfig.getInt("dbThreadPoolSize"),
          new ThreadFactoryBuilder().build()))))

  val xa = CustomTransactor.buildTransactor(xaConfig)

  val routes = HttpRoutes.of[IO] {
    // test route to confirm the service is functional in the most basic sense
    case GET -> Root / "test" / testValue =>
      Ok(s"Is this what you just gave me? --> $testValue")

    // API endpoint to get ongoing Tweet statistics of interest
    case GET -> Root / "tweets" / "stats" =>
      TweetDao.getTweetStats().transact(xa).flatMap {
        case (ts: TweetStats) => Ok(ts asJson)
        case _ => BadRequest("Could not derive TweetStats")
      }
  }

  def run(args: List[String]): IO[ExitCode] =
    BlazeServerBuilder[IO]
      .bindHttp(8080, "0.0.0.0")
      .withHttpApp(routes.orNotFound)
      .serve
      .compile
      .drain
      .as(ExitCode.Success)
}
