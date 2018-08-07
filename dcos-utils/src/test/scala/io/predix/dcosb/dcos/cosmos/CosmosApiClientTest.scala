package io.predix.dcosb.dcos.cosmos

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, Props}
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{DateTime => _, _}
import akka.testkit.{CallingThreadDispatcher, TestActorRef}
import io.predix.dcosb.dcos.security.TokenKeeper
import io.predix.dcosb.util.{ActorSuite, DCOSBSuite}
import com.github.nscala_time.time.Imports._
import akka.pattern.ask
import akka.util.Timeout
import io.predix.dcosb.dcos.DCOSCommon.PackageOptionsService
import io.predix.dcosb.dcos.security.TokenKeeper
import io.predix.dcosb.dcos.DCOSCommon
import io.predix.dcosb.dcos.cosmos.CosmosApiClient.{ApiModel, PackageInstallStarted}
import io.predix.dcosb.dcos.security.TokenKeeper.DCOSAuthorizationTokenHeader
import spray.json.JsValue

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Success, Try}

class CosmosApiClientJsonSupportTest extends DCOSBSuite {
  import spray.json._
  import io.predix.dcosb.dcos.cosmos.CosmosApiClient.ApiModel.JsonSupport

  "Given an install package request" - {

    val installRequest = CosmosApiClient.ApiModel.InstallRequest(
    "foo",
    "1.0.1",
    Some("""{"foo":"bar"}""".parseJson))

    "formats in implicit scope in JsonSupport should create a json representation from the object" in new JsonSupport {

      installRequestFormat.write(installRequest) shouldEqual """{"packageName":"foo","packageVersion":"1.0.1", "options": {"foo":"bar"}}""".parseJson

    }

  }

  "Given an update package request" - {

    val updateRequest = CosmosApiClient.ApiModel.UpdateRequest("foo-1", """{"service":{"name":"foo-1"},"foo":"bar","baz":123}""".parseJson, true)

    "formats in implicit scope in JsonSupport should create a json representation from the object" in new JsonSupport {
      updateRequestFormat.write(updateRequest) shouldEqual """{"appId":"foo-1","options":{"service":{"name":"foo-1"},"foo":"bar","baz":123}, "replace": true}""".parseJson
    }


  }

  "Give an uninstall package request" - {

    val uninstallRequest = CosmosApiClient.ApiModel.UninstallRequest("foo", "foo-1")

    "formats in implicit scope in JsonSupport should create a json representation from the object" in new JsonSupport {
      uninstallRequestFormat.write(uninstallRequest) shouldEqual """{"packageName":"foo","appId":"foo-1"}""".parseJson
    }

  }

  "Given a json representation of an update package response" - {

    val updateResponseJson = """{"marathonDeploymentId":"240420-24040-24020-204020","resolvedOptions":{"service":{"name":"foo-1"},"foo":"bar","baz":123,"bar":"foo"}}""".parseJson

    "formats in implicit scope in JsonSupport should create an UpdateResponse object" in new JsonSupport {
      updateResponseFormat.read(updateResponseJson) shouldEqual CosmosApiClient.ApiModel.UpdateResponse("240420-24040-24020-204020", """{"service":{"name":"foo-1"},"foo":"bar","baz":123,"bar":"foo"}""".parseJson)
    }

  }

}

