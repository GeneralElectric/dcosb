package io.predix.dcosb.util

import java.util.concurrent.TimeUnit

import akka.actor.Actor
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.testkit.{CallingThreadDispatcher, TestActorRef}
import akka.util.Timeout
import spray.json.DefaultJsonProtocol

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

class HttpRequestEqualityTest extends ActorSuite {
  implicit val executionContext = system.dispatchers.lookup(CallingThreadDispatcher.Id)
  implicit val timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))

  "Two HttpRequest objects with matching method, uri, headers and entity marshalled via spray from a case class instance" - {

    new SprayJsonSupport with DefaultJsonProtocol {

      case class Foo(bar: String)

      implicit val fooFormatSupport = jsonFormat1(Foo)

      val one = HttpRequest(
        method = HttpMethods.POST,
        uri = "/foo",
        entity = FormData("deez"->fooFormatSupport.write(Foo("nuts")).compactPrint).toEntity
      ).withHeaders(RawHeader("bar", "baz"))

      val two = HttpRequest(
        method = HttpMethods.POST,
        uri = "/foo",
        entity = FormData("deez"->fooFormatSupport.write(Foo("nuts")).compactPrint).toEntity
      ).withHeaders(RawHeader("bar", "baz"))

      "should be considered equal" in {
        one shouldEqual two

      }

      "when passed in as a parameters to a mock, should be considered equal" - {

        "when the mock is directly invoked" in {

          val mock = mockFunction[HttpRequest, Unit]
          mock expects(one) once()
          mock(two)

        }

        "when the mock is invoked from within a (synchronous) test actor" in {

          val testActor = TestActorRef(new Actor {
            override def receive: Receive = {
              case m: (HttpRequest => Unit) => m(two)
            }
          })

          val mock = mockFunction[HttpRequest, Unit]
          mock expects(one) once()

          testActor ! mock

        }



      }




    }





  }

}
