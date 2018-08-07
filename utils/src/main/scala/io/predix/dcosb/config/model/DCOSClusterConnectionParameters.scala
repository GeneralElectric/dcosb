package io.predix.dcosb.config.model

case class DCOSClusterConnectionParameters(host: String, port: Int, principal: Option[String], privateKey: Option[Array[Byte]])
