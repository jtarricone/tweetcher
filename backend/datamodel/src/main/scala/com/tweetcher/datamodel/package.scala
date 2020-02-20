package com.tweetcher.datamodel

import cats.data._

import io.circe._
import io.circe.generic._
import io.circe.parser._
import io.circe.optics.JsonPath._

import io.jvm.uuid._
import java.time.{Instant, OffsetDateTime}

// see https://developer.twitter.com/en/docs/tweets/data-dictionary/overview/intro-to-tweet-json

final case class Hashtag(
  id: UUID,
  text: String,
  tweetId: UUID)

final case class UrlEntity(
  id: UUID,
  url: String,
  tweetId: UUID)

final case class Emoji(
  id: UUID,
  text: String,
  tweetId: UUID)

final case class Tweet(
  id: UUID,
  timestamp: Long,
  text: String,
  photos: Int,
  hashtags: List[Hashtag],
  urls: List[UrlEntity],
  emojis: List[Emoji],
)

object Tweet {
  def tupled = (Tweet.apply _).tupled

  final case class Shallow(
    id: UUID,
    timestamp: Long,
    text: String,
    photos: Int,
    )


  /* Parse the inbound JSON object into the comparably small slices of interest.
      In this case it was more expedient to just use circe's optics than to
      implement the various sets of case classes and derive codecs from them */
  def parseJsonBlob(blob: Json): Option[Tweet] = {
    // this regex will match any unicode outside language characters
    // strictly speaking emojis are a subset of this but for reporting purposes
    // they could reasonably be expected to be the most used
    val emoji_regx = "[^\u0000-\uFFFF]".r

    for {
      timestamp <- Some(OffsetDateTime.now().toEpochSecond)

      tweetText <- root.text.string.getOption(blob)

      photoCount <- (
        Some(
          root
            .entities
            .media
            .each
            .`type`
            .string
            .getAll(blob)
            .filter(_ == "photo")
            .size)
      )

      tweetId <- Some(UUID.randomUUID())

      emojis <- (
        root
          .text
          .string
          .getOption(blob)
          .flatMap(
            fullText =>
              Some(
                emoji_regx
                  .findAllMatchIn(fullText)
                  .map(_.toString)
                  .toList.map(s => Emoji(UUID.randomUUID(), s, tweetId))))
      )

      hashtags <- (
        Some(
          root
            .entities
            .hashtags
            .each
            .text
            .string
            .getAll(blob)
            .map(s => Hashtag(UUID.randomUUID(), s, tweetId)))
      )

      urls <- (
        Some(
          root
            .entities
            .urls
            .each
            .display_url
            .string
            .getAll(blob)
            .map(_ ++ "/") // this guarantees that the split operation will succeed
            .map(s => s.splitAt(s.indexOf("/"))._1)
            .map(s => UrlEntity(UUID.randomUUID(), s, tweetId)))
      )
    } yield {
      Tweet(tweetId, timestamp, tweetText, photoCount, hashtags, urls, emojis)
    }
  }
}

@JsonCodec
case class TweetStats(
  totalTweets: Long,
  tweetsPerSecond: Float,
  percentWithPhoto: Float,
  percentWithLink: Float,
  percentWithEmoji: Float,
  topEmojis: List[String],
  topLinks: List[String],
  topHashtags: List[String],
  )

object TweetStats {
  def tupled = (TweetStats.apply _).tupled
}