class CosmosApiClientTest extends ActorSuite {
  implicit val executionContext =
    system.dispatchers.lookup(CallingThreadDispatcher.Id)
  implicit val timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))

  "A configured CosmosApiClient" - {

    val httpClient =
      mockFunction[HttpRequest, String, Future[(HttpResponse, String)]]

    val cosmosApiClient = TestActorRef(
      Props(classOf[CosmosApiClient])
        .withDispatcher(CallingThreadDispatcher.Id))

    Await.result(
      (cosmosApiClient ? CosmosApiClient.Configuration(httpClient)),
      timeout.duration)

    trait FooPkg {

      case class FooPkgOptsService(name: String)
          extends PackageOptionsService

      case class FooPkgOpts(bar: Option[String] = None,
                            baz: Option[Double] = None,
                            service: DCOSCommon.PackageOptionsService)
          extends DCOSCommon.PackageOptions

    }

    "in response to an InstallService message" - new FooPkg {
      import spray.json._

      val optionsJson = """{"service":{"name":"foo-1"},"bar":"baz"}""".parseJson
      val optionsWriter: (FooPkgOpts => JsValue) = { _ =>
        optionsJson
      }
      val installPackage = CosmosApiClient.InstallPackage[FooPkgOpts](
        packageName = "fooPackage",
        packageVersion = "1.0.1",
        packageOptions =
          FooPkgOpts(bar = Some("baz"), service = FooPkgOptsService("foo-1")),
        optionsWriter
      )

      "uses it's configured TokenKeeper and http client" - {

        "to send an http request to install a universe package" in new ApiModel.JsonSupport {
          val installRequestBody = CosmosApiClient.ApiModel.InstallRequest(
            installPackage.packageName,
            installPackage.packageVersion,
            Some(optionsJson))

          Marshal(installRequestBody).to[RequestEntity] onComplete {

            case Success(re: RequestEntity) =>
              val installRequest =
                HttpRequest(method = HttpMethods.POST,
                            uri = "/package/install",
                            entity = re.withContentType(
                              ContentType(ApiModel.installRequestMediaType)))
                  .withHeaders(
                    Accept(List(MediaRange(ApiModel.installResponseMediaType))))

              httpClient expects (installRequest, *) returning (Future
                .successful((HttpResponse(), ""))) once ()

              Await.result((cosmosApiClient ? installPackage), timeout.duration)

            case _ =>
              fail(
                "Could not encode InstallRequest, is CosmosApiClientJsonSupportTest passing?")
          }

        }

      }

      "upon the cosmos api accepting the install package request, responds with Success(PackageInstallStarted())" in {

        httpClient expects (*, *) returning (Future
          .successful((HttpResponse(), ""))) once ()

        Await.result((cosmosApiClient ? installPackage), timeout.duration) shouldEqual Success(
          PackageInstallStarted("fooPackage", "1.0.1", "foo-1"))

      }

    }

    "in response to an UpdatePackageOptions message" - new FooPkg {
      import spray.json._

      val optionsJson = """{"service":{"name":"foo-1"},"baz":3.14}""".parseJson
      val optionsWriter: (FooPkgOpts => JsValue) = { _ =>
        optionsJson
      }

      val updatePackageOptions =
        CosmosApiClient.UpdatePackageOptions(
          "foo-1",
          FooPkgOpts(service = FooPkgOptsService("foo-1"), baz = Some(3.14d)),
          optionsWriter)

      val updateResponseJson = """{"resolvedOptions":{"service":{"name":"foo-1"},"baz":3.14,"bar":"foo"},"marathonDeploymentId":"240420-24040-24020-204020"}"""
      val updateResponseEntity = HttpEntity(ContentTypes.`application/json`, updateResponseJson)

      "uses it's configured TokenKeeper and http client" - {

        "to send an http request to install a universe package" in new ApiModel.JsonSupport {

          val updateBody =
            CosmosApiClient.ApiModel.UpdateRequest("foo-1", optionsJson, false)

          Marshal(updateBody).to[RequestEntity] onComplete {

            case Success(re: RequestEntity) =>
              val updateRequest = HttpRequest(
                method = HttpMethods.POST,
                uri = "/cosmos/service/update",
                entity = re.withContentType(
                  ContentType(ApiModel.updateRequestMediaType))).withHeaders(
                Accept(List(MediaRange(ApiModel.updateResponseMediaType))))

              httpClient expects (updateRequest, *) returning Future.successful(
                (HttpResponse(status = StatusCodes.OK, entity = updateResponseEntity), "")) once ()

              Await.result((cosmosApiClient ? updatePackageOptions),
                           timeout.duration)

            case _ =>
              fail(
                "Could not encode UpdateRequest, is CosmosApiClientJsonSupportTest passing?")
          }

        }

        "upon the cosmos api accepting the update package request, responds with Success(PackageUpdated())" in {

          httpClient expects (*, *) returning Future.successful(
            (HttpResponse(status = StatusCodes.OK, entity = updateResponseEntity), "")) once ()

          Await.result((cosmosApiClient ? updatePackageOptions),
            timeout.duration) shouldEqual Success(CosmosApiClient.PackageUpdated("foo-1", """{"service":{"name":"foo-1"},"baz":3.14,"bar":"foo"}""".parseJson))
        }

      }

    }

    "in response to an UninstallPackage message" - {

      val uninstallPackage = CosmosApiClient.UninstallPackage("foo", "foo-1")

      "uses it's configured TokenKeeper and http client" - {

        "to send an http request to uninstall a package" in new ApiModel.JsonSupport {

          val uninstallRequestBody =
            CosmosApiClient.ApiModel.UninstallRequest("foo", "foo-1")

          Marshal(uninstallRequestBody).to[RequestEntity] onComplete {

            case Success(re: RequestEntity) =>
              val uninstallRequest =
                HttpRequest(method = HttpMethods.POST,
                            uri = "/package/uninstall",
                            entity = re.withContentType(
                              ContentType(ApiModel.uninstallRequestMediaType)))
                  .withHeaders(
                    Accept(
                      List(MediaRange(ApiModel.uninstallResponseMediaType))))

              httpClient expects (uninstallRequest, *) returning (Future
                .successful((HttpResponse(), ""))) once ()

              Await.result((cosmosApiClient ? uninstallPackage),
                           timeout.duration)

            case _ =>
              fail(
                "Could not encode InstallRequest, is CosmosApiClientJsonSupportTest passing?")
          }

        }

      }

    }

  }

}
