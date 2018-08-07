package io.predix.dcosb.servicemodule.api.util

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.typesafe.config.ConfigFactory
import io.predix.dcosb.servicemodule.api.ServiceModuleConfiguration
import io.predix.dcosb.servicemodule.api.ServiceModuleConfiguration.OpenServiceBrokerApi
import io.predix.dcosb.servicemodule.api.ServiceModuleConfiguration.OpenServiceBrokerApi.Currency
import io.predix.dcosb.dcos.DCOSCommon
import io.predix.dcosb.servicemodule.api.ServiceModuleConfiguration.OpenServiceBrokerApi.Currency
import io.predix.dcosb.servicemodule.api.util.BasicServiceModule.CommonServiceConfiguration
import io.predix.dcosb.servicemodule.api.ServiceModuleConfiguration
import io.predix.dcosb.util.FileUtils
import spray.json.{DefaultJsonProtocol, JsObject, JsValue}

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

object BasicServiceModule {
  type ProvisionInstanceParameters =
    ServiceModuleConfiguration.OpenServiceBrokerApi.ProvisionInstanceParameters
  type UpdateInstanceParameters =
    ServiceModuleConfiguration.OpenServiceBrokerApi.UpdateInstanceParameters
  type BindParameters =
    ServiceModuleConfiguration.OpenServiceBrokerApi.BindParameters
  type PackageOptions = DCOSCommon.PackageOptions
  type BindResponse =
    ServiceModuleConfiguration.OpenServiceBrokerApi.BindResponse

  /**
    * A configuration class that adds nothing to {@link ServiceModuleConfiguration}
    */
  case class CommonServiceConfiguration[I <: ProvisionInstanceParameters,
                                        J <: UpdateInstanceParameters,
                                        K <: BindParameters,
                                        B <: BindResponse,
                                        P <: PackageOptions](
      serviceId: String,
      serviceName: String,
      description: String,
      openService: ServiceModuleConfiguration.OpenServiceBrokerApi.Service[I,
                                                                           J,
                                                                           K,
                                                                           B],
      dcosService: DCOSCommon.Platform[P])
      extends ServiceModuleConfiguration[I, J, K, B, P]

  object OpenServiceBrokerModelSupport {

    case class CommonProvisionInstanceParameters(nodes: Int)
        extends ServiceModuleConfiguration.OpenServiceBrokerApi.ProvisionInstanceParameters
    case class CommonUpdateInstanceParameters(nodes: Int)
        extends ServiceModuleConfiguration.OpenServiceBrokerApi.UpdateInstanceParameters
    case class CommonBindParameters(user: Option[String])
        extends ServiceModuleConfiguration.OpenServiceBrokerApi.BindParameters

    case class CommonBindResponse(credentials: Option[Credentials])
        extends ServiceModuleConfiguration.OpenServiceBrokerApi.BindResponse
    case class Credentials(username: String, password: String, nodes: List[Node])
    case class Node(host: String, port: Int)

    trait JsonSupport extends DefaultJsonProtocol with SprayJsonSupport {

      val commonProvisionInstanceParametersReader: (
          Option[JsValue] => Try[Option[CommonProvisionInstanceParameters]]) = {

        case Some(jsValue) =>
          try {
            Success(
              Some(
                jsonFormat1(CommonProvisionInstanceParameters).read(jsValue)))
          } catch {
            case (e: Throwable) =>
              Failure(e)
          }
        case None => Success(Some(CommonProvisionInstanceParameters(1)))
      }

      val commonUpdateInstanceParametersReader
        : (Option[JsValue] => Try[Option[CommonUpdateInstanceParameters]]) = {
        case Some(jsValue) =>
          try {
            Success(
              Some(jsonFormat1(CommonUpdateInstanceParameters).read(jsValue)))
          } catch {
            case (e: Throwable) =>
              Failure(e)
          }
        case None => Success(None)
      }

      val commonBindParametersReader
        : (Option[JsValue] => Try[Option[CommonBindParameters]]) = {
        case Some(jsValue) =>
          try {
            Success(Some(jsonFormat1(CommonBindParameters).read(jsValue)))
          } catch {
            case (e: Throwable) =>
              Failure(e)
          }
        case None => Success(None)
      }

