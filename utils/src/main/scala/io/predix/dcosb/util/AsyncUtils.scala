package io.predix.dcosb.util

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object AsyncUtils {


  def contextualize[T, C](future: Future[T], context: C)(implicit executionContext: ExecutionContext) = { future map { r => (r, context) } }

  // https://stackoverflow.com/questions/29344430/scala-waiting-for-sequence-of-futures
  private def lift[T](futures: Seq[Future[T]])(implicit executionContext: ExecutionContext) =
    futures.map(
      _.map { Success(_) }.recover { case t => Failure(t) })

  def waitAll[T](futures: Seq[Future[T]])(implicit executionContext: ExecutionContext) =
    Future.sequence(lift(futures)) // having neutralized exception completions through the lifting, .sequence can now be used



}
