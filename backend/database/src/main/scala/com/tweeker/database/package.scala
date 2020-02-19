package com.tweetcher.database

import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration.FiniteDuration
import scala.util.Properties

import cats._
import cats.effect._
import cats.effect.{Concurrent, ExitCode, IO, IOApp, Timer}
import cats.data._
import cats.implicits._

import fs2.concurrent._

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.ExecutionContexts
import doobie.hikari.HikariTransactor
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import org.http4s._
import org.http4s.client.blaze._
import org.http4s.client.oauth1
import org.http4s.implicits._

import jawnfs2._
import java.time._
import java.util.concurrent.{Executors, TimeUnit, ExecutorService}
import java.util.UUID

import com.tweetcher.datamodel._

object CustomTransactor {
  private val config = ConfigFactory.load()
  private val parallelismConfig = config.getConfig("parallelism")
  private val postgresConfig = config.getConfig("postgres")

  final case class TransactorConfig(
    dbName: String = postgresConfig.getString("dbName"),
    driver: String = "org.postgresql.Driver",
    postgresUrl: String = Properties.envOrElse(
      "POSTGRES_URL",
      s"jdbc:postgresql://database.service.${postgresConfig.getString("dbName")}.internal/"
    ),
    user: String = postgresConfig.getString("username"),
    password: String = postgresConfig.getString("password"),
    statementTimeout: String = "30000",
    maximumPoolSize: Int = 8,
    poolName: String = "Hikari-Pool",
    maybeInitSql: Option[String] = None,
    contextShift: ContextShift[IO] = IO.contextShift(
      ExecutionContext.fromExecutor(
        Executors.newFixedThreadPool(
          8,
          new ThreadFactoryBuilder().build()))))
  {
    val url = postgresUrl ++ dbName

    val initSql =
      maybeInitSql.getOrElse(s"SET statement_timeout = ${statementTimeout};")

    lazy val hikariDataSource = {
      val hikariConfig = new HikariConfig()
      hikariConfig.setPoolName(poolName)
      hikariConfig.setMaximumPoolSize(maximumPoolSize)
      hikariConfig.setConnectionInitSql(initSql)
      hikariConfig.setJdbcUrl(url)
      hikariConfig.setUsername(user)
      hikariConfig.setPassword(password)
      hikariConfig.setDriverClassName(driver)

      new HikariDataSource(hikariConfig)
    }

    val connectionEC: ExecutionContext =
      ExecutionContext.fromExecutor(
        Executors.newFixedThreadPool(
          Properties.envOrElse("HIKARI_CONNECTION_THREADS", "8").toInt,
          new ThreadFactoryBuilder().setNameFormat("db-connection-%d").build()
        )
      )

    val transactionEC: ExecutionContext =
      ExecutionContext.fromExecutor(
        Executors.newFixedThreadPool(
          Properties.envOrElse("HIKARI_TRANSACTION_THREADS", "8").toInt,
          new ThreadFactoryBuilder().setNameFormat("db-transaction-%d").build()
        )
      )
  }

  def buildTransactor(
      config: TransactorConfig = TransactorConfig()
  ): HikariTransactor[IO] = {
    implicit val cs: ContextShift[IO] = config.contextShift
    HikariTransactor.apply[IO](
      config.hikariDataSource,
      config.connectionEC,
      config.transactionEC
    )
  }

  def buildTransactorResource(
      config: TransactorConfig = TransactorConfig()
  ): Resource[IO, HikariTransactor[IO]] = {
    implicit val cs: ContextShift[IO] = config.contextShift
    HikariTransactor.newHikariTransactor[IO](
      config.driver,
      config.postgresUrl,
      config.user,
      config.password,
      config.connectionEC,
      config.transactionEC
    )
  }

  def nonHikariTransactor(config: TransactorConfig) = {
    implicit val cs: ContextShift[IO] = config.contextShift
    Transactor.fromDriverManager[IO](
      "org.postgresql.Driver",
      config.url,
      config.user,
      config.password
    )
  }

  lazy val xaResource: Resource[IO, HikariTransactor[IO]] =
    buildTransactorResource()

}

object TweetDao {

  val tableName = Fragment.const("twitter_tweet")

  /* generic type for "cross-referencing" records representing
      many-to-many relation between two distinct record types,
      e.g. Emoji<->Tweets */
  type CrossRefRecord = (UUID, String, UUID)

