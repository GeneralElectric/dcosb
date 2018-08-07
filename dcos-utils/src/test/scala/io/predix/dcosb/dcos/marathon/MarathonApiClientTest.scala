package io.predix.dcosb.dcos.marathon

import java.util.concurrent.TimeUnit

import akka.actor.Props
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.testkit.{CallingThreadDispatcher, TestActorRef}
import akka.util.Timeout
import akka.pattern.ask
import io.predix.dcosb.util.ActorSuite

import scala.collection.immutable.HashMap
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

object MarathonApiClientTest {}

class MarathonApiClientTest extends ActorSuite {
  implicit val executionContext =
    system.dispatchers.lookup(CallingThreadDispatcher.Id)
  implicit val timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))

  "A Configured MarathonApiClient" - {

    trait ConfiguredMarathonApiClient {

      val httpClient =
        mockFunction[HttpRequest, String, Future[(HttpResponse, String)]]

      val marathonApiClient = TestActorRef(
        Props(classOf[MarathonApiClient])
          .withDispatcher(CallingThreadDispatcher.Id))

      Await.result(
        marathonApiClient ? MarathonApiClient.Configuration(httpClient),
        timeout.duration)

    }

    "in response to a GetApp message" - {

      val getApp = MarathonApiClient.GetApp("foo")

      val getAppRequest = HttpRequest(method = HttpMethods.GET,
                                      uri = "/service/marathon/v2/apps/foo")

      "for an app id that exists" - new ConfiguredMarathonApiClient
      with MarathonApiClient.APIModel.JsonSupport {
        import MarathonApiClient.APIModel._

        val appResponse = AppDescriptorResponse(
          app = App(id = "foo",
                    env = HashMap("envvar" -> "value"),
                    labels = HashMap("label" -> "labelvalue")))

        "it returns the App object representing the Marathon Application configuration and state" in {

          Await.result(Marshal(appResponse).to[ResponseEntity],
                       timeout.duration) match {
            case re: ResponseEntity =>
              httpClient expects (getAppRequest, *) returning (Future
                .successful((HttpResponse(status = StatusCodes.OK, entity = re),
                             ""))) once ()

              Await.result(marathonApiClient ? MarathonApiClient.GetApp("foo"),
                           timeout.duration) shouldEqual Success(
                appResponse.app)

          }

        }

      }

      "for an app id that does not exist" - new ConfiguredMarathonApiClient {

        "it returns a Failure pointing to a non-existant app id" in {

          httpClient expects (getAppRequest, *) returning (Future.successful(
            (HttpResponse(status = StatusCodes.NotFound), ""))) once ()

          Await.result(marathonApiClient ? MarathonApiClient.GetApp("foo"),
                       timeout.duration) shouldEqual Failure(
            MarathonApiClient.AppNotFound("foo"))

        }

      }

    }

    "in response to a GetAppIds message" - {

      val getAppIds = MarathonApiClient.GetAppIds("cassandra")
      val getAppsRequest = HttpRequest(method = HttpMethods.GET,
        uri = "/service/marathon/v2/apps/?id=cassandra")

      "for an id substring that yields app objects" - new ConfiguredMarathonApiClient
        with MarathonApiClient.APIModel.JsonSupport {
        import MarathonApiClient.APIModel._

        val appsResponse = AppsResponse(
          apps = List(
            App(id = "/andras/cassandra",
              env = HashMap("envvar" -> "value"),
              labels = HashMap("label" -> "labelvalue")),
            App(id = "/some/other/cassandra",
              env = HashMap("envvar" -> "value"),
              labels = HashMap("label" -> "labelvalue"))

          ))

        "it returns matching app ids as a sequence of Strings" in {

          Await.result(Marshal(appsResponse).to[ResponseEntity],
                                 timeout.duration) match {
            case re: ResponseEntity =>
              httpClient expects(getAppsRequest, *) returning (Future
                .successful((HttpResponse(status = StatusCodes.OK, entity = re),
                  ""))) once()

              Await.result(marathonApiClient ? getAppIds,
                timeout.duration) shouldEqual Success(
                List("/andras/cassandra", "/some/other/cassandra"))

          }

        }

      }

      "for an id substring that yields no app objects" - new ConfiguredMarathonApiClient
        with MarathonApiClient.APIModel.JsonSupport {
        import MarathonApiClient.APIModel._

        val appsResponse = AppsResponse(apps = List())

        "it returns an empty sequence of Strings" in {

          Await.result(Marshal(appsResponse).to[ResponseEntity],
            timeout.duration) match {
            case re: ResponseEntity =>
              httpClient expects(getAppsRequest, *) returning (Future
                .successful((HttpResponse(status = StatusCodes.OK, entity = re),
                  ""))) once()

              Await.result(marathonApiClient ? getAppIds,
                timeout.duration) shouldEqual Success(List())

          }

        }


      }

    }

  }

}
