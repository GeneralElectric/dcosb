package io.predix.dcosb.mesos

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorSystem, Props}
import akka.http.scaladsl.model.{DateTime => _, _}
import akka.http.scaladsl.server.ContentNegotiator.Alternative.ContentType
import akka.testkit.{CallingThreadDispatcher, TestActorRef, TestKit}
import org.scalatest.{FreeSpecLike, Matchers}
import org.scalamock.scalatest.MockFactory
import akka.pattern._
import akka.util.Timeout
import com.github.nscala_time.time.Imports._
import spray.json._
import com.typesafe.config.ConfigFactory
import io.predix.dcosb.util.ActorSuite
import io.predix.dcosb.util.actor.ConfiguredActor
import akka.http.scaladsl.marshalling.Marshal
import io.predix.dcosb.mesos.MesosApiClient.Master._
import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master

import collection.JavaConverters._
import scala.collection.immutable.HashMap
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.FiniteDuration
import scala.io.Source
import scala.util.{Failure, Success, Try}


class MesosApiClientTest extends ActorSuite {
  implicit val executionContext = system.dispatchers.lookup(CallingThreadDispatcher.Id)
  implicit val timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))

  trait HttpClientMock {

    val httpClient = mockFunction[HttpRequest, String, Future[(HttpResponse, String)]]

  }



  "A configured MesosApiClient" - {

    trait ConfiguredMesosApiClient extends HttpClientMock {

      val apiClient = TestActorRef(Props(classOf[MesosApiClient]).withDispatcher(CallingThreadDispatcher.Id))
      apiClient ! MesosApiClient.Configuration(httpClient)
      expectMsg(Success(ConfiguredActor.Configured()))

    }


    import MesosApiClient.MesosApiModel
    "responds with a sequence of Role objects to the message GetRoles" in new ConfiguredMesosApiClient {
      import MesosApiClient.MesosApiModel.Master.Roles.{RolesResponse,Role,Resources}

      val json = Source.fromURL(getClass.getResource("/roles.json")).getLines.mkString

      httpClient expects(*, *) returning Future.successful((HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, json)), ""))

      // verify respomnse is Seq[Role]
      implicit val timeout: Timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))
      Await.result(apiClient ? MesosApiClient.Master.GetRoles(), timeout.duration) shouldEqual Success(List(
            Role(
              frameworks = List("62033811-f2cd-47f1-97a1-a3b2f28802a4-0000"),
              name = "*",
              resources = Resources(
                cpus = 0d,
                disk = 0d,
                gpus = 0d,
                mem = 0d,
                ports = None
              ),
              weight = 1.0d
            ),
            Role(
              frameworks = List("62033811-f2cd-47f1-97a1-a3b2f28802a4-0006"),
              name = "predix-andras-101-role",
              resources = Resources(
                cpus = 2.5d,
                disk = 40960.0d,
                gpus = 0d,
                mem = 9216.0d,
                ports = Some("[24872-24872, 26522-26522, 28382-28382, 29466-29466, 29893-29893, 29968-29968]")
              ),
              weight = 1.0d
            )
            ,Role(
              frameworks = List("62033811-f2cd-47f1-97a1-a3b2f28802a4-0007"),
              name = "predix-demo-1-role",
              resources = Resources(
                cpus = 5.0d,
                disk = 81920.0d,
                gpus = 0d,
                mem = 18432.0d,
                ports = Some("[21468-21468, 21832-21832, 25525-25525, 26866-26866, 27914-27914, 29853-29853]")
              ),
              weight = 1.0d
            ),
            Role(
              frameworks = List("62033811-f2cd-47f1-97a1-a3b2f28802a4-0001"),
              name = "slave_public",
              resources = Resources(
                cpus = 1.1d,
                disk = 1024.0d,
                gpus = 0d,
                mem = 4608.0d,
                ports = Some("[24470-24470, 25598-25598, 27958-27958]")
              ),
              weight = 1.0d
            )
          ))


    }


    "responds with a sequence of Slave objects to the message GetSlaves" in new ConfiguredMesosApiClient {
      import MesosApiClient.MesosApiModel.Master.Slaves.{Disk, Label, Labels, Persistence, PrincipalAndLabels, Range, Ranges, ResourceReservation, Scalar, Slave, Volume}

      val json = Source.fromURL(getClass.getResource("/slaves.json")).getLines.mkString

      httpClient expects(*, *) returning Future.successful((HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, json)), ""))

      // verify response is Seq[Slave]
      implicit val timeout: Timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))
      Await.result(apiClient ? MesosApiClient.Master.GetSlaves(), timeout.duration) shouldEqual Success(List(
            Slave(
              id = "f18fcd65-5c63-4dfb-b233-f289aac416c5-S43",
              pid = "slave(1)@10.72.154.222:5051",
              hostname = "10.72.154.222",
              reserved_resources_full = collection.immutable.HashMap(
                "predix-c6be02f0-11d7-4977-b0d8-0a56e0e3b179-role" -> List(
                  ResourceReservation(
                    name = "cpus",
                    `type` = "SCALAR",
                    role = "predix-c6be02f0-11d7-4977-b0d8-0a56e0e3b179-role",
                    scalar = Some(Scalar(0.5d)),
                    ranges = None,
                    disk = None,
                    reservation = Some(PrincipalAndLabels(
                      principal = Some("cassandra-principal"),
                      labels = Some(Labels(
                        labels = List(
                          Label("resource_id", "184c3a1f-85bb-4bd2-8f3f-4eb4e7696038")
                        )
                      ))
                    ))
                  ),
                  ResourceReservation(
                    name = "mem",
                    `type` = "SCALAR",
                    role = "predix-c6be02f0-11d7-4977-b0d8-0a56e0e3b179-role",
                    scalar = Some(Scalar(1024.0d)),
                    ranges = None,
                    disk = None,
                    reservation = Some(PrincipalAndLabels(
                      principal = Some("cassandra-principal"),
                      labels = Some(Labels(
                        labels = List(
                          Label("resource_id", "4e3affdd-10d8-4391-958d-afa15c52ccd6")
                        )
                      ))
                    ))
                  ),
                  ResourceReservation(
                    name = "ports",
                    `type` = "RANGES",
                    role = "predix-c6be02f0-11d7-4977-b0d8-0a56e0e3b179-role",
                    scalar = None,
                    disk = None,
                    ranges = Some(Ranges(
                      range = List(
                        Range(20645, 20645)
                      )
                    )),
                    reservation = Some(PrincipalAndLabels(
                      principal = Some("cassandra-principal"),
                      labels = Some(Labels(
                        labels = List(
                          Label("resource_id", "323e2d42-10cc-4adf-bfae-e339ce7c9758")
                        )
                      ))
                    ))
                  ),
                  ResourceReservation(
                    name = "disk",
                    `type` = "SCALAR",
                    role = "predix-c6be02f0-11d7-4977-b0d8-0a56e0e3b179-role",
                    scalar = Some(Scalar(40960.0)),
                    ranges = None,
                    disk = Some(Disk(
                      persistence = Persistence(
                        id = "e2ec048d-f700-4ecd-894c-262214efa9d4",
                        principal = Some("cassandra-principal")
                      ),
                      volume = Volume(
                        mode = "RW",
                        container_path = "volume"
                      )
                    )),
                    reservation = Some(PrincipalAndLabels(
                      principal = Some("cassandra-principal"),
                      labels = Some(Labels(
                        labels = List(
                          Label("resource_id", "407f8823-0fc8-4819-b7f6-078c9c2254d7")
                        )
                      ))
                    ))
                  )
                )
              )
            )))


    }

    import MesosApiClient.MesosApiModel.Master.Frameworks.{Framework, Task, Status, ContainerStatus, ContainerId, Discovery, Ports, Port, Protocol, Visibility}
    trait FrameworksHttpResponse extends ConfiguredMesosApiClient {

      val json = Source.fromURL(getClass.getResource("/frameworks.json")).getLines.mkString
      httpClient expects(*, *) returning Future.successful((HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, json)), ""))
    }

    trait EmptyFrameworksHttpResponse extends ConfiguredMesosApiClient {

      httpClient expects(*, *) returning Future.successful((HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, """{"frameworks":[],"unregistered_frameworks":[],"completed_frameworks":[]}""")), ""))

    }

    trait FrameworkStub {

      val frakework = Framework(
        id = "62033811-f2cd-47f1-97a1-a3b2f28802a4-0007",
        name = "predix-demo-1",
        pid = "scheduler-050103fc-664a-49ed-9bc2-d2100d9fb5f4@10.42.60.66:59180",
        active = true,
        role = "predix-demo-1-role",
        principal = Some("cassandra-principal"),
        tasks = List(
          Task(
            id = "node-1__bf5de62a-ab45-4843-8cd0-0fbca2c5e4c0",
            name = "node-1",
            slave_id = "62033811-f2cd-47f1-97a1-a3b2f28802a4-S2",
            state = "TASK_RUNNING",
            statuses = List(
              Status(
                state = "TASK_RUNNING",
                container_status = ContainerStatus(
                  container_id = ContainerId("4f9af14a-abbf-4558-af92-32496565d1aa")
                )
              )
            ),
            discovery = Some(Discovery(
              visibility = Some(Visibility.EXTERNAL),
              name = "node-1",
              ports = Some(Ports(
                ports = Some(List(Port(number = 25525, protocol = Protocol.TCP, visibility = None, name = None)))
              ))
            ))
          ),
          Task(
            id = "node-0__9228c40a-ceb6-48f7-9a33-b7b8fe8af011",
            name = "node-0",
            slave_id = "62033811-f2cd-47f1-97a1-a3b2f28802a4-S1",
            state = "TASK_RUNNING",
            statuses = List(
              Status(
                state = "TASK_RUNNING",
                container_status = ContainerStatus(
                  container_id = ContainerId("d73fa218-5e65-4bd4-9209-0cefa82398b0")
                )
              )
            ),
            discovery = Some(Discovery(
              visibility = Some(Visibility.EXTERNAL),
              name = "node-0",
              ports = Some(Ports(
                ports = Some(List(Port(number = 25525, protocol = Protocol.TCP, visibility = None, name = None)))
              ))
            ))

          )
        ),
        unreachable_tasks = List()
      )

    }

    "responds with a sequence of Framework objects to the message GetFrameworks" in new FrameworksHttpResponse with FrameworkStub {

      // verify response is Seq[Frameworks]
      implicit val timeout: Timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))

      Await.result(apiClient ? MesosApiClient.Master.GetFrameworks(), timeout.duration) shouldEqual Success(List(frakework))


    }

    "given a GetFramework message" - {

      "with an existing framework id" - {

        "responds with a Success(Framework) object for the provided framework id" in new FrameworksHttpResponse with FrameworkStub {

          implicit val timeout: Timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))

          Await.result(apiClient ? MesosApiClient.Master.GetFramework("62033811-f2cd-47f1-97a1-a3b2f28802a4-0007"), timeout.duration) shouldEqual Success(frakework)



        }

      }

      "with a non-existant framework id" - {

        "responds with a Failure(FrameworkNotFound) object" in new EmptyFrameworksHttpResponse {

          implicit val timeout: Timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))
          Await.result(apiClient ? MesosApiClient.Master.GetFramework("foo"), timeout.duration) shouldEqual Failure(FrameworkNotFound("foo"))

        }

      }

    }

    "responds with a StateSummary object to the message GetStateSummary" in new ConfiguredMesosApiClient {
      import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master.StateSummary._

      val summary = MesosApiClient.Master.StateSummary(
        frameworks = List(
          FrameworkSummary(
            id = "foo-id",
            name = "foo-name")))

      httpClient expects(*, *) returning Future.successful((HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, """{"frameworks":[{"id":"foo-id","name":"foo-name"}]}""")), ""))

      implicit val timeout: Timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))
      Await.result(apiClient? MesosApiClient.Master.GetStateSummary(), timeout.duration) shouldEqual
        Success(summary)

    }

    "given a map of mixed valid ( existing and un-reserving principal has permissions to un-reserve ) and invalid ( non-existing or un-reserving principal has no permissions to un-reserve ) slave id to sequence of resource reservations mapping" - {
      import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master.Slaves.{ResourceReservation, Scalar, PrincipalAndLabels, Labels, Label, Disk, Persistence, Volume, Ranges, Range}

      val r = HashMap(
        "s1" -> Vector(
          ResourceReservation(
            name = "cpus",
            `type` = "SCALAR",
            scalar = Some(Scalar(value = 0.5)),
            role = "elastic12345-role",
            reservation = Some(PrincipalAndLabels(
              principal = Some("elastic12345-principal"),
              labels = Some(Labels(
                labels = List(Label(key = "resource_id", value = "6ec82995-b74d-49c8-95ab-ffb010cdf769"))
              ))
            ))
          ),
          // unreserving this should be denied
          ResourceReservation(
            name = "disk",
            `type` = "SCALAR",
            scalar = Some(Scalar(value = 1024.0)),
            role = "deity",
            reservation = Some(PrincipalAndLabels(
              principal = Some("god"),
              labels = Some(Labels(
                labels = List(Label(key = "resource_id", value = "eden-media-1"))
              ))
            )),
            disk = Some(Disk(
              persistence = Persistence(
                id = "eden-media-1-disk",
                principal = Some("god")
              ),
              volume = Volume(
                mode = "RW",
                container_path = "eden"
              )
            ))
          )
        ),
        "s2" -> Vector(
        ResourceReservation(
          name = "cpus",
          `type` = "SCALAR",
          scalar = Some(Scalar(value = 1.0)),
          role = "elastic12345-role",
          reservation = Some(PrincipalAndLabels(
            principal = Some("elastic12345-principal"),
            labels = Some(Labels(
              labels = List(Label(key = "resource_id", value = "bb2dd1c6-2251-4337-9da8-14cbf8c51a63"))
            ))
          ))
        ),
        // this shouldn't exist
        ResourceReservation(
          name = "cpus",
          `type` = "SCALAR",
          scalar = Some(Scalar(value = 16.0)),
          role = "some-role",
          reservation = Some(PrincipalAndLabels(
            principal = Some("some-principal"),
            labels = Some(Labels(
              labels = List(Label(key = "resource_id", value = "some-resource-id"))
            ))
          ))
        ),
        ResourceReservation(
          name = "disk",
          `type` = "SCALAR",
          scalar = Some(Scalar(value = 2000.0)),
          role = "elastic12345-role",
          reservation = Some(PrincipalAndLabels(
            principal = Some("elastic12345-principal"),
            labels = Some(Labels(
              labels = List(Label(key = "resource_id", value = "133f277b-32b0-462c-8d1b-92198533c701"))
            ))
          )),
          disk = Some(Disk(
            persistence = Persistence(
              id = "a1b35f74-4e92-4408-9047-a30722c4c756",
              principal = Some("elastic12345-principal")
            ),
            volume = Volume(
              mode = "RW",
              container_path = "container-path"
            )
          ))
        ),
        ResourceReservation(
          name = "ports",
          `type` = "RANGES",
          ranges = Some(Ranges(
            range = List(Range(begin = 1025, end = 1025), Range(begin = 9300, end = 9300))
          )),
          role = "elastic12345-role",
          reservation = Some(PrincipalAndLabels(
            principal = Some("elastic12345-principal"),
            labels = Some(Labels(
              labels = List(Label(key = "resource_id", value = "133f277b-32b0-462c-8d1b-92198533c701"))
            ))
          ))
        )

      ))
      "utilises it's configured http client to unreserve these resources on all slaves" in new Master.JsonSupport with ConfiguredMesosApiClient {

        httpClient expects(*,*) onCall { (request, context) =>
        context match {
          case c if c == r.get("s1").get(1).hashCode().toString => Future.successful((HttpResponse(status = StatusCodes.Unauthorized), context))
          case c if c == r.get("s2").get(1).hashCode().toString => Future.successful((HttpResponse(status = StatusCodes.NotFound), context))
          case _=> Future.successful((HttpResponse(status = StatusCodes.Accepted), context))
        }
        } anyNumberOfTimes()
        // ^ effectively a stub, because for some #&^$@( reason httprequest equality is borked when an entity is present ?!

        Await.result((apiClient ? MesosApiClient.Master.Unreserve(r)), FiniteDuration(1, TimeUnit.SECONDS)) match {
          case Success(results: ResourceUnreservationResults) =>
            results.resourceReservations should contain theSameElementsAs
              Iterable(
                ResourceUnreserved(r.get("s1").get(0)),
                InsufficientPermissionsToUnreserve(r.get("s1").get(1)),
                ResourceUnreserved(r.get("s2").get(0)),
                ReservationNotFound(r.get("s2").get(1)),
                ResourceUnreserved(r.get("s2").get(2)),
                ResourceUnreserved(r.get("s2").get(3))
              )

          case e => fail(s"Unexpected response: $e")
        }





      }

    }
  }

}

