import dcosb.DcosbBuild._

commonSettings

name := "service-broker"
version := "1.0-SNAPSHOT"

libraryDependencies := commonDependencies ++ akkaHttpDependencies ++ akkaDependencies ++ Seq(
  "commons-codec" % "commons-codec" % "1.10"
)