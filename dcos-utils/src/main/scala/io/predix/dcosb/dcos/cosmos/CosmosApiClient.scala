package io.predix.dcosb.dcos.cosmos

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import io.predix.dcosb.dcos.DCOSCommon
import io.predix.dcosb.dcos.security.TokenKeeper.DCOSAuthorizationTokenHeader
import io.predix.dcosb.dcos.security.{TokenAuthenticatingActor, TokenKeeper}
import io.predix.dcosb.util.actor.HttpClientActor
import io.predix.dcosb.util.actor.ConfiguredActor
import spray.json.{DefaultJsonProtocol, JsValue}

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

object CosmosApiClient {

  type PackageOptions = DCOSCommon.PackageOptions

  case class Configuration(
      httpClient: (HttpRequest, String) => Future[(HttpResponse, String)])

  // handled messages
  case class InstallPackage[P <: PackageOptions](
      packageName: String,
      packageVersion: String,
      packageOptions: P,
      packageOptionsWriter: (P => JsValue))
  case class UpdatePackageOptions[P <: PackageOptions](
      appId: String,
      packageOptions: P,
      packageOptionsWriter: (P => JsValue))
  case class UninstallPackage(packageName: String, appId: String)

  // responses
  case class PackageInstallStarted(packageName: String,
                                   packageVersion: String,
                                   appId: String)
  case class PackageUninstalled(packageName: String, appId: String)
  case class PackageUpdated(appId: String, renderedOptions: JsValue)

  // exceptions
  case class UnexpectedResponse(resp: HttpResponse) extends Throwable {
    override def toString: String = {
      super.toString + s", $resp"
    }
  }

  object ApiModel {

    val installRequestMediaType: MediaType.WithFixedCharset =
      MediaType.customWithFixedCharset(
        mainType = "application",
        subType = "vnd.dcos.package.install-request+json",
        params = Map("charset" -> "utf-8", "version" -> "v1"),
        charset = HttpCharsets.`UTF-8`,
        allowArbitrarySubtypes = true
      )

    val installResponseMediaType: MediaType.WithFixedCharset =
      MediaType.customWithFixedCharset(
        mainType = "application",
        subType = "vnd.dcos.package.install-response+json",
        params = Map("charset" -> "utf-8", "version" -> "v2"),
        charset = HttpCharsets.`UTF-8`,
        allowArbitrarySubtypes = true
      )

    val updateRequestMediaType: MediaType.WithFixedCharset =
      MediaType.customWithFixedCharset(
        mainType = "application",
        subType = "vnd.dcos.service.update-request+json",
        params = Map("charset" -> "utf-8", "version" -> "v1"),
        charset = HttpCharsets.`UTF-8`,
        allowArbitrarySubtypes = true
      )

    val updateResponseMediaType: MediaType.WithFixedCharset =
      MediaType.customWithFixedCharset(
        mainType = "application",
        subType = "vnd.dcos.service.update-response+json",
        params = Map("charset" -> "utf-8", "version" -> "v1"),
        charset = HttpCharsets.`UTF-8`,
        allowArbitrarySubtypes = true
      )

    val uninstallRequestMediaType: MediaType.WithFixedCharset =
      MediaType.customWithFixedCharset(
        mainType = "application",
        subType = "vnd.dcos.package.uninstall-request+json",
        params = Map("charset" -> "utf-8", "version" -> "v1"),
        charset = HttpCharsets.`UTF-8`,
        allowArbitrarySubtypes = true
      )

    val uninstallResponseMediaType: MediaType.WithFixedCharset =
      MediaType.customWithFixedCharset(
        mainType = "application",
        subType = "vnd.dcos.package.uninstall-response+json",
        params = Map("charset" -> "utf-8", "version" -> "v1"),
        charset = HttpCharsets.`UTF-8`,
        allowArbitrarySubtypes = true
      )

    case class InstallRequest(packageName: String,
                              packageVersion: String,
                              options: Option[JsValue])
    case class UpdateRequest(appId: String, options: JsValue, replace: Boolean)
    case class UpdateResponse(marathonDeploymentId: String,
                              resolvedOptions: JsValue)
    case class UninstallRequest(packageName: String, appId: String)

    trait JsonSupport extends DefaultJsonProtocol with SprayJsonSupport {

      implicit val installRequestFormat = jsonFormat3(InstallRequest)
      implicit val updateRequestFormat = jsonFormat3(UpdateRequest)
      implicit val updateResponseFormat = jsonFormat2(UpdateResponse)
      implicit val uninstallRequestFormat = jsonFormat2(UninstallRequest)

    }

  }

  val name = "cosmos-api-client"

}

