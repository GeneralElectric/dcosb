import dcosb.DcosbBuild._

commonSettings

name := "dcos-utils"
version := "1.0-SNAPSHOT"

publishArtifact in (Test, packageBin) := true

libraryDependencies := commonDependencies ++ akkaHttpDependencies ++ Seq(
  "com.pauldijou" %% "jwt-core" % "0.12.1"
)