      implicit val nodeFormat = jsonFormat2(Node)
      implicit val credentialsFormat = jsonFormat3(Credentials)
      val commonBindResponseWriter: (CommonBindResponse => JsValue) = {
        jsonFormat1(CommonBindResponse).write(_)
      }

    }

  }

  object DCOSModelSupport {

    case class CommonService(name: String)
        extends DCOSCommon.PackageOptionsService

    /**
      * A default package-options structure that will ( most likely ) start ( newer versions of ) DC/OS Services
      * with default values
      */
    case class CommonPackageOptions(service: CommonService)
        extends DCOSCommon.PackageOptions

    trait JsonSupport extends DefaultJsonProtocol with SprayJsonSupport {

      implicit val commonServiceFormat = jsonFormat1(CommonService)
      val commonPackageOptionsWriter: (CommonPackageOptions => JsValue) = {

        jsonFormat1(CommonPackageOptions).write(_)
      }

      val commonPackageOptionsReader
        : (Option[JsValue] => Try[CommonPackageOptions]) = {
        case Some(js: JsValue) =>
          try {

            Success(jsonFormat1(CommonPackageOptions).read(js))
          } catch {
            case e: Throwable => Failure(e)
          }
        case None => Success(CommonPackageOptions(CommonService("common-1")))
      }

    }

  }

}

/**
  * Mix this in to your {@link ServiceModule} implementation for some useful helpers
  */
