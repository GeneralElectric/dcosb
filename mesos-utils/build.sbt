import dcosb.DcosbBuild._

commonSettings

name := "mesos-utils"
version := "1.0-SNAPSHOT"

libraryDependencies := commonDependencies ++ akkaHttpDependencies ++ Seq()
