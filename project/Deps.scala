import sbt._
import Keys._
import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._

object Deps {
  // hack to expand %%% in settings, needs .value in build.sbt
  import Def.{setting => dep}

  val scalaTest = dep("org.scalatest" %%% "scalatest" % "3.0.8")
  val scalajs = new {
    val dom = dep("org.scala-js" %%% "scalajs-dom" % "0.9.6")
  }
  val sloth = dep("com.github.cornerman.sloth" %%% "sloth" % "206b005")
  val mycelium = dep("com.github.cornerman.mycelium" %%% "mycelium" % "44906c6")
  val kittens = dep("org.typelevel" %%% "kittens" % "1.0.0")
  val akka = new {
    private val version = "2.5.11"
    val http = dep("com.typesafe.akka" %% "akka-http" % "10.1.0")
    val stream = dep("com.typesafe.akka" %% "akka-stream" % version)
    val actor = dep("com.typesafe.akka" %% "akka-actor" % version)
  }
  val boopickle = dep("io.suzaku" %%% "boopickle" % "1.3.1")
  val scribe = dep("com.outr" %%% "scribe" % "2.6.0")
  val monix = dep("io.monix" %%% "monix" % "3.0.0-RC1")
}
