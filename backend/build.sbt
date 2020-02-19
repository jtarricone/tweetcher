val Http4sVersion = "0.21.1"
val monocleVersion = "1.5.0"
val freedslVersion = "0.26"
val ScalatestVersion = "3.0.5"
val ScalacticVersion = "3.0.5"
val RefinedCatsVersion = "0.9.3"
val ScalaVersion = "2.12.8"
val LinterVersion = "0.1.17"
val MacroParadiseVersion = "2.1.0"
val DoobieVersion = "0.7.0"
val FlywayVersion = "5.2.4"
val CatsVersion = "1.6.0"
val CatsEffectVersion = "1.0.0"
val LogbackVersion = "1.2.3"
val CirceVersion = "0.13.0"

lazy val sharedSettings = Seq(
    organization := "na",
    version := "0.0.1",
    scalaVersion := ScalaVersion,
    scalacOptions := Seq(
      "-deprecation",
      "-unchecked",
      "-feature",
      "-language:implicitConversions",
      "-language:reflectiveCalls",
      "-language:higherKinds",
      "-language:postfixOps",
      "-language:existentials",
      "-language:experimental.macros",
      "-Yrangepos",
    ),
    assemblyMergeStrategy in assembly := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case x => MergeStrategy.first
    },
    libraryDependencies ++= Seq(
      "com.google.guava" % "guava" % "28.1-jre",
      "io.circe"             %% "circe-generic"       % CirceVersion,
      "io.circe"             %% "circe-literal"       % CirceVersion,
      "io.circe"             %% "circe-parser"       % CirceVersion,
      "io.circe"             %% "circe-refined"       % CirceVersion,
      "io.circe"             %% "circe-optics"       % "0.12.0",
      "io.jvm.uuid" %% "scala-uuid" % "0.3.1",
      "org.typelevel"   %% "cats-core"           % CatsVersion,
      "org.typelevel"   %% "cats-effect"         % CatsEffectVersion,
      "org.http4s"      %% "http4s-blaze-server" % Http4sVersion,
      "org.http4s"      %% "http4s-blaze-client" % Http4sVersion,
      "org.http4s"      %% "http4s-circe"        % Http4sVersion,
      "org.http4s"      %% "http4s-core"         % Http4sVersion,
      "org.http4s"      %% "http4s-dsl"          % Http4sVersion,
      "org.scalactic"   %% "scalactic"           % ScalacticVersion,
      "org.scalatest"   %% "scalatest"           % ScalatestVersion % "test",
      "org.tpolecat"    %% "doobie-core"         % DoobieVersion,
      "org.tpolecat"    %% "doobie-postgres"     % DoobieVersion,
      "org.tpolecat"    %% "doobie-hikari"       % DoobieVersion, 
      "org.flywaydb"    % "flyway-core"          % FlywayVersion,
      "ch.qos.logback"  % "logback-classic"      % LogbackVersion,
      "com.typesafe" % "config" % "1.4.0"

    ),
    addCompilerPlugin("org.psywerx.hairyfotr" %% "linter" % LinterVersion),
    addCompilerPlugin("org.scalamacros" % "paradise" % MacroParadiseVersion cross CrossVersion.full),
)
lazy val root = project
  .in(file("."))
  .settings(sharedSettings: _*)
  .aggregate(api, database, datamodel, reader)

lazy val api = project
  .in(file("api"))
  .dependsOn(database, datamodel % "compile->compile")
  .settings(sharedSettings ++ Seq(
              fork in run := true,
              connectInput in run := true,
              cancelable in Global := true))

lazy val reader = project
  .in(file("reader"))
  .dependsOn(database, datamodel % "compile->compile")
  .settings(sharedSettings ++ Seq(
            assemblyJarName in assembly := "reader.jar"),
            mainClass in (Compile, assembly) := Some("com.tweetcher.reader.Main"),
            name := "reader",
            javaOptions += "-Xmx1G",
            fork in run := true)

lazy val database = project
  .in(file("database"))
  .dependsOn(datamodel)
  .settings(sharedSettings ++ Seq(
              assemblyJarName in assembly := "database.jar"))

lazy val datamodel = project
  .in(file("datamodel"))
  .settings(sharedSettings ++ Seq(
              assemblyJarName in assembly := "datamodel.jar"))

resolvers += Resolver.bintrayRepo("ovotech", "maven")
resolvers += Resolver.sonatypeRepo("releases")
resolvers += Resolver.sonatypeRepo("public")
resolvers += Resolver.bintrayRepo("projectseptemberinc", "maven")