trait BasicServiceModule
    extends BasicServiceModule.DCOSModelSupport.JsonSupport
    with BasicServiceModule.OpenServiceBrokerModelSupport.JsonSupport {

  private val tConf = ConfigFactory.load()

  def loadFromTypeSafeConfig(key: String = "my-service") = {

    loadFromTypeSafeConfig[
      BasicServiceModule.OpenServiceBrokerModelSupport.CommonProvisionInstanceParameters,
      BasicServiceModule.OpenServiceBrokerModelSupport.CommonUpdateInstanceParameters,
      BasicServiceModule.OpenServiceBrokerModelSupport.CommonBindParameters,
      BasicServiceModule.OpenServiceBrokerModelSupport.CommonBindResponse,
      BasicServiceModule.DCOSModelSupport.CommonPackageOptions](
      key,
      commonProvisionInstanceParametersReader,
      commonUpdateInstanceParametersReader,
      commonBindParametersReader,
      commonBindResponseWriter,
      commonPackageOptionsWriter,
      commonPackageOptionsReader
    )
  }

  /**
    * Obtain a {@link ServiceConfiguration} instance with values loaded via [[https://github.com/typesafehub/config#standard-behavior Typesafe Configuration's default mechanisms]]
    * @param key Configuration key that points to the root Config object (e.g. "my-service")
    * @return
    */
  def loadFromTypeSafeConfig[
      I <: BasicServiceModule.ProvisionInstanceParameters,
      J <: BasicServiceModule.UpdateInstanceParameters,
      K <: BasicServiceModule.BindParameters,
      B <: BasicServiceModule.BindResponse,
      P <: BasicServiceModule.PackageOptions](
      key: String,
      provisionInstanceParametersReader: (Option[JsValue] => Try[Option[I]]),
      updateInstanceParametersReader: (Option[JsValue] => Try[Option[J]]),
      bindParametersReader: (Option[JsValue] => Try[Option[K]]),
      bindResponseWriter: (B => JsValue),
      packageOptionsWriter: (P => JsValue),
      packageOptionsReader: (Option[JsValue] => Try[P]))
    : Try[BasicServiceModule.CommonServiceConfiguration[I, J, K, B, P]] = {
    val config = tConf.getConfig(key)

    // commence Ghetto Object Mapping
    try {
      Success(
        CommonServiceConfiguration[I, J, K, B, P](
          serviceId = config.getString("service-id"),
          serviceName = config.getString("service-name"),
          description = config.getString("description"),
          dcosService = DCOSCommon.Platform[P](
            pkg = DCOSCommon.PkgInfo(
              pkgName = config.getString("dcos.package-info.name"),
              pkgVersion = config.getString("dcos.package-info.version"),
              planApiCompatible =
                config.getBoolean("dcos.package-info.plan-api-compatible")
            ),
            pkgOptionsWriter = packageOptionsWriter,
            // ^ if have any configuration options to the DC/OS service
            // you are integrating ( ..like you probably do )
            // replace pkgOptionsFormat with a JsonWriter that handles that type
            pkgOptionsReader = packageOptionsReader,
            connection = DCOSCommon.Connection(
              apiHost = config.getString("dcos.connection.api.host"),
              apiPort = config.getInt("dcos.connection.api.port"),
              principal =
                if (config.hasPath("dcos.connection.principal"))
                  Some(config.getString("dcos.connection.principal"))
                else None,
              privateKeyAlias = if (config.hasPath("dcos.connection.private-key-alias"))
                Some(config.getString("dcos.connection.private-key-alias"))
              else None,
              privateKeyStoreId = if (config.hasPath("dcos.connection.private-key-store-id"))
                Some(config.getString("dcos.connection.private-key-store-id"))
              else None,
              privateKeyPassword = if (config.hasPath("dcos.connection.private-key-password"))
                Some(config.getString("dcos.connection.private-key-password"))
              else None
            )
          ),
          openService = OpenServiceBrokerApi
            .Service[I, J, K, B](
              id = config.getString("osb.service.id"),
              name = config.getString("osb.service.name"),
              description = config.getString("osb.service.description"),
              provisionInstanceParametersReader =
                provisionInstanceParametersReader,
              updateInstanceParametersReader = updateInstanceParametersReader,
              bindParametersReader = bindParametersReader,
              bindResponseWriter = bindResponseWriter,
              bindable = config.getBoolean("osb.service.bindable"),
              plan_updateable = config.getBoolean("osb.service.plan-updateable"),
              tags = config.getStringList("osb.service.tags").asScala,
              metadata = ServiceModuleConfiguration.OpenServiceBrokerApi
                .ServiceMetadata(
                  displayName =
                    config.getString("osb.service.meta-data.display-name"),
                  imageUrl =
                    Some(config.getString("osb.service.meta-data.image-url")),
                  longDescription = Some(
                    config.getString("osb.service.meta-data.long-description")),
                  providerDisplayName = config.getString(
                    "osb.service.meta-data.provider-display-name"),
                  documentationUrl =
                    config.getString("osb.service.meta-data.documentation-url"),
                  supportUrl =
                    config.getString("osb.service.meta-data.support-url")
                ),
              plans = config
                .getConfigList("osb.service.plans")
                .asScala
                .map(plan =>
                  ServiceModuleConfiguration.OpenServiceBrokerApi
                    .ServicePlan(
                      id = plan.getString("id"),
                      name = plan.getString("name"),
                      description = plan.getString("description"),
                      metadata = ServiceModuleConfiguration.OpenServiceBrokerApi
                        .ServicePlanMetadata(
                          displayName = plan.getString("meta-data.display-name"),
                          bullets =
                            plan.getStringList("meta-data.bullets").asScala,
                          costs = plan
                            .getConfigList("meta-data.costs")
                            .asScala
                            .map(cost =>
                              ServiceModuleConfiguration.OpenServiceBrokerApi
                                .ServicePlanPricing(
                                  amount = ServiceModuleConfiguration.OpenServiceBrokerApi
                                    .ServicePlanPricingAmount(
                                      currency = Currency.withName(
                                        cost.getString("amount.currency")),
                                      amount = cost.getDouble("amount.value")
                                    ),
                                  unit = ServiceModuleConfiguration.OpenServiceBrokerApi.BillingUnit
                                    .withName(cost.getString("unit"))
                              ))
                        ),
                      resources = plan.hasPath("resources") match {case false => None case true => Some((plan.getObject("resources").asScala flatMap {
                        case (id, _) =>
                          Some((id, ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlanResources(plan.getInt(s"resources.$id.cpu"),plan.getInt(s"resources.$id.memoryMB"),plan.getInt(s"resources.$id.diskMB"))))
                      }).toMap) }
                  ))
            )
        ))

    } catch {
      case e: Throwable => Failure(e)
    }

  }

}
