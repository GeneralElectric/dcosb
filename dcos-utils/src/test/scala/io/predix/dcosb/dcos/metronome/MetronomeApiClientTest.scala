package io.predix.dcosb.dcos.metronome

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, Props}
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.{DateTime => _, _}
import akka.testkit.{CallingThreadDispatcher, TestActorRef}
import akka.util.Timeout
import io.predix.dcosb.dcos.security.TokenKeeper
import io.predix.dcosb.util.{ActorSuite, DCOSBSuite}
import com.github.nscala_time.time.Imports._
import io.predix.dcosb.dcos.security.TokenKeeper.DCOSAuthorizationTokenHeader
import io.predix.dcosb.dcos.metronome.MetronomeApiClient.MetronomeApiModel.JsonSupport
import org.scalatest.{FreeSpecLike, OneInstancePerTest}
import spray.json._
import akka.pattern.ask
import io.predix.dcosb.util.actor.ConfiguredActor.Configured
import org.joda.time.chrono.ISOChronology

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.FiniteDuration
import scala.io.Source
import scala.util.{Failure, Success, Try}

class MetronomeApiClientJsonSupportTest extends FreeSpecLike with DCOSBSuite with OneInstancePerTest {

  "given a valid json string representing a metronome job" - {

    val json = Source.fromURL(getClass.getResource("/metronome/job.json")).getLines.mkString

    "formats in implicit scope in JsonSupport should create an object graph representing job" in {
      import io.predix.dcosb.dcos.metronome.MetronomeApiClient.MetronomeApiModel._

      val parser = new JsonSupport {

        def fromString(json: String): Job = jobFormatSupport.read(json.parseJson)

      }

      parser.fromString(json) shouldEqual Job(
        id = "prod.example.app",
        description = "Example Application",
        run = RunSpec(
          cmd = "nuke --dry --master local",
          disk = 128d,
          mem = 32d,
          cpus = 1.5d,
          docker = Docker(
            image = "foo/bla:test"
          )
        ),
        schedules = Seq(
          Schedule(
            id = "sleep-nightly",
            cron = "20 0 * * *",
            enabled = true,
            concurrencyPolicy = ConcurrencyPolicy.ALLOW
          )
        )
      )


    }

  }

  "given a metronome job object" - {



  }

  "given a valid json string representing a metronome job run" - {

    val json = Source.fromURL(getClass.getResource("/metronome/run.json")).getLines.mkString

    "formats in implicit scope in JsonSupport should create an object representing a job run" in {
      import io.predix.dcosb.dcos.metronome.MetronomeApiClient.MetronomeApiModel._

      val parser = new JsonSupport {

        def fromString(json: String): Run = runFormatSupport.read(json.parseJson)

      }

      parser.fromString(json) shouldEqual Run(
        id = "20160712081059ergsi",
        jobId = "prod",
        status = Status.STARTING,
        createdAt = DateTime.parse("2016-07-12T08:10:59.947+0000").withChronology(ISOChronology.getInstance(DateTimeZone.getDefault()))
      )

    }


  }

  "given a metronome job run object" - {

  }

  "given a valid json string representing a metronome api error response" - {

    val json = Source.fromURL(getClass.getResource("/metronome/error.json")).getLines.mkString

    "formats in implicit scope in JsonSupport should create an object representing a metronome api error response" in {
      import io.predix.dcosb.dcos.metronome.MetronomeApiClient.MetronomeApiModel._

      val parser = new JsonSupport {

        def fromString(json: String): Error = errorFormatSupport.read(json.parseJson)
      }

      parser.fromString(json) shouldEqual Error(
        message = "Object is not valid",
        details = Seq(ErrorDetail(
          path = "/mem",
          errors = Seq("is less than than 32")
        ))
      )

    }


  }

  "given a metronome api error object" - {

  }


}

class MetronomeApiClientTest extends ActorSuite with JsonSupport {
  implicit val executionContext = system.dispatchers.lookup(CallingThreadDispatcher.Id)
  implicit val timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))

  "A configured MetronomeApiClient" - {

    val httpClient = mockFunction[HttpRequest, String, Future[(HttpResponse, String)]]
    val metronomeApiClient = TestActorRef(Props(new MetronomeApiClient()))

    Await.result(metronomeApiClient ? MetronomeApiClient.Configuration(httpClient), timeout.duration) shouldEqual Success(Configured())

    "in response to a CreateJob message" - {
    import io.predix.dcosb.dcos.metronome.MetronomeApiClient._

      val job = MetronomeApiModel.Job(
        id = "foo-job",
        description = "A really awesome job",
        run = MetronomeApiModel.RunSpec(
          cpus = 1d,
          mem = 1024d,
          disk = 4096d,
          cmd = "echo 'hello world'",
          docker = MetronomeApiModel.Docker(
            image = "ubuntu"
          ))
      )

      "uses it's configured TokenKeeper and http client to communicate with the metronome api" - {

        (Marshal(job).to[RequestEntity]) onComplete {
          case Success(reqe: RequestEntity) =>

            val createRequest = HttpRequest(
              method = HttpMethods.POST,
              uri = "/service/metronome/v1/jobs",
              entity = reqe)

            "on the metronome api returning a non-error" in {

              (Marshal(job).to[ResponseEntity]) onComplete {
                case Success(respe: ResponseEntity) =>
                  httpClient expects(createRequest, *) returning Future.successful((HttpResponse(status = StatusCodes.Created, entity = respe), "")) once()

                  Await.result((metronomeApiClient ? MetronomeApiClient.CreateJob(job)), timeout.duration) shouldEqual Success(MetronomeApiClient.JobCreated(job))

                case Failure(e: Throwable) => fail(s"Failed to encode CreateJob object: $e")

              }

            }

            "on the metronome api returning 401 unauthorized" in {



            }

          case Failure(e: Throwable) => fail(s"Failed to encode CreateJob object: $e")
        }




      }

    }

  }

}
