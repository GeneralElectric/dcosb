package dcosb

import java.net.URI

import sbt.{AutoPlugin, Logger, ThisBuild}
//import sbtwhitesource.WhiteSourcePlugin
//import sbtwhitesource.WhiteSourcePlugin.autoImport._
//
//object WhiteSource extends AutoPlugin {
//  override def requires = WhiteSourcePlugin
//
//  val whitesourceSettings: Seq[sbt.Setting[_]] =
//    (sys.env.get("WHITESOURCE_URL"),
//     sys.env.get("WHITESOURCE_PRODUCT_NAME"),
//     sys.env.get("WHITESOURCE_PROJECT_NAME"),
//     sys.env.get("WHITESOURCE_PROJECT_TOKEN")) match {
//      case (Some(whitesourceUrl), Some(productName), Some(projectName), projectToken) =>
//        Seq(
//          whitesourceFailOnError := true,
//          whitesourceForceCheckAllDependencies := true,
//          whitesourceCheckPoliciesBeforeUpdate := true,
//          whitesourceServiceUrl in ThisBuild := URI.create(whitesourceUrl),
//          whitesourceProduct in ThisBuild := productName,
//          whitesourceAggregateProjectName in ThisBuild := projectName,
//          whitesourceAggregateProjectToken in ThisBuild := projectToken.getOrElse("")
//        )
//      case _ => List.empty
//    }
//
//}
