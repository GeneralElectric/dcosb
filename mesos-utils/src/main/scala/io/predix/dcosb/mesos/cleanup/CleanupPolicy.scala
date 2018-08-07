package io.predix.dcosb.mesos.cleanup

import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master.Frameworks.Framework
import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master.Roles.Role
import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master.Slaves.{ResourceReservation, Slave}

trait CleanupPolicy {

  /**
    * Identify @{link ResourceReservation}s for removal according to this @{Link ResourceReservation}
    * @param roles
    * @param frameworks
    * @param slaves
    * @return
    */
  def execute(roles: Seq[Role], frameworks: Seq[Framework], slaves: Seq[Slave]): Map[String, Seq[ResourceReservation]]

}
