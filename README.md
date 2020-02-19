# tweetcher (Tweet + Watcher)

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


## Notes on Enhancements

### Scalability

As-is, stream processing is thread-bound, so substantially more complex processing (e.g. deriving more information from each Tweet) or substantially higher volume would eventually present problems. From an architecture standpoint, I think the answer to this is decoupling the Twitter API sink and the Tweet processing. 

Concretely, that means that there would be three primary components replacing the current stream processor:
- a `Reader` which pushes results from Twitter in either an unmodified or very lightly modified form to some high-performance sink
- a `Processor` which pulls tweets from some sink and processes them to derive or enrich data of interest and ultimately persist those data
- a `Sink` which acts essentially as a queue for any number of `Reader`s to push incoming tweets into and any number of `Processor`s to pull tweets from

From the outset, the usage of the `Sink` seems like a good use-case for something like RabbitMQ or possibly Apache Kafka. The `Reader` and `Processor` would probably be close to the current implementation, but with the two responsibilities split into separate apps that could be deployed/scaled differently.