  /* bulk insert the tweet and all derived hashtag/emoji/url instances */
  def insertAll(newTweet: Tweet): ConnectionIO[Tweet.Shallow] = {
    for {
      _ <- (
        (fr"INSERT INTO"
          ++ tableName
          ++ fr"(id, timestamp, text, photos)"
          ++ fr"VALUES (${newTweet.id}, ${newTweet.timestamp}, ${newTweet.text}, ${newTweet.photos})")
        .update
        .withUniqueGeneratedKeys[Tweet.Shallow]("id", "timestamp", "text", "photos")
      )
      _ <- (
        Update[CrossRefRecord]("INSERT INTO twitter_hashtags (id, text, tweet_id) VALUES (?, ?, ?)")
          .updateMany(newTweet.hashtags.map(d => (d.id, d.text, d.tweetId)))
      )

      _ <- (
        Update[CrossRefRecord]("INSERT INTO twitter_emoji (id, text, tweet_id) VALUES (?, ?, ?)")
          .updateMany(newTweet.emojis.map(d => (d.id, d.text, d.tweetId)))
      )

      _ <- (
        Update[CrossRefRecord]("INSERT INTO twitter_urlentity (id, url, tweet_id) VALUES (?, ?, ?)")
          .updateMany(newTweet.urls.map(d => (d.id, d.url, d.tweetId)))
      )

      result <- (
        sql"SELECT id, timestamp, text, photos FROM twitter_tweet WHERE id=${newTweet.id}"
          .query[Tweet.Shallow]
          .unique
      )
    } yield {
      result
    }
  }

  /* finds the top N emojis ranked by observation count */
  def getTopEmojis(n: Int): ConnectionIO[List[String]] = {
    sql"SELECT text from twitter_emoji GROUP BY text ORDER BY count(*) DESC LIMIT $n"
      .query[String]
      .to[List]
  }

  /* computes the percentage of tweets that contain an emoji */
  def getEmojiPercent(): ConnectionIO[Float] = {
   for {
     emojiTweets <- (
       sql"SELECT COUNT(*) FROM twitter_tweet tw INNER JOIN twitter_emoji em ON tw.id=em.tweet_id HAVING COUNT(em.id) > 0"
         .query[Long]
         .unique
     )
    allTweets <- sql"SELECT COUNT(*) FROM twitter_tweet".query[Long].unique
   } yield {
    emojiTweets.toFloat / allTweets.toFloat
   }
  }

  /* computes the percentage of tweets that contain a URL */
  def getUrlPercent(): ConnectionIO[Float] = {
   for {
     urlTweets <- (
       sql"SELECT COUNT(*) FROM twitter_tweet tw INNER JOIN twitter_urlentity url ON tw.id=url.tweet_id HAVING COUNT(url.id) > 0"
         .query[Long]
         .unique
     )
    allTweets <- sql"SELECT COUNT(*) FROM twitter_tweet".query[Long].unique
   } yield {
    urlTweets.toFloat / allTweets.toFloat
   }
  }

  /* computes the percentage of tweets that contain a photo link */
  def getPhotoPercent(): ConnectionIO[Float] = {
   for {
    photoTweets <- sql"SELECT COUNT(*) FROM twitter_tweet WHERE photos > 0".query[Long].unique
    allTweets <- sql"SELECT COUNT(*) FROM twitter_tweet".query[Long].unique
   } yield {
    photoTweets.toFloat / allTweets.toFloat
   }
  }

  /* computes the most frequently observed hashtags */
  def getTopHashtags(n: Int): ConnectionIO[List[String]] = {
    sql"SELECT text FROM twitter_hashtags GROUP BY text ORDER BY count(*) DESC LIMIT $n"
      .query[String]
      .to[List]
  }

  /* computes the most frequently observed URLs */
  def getTopUrls(n: Int): ConnectionIO[List[String]] = {
    sql"SELECT url from twitter_urlentity GROUP BY url ORDER BY count(*) DESC LIMIT $n"
      .query[String]
      .to[List]
  }

  /* computes the total number of tweets recorded */
  def getTweetCount(): ConnectionIO[Long] = {
    sql"SELECT count(*) from twitter_tweet".query[Long].unique
  }

  /* computes the running average tweet rate per-second */
  def getTweetAveragePerSecond(): ConnectionIO[Float] = {
    sql"SELECT AVG(total.second_total) FROM (SELECT COUNT(*) AS second_total FROM twitter_tweet GROUP BY timestamp) total"
      .query[Float]
      .unique
  }

  def getTweetStats(): ConnectionIO[TweetStats] = {
    val query = for {
      tweetCount <- getTweetCount
      tweetsPerSecond <- getTweetAveragePerSecond
      percentWithPhoto <- getPhotoPercent
      percentWithLink <- getUrlPercent
      percentWithEmoji <- getEmojiPercent
      topEmojis <- getTopEmojis(10)
      topLinks <- getTopUrls(10)
      topHashtags <- getTopHashtags(10)
    } yield {
      TweetStats(
        tweetCount,
        tweetsPerSecond,
        percentWithPhoto,
        percentWithLink,
        percentWithEmoji,
        topEmojis,
        topLinks,
        topHashtags)
    }
    query
  }
}
