package io.predix.dcosb.dcos.marathon

import akka.actor.ActorLogging
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{
  HttpMethods,
  HttpRequest,
  HttpResponse,
  StatusCodes
}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import io.predix.dcosb.util.actor.{ConfiguredActor, HttpClientActor}
import spray.json.DefaultJsonProtocol

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

object MarathonApiClient {

  case class Configuration(
      httpClient: (HttpRequest, String) => Future[(HttpResponse, String)])

  // handled messages
  case class GetApp(id: String)
  case class GetAppIds(idContains: String)

  // responses
  case class AppNotFound(id: String) extends Throwable
  case class UnexpectedResponse(response: HttpResponse) extends Throwable

  object APIModel {

    case class App(id: String,
                   env: Map[String, String],
                   labels: Map[String, String])
    case class AppDescriptorResponse(app: App)
    case class AppsResponse(apps: Seq[App])

    trait JsonSupport extends DefaultJsonProtocol with SprayJsonSupport {

      implicit val appFormat = jsonFormat3(App)
      implicit val appDescriptorResponseFormat = jsonFormat1(
        AppDescriptorResponse)
      implicit val appsResponseFormat = jsonFormat1(AppsResponse)

    }
  }

  val name = "marathon-api-client"

}

class MarathonApiClient
    extends ConfiguredActor[MarathonApiClient.Configuration]
    with ActorLogging
    with HttpClientActor
    with MarathonApiClient.APIModel.JsonSupport {
  import MarathonApiClient._
  implicit val ec = context.dispatcher
  implicit val mat = ActorMaterializer(
    ActorMaterializerSettings(context.system))

  override def configure(configuration: Configuration): Future[ConfiguredActor.Configured] = {
    this.httpClient = Some(configuration.httpClient)

    super.configure(configuration)
  }

  override def configuredBehavior = {

    case GetApp(id: String) => broadcastFuture(getApp(id), sender())
    case GetAppIds(idContains: String) => broadcastFuture(getAppIds(idContains), sender())

  }

  def getApp(id: String): Future[APIModel.App] = {

    val promise = Promise[APIModel.App]()
    val getAppRequest =
      HttpRequest(method = HttpMethods.GET, uri = s"/service/marathon/v2/apps/$id")

    `sendRequest and handle response`(
      getAppRequest, {
        case Success(HttpResponse(StatusCodes.OK, _, appDescriptorResponseEntity, _)) =>
          Unmarshal(appDescriptorResponseEntity).to[APIModel.AppDescriptorResponse] onComplete {
            case Success(app: APIModel.AppDescriptorResponse) =>
              promise.success(app.app)
            case Failure(e: Throwable) =>
              appDescriptorResponseEntity.discardBytes(mat)
              log.error(s"Failed to unmarshal app response from marathon: $e")
          }

        case Success(HttpResponse(StatusCodes.NotFound, _, re, _)) =>
          re.discardBytes(mat)
          promise.failure(AppNotFound(id))

        case Success(r: HttpResponse) =>
          log.error(
            s"Unexpected HTTP response while trying to get app with id $id: $r")
          r.entity.discardBytes(mat)
          promise.failure(UnexpectedResponse(r))

        case Failure(e: Throwable) =>
          log.error(
            s"Failed to send request to Marathon to retrieve app with id $id, exception was: $e")
          promise.failure(e)
      }
    )

    promise.future

  }

  def getAppIds(idContains: String): Future[Seq[String]] = {

    val promise = Promise[Seq[String]]()
    val getAppsRequest =
      HttpRequest(method = HttpMethods.GET, uri = s"/service/marathon/v2/apps/?id=$idContains")

    `sendRequest and handle response`(
      getAppsRequest, {
        case Success(HttpResponse(StatusCodes.OK, _, appsResponseEntity, _)) =>
          Unmarshal(appsResponseEntity).to[APIModel.AppsResponse] onComplete {
            case Success(app: APIModel.AppsResponse) =>
              promise.success(app.apps map { _.id })
            case Failure(e: Throwable) =>
              appsResponseEntity.discardBytes(mat)
              log.error(s"Failed to unmarshal apps response from marathon: $e")
          }


        case Success(r: HttpResponse) =>
          log.error(
            s"Unexpected HTTP response while trying to get apps with id substring $idContains: $r")
          r.entity.discardBytes(mat)
          promise.failure(UnexpectedResponse(r))

        case Failure(e: Throwable) =>
          log.error(
            s"Failed to send request to Marathon to retrieve apps with id substring $idContains, exception was: $e")
          promise.failure(e)
      }
    )

    promise.future

  }

}
