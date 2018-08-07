package io.predix.dcosb.mesos.cleanup.policy

import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master.Frameworks.{Framework, Task}
import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master.Roles.{Resources, Role}
import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master.Slaves.{Disk, Label, Labels, Persistence, PrincipalAndLabels, Range, Ranges, ResourceReservation, Scalar, Slave, Volume}
import io.predix.dcosb.util.DCOSBSuite
import io.predix.dcosb.mesos.cleanup.policy.NoTaskRunningFrameworks

import scala.collection.immutable.HashMap

class NoTaskRunningFrameworksTest extends DCOSBSuite {

  "A NoTaskRunningFrameworksTest instance" - {

    trait MesosApiMock {

      val roles = List[Role](
        Role(
          frameworks = List("no-role-framework"),
          name = "*",
          resources = Resources(
            cpus = 8d,
            disk = 0d,
            gpus = 0d,
            mem = 1024d,
            ports = None
          ),
          weight = 1.0d
        ),
        Role(
          frameworks = List("ID-awesome-framework-instance-1-tasks"),
          name = "awesome-framework-instance-1-tasks-role",
          resources = Resources(
            cpus = 16d,
            disk = 1024d,
            gpus = 0d,
            mem = 16384d,
            ports = None
          ),
          weight = 1.0d
        ),
        Role(
          frameworks = List("ID-other-framework-instance-1"),
          name = "other-framework-instance-1-role",
          resources = Resources(
            cpus = 0d,
            disk = 0d,
            gpus = 0d,
            mem = 0d,
            ports = None
          ),
          weight = 1.0d
        )
      )

      val frameworks = List[Framework](
        Framework(
          id = "ID-awesome-framework-instance-1-tasks",
          name = "NAME-awesome-framework-instance-1-tasks",
          pid = "scheduler-awesome-framework-instance-1-tasks@1.2.3.1:59180",
          active = true,
          role = "awesome-framework-instance-1-tasks-role",
          principal = Some("awesome-principal"),
          tasks = List(
            Task(
              id = "task_1-awesome-framework-instance-1-tasks",
              name = "task-1",
              slave_id = "slave-1",
              state = "TASK_RUNNING",
              statuses = List(),
              discovery = None
            ),
            Task(
              id = "task_2-awesome-framework-instance-1-tasks",
              name = "task-2",
              slave_id = "slave-2",
              state = "TASK_RUNNING",
              statuses = List(),
              discovery = None
            )
          ),
          unreachable_tasks = List()
        ),
        Framework(
          id = "ID-other-framework-instance-1",
          name = "NAME-other-framework-instance-1",
          pid = "scheduler-other-framework-instance-1@1.2.3.1:59180",
          active = true,
          role = "other-framework-instance-1-role",
          principal = Some("other-principal"),
          tasks = List(),
          unreachable_tasks = List()
        )
      )

      val slaves = List(
        Slave(
          id = "slave-1",
          pid = "slave(1)@1.2.3.1:5051",
          hostname = "1.2.3.1",
          reserved_resources_full = collection.immutable.HashMap(
            "other-framework-instance-1-role" -> List(
              ResourceReservation(
                name = "cpus",
                `type` = "SCALAR",
                role = "other-framework-instance-1-role",
                scalar = Some(Scalar(3d)),
                ranges = None,
                disk = None,
                reservation = Some(PrincipalAndLabels(
                  principal = Some("awesome-principal"),
                  labels = Some(Labels(
                    labels = List(
                      Label("resource_id", "resource-7")
                    )
                  ))
                ))
              ),
              ResourceReservation(
                name = "mem",
                `type` = "SCALAR",
                role = "other-framework-instance-1-role",
                scalar = Some(Scalar(256.0d)),
                ranges = None,
                disk = None,
                reservation = Some(PrincipalAndLabels(
                  principal = Some("awesome-principal"),
                  labels = Some(Labels(
                    labels = List(
                      Label("resource_id", "resource-8")
                    )
                  ))
                ))
              ),
              ResourceReservation(
                name = "disk",
                `type` = "SCALAR",
                role = "other-framework-instance-1-role",
                scalar = Some(Scalar(1024.0)),
                ranges = None,
                disk = Some(Disk(
                  persistence = Persistence(
                    id = "disk-1",
                    principal = Some("awesome-principal")
                  ),
                  volume = Volume(
                    mode = "RW",
                    container_path = "volume"
                  )
                )),
                reservation = Some(PrincipalAndLabels(
                  principal = Some("awesome-principal"),
                  labels = Some(Labels(
                    labels = List(
                      Label("resource_id", "resource-9")
                    )
                  ))
                ))
              )
            ),
            "awesome-framework-instance-1-tasks-role" -> List(
              ResourceReservation(
                name = "cpus",
                `type` = "SCALAR",
                role = "awesome-framework-instance-1-tasks-role",
                scalar = Some(Scalar(8d)),
                ranges = None,
                disk = None,
                reservation = Some(PrincipalAndLabels(
                  principal = Some("awesome-principal"),
                  labels = Some(Labels(
                    labels = List(
                      Label("resource_id", "resource-1")
                    )
                  ))
                ))
              ),
              ResourceReservation(
                name = "mem",
                `type` = "SCALAR",
                role = "awesome-framework-instance-1-tasks-role",
                scalar = Some(Scalar(512.0d)),
                ranges = None,
                disk = None,
                reservation = Some(PrincipalAndLabels(
                  principal = Some("awesome-principal"),
                  labels = Some(Labels(
                    labels = List(
                      Label("resource_id", "resource-2")
                    )
                  ))
                ))
              ),
              ResourceReservation(
                name = "disk",
                `type` = "SCALAR",
                role = "awesome-framework-instance-1-tasks-role",
                scalar = Some(Scalar(5120.0)),
                ranges = None,
                disk = Some(Disk(
                  persistence = Persistence(
                    id = "disk-1",
                    principal = Some("awesome-principal")
                  ),
                  volume = Volume(
                    mode = "RW",
                    container_path = "volume"
                  )
                )),
                reservation = Some(PrincipalAndLabels(
                  principal = Some("awesome-principal"),
                  labels = Some(Labels(
                    labels = List(
                      Label("resource_id", "resource-3")
                    )
                  ))
                ))
              )
            )
          )
        ),
        Slave(
          id = "slave-2",
          pid = "slave(2)@1.2.3.2:5051",
          hostname = "1.2.3.2",
          reserved_resources_full = collection.immutable.HashMap(
            "other-framework-instance-1-role" -> List(
              ResourceReservation(
                name = "cpus",
                `type` = "SCALAR",
                role = "other-framework-instance-1-role",
                scalar = Some(Scalar(3d)),
                ranges = None,
                disk = None,
                reservation = Some(PrincipalAndLabels(
                  principal = Some("awesome-principal"),
                  labels = Some(Labels(
                    labels = List(
                      Label("resource_id", "resource-4")
                    )
                  ))
                ))
              ),
              ResourceReservation(
                name = "mem",
                `type` = "SCALAR",
                role = "other-framework-instance-1-role",
                scalar = Some(Scalar(256.0d)),
                ranges = None,
                disk = None,
                reservation = Some(PrincipalAndLabels(
                  principal = Some("awesome-principal"),
                  labels = Some(Labels(
                    labels = List(
                      Label("resource_id", "resource-5")
                    )
                  ))
                ))
              ),
              ResourceReservation(
                name = "disk",
                `type` = "SCALAR",
                role = "other-framework-instance-1-role",
                scalar = Some(Scalar(1024.0)),
                ranges = None,
                disk = Some(Disk(
                  persistence = Persistence(
                    id = "disk-1",
                    principal = Some("awesome-principal")
                  ),
                  volume = Volume(
                    mode = "RW",
                    container_path = "volume"
                  )
                )),
                reservation = Some(PrincipalAndLabels(
                  principal = Some("awesome-principal"),
                  labels = Some(Labels(
                    labels = List(
                      Label("resource_id", "resource-6")
                    )
                  ))
                ))
              )
            ),
            "awesome-framework-instance-1-tasks-role" -> List(
              ResourceReservation(
                name = "cpus",
                `type` = "SCALAR",
                role = "awesome-framework-instance-1-tasks-role",
                scalar = Some(Scalar(8d)),
                ranges = None,
                disk = None,
                reservation = Some(PrincipalAndLabels(
                  principal = Some("awesome-principal"),
                  labels = Some(Labels(
                    labels = List(
                      Label("resource_id", "resource-4")
                    )
                  ))
                ))
              ),
              ResourceReservation(
                name = "mem",
                `type` = "SCALAR",
                role = "awesome-framework-instance-1-tasks-role",
                scalar = Some(Scalar(512.0d)),
                ranges = None,
                disk = None,
                reservation = Some(PrincipalAndLabels(
                  principal = Some("awesome-principal"),
                  labels = Some(Labels(
                    labels = List(
                      Label("resource_id", "resource-5")
                    )
                  ))
                ))
              ),
              ResourceReservation(
                name = "disk",
                `type` = "SCALAR",
                role = "awesome-framework-instance-1-tasks-role",
                scalar = Some(Scalar(5120.0)),
                ranges = None,
                disk = Some(Disk(
                  persistence = Persistence(
                    id = "disk-1",
                    principal = Some("awesome-principal")
                  ),
                  volume = Volume(
                    mode = "RW",
                    container_path = "volume"
                  )
                )),
                reservation = Some(PrincipalAndLabels(
                  principal = Some("awesome-principal"),
                  labels = Some(Labels(
                    labels = List(
                      Label("resource_id", "resource-6")
                    )
                  ))
                ))
              )
            )
          )
        )
      )

    }


    "should find reservations for roles without any task-running framework" in new MesosApiMock {

      val policy = new NoTaskRunningFrameworks()

      // other-framework-instance-1 's reservations should be selected
      policy.execute(roles, frameworks, slaves) shouldEqual HashMap(
        "slave-1" ->
          slaves.
            filter(_.id == "slave-1").
            flatMap(_.reserved_resources_full).
            filter((t) => { t._1 == "other-framework-instance-1-role" } ).
            flatMap(_._2)
        ,
        "slave-2" ->
          slaves.
            filter(_.id == "slave-2").
            flatMap(_.reserved_resources_full).
            filter((t) => { t._1 == "other-framework-instance-1-role" } ).
            flatMap(_._2)
      )


    }

  }

}
