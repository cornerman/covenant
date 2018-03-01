import sbt._
import Keys._
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._

object Deps {
  // hack to expand %%% in settings, needs .value in build.sbt
  import Def.{setting => dep}

  val scalaTest = dep("org.scalatest" %%% "scalatest" % "3.0.4")
  val scalajs = new {
    val dom = dep("org.scala-js" %%% "scalajs-dom" % "0.9.4")
  }
  val sloth = dep("com.github.cornerman.sloth" %%% "sloth" % "228ac9c")
  val mycelium = dep("com.github.cornerman.mycelium" %%% "mycelium" % "0307069")
  val kittens = dep("org.typelevel" %%% "kittens" % "1.0.0-RC2")
  val akka = new {
    private val version = "2.5.8"
    val http = dep("com.typesafe.akka" %% "akka-http" % "10.1.0-RC1")
    val stream = dep("com.typesafe.akka" %% "akka-stream" % version)
    val actor = dep("com.typesafe.akka" %% "akka-actor" % version)
  }
  val boopickle = dep("io.suzaku" %%% "boopickle" % "1.2.6")
  val scribe = dep("com.outr" %%% "scribe" % "2.1.0")
  val monix = dep("io.monix" %%% "monix" % "3.0.0-M3")
}
