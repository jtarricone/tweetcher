# tweetcher (Tweet + Watcher)

A Tweet-Watcher built as an exercise to learn more about `fs2` streams.

## Usage

- Open `/.env` in an editor and add some Twitter API credentials.
- Build the artifacts:
  - `docker-compose up sbt-compile`
  - in another terminal, `docker-compose exec sbt-compile "/bin/sh"` and then `sbt assembly` to build the assemblies
- Spin up the database with `docker-compose up postgres`
  - in another terminal, run the initial migrations: `docker-compose up run-migrations`
- With 2 terminals:
  - `docker-compose up run-reader` to spin up the Twitter API reader
  - `docker-compose up api-server` to spin up the Http4s server
- In a browser, browse to `http://localhost:8080/tweet/stats` to see the summary (refresh to see ongoing results)
  - the API can also be reached with a `curl` or the like, but browsers will render the emojis while terminals often will not

## Example

![Sample](https://github.com/jtarricone/tweetcher/blob/master/misc/example.png)
