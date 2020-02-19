
import java.util.concurrent.{Executors}

import com.google.common.util.concurrent.ThreadFactoryBuilder

import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.global

import cats.effect._
import cats.implicits._
import cats.effect.{Concurrent, ExitCode, IO, IOApp, Timer}

import fs2.io.stdout
import fs2.concurrent._
import fs2.Stream

import org.http4s._
import org.http4s.client.blaze._
import org.http4s.client.oauth1
import org.http4s.implicits._

import jawnfs2._

import doobie._
import doobie.implicits._

import org.http4s.dsl.io._

import com.tweetcher.datamodel._
import com.tweetcher.database._

class TwitterReader[F[_]: ConcurrentEffect : ContextShift] {
  private val config = ConfigFactory.load()
  private val parallelismConfig = config.getConfig("parallelism")
  private val twitterApiConfig = config.getConfig("twitterApi")

  implicit val timer: Timer[IO] = IO.timer(global)
  implicit val f = new io.circe.jawn.CirceSupportParser(None, false).facade

  /* Implicit declaration to specify concurrency configuration of execution context */
  implicit val contextShift: ContextShift[IO] =
    IO.contextShift(
      ExecutionContext.fromExecutor(
        Executors.newFixedThreadPool(
          parallelismConfig.getInt("streamConcurrency"),
          new ThreadFactoryBuilder().build())))

  /* Twitter API credentials */
  val consumerKey = twitterApiConfig.getString("consumerKey")
  val consumerSecret = twitterApiConfig.getString("consumerSecret")
  val accessToken = twitterApiConfig.getString("accessToken")
  val accessSecret = twitterApiConfig.getString("accessSecret")

  // doobie transactor
  val xaConfig = CustomTransactor.TransactorConfig(
    contextShift = IO.contextShift(
      ExecutionContext.fromExecutor(
        Executors.newFixedThreadPool(
          parallelismConfig.getInt("dbThreadPoolSize"),
          new ThreadFactoryBuilder().build()))))

  val xa = CustomTransactor.buildTransactor(xaConfig)

  /* sign Twitter API request with developer credentials */
  def signRequest(consumerKey: String, consumerSecret: String, accessToken: String, accessSecret: String)
          (req: Request[F]): F[Request[F]] = {
    val consumer = oauth1.Consumer(consumerKey, consumerSecret)
    val token    = oauth1.Token(accessToken, accessSecret)
    oauth1.signRequest(req, consumer, callback = None, verifier = None, token = Some(token))
  }

  def jsonStream(consumerKey: String, consumerSecret: String, accessToken: String, accessSecret: String)
            (req: Request[F]): Stream[F, Unit] =
    for {
      client <- BlazeClientBuilder(global).stream

      requestStream <- Stream.eval(signRequest(consumerKey, consumerSecret, accessToken, accessSecret)(req))

      result <- (
        client
          .stream(requestStream)
          .flatMap(
            _.body
              .chunks
              .parseJsonStream // parse byte chunks into JSON object
              .map(Tweet.parseJsonBlob(_)) // parse JSON object into internal trimmed-down Tweet representation
              .map(maybeTweet => { // map parse result to appropriate effect
                maybeTweet match {
                  case Some(value) => { // effect: DB insert
                    IO {
                      val result = for {
                        query <- TweetDao.insertAll(value).transact(xa)
                      } yield {
                        query
                      }

                      result.unsafeRunSync()
                    }
                  }
                  case None => // effect: stdout log
                    IO {
                      println("Skipping tweet...")
                    }
                }
            }.unsafeRunSync)
          )
      )
    } yield result

  /* create API stream */
  def stream(blocker: Blocker): Stream[F, Unit] = {
    val req = Request[F](Method.GET, uri"https://stream.twitter.com/1.1/statuses/sample.json")
    jsonStream(consumerKey, consumerSecret, accessToken, accessSecret)(req)
  }

  /* build stream and start processing */
  def run: F[Unit] =
    Stream
      .resource(Blocker[F])
      .flatMap { blocker => stream(blocker) }
      .compile
      .drain
}

object Main extends IOApp {
  def run(args: List[String]) =
    (new TwitterReader[IO]).run.as(ExitCode.Success)
}
