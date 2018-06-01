package dcosb

import sbt._
import sbt.Log
import Keys._
import sbt.Level
import org.scalastyle.sbt.ScalastylePlugin
import org.scalastyle.sbt.ScalastylePlugin.autoImport._

object DcosbBuild extends AutoPlugin {
  override def requires = ScalastylePlugin

  lazy val commonSettings: Seq[sbt.Setting[_]] = Seq(
    scalaVersion := "2.12.3",
    organization := "io.predix.dcosb",
    resolvers in ThisBuild += "Akka Snapshot Repository" at "http://repo.akka.io/snapshots/",
    resolvers in ThisBuild += "Scalastyle Releases Repository" at "https://oss.sonatype.org/content/repositories/releases",
    // resolvers in ThisBuild += "GE DevCloud dcosb Relases Repository" at "https://devcloud.swcoe.ge.com/artifactory/FGGPD",
    scalacOptions := Seq("-target:jvm-1.8",
      "-unchecked",
      "-deprecation",
      "-encoding",
      "utf8"),
    scalastyleFailOnError := true,
    logLevel in test := Level.Debug,
    publishTo in ThisBuild := publishSettings(version.value)(sLog.value)
  )

  def publishSettings(version: String)(implicit log: Logger): Option[Resolver] = (sys.env.get("PX_ARTIFACTORY_URL"), sys.env.get("PX_ARTIFACTORY_REPO_RELEASE"), sys.env.get("PX_ARTIFACTORY_REPO_SNAPSHOT"), version) match {
    case (Some(repo), _, Some(snapshot), version) if version endsWith "-SNAPSHOT" =>
      Some("snapshots" at repo + snapshot)
    case (Some(repo), Some(release), _, _) =>
      Some("releases" at repo + release)
    case e =>
      log.warn(s"Unable to configure publish repository due to missing configuration ($e)")
      None

  }

  lazy val commonDependencies: Seq[sbt.ModuleID] = Seq(
    "ch.qos.logback" % "logback-classic" % "1.1.7",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0",
    "com.github.pureconfig" %% "pureconfig" % "0.7.0",
    "com.github.nscala-time" %% "nscala-time" % "2.16.0",
    "com.github.pathikrit" %% "better-files" % "3.0.0",
    "com.wix" %% "accord-core" % "0.6.1",
    "org.scalatest" %% "scalatest" % "3.0.1" % "test",
    "org.scalamock" %% "scalamock-scalatest-support" % "3.6.0" % "test"
  )

  lazy val akkaHttpDependencies: Seq[sbt.ModuleID] = Seq(
    "com.typesafe.akka" %% "akka-http" % "10.0.5",
    "com.typesafe.akka" %% "akka-http-spray-json" % "10.0.5"
  ) ++ akkaDependencies

  lazy val akkaDependencies: Seq[sbt.ModuleID] = Seq(
    "com.typesafe.akka" %% "akka-actor" % "2.5.1",
    "com.typesafe.akka" %% "akka-stream" % "2.5.1",
    "com.typesafe.akka" %% "akka-http-testkit" % "10.0.5" % "test"
  )


}