package io.predix.dcosb.dcos.metronome

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.{DateTime => _, _}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import io.predix.dcosb.dcos.security.{TokenAuthenticatingActor, TokenKeeper}
import io.predix.dcosb.util.actor.ConfiguredActor
import io.predix.dcosb.util.actor.ConfiguredActor.Configured
import spray.json.DefaultJsonProtocol
import com.github.nscala_time.time.Imports._
import io.predix.dcosb.dcos.security.TokenKeeper.DCOSAuthorizationTokenHeader
import io.predix.dcosb.util.JsonFormats
import io.predix.dcosb.util.actor.HttpClientActor

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

object MetronomeApiClient {

  case class Configuration(httpClient: (HttpRequest, String) => Future[(HttpResponse, String)])

  // handled messages
  case class CreateJob(job: MetronomeApiModel.Job)
  case class DeleteJob(jobId: String)
  case class RunJob(jobId: String)
  case class ScheduleJob(jobId: String, schedule: MetronomeApiModel.Schedule)

  // responses
  case class JobCreated(job: MetronomeApiModel.Job)
  case class JobDeleted(jobId: String)
  case class JobNotFound(jobId: String) extends Throwable
  case class InsufficientPermissions(error: MetronomeApiModel.Error) extends Throwable
  case class JobStarted(run: MetronomeApiModel.Run)
  case class JobScheduled(jobId: String, schedule: MetronomeApiModel.Schedule)

  // exceptions
  case class UnexpectedResponse(resp: HttpResponse) extends Throwable {
    override def toString: String = {
      super.toString + s", $resp"
    }
  }

  object MetronomeApiModel {

    object Status extends Enumeration {
      val INITIAL = Value("INITIAL")
      val STARTING = Value("STARTING")
      val ACTIVE = Value("ACTIVE")
      val SUCCESS = Value("SUCCESS")
      val FAILED = Value("FAILED")

    }

    object ConcurrencyPolicy extends Enumeration {
      val ALLOW = Value("ALLOW")
      val DENY = Value("DENY")
    }

    case class ErrorDetail(path: String, errors: Seq[String])
    case class Error(message: String, details: Seq[ErrorDetail] = List.empty)

    case class Run(
                    id: String,
                    jobId: String,
                    createdAt: DateTime,
                    completedAt: Option[DateTime] = None,
                    status: Status.Value = Status.INITIAL)

    case class Schedule(
                         id: String,
                         cron: String,
                         concurrencyPolicy: ConcurrencyPolicy.Value = ConcurrencyPolicy.ALLOW,
                         enabled: Boolean = true,
                         startingDeadlineSeconds: Option[Int] = None,
                         timezone: Option[String] = None)

    case class Docker(image: String)
    case class RunSpec(cpus: Double, mem: Double, disk: Double, docker: Docker, cmd: String)
    case class Job(id: String, description: String, run: RunSpec, schedules: Seq[Schedule] = List.empty)

    trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

      implicit val dateTimeFormatSupport = new JsonFormats.DateTimeFormat
      implicit val statusDetailFormatSupport = new JsonFormats.EnumJsonConverter(Status)

      implicit val runFormatSupport = jsonFormat5(Run)
      implicit val concurrencyPolicyDetailFormatSupport = new JsonFormats.EnumJsonConverter(ConcurrencyPolicy)

      implicit val errorDetailFormatSupport = jsonFormat2(ErrorDetail)
      implicit val errorFormatSupport = jsonFormat2(Error)

      implicit val dockerFormatSupport = jsonFormat1(Docker)
      implicit val runSpecFormatSupport = jsonFormat5(RunSpec)

      implicit val scheduleFormatSupport = jsonFormat6(Schedule)

      implicit val jobFormatSupport = jsonFormat4(Job)

    }

  }

}

class MetronomeApiClient extends ConfiguredActor[MetronomeApiClient.Configuration] with ActorLogging with HttpClientActor with MetronomeApiClient.MetronomeApiModel.JsonSupport {

  import MetronomeApiClient._

  implicit val ec = context.dispatcher
  implicit val mat = ActorMaterializer(ActorMaterializerSettings(context.system))

  override def configure(configuration: MetronomeApiClient.Configuration): Future[Configured] = {
    this.httpClient = Some(configuration.httpClient)

    super.configure(configuration)
  }

  override def configuredBehavior: Receive = {
    case MetronomeApiClient.CreateJob(job: MetronomeApiClient.MetronomeApiModel.Job) => broadcastFuture(createJob(job), sender())
  }

  def createJob(job: MetronomeApiClient.MetronomeApiModel.Job): Future[MetronomeApiClient.JobCreated] = {

    val promise = Promise[MetronomeApiClient.JobCreated]()

    def `sendRequest, unmarshall and fulfill promise`(request: HttpRequest, p: Promise[MetronomeApiClient.JobCreated]): Unit = {

      `sendRequest and handle response`(request, {
        case Success(HttpResponse(StatusCodes.Created, _, re, _)) =>
          Unmarshal(re).to[MetronomeApiClient.MetronomeApiModel.Job] onComplete {
            case Success(job: MetronomeApiClient.MetronomeApiModel.Job) => p.success(MetronomeApiClient.JobCreated(job))
            case Failure(e: Throwable) =>
              re.discardBytes()
              p.failure(e)
          }
        case Success(r: HttpResponse) =>
          r.entity.discardBytes()
          promise.failure(new UnexpectedResponse(r))
        case Failure(e: Throwable) =>
          promise.failure(e)
      })

    }

    (Marshal(job).to[RequestEntity]) onComplete {
      case Success(requestEntity: RequestEntity) =>

        configured((configuration: MetronomeApiClient.Configuration) => {


          `sendRequest, unmarshall and fulfill promise`(HttpRequest(
            method = HttpMethods.POST,
            uri = "/service/metronome/v1/jobs",
            entity = requestEntity
          ), promise)



        })


      case Failure(e: Throwable) => promise.failure(e)
    }

    promise.future

  }

}
