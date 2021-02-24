import sbt._
import Keys._
import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._

object Deps {
  // hack to expand %%% in settings, needs .value in build.sbt
  import Def.{setting => dep}

  val scalaTest = dep("org.scalatest" %%% "scalatest" % "3.2.2")
  val scalajs = new {
    val dom = dep("org.scala-js" %%% "scalajs-dom" % "1.0.0")
  }
  val sloth = dep("com.github.cornerman" %%% "sloth" % "0.3.0")
  val mycelium = dep("com.github.cornerman.mycelium" %%% "mycelium" % "201805f")
  val kittens = dep("org.typelevel" %%% "kittens" % "2.1.0")
  val akka = new {
    private val version = "2.6.6"
    val http = dep("com.typesafe.akka" %% "akka-http" % "10.1.14")
    val stream = dep("com.typesafe.akka" %% "akka-stream" % version)
    val actor = dep("com.typesafe.akka" %% "akka-actor" % version)
  }
  val boopickle = dep("io.suzaku" %%% "boopickle" % "1.3.3")
  val scribe = dep("com.outr" %%% "scribe" % "2.7.13")
  val monix = dep("io.monix" %%% "monix" % "3.2.2")
}
