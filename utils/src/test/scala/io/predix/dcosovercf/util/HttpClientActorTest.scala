package io.predix.dcosb.util

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, Props}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.testkit.{CallingThreadDispatcher, TestActorRef}
import akka.util.Timeout
import io.predix.dcosb.util.actor.HttpClientActor
import spray.json.DefaultJsonProtocol

import scala.collection.immutable.HashMap
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class HttpClientActorTest extends ActorSuite {
  implicit val executionContext = system.dispatchers.lookup(CallingThreadDispatcher.Id)
  implicit val timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))

  "An Actor mixing in the HttpClientActor trait" - {


    "should be able to use it's configured http client" - {

      val httpClientMock = mockFunction[HttpRequest, String, Future[(HttpResponse, String)]]
      val httpClientActor = TestActorRef(Props(new HttpClientActor {

        httpClient = Some(httpClientMock)

        override def receive: Receive = {
          case _ => // not a lot of behavior here
        }

      }))

      "and multipleHttpRequestsSending to send a sequence of HttpRequests and have a callback invoked on it's results - HttpResponse or Failure objects" in {

        val handler = mockFunction[Seq[Try[(HttpResponse, String)]], Unit]
        val underlyingActor = httpClientActor.underlyingActor.asInstanceOf[HttpClientActor]

        val httpRequests = List(
          (HttpRequest(method = HttpMethods.GET, uri = "/foo"), "foo"),
          (HttpRequest(method = HttpMethods.POST, uri = "/bar"), "bar"))

        for (httpRequest <- httpRequests) {
          httpClientMock expects(httpRequest._1, httpRequest._2) returning Future.successful((HttpResponse(), httpRequest._2)) once()
        }

        handler expects(List(Success((HttpResponse(), "foo")), Success((HttpResponse(), "bar")))) once()

        underlyingActor.multipleHttpRequestsSending(httpRequests, handler)

      }

    }



  }

}
