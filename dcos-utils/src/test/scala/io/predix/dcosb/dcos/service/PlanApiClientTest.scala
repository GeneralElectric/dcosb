package io.predix.dcosb.dcos.service

import java.util.concurrent.TimeUnit

import akka.actor.Props
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.pattern.ask
import akka.testkit.{CallingThreadDispatcher, TestActorRef}
import akka.util.Timeout
import io.predix.dcosb.dcos.service.PlanApiClient.{
  PlanNotFound,
  UnexpectedResponse
}
import io.predix.dcosb.util.{ActorSuite, DCOSBSuite}
import io.predix.dcosovercf.util.AsyncActorSuite
import spray.json.DefaultJsonProtocol

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Future}
import scala.io.Source
import scala.util.{Failure, Success}

class PlanApiClientJsonSupportTest extends DCOSBSuite {

  "Given the json representation of a Plan object" - {

    val json = Source
      .fromURL(getClass.getResource("/service/plan.json"))
      .getLines
      .mkString

    "formats in implicit scope in JsonSupport should create an object graph representing the plan" in {
      import PlanApiClient.ApiModel._
      import spray.json._

      val parser = new JsonSupport {

        def fromString(json: String): Plan = planFormat.read(json.parseJson)

      }

      parser.fromString(json) shouldEqual (
        Plan(
          status = "COMPLETE",
          errors = List(),
          phases = List(
            Phase(
              id = "ec3c6469-9c40-4e2b-8bc0-593c8c10c708",
              name = "node-deploy",
              status = "COMPLETE",
              steps = List(Step(
                id = "55ec5998-fb3d-4930-9b11-52ea69abaa53",
                name = "node-0:[server]",
                status = "COMPLETE",
                message = "com.mesosphere.sdk.scheduler.plan.DeploymentStep: 'node-0:[server] [55ec5998-fb3d-4930-9b11-52ea69abaa53]' has status: 'COMPLETE'."
              ))
            ))
        )
      )

    }

  }

}

class PlanApiClientTest extends ActorSuite {
  import PlanApiClient.ApiModel._

  implicit val executionContext =
    system.dispatchers.lookup(CallingThreadDispatcher.Id)
  implicit val timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))

  "A PlanApiClient configured" - {

    "for a plan api incompatible service" - {

      trait PlanApiIncompatibleConfiguredPlanApiClient {
        val httpClient =
          mockFunction[HttpRequest, String, Future[(HttpResponse, String)]]
        val planApiClient = TestActorRef(
          Props(classOf[PlanApiClient])
            .withDispatcher(CallingThreadDispatcher.Id))

        Await.result(planApiClient ? PlanApiClient.Configuration(httpClient, false),
          timeout.duration)

      }

      "on receiving a RetrievePlan message" - {

        "it responds with Failure(NotPlanApiCompatibleService)" in new PlanApiIncompatibleConfiguredPlanApiClient {
          import PlanApiClient._
          val retrievePlan = PlanApiClient.RetrievePlan("andras-4", "deploy")

          Await.result(planApiClient ? retrievePlan, timeout.duration) shouldEqual Failure(
            new NotPlanApiCompatibleService)

        }

      }

    }

    "for a plan api compatible service" - {

      trait PlanApiCompatibleConfiguredPlanApiClient {
        val httpClient =
          mockFunction[HttpRequest, String, Future[(HttpResponse, String)]]
        val planApiClient = TestActorRef(
          Props(classOf[PlanApiClient])
            .withDispatcher(CallingThreadDispatcher.Id))

        Await.result(planApiClient ? PlanApiClient.Configuration(httpClient, true),
          timeout.duration)

      }

      trait PlansResponse
        extends DefaultJsonProtocol
          with PlanApiCompatibleConfiguredPlanApiClient {

        import spray.json._

        def plans = Array[String]("deploy")

        val listPlansRequest = HttpRequest(method = HttpMethods.GET,
          uri = "/service/andras-4/v1/plans")

        httpClient expects(listPlansRequest, *) returning (Future.successful(
          (HttpResponse(entity =
            HttpEntity(ContentTypes.`application/json`, plans.toJson.toString)),
            "")))

      }

      "on receiving a RetrievePlan message" - {

        "for a compliant service api endpoint with a valid plan name" - {

          val retrievePlan = PlanApiClient.RetrievePlan("andras-4", "deploy")

          "it responds with Success(Plan)" in new PlansResponse with JsonSupport
            with SprayJsonSupport {

            val planResponse = Plan(
              status = "COMPLETE",
              errors = List(),
              phases = List(
                Phase(
                  id = "ec3c6469-9c40-4e2b-8bc0-593c8c10c708",
                  name = "node-deploy",
                  status = "COMPLETE",
                  steps = List(Step(
                    id = "55ec5998-fb3d-4930-9b11-52ea69abaa53",
                    name = "node-0:[server]",
                    status = "COMPLETE",
                    message = "com.mesosphere.sdk.scheduler.plan.DeploymentStep: 'node-0:[server] [55ec5998-fb3d-4930-9b11-52ea69abaa53]' has status: 'COMPLETE'."
                  ))
                ))
            )

            Await.result(Marshal(planResponse).to[ResponseEntity],
              timeout.duration) match {
              case re: ResponseEntity =>
                val retrievePlanRequest =
                  HttpRequest(method = HttpMethods.GET,
                    uri = "/service/andras-4/v1/plans/deploy")
                httpClient expects(retrievePlanRequest, *) returning (Future
                  .successful((HttpResponse(entity = re), "")))

                Await.result(planApiClient ? retrievePlan, timeout.duration) shouldEqual Success(
                  planResponse)

            }

          }

        }

        "for a non-existant plan" - {

          val retrievePlan = PlanApiClient.RetrievePlan("andras-4", "party")

          "it responds with Failure(PlanNotFound)" in new PlansResponse
            with JsonSupport with SprayJsonSupport {

            Await.result(planApiClient ? retrievePlan, timeout.duration) shouldEqual Failure(
              new PlanNotFound("andras-4", "party"))

          }

        }

        "for a non-compliant service api" - {

          val retrievePlan = PlanApiClient.RetrievePlan("andras-4", "deploy")

          "in that plans can't be listed" - {

            trait InvalidPlansResponse extends PlanApiCompatibleConfiguredPlanApiClient {

              val listPlansRequest = HttpRequest(method = HttpMethods.GET,
                uri =
                  "/service/andras-4/v1/plans")

              httpClient expects(listPlansRequest, *) returning (Future
                .successful(
                  (HttpResponse(status = StatusCodes.InternalServerError)),
                  ""))

            }

            "it responds with Failure(UnexpectedResponse)" in new InvalidPlansResponse {

              Await.result(planApiClient ? retrievePlan, timeout.duration) shouldEqual Failure(
                new UnexpectedResponse(
                  HttpResponse(status = StatusCodes.InternalServerError)))

            }

          }

          "in that plan details can't be retrieved" - {

            "it responds with Failure(UnexpectedResponse)" in new PlansResponse {

              val retrievePlanRequest =
                HttpRequest(method = HttpMethods.GET,
                  uri = "/service/andras-4/v1/plans/deploy")
              httpClient expects(retrievePlanRequest, *) returning (Future
                .successful((HttpResponse(status = StatusCodes.InternalServerError), "")))

              Await.result(planApiClient ? retrievePlan, timeout.duration) shouldEqual Failure(
                new UnexpectedResponse(
                  HttpResponse(status = StatusCodes.InternalServerError)))

            }

          }

        }

        "for a scheduler that's been shut down" - {}

      }

    }
  }
}