class MesosApiClientJsonSupportTest extends FreeSpecLike with Matchers {

  "given a valid json string representing framework state" - {

    val json = Source.fromURL(getClass.getResource("/frameworks.json")).getLines.mkString

    "formats in implicit scope in JsonSupport should create an object graph representing framework state" in {
      import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master.Frameworks._

      val parser = new JsonSupport {

        def fromString(json: String): FrameworksResponse = frameworksResponseFormat.read(json.parseJson)

      }

      parser.fromString(json) shouldEqual FrameworksResponse(
        List(
          Framework(
            id = "62033811-f2cd-47f1-97a1-a3b2f28802a4-0007",
            name = "predix-demo-1",
            pid = "scheduler-050103fc-664a-49ed-9bc2-d2100d9fb5f4@10.42.60.66:59180",
            active = true,
            role = "predix-demo-1-role",
            principal = Some("cassandra-principal"),
            tasks = List(
              Task(
                id = "node-1__bf5de62a-ab45-4843-8cd0-0fbca2c5e4c0",
                name = "node-1",
                slave_id = "62033811-f2cd-47f1-97a1-a3b2f28802a4-S2",
                state = "TASK_RUNNING",
                statuses = List(
                  Status(
                    state = "TASK_RUNNING",
                    container_status = ContainerStatus(
                      container_id = ContainerId("4f9af14a-abbf-4558-af92-32496565d1aa")
                    )
                  )
                ),
                discovery = Some(Discovery(
                  visibility = Some(Visibility.EXTERNAL),
                  name = "node-1",
                  ports = Some(Ports(
                    ports = Some(List(Port(number = 25525, protocol = Protocol.TCP, visibility = None, name = None)))
                  ))
                ))
              ),
              Task(
                id = "node-0__9228c40a-ceb6-48f7-9a33-b7b8fe8af011",
                name = "node-0",
                slave_id = "62033811-f2cd-47f1-97a1-a3b2f28802a4-S1",
                state = "TASK_RUNNING",
                statuses = List(
                  Status(
                    state = "TASK_RUNNING",
                    container_status = ContainerStatus(
                      container_id = ContainerId("d73fa218-5e65-4bd4-9209-0cefa82398b0")
                    )
                  )
                ),
                discovery = Some(Discovery(
                  visibility = Some(Visibility.EXTERNAL),
                  name = "node-0",
                  ports = Some(Ports(
                    ports = Some(List(Port(number = 25525, protocol = Protocol.TCP, visibility = None, name = None)))
                  ))
                ))
              )
            ),
            unreachable_tasks = List()
          )

        )
      )

    }

  }

