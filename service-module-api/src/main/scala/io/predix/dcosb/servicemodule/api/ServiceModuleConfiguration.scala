package io.predix.dcosb.servicemodule.api

import io.predix.dcosb.dcos.DCOSCommon
import spray.json.{
  DefaultJsonProtocol,
  JsArray,
  JsBoolean,
  JsObject,
  JsString,
  JsValue,
  RootJsonFormat
}

import scala.util.Try

object ServiceModuleConfiguration {
  import com.wix.accord.dsl._

  trait Validators {

    implicit val basicAuthValidator = validator[BasicAuth] { s =>
      s.username as "User Name" is notEmpty
      s.passwordHash as "Password Hash" is notEmpty
    }

    implicit val serviceModuleConfigurationValidator =
      validator[ServiceModuleConfiguration[_, _, _, _, _]] { s =>
        s.serviceId as "Service ID" is notEmpty
        s.serviceName as "Service Name" is notEmpty
        s.description as "Description" is notEmpty
      }

  }

  case class BasicAuth(username: String, passwordHash: String)

  /**
    * Configuration types (and their validators) for the Open Service Broker service instance
    * that will serve OpenServiceBroker service requests on behalf of the {@link ServiceModule} implementation,
    * the encapsulating {@link ServiceModuleConfiguration} configures
    */
  object OpenServiceBrokerApi {

    trait ProvisionInstanceParameters
    trait UpdateInstanceParameters
    trait BindParameters

    trait BindResponse {
      def credentials: Option[Any]
    }

    trait Validators extends ServiceModuleConfiguration.Validators {

      implicit val servicePlanMetadataValidator =
        validator[ServicePlanMetadata] { s =>
          s.displayName is notEmpty
        }

      implicit val servicePlanValidator = validator[ServicePlan] { s =>
        s.id as "ID" is notEmpty
        s.name as "Name" is notEmpty
        s.description as "Description" is notEmpty
      }

      implicit val serviceMetadataValidator = validator[ServiceMetadata] { s =>
        s.displayName as "Display Name" is notEmpty
        s.providerDisplayName as "Provider Display Name" is notEmpty
        s.documentationUrl as "Documentation URL" is notEmpty // TODO check for valid url
        s.supportUrl as "Support URL" is notEmpty // TODO check for valid url

      }

      implicit val serviceValidator = validator[Service[_, _, _, _]] { s =>
        s.id as "ID" is notEmpty
        s.description as "Description" is notEmpty
        s.plans.size as "Number of Plans" should be > 0
      }

    }

    case class ServiceBroker(auth: Option[BasicAuth])

    object BillingUnit extends Enumeration {
      val MONTHLY = Value("MONTHLY")
      val DAILY = Value("DAILY")
      val HOURLY = Value("HOURLY")

    }

    object Currency extends Enumeration {
      val USD = Value("USD")
    }

    case class ServicePlanPricingAmount(currency: Currency.Value,
                                        amount: Double)
    case class ServicePlanPricing(amount: ServicePlanPricingAmount,
                                  unit: BillingUnit.Value)

    case class ServicePlanMetadata(
        bullets: Seq[String] = List(),
        costs: Seq[ServicePlanPricing] = List(
          ServicePlanPricing(
            amount =
              ServicePlanPricingAmount(currency = Currency.USD, amount = 0d),
            BillingUnit.MONTHLY)),
        displayName: String)

    case class ServicePlanResources(cpu: Int, memoryMB: Int, diskMB: Int)

    case class ServicePlan(id: String,
                           name: String,
                           description: String,
                           metadata: ServicePlanMetadata,
                           resources: Option[Map[String, ServicePlanResources]] = None)

    case class ServiceMetadata(displayName: String,
                               imageUrl: Option[String] = None,
                               longDescription: Option[String] = None,
                               providerDisplayName: String,
                               documentationUrl: String,
                               supportUrl: String)

