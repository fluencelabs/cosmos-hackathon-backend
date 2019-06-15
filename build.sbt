import org.apache.ivy.core.module.descriptor.License

name := "http-scala-api"

version := "0.1"

scalaVersion := "2.12.8"

resolvers += Resolver.sonatypeRepo("releases")
addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.0")

licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt"))
resolvers += Resolver.bintrayRepo("fluencelabs", "releases")
// see good explanation https://gist.github.com/djspiewak/7a81a395c461fd3a09a6941d4cd040f2
scalacOptions ++= Seq("-Ypartial-unification", "-deprecation")
addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.0")

libraryDependencies ++= Seq(
  fs2,
  fs2rx,
  fs2io,
  sttp,
  sttpCirce,
  sttpFs2Backend,
  sttpCatsBackend,
  http4sDsl,
  http4sServer,
  http4sCirce,
  circeCore,
  circeGeneric,
  circeGenericExtras,
  circeParser,
  circeFs2,
  cats,
  catsEffect
)

val fs2Version = "1.0.4"
val fs2 = "co.fs2" %% "fs2-core" % fs2Version
val fs2rx = "co.fs2" %% "fs2-reactive-streams" % fs2Version
val fs2io = "co.fs2" %% "fs2-io" % fs2Version

val sttpVersion = "1.5.17"
val sttp = "com.softwaremill.sttp" %% "core" % sttpVersion
val sttpCirce = "com.softwaremill.sttp" %% "circe" % sttpVersion
val sttpFs2Backend = "com.softwaremill.sttp" %% "async-http-client-backend-fs2" % sttpVersion
val sttpCatsBackend = "com.softwaremill.sttp" %% "async-http-client-backend-cats" % sttpVersion

val http4sVersion = "0.20.0-M7"
val http4sDsl = "org.http4s" %% "http4s-dsl" % http4sVersion
val http4sServer = "org.http4s" %% "http4s-blaze-server" % http4sVersion
val http4sCirce = "org.http4s" %% "http4s-circe" % http4sVersion

val circeVersion = "0.11.1"
val circeCore = "io.circe" %% "circe-core" % circeVersion
val circeGeneric = "io.circe" %% "circe-generic" % circeVersion
val circeGenericExtras = "io.circe" %% "circe-generic-extras" % circeVersion
val circeParser = "io.circe" %% "circe-parser" % circeVersion
val circeFs2 = "io.circe" %% "circe-fs2" % "0.11.0"

val catsVersion = "1.6.0"
val cats = "org.typelevel" %% "cats-core" % catsVersion
val catsEffect = "org.typelevel" %% "cats-effect" % "1.3.0"
