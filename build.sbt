inThisBuild(Seq(
  organization := "com.github.cornerman",
  version      := "0.1.0-SNAPSHOT",

  scalaVersion := "2.12.10",
  crossScalaVersions := Seq("2.12.10", "2.13.0"),

  resolvers ++= (
    ("jitpack" at "https://jitpack.io") ::
    Nil
  )
))

lazy val commonSettings = Seq(
  scalacOptions ++=
    "-encoding" :: "UTF-8" ::
    "-unchecked" ::
    "-deprecation" ::
    "-explaintypes" ::
    "-feature" ::
    "-language:_" ::
    "-Xfuture" ::
    "-Xlint" ::
    "-Ywarn-value-discard" ::
    "-Ywarn-extra-implicit" ::
    "-Ywarn-unused" ::
    Nil,

  scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 12)) =>
        "-Ywarn-nullary-override" ::
        "-Ywarn-nullary-unit" ::
        "-Ywarn-infer-any" ::
        "-Yno-adapted-args" ::
        "-Ypartial-unification" ::
        Nil
      case _ =>
        Nil
    }
  },

  libraryDependencies ++=
    Deps.boopickle.value % Test ::
    Deps.scalaTest.value % Test ::
    Nil,

  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3")
)

enablePlugins(ScalaJSPlugin)

lazy val root = (project in file("."))
  .aggregate(coreJS, coreJVM, httpJS, httpJVM, wsJS, wsJVM)
  .settings(commonSettings)

lazy val core = crossProject.crossType(CrossType.Pure)
  .settings(commonSettings)
  .settings(
    name := "covenant-core",
    libraryDependencies ++=
      Deps.sloth.value ::
      Deps.kittens.value ::
      Deps.scribe.value ::
      Deps.monix.value ::
      Nil
  )

lazy val coreJS = core.js
lazy val coreJVM = core.jvm

lazy val http = crossProject
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "covenant-http",
  )
  .jvmSettings(
    libraryDependencies ++=
      Deps.akka.http.value ::
      Deps.akka.actor.value ::
      Deps.akka.stream.value ::
      Nil
  )
  .jsSettings(
    libraryDependencies ++=
      Deps.scalajs.dom.value ::
      Nil
  )

lazy val httpJS = http.js
lazy val httpJVM = http.jvm

lazy val ws = crossProject
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "covenant-ws",
    libraryDependencies ++=
      Deps.mycelium.value ::
      Nil
  )

lazy val wsJS = ws.js
lazy val wsJVM = ws.jvm
