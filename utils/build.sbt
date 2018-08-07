import dcosb.DcosbBuild._

commonSettings

name := "utils"
version := "1.0-SNAPSHOT"

publishArtifact in (Test, packageBin) := true

libraryDependencies := commonDependencies ++ akkaDependencies ++ akkaHttpDependencies ++ Seq()