  "given a valid json string representing role state" - {

    val json = Source.fromURL(getClass.getResource("/roles.json")).getLines.mkString

    "formats in implicit scope in JsonSupport should create an object graph representing role state" in {
      import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master.Roles._

      val parser = new JsonSupport {

        def fromString(json: String): RolesResponse = rolesResponseFormat.read(json.parseJson)

      }

      parser.fromString(json) shouldEqual RolesResponse(
        List(
          Role(
            frameworks = List("62033811-f2cd-47f1-97a1-a3b2f28802a4-0000"),
            name = "*",
            resources = Resources(
              cpus = 0d,
              disk = 0d,
              gpus = 0d,
              mem = 0d,
              ports = None
            ),
            weight = 1.0d
          ),
          Role(
            frameworks = List("62033811-f2cd-47f1-97a1-a3b2f28802a4-0006"),
            name = "predix-andras-101-role",
            resources = Resources(
              cpus = 2.5d,
              disk = 40960.0d,
              gpus = 0d,
              mem = 9216.0d,
              ports = Some("[24872-24872, 26522-26522, 28382-28382, 29466-29466, 29893-29893, 29968-29968]")
            ),
            weight = 1.0d
          )
          ,Role(
            frameworks = List("62033811-f2cd-47f1-97a1-a3b2f28802a4-0007"),
            name = "predix-demo-1-role",
            resources = Resources(
              cpus = 5.0d,
              disk = 81920.0d,
              gpus = 0d,
              mem = 18432.0d,
              ports = Some("[21468-21468, 21832-21832, 25525-25525, 26866-26866, 27914-27914, 29853-29853]")
            ),
            weight = 1.0d
          ),
          Role(
            frameworks = List("62033811-f2cd-47f1-97a1-a3b2f28802a4-0001"),
            name = "slave_public",
            resources = Resources(
              cpus = 1.1d,
              disk = 1024.0d,
              gpus = 0d,
              mem = 4608.0d,
              ports = Some("[24470-24470, 25598-25598, 27958-27958]")
            ),
            weight = 1.0d
          )
        )
      )

    }

  }