    /**
      * Open Service descriptor. Most of this goes in the Catalog response.
      * @param id Open Service UUID
      * @param name Human readable name
      * @param description
      * @param provisionInstanceParametersReader A closure that Optionally receives a JsValue object, which it is expected to attempt to turn in to InstanceParameter implementation I
      * @param bindable Is this Open Service Broker Service bindable?
      * @param tags Open Service Broker Catalog tags
      * @param metadata Open Service Broker Service Metadata
      * @param plan_updateable Can you update plans on an instance post creation?
      * @param plans Service plans
      * @tparam I
      */
    case class Service[+I <: ProvisionInstanceParameters,
                       +J <: UpdateInstanceParameters,
                       +K <: BindParameters,
                       B <: BindResponse](
        id: String, // open service broker service uuid
        name: String,
        description: String,
        // Optional because the field may be empty ( in this case, load a default ), Try[Option] because the JsValue may not map to I correctly
        // or may map to no I ( typically if the JsValue is None, but this is left to the implementation )
        // Also note that OpenServiceBroker treats only Failure(spray.json.DeserializationException) as a malformed request
        provisionInstanceParametersReader: (Option[JsValue] => Try[Option[I]]),
        updateInstanceParametersReader: (Option[JsValue] => Try[Option[J]]),
        bindParametersReader: (Option[JsValue] => Try[Option[K]]),
        bindResponseWriter: (B => JsValue),
        bindable: Boolean = true,
        tags: Seq[String] = List(),
        metadata: ServiceMetadata,
        plan_updateable: Boolean = false,
        plans: Seq[ServicePlan])

    trait JsonSupport extends DefaultJsonProtocol {

      val catalogFormat: (Service[_ <: ProvisionInstanceParameters,
                                  _ <: UpdateInstanceParameters,
                                  _ <: BindParameters,
                                  _ <: BindResponse] => JsValue) = {

        implicit val metadataFormat = jsonFormat6(ServiceMetadata)

        def enumWriter[T] = new RootJsonFormat[T] {

          override def read(json: JsValue): T = throw new NotImplementedError()

          override def write(obj: T): JsValue = JsString(obj.toString)
        }

        implicit val billingUnitFormat = enumWriter[BillingUnit.Value]
        implicit val currencyFormat = enumWriter[Currency.Value]

        implicit val servicePlanPricingAmount = jsonFormat2(
          ServicePlanPricingAmount)
        implicit val servicePlanPricingFormat = jsonFormat2(ServicePlanPricing)
        implicit val servicePlanMetadataFormat = jsonFormat3(
          ServicePlanMetadata)
        implicit val servicePlanResourcesFormat = jsonFormat3(ServicePlanResources)
        implicit val servicePlanFormat = jsonFormat5(ServicePlan)
        val underLyingCatalogFormat =
          new RootJsonFormat[
            Service[_ <: ProvisionInstanceParameters, _ <: UpdateInstanceParameters, _ <: BindParameters, _ <: BindResponse]] {

            override def read(json: JsValue)
              : Service[_ <: ProvisionInstanceParameters, _ <: UpdateInstanceParameters, _ <: BindParameters, _ <: BindResponse] =
              throw new NotImplementedError()

            override def write(
                obj: Service[_ <: ProvisionInstanceParameters,
                             _ <: UpdateInstanceParameters,
                             _ <: BindParameters,
                             _ <: BindResponse]): JsValue = {
              JsObject(
                "services" -> JsArray(
                  JsObject(
                    "id" -> JsString(obj.id),
                    "name" -> JsString(obj.name),
                    "description" -> JsString(obj.description),
                    "bindable" -> JsBoolean(obj.bindable),
                    "plan_updateable" -> JsBoolean(obj.plan_updateable),
                    "tags" -> JsArray((obj.tags map { JsString(_) }).toVector),
                    "metadata" -> metadataFormat.write(obj.metadata),
                    "plans" -> JsArray(
                      (obj.plans map { servicePlanFormat.write(_) }).toVector)
                  )
                )
              )
            }
          }

        underLyingCatalogFormat.write(_)
      }

    }

  }

}

/**
  * An implementing class of {@link ServiceModuleConfiguration} encapsulates all configuration for
  * - starting and configuring an Open Service Broker HTTP service and
  * - to inform the creation of service lifecycle related requests to DC/OS Cosmos and other package management APIs
  * around the {@link ServiceModule} it configures
  */
abstract class ServiceModuleConfiguration[
    +I <: ServiceModuleConfiguration.OpenServiceBrokerApi.ProvisionInstanceParameters,
    +J <: ServiceModuleConfiguration.OpenServiceBrokerApi.UpdateInstanceParameters,
    +K <: ServiceModuleConfiguration.OpenServiceBrokerApi.BindParameters,
    B <: ServiceModuleConfiguration.OpenServiceBrokerApi.BindResponse,
    P <: DCOSCommon.PackageOptions] {

  /**
    *
    * @return A service ID that is unique per daemon instance
    */
  def serviceId: String
  def serviceName: String
  def description: String
  def openService
    : ServiceModuleConfiguration.OpenServiceBrokerApi.Service[I, J, K, B]
  def dcosService: DCOSCommon.Platform[P]
}
