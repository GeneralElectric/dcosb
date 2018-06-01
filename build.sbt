import java.io.File
import DcosbBuild._
// import WhiteSource._
import sbt.Keys.libraryDependencies

name := "dcosb"


commonSettings
// whitesourceSettings

lazy val dcosb = (project in file(".")).aggregate(mesosUtils, dcosUtils, serviceBroker, smApi, utils)

lazy val mesosUtils = (project in file("mesos-utils")).dependsOn(utils % "test->test;compile->compile")

lazy val dcosUtils = (project in file("dcos-utils")).dependsOn(utils % "test->test;compile->compile", mesosUtils)

lazy val serviceBroker = (project in file("service-broker")).dependsOn(utils % "test->test;compile->compile",
  smApi % "test->test;compile->compile")

lazy val smApi = (project in file("service-module-api")).dependsOn(utils % "test->test;compile->compile", dcosUtils, mesosUtils)


/* Misc
   ------------------- */

lazy val utils = (project in file("utils"))