  "given a valid json string representing slave state" - {

    val json = Source.fromURL(getClass.getResource("/slaves.json")).getLines.mkString

    "formats in implicit scope in JsonSupport should create an object graph representing slave state" in {
      import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master.Slaves._

      val parser = new JsonSupport {

        def fromString(json: String): SlavesResponse = slavesResponseFormat.read(json.parseJson)

      }

      parser.fromString(json) shouldEqual SlavesResponse(
        List(
          Slave(
            id = "f18fcd65-5c63-4dfb-b233-f289aac416c5-S43",
            pid = "slave(1)@10.72.154.222:5051",
            hostname = "10.72.154.222",
            reserved_resources_full = collection.immutable.HashMap(
              "predix-c6be02f0-11d7-4977-b0d8-0a56e0e3b179-role" -> List(
                ResourceReservation(
                  name = "cpus",
                  `type` = "SCALAR",
                  role = "predix-c6be02f0-11d7-4977-b0d8-0a56e0e3b179-role",
                  scalar = Some(Scalar(0.5d)),
                  ranges = None,
                  disk = None,
                  reservation = Some(PrincipalAndLabels(
                    principal = Some("cassandra-principal"),
                    labels = Some(Labels(
                      labels = List(
                        Label("resource_id", "184c3a1f-85bb-4bd2-8f3f-4eb4e7696038")
                      )
                    ))
                  ))
                ),
                ResourceReservation(
                  name = "mem",
                  `type` = "SCALAR",
                  role = "predix-c6be02f0-11d7-4977-b0d8-0a56e0e3b179-role",
                  scalar = Some(Scalar(1024.0d)),
                  ranges = None,
                  disk = None,
                  reservation = Some(PrincipalAndLabels(
                    principal = Some("cassandra-principal"),
                    labels = Some(Labels(
                      labels = List(
                        Label("resource_id", "4e3affdd-10d8-4391-958d-afa15c52ccd6")
                      )
                    ))
                  ))
                ),
                ResourceReservation(
                  name = "ports",
                  `type` = "RANGES",
                  role = "predix-c6be02f0-11d7-4977-b0d8-0a56e0e3b179-role",
                  scalar = None,
                  disk = None,
                  ranges = Some(Ranges(
                    range = List(
                      Range(20645, 20645)
                    )
                  )),
                  reservation = Some(PrincipalAndLabels(
                    principal = Some("cassandra-principal"),
                    labels = Some(Labels(
                      labels = List(
                        Label("resource_id", "323e2d42-10cc-4adf-bfae-e339ce7c9758")
                      )
                    ))
                  ))
                ),
                ResourceReservation(
                  name = "disk",
                  `type` = "SCALAR",
                  role = "predix-c6be02f0-11d7-4977-b0d8-0a56e0e3b179-role",
                  scalar = Some(Scalar(40960.0)),
                  ranges = None,
                  disk = Some(Disk(
                    persistence = Persistence(
                      id = "e2ec048d-f700-4ecd-894c-262214efa9d4",
                      principal = Some("cassandra-principal")
                    ),
                    volume = Volume(
                      mode = "RW",
                      container_path = "volume"
                    )
                  )),
                  reservation = Some(PrincipalAndLabels(
                    principal = Some("cassandra-principal"),
                    labels = Some(Labels(
                      labels = List(
                        Label("resource_id", "407f8823-0fc8-4819-b7f6-078c9c2254d7")
                      )
                    ))
                  ))
                )
              )
            )
          )))



    }

  }

  "given a valid json string representing master state summary" - {

    val json = Source.fromURL(getClass.getResource("/state-summary.json")).getLines.mkString

    "formats in implicit scope in JsonSupport should create an object graph representing master state summary" in {
      import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master.StateSummary._

      val parser = new JsonSupport {

        def fromString(json: String): StateSummaryResponse = stateSummaryResponseFormat.read(json.parseJson)

      }

      parser.fromString(json) shouldEqual
        StateSummaryResponse(
          frameworks = List(
            FrameworkSummary(
              id = "1a5c08b6-4138-4205-aeab-630cbeb99b22-0000",
              name = "metronome")))

    }

  }

}
