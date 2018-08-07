package io.predix.dcosb.mesos.cleanup.policy

import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master.Slaves.ResourceReservation
import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master.{Frameworks, Roles, Slaves}
import io.predix.dcosb.mesos.cleanup.CleanupPolicy

import scala.collection.mutable

object NoTaskRunningFrameworks {}


/**
  * Identifies roles with 0 frameworks running 1 or more tasks on any slaves
  */
class NoTaskRunningFrameworks extends CleanupPolicy {

  override def execute(roles: Seq[Roles.Role], frameworks: Seq[Frameworks.Framework], slaves: Seq[Slaves.Slave]): Map[String, Seq[ResourceReservation]] = {

    var targetedReservations = mutable.HashMap[String, Seq[ResourceReservation]]()

    for (role <- roles) {
      var runningTasks:Int = 0
      for (frameworkId <- role.frameworks) {
        for (framework <- frameworks.filter(_.id == frameworkId)) runningTasks += framework.tasks.size

      }

      if (runningTasks == 0) {

        // couldn't find any frameworks running tasks under this role.. let's clean up
        // the individual reservations on slaves
        for (slave <- slaves) {

          targetedReservations.put(
            slave.id,
            slave.
              reserved_resources_full.
              filter(_._1 == role.name).
              flatMap(_._2).toSeq)
        }


      }
    }


    targetedReservations.toMap

  }
}
