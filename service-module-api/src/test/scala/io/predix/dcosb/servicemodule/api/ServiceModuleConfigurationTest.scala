package io.predix.dcosb.servicemodule.api

import java.util.concurrent.TimeUnit

import com.wix.accord.{Descriptions, Failure, RuleViolation}
import io.predix.dcosb.servicemodule.api.ServiceModuleConfiguration.OpenServiceBrokerApi.Validators
import io.predix.dcosb.dcos.DCOSCommon
import io.predix.dcosb.servicemodule.api.ServiceModuleConfiguration.{
  BasicAuth,
  OpenServiceBrokerApi
}
import io.predix.dcosb.servicemodule.api.util.BasicServiceModule
import io.predix.dcosb.servicemodule.api.util.BasicServiceModule.{
  DCOSModelSupport,
  OpenServiceBrokerModelSupport
}
import io.predix.dcosb.util.{ActorSuite, DCOSBSuite}
import spray.json.{JsValue, JsonReader, JsonWriter}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import scala.io.Source
import scala.util.{Success, Try}

class ServiceModuleConfigurationTest extends DCOSBSuite {}

class ServiceModuleConfigurationJsonSupportTest
    extends ActorSuite
    with ServiceModuleMockingSuite {

  implicit val timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))

  "given an Open Service configuration object " - {
    import ServiceModuleMockingSuite._
    import spray.json._

    val serviceModuleMock = new TestServiceModuleMock()
    val serviceModule = serviceModuleMock.serviceModule
    val serviceConfiguration = Await
      .result(serviceModule ? ServiceModule.GetServiceModuleConfiguration(
                "test-service"),
              timeout.duration)
      .asInstanceOf[Success[BasicServiceModule.CommonServiceConfiguration[
        OpenServiceBrokerModelSupport.CommonProvisionInstanceParameters,
        OpenServiceBrokerModelSupport.CommonUpdateInstanceParameters,
        OpenServiceBrokerModelSupport.CommonBindParameters,
        OpenServiceBrokerModelSupport.CommonBindResponse,
        DCOSModelSupport.CommonPackageOptions]]]

    val openServiceConfiguration = serviceConfiguration match {
      case Success(
          c: BasicServiceModule.CommonServiceConfiguration[
            OpenServiceBrokerModelSupport.CommonProvisionInstanceParameters,
            OpenServiceBrokerModelSupport.CommonUpdateInstanceParameters,
            OpenServiceBrokerModelSupport.CommonBindParameters,
            OpenServiceBrokerModelSupport.CommonBindResponse,
            DCOSModelSupport.CommonPackageOptions]) =>
        Some(c.openService)
      case _ => None
    }

    "jsonformats in JsonFormatSupport will produce it's JSON representation" in new OpenServiceBrokerApi.JsonSupport {

      openServiceConfiguration match {

        case Some(
            c: ServiceModuleConfiguration.OpenServiceBrokerApi.Service[
              OpenServiceBrokerModelSupport.CommonProvisionInstanceParameters,
              OpenServiceBrokerModelSupport.CommonUpdateInstanceParameters,
              OpenServiceBrokerModelSupport.CommonBindParameters,
              OpenServiceBrokerModelSupport.CommonBindResponse]) =>
          val jsonString =
            Source
              .fromURL(getClass.getResource("/catalog.json"))
              .getLines
              .mkString

          catalogFormat(c) shouldEqual jsonString.parseJson

        case _ =>
          fail(
            "Could not get ServiceModuleConfiguration.OpenServiceBrokerApi.Service")

      }

    }

  }

}

class ServiceModuleConfigurationValidatorTest extends DCOSBSuite {

  import com.wix.accord.validate

