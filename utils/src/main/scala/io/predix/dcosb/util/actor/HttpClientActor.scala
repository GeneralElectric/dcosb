package io.predix.dcosb.util.actor

import akka.actor.{Actor, ActorLogging}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import io.predix.dcosb.util.AsyncUtils
import pureconfig.syntax._
import spray.json.{JsValue, RootJsonReader}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

trait HttpClientActor extends Actor with ActorLogging with SprayJsonSupport {

  var httpClient
    : Option[(HttpRequest, String) => Future[(HttpResponse, String)]] = None
  val tConfig = ConfigFactory.load()
  val timeout = Timeout(
    tConfig.getValue("http.client.default-timeout").toOrThrow[FiniteDuration])

  /**
    * @deprecated in favor of [[HttpClientActor.`sendRequest and handle response`()]]
    */
  def httpRequestSending(
      r: HttpRequest,
      f: (Try[(HttpResponse, String)] => _),
      c: String = "")(implicit executionContext: ExecutionContext) = {
    httpClient match {

      case Some(h: ((HttpRequest, String) => Future[(HttpResponse, String)])) =>
        h(r, c) onComplete {
          case Success((response: HttpResponse, context: String)) =>
            f(Success((response, context)))
          case Failure(e: Throwable) => f(Failure(e))
          case e =>
            f(
              Failure(new IllegalStateException(
                s"Received unexpected response from HTTP client: $e")))

        }

      case None =>
        f(Failure(new IllegalStateException(s"No HTTP client was configured")))

    }
  }

  /**
    * @deprecated in favor of [[HttpClientActor.`sendRequest and handle response`()]]
    */
  def multipleHttpRequestsSending(rqs: Seq[(HttpRequest, String)],
                                  f: (Seq[Try[(HttpResponse, String)]] => _))(
      implicit executionContext: ExecutionContext) = {
    log.debug(s"Sending requests $rqs")
    httpClient match {

      case Some(h: ((HttpRequest, String) => Future[(HttpResponse, String)])) =>
        AsyncUtils.waitAll[(HttpResponse, String)](rqs map (r => h(r._1, r._2))) map {
          responses =>
            f(responses)
        }

      case None =>
        f(List(
          Failure(new IllegalStateException(s"No HTTP client was configured"))))

    }

  }

  def `sendRequest and handle response`[T](request: HttpRequest,
                                           handling: Try[HttpResponse] => T)(
      implicit executionContext: ExecutionContext,
      materializer: ActorMaterializer) = {

    httpRequestSending(
      request,
      (response: Try[(HttpResponse, String)]) => {

        response match {
          case Success((r: HttpResponse, c: String)) => handling(Success(r))
          case Failure(e: Throwable)                 => handling(Failure(e))
        }

      }
    )
  }

}
