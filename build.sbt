inThisBuild(Seq(
  organization := "com.github.cornerman",
  version      := "0.1.0-SNAPSHOT",

  scalaVersion := crossScalaVersions.value.last,
  crossScalaVersions := Seq("2.12.13", "2.13.3"),

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
  .aggregate(core.js, core.jvm, http.js, http.jvm, ws.js, ws.jvm)
  .settings(commonSettings)

lazy val core = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
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

lazy val http = crossProject(JVMPlatform, JSPlatform)
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

lazy val ws = crossProject(JVMPlatform, JSPlatform)
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "covenant-ws",
    libraryDependencies ++=
      Deps.mycelium.value ::
      Nil
  )