  "An OpenService configuration validator" - new Validators {

    "when validating a ServiceModuleConfiguration instance" - {

      case class TestServiceModuleConfiguration(
          serviceId: String,
          serviceName: String,
          description: String,
          openService: ServiceModuleConfiguration.OpenServiceBrokerApi.Service[
            BasicServiceModule.OpenServiceBrokerModelSupport.CommonProvisionInstanceParameters,
            OpenServiceBrokerModelSupport.CommonUpdateInstanceParameters,
            OpenServiceBrokerModelSupport.CommonBindParameters,
            BasicServiceModule.OpenServiceBrokerModelSupport.CommonBindResponse],
          dcosService: DCOSCommon.Platform[
            BasicServiceModule.DCOSModelSupport.CommonPackageOptions])
          extends ServiceModuleConfiguration[
            BasicServiceModule.OpenServiceBrokerModelSupport.CommonProvisionInstanceParameters,
            OpenServiceBrokerModelSupport.CommonUpdateInstanceParameters,
            OpenServiceBrokerModelSupport.CommonBindParameters,
            BasicServiceModule.OpenServiceBrokerModelSupport.CommonBindResponse,
            BasicServiceModule.DCOSModelSupport.CommonPackageOptions]

      "empty strings fail validation on serviceId, serviceName and description" in {

        val validation = validate(
          TestServiceModuleConfiguration(
            serviceId = "",
            serviceName = "",
            description = "",
            openService = ServiceModuleConfiguration.OpenServiceBrokerApi
              .Service[
                BasicServiceModule.OpenServiceBrokerModelSupport.CommonProvisionInstanceParameters,
                OpenServiceBrokerModelSupport.CommonUpdateInstanceParameters,
                OpenServiceBrokerModelSupport.CommonBindParameters,
                BasicServiceModule.OpenServiceBrokerModelSupport.CommonBindResponse](
                id = "baz",
                name = "foo",
                description = "bar",
                provisionInstanceParametersReader = stubFunction[
                  Option[JsValue],
                  Try[Option[BasicServiceModule.OpenServiceBrokerModelSupport.CommonProvisionInstanceParameters]]],
                updateInstanceParametersReader = stubFunction[
                  Option[JsValue],
                  Try[Option[BasicServiceModule.OpenServiceBrokerModelSupport.CommonUpdateInstanceParameters]]],
                bindParametersReader = stubFunction[
                  Option[JsValue],
                  Try[Option[BasicServiceModule.OpenServiceBrokerModelSupport.CommonBindParameters]]],
                bindResponseWriter =
                  stubFunction[BasicServiceModule.OpenServiceBrokerModelSupport.CommonBindResponse,
                               JsValue],
                metadata =
                  stub[ServiceModuleConfiguration.OpenServiceBrokerApi.ServiceMetadata],
                plans = List(stub[
                  ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan])
              ),
            dcosService = DCOSCommon.Platform[
              BasicServiceModule.DCOSModelSupport.CommonPackageOptions](
              pkg = stub[DCOSCommon.PkgInfo],
              pkgOptionsWriter = stubFunction[
                BasicServiceModule.DCOSModelSupport.CommonPackageOptions,
                JsValue],
              pkgOptionsReader = stubFunction[
                Option[JsValue],
                Try[BasicServiceModule.DCOSModelSupport.CommonPackageOptions]],
              connection = stub[DCOSCommon.Connection]
            )
          ))

        validation shouldEqual Failure(
          Set(
            RuleViolation("",
                          "must not be empty",
                          Descriptions.Explicit("Service ID")),
            RuleViolation("",
                          "must not be empty",
                          Descriptions.Explicit("Service Name")),
            RuleViolation("",
                          "must not be empty",
                          Descriptions.Explicit("Description"))
          ))

      }

    }

    "when validating a BasicAuth instance" - {

      "empty strings fail validation on userName and passwordHash" in {

        val validation = validate(BasicAuth(username = "", passwordHash = ""))

        validation shouldEqual Failure(
          Set(
            RuleViolation("",
                          "must not be empty",
                          Descriptions.Explicit("User Name")),
            RuleViolation("",
                          "must not be empty",
                          Descriptions.Explicit("Password Hash"))
          ))

      }

    }

  }

}