class CosmosApiClient
    extends ConfiguredActor[CosmosApiClient.Configuration]
    with ActorLogging
    with HttpClientActor
    with CosmosApiClient.ApiModel.JsonSupport {

  import CosmosApiClient._

  implicit val ec = context.dispatcher
  implicit val mat = ActorMaterializer(ActorMaterializerSettings(context.system))

  override def configure(
      configuration: Configuration): Future[ConfiguredActor.Configured] = {
    httpClient = Some(configuration.httpClient)

    super.configure(configuration)
  }

  override def configuredBehavior: Receive = {
    case InstallPackage(packageName,
                        packageVersion,
                        packageOptions,
                        packageOptionsWriter) =>
      broadcastFuture(installPackage(packageName,
                                     packageVersion,
                                     packageOptions,
                                     packageOptionsWriter),
                      sender())

    case UpdatePackageOptions(appId, packageOptions, packageOptionsWriter) =>
      broadcastFuture(
        updatePackageOptions(appId, packageOptions, packageOptionsWriter),
        sender())

    case UninstallPackage(packageName, appId) =>
      broadcastFuture(uninstallPackage(packageName, appId), sender())
  }

  def installPackage[P <: PackageOptions](
      packageName: String,
      packageVersion: String,
      packageOptions: P,
      packageOptionsWriter: (P => JsValue)): Future[PackageInstallStarted] = {

    val promise = Promise[PackageInstallStarted]()

    Marshal(
      ApiModel.InstallRequest(packageName,
                              packageVersion,
                              Some(packageOptionsWriter(packageOptions))))
      .to[RequestEntity] onComplete {

      case Success(re: RequestEntity) =>
        configured((configuration: CosmosApiClient.Configuration) => {

          val handler: (Try[(HttpResponse, String)] => Unit) = {
            case Success((r: HttpResponse, _)) if r.status == StatusCodes.OK =>
              promise.success(
                PackageInstallStarted(packageName,
                                      packageVersion,
                                      packageOptions.service.name))
            case Success((r: HttpResponse, _)) =>
              log.debug(s"Received unexpected response $r")
              promise.failure(UnexpectedResponse(r))
            case Failure(e: Throwable) => promise.failure(e)
          }

          val installRequest = HttpRequest(
            method = HttpMethods.POST,
            uri = "/package/install",
            entity =
              re.withContentType(ContentType(ApiModel.installRequestMediaType)))
            .withHeaders(
              Accept(List(MediaRange(ApiModel.installResponseMediaType))))

          log.debug(s"Sending install request: $installRequest")

          httpRequestSending(
            installRequest,
            handler
          )

        })

      case Failure(e: Throwable) => promise.failure(e)
      case _                     =>
    }

    promise.future

  }

  def updatePackageOptions[P <: PackageOptions](
      appId: String,
      packageOptions: P,
      packageOptionsWriter: (P => JsValue)): Future[PackageUpdated] = {
    val promise = Promise[PackageUpdated]()

    Marshal(
      ApiModel
        .UpdateRequest(appId, packageOptionsWriter(packageOptions), false))
      .to[RequestEntity] onComplete {

      case Success(re: RequestEntity) =>
        configured((configuration: CosmosApiClient.Configuration) => {

          val updateService = HttpRequest(
            method = HttpMethods.POST,
            uri = "/cosmos/service/update",
            entity = re.withContentType(
              ContentType(ApiModel.updateRequestMediaType))).withHeaders(
            Accept(List(MediaRange(ApiModel.updateResponseMediaType))))

          `sendRequest and handle response`(updateService, {
            case Success(HttpResponse(StatusCodes.OK, _, re, _)) =>
              Unmarshal(re.withContentType(ContentTypes.`application/json`)).to[CosmosApiClient.ApiModel.UpdateResponse] onComplete {
                case Success(r: CosmosApiClient.ApiModel.UpdateResponse) =>
                  promise.success(PackageUpdated(appId, r.resolvedOptions))
                case Failure(e: Throwable) =>
                  re.discardBytes()
                  promise.failure(e)
              }
            case Success(r: HttpResponse) =>
              r.entity.discardBytes()
              promise.failure(new UnexpectedResponse(r))
            case Failure(e: Throwable) =>
              promise.failure(e)
          })

        })

      case Failure(e: Throwable) => promise.failure(e)
      case _                     =>
    }

    promise.future
  }

  def uninstallPackage(packageName: String,
                       appId: String): Future[PackageUninstalled] = {

    val promise = Promise[PackageUninstalled]()

    Marshal(ApiModel.UninstallRequest(packageName, appId))
      .to[RequestEntity] onComplete {
      case Success(re: RequestEntity) =>
        configured((configuration: CosmosApiClient.Configuration) => {

          val handler: (Try[(HttpResponse, String)] => Unit) = {
            case Success((r: HttpResponse, _)) if r.status == StatusCodes.OK =>
              promise.success(PackageUninstalled(packageName, appId))
            case Success((r: HttpResponse, _)) =>
              promise.failure(UnexpectedResponse(r))
            case Failure(e: Throwable) => promise.failure(e)
          }

          httpRequestSending(
            HttpRequest(method = HttpMethods.POST,
                        uri = "/package/uninstall",
                        entity = re.withContentType(
                          ContentType(ApiModel.uninstallRequestMediaType)))
              .withHeaders(
                Accept(List(MediaRange(ApiModel.uninstallResponseMediaType)))),
            handler
          )

        })

      case Failure(e: Throwable) => promise.failure(e)
      case _                     =>
    }

    promise.future

  }

}
