package org.thp.thehive.controllers.v1

import scala.util.Try

import play.api.libs.json.Json
import play.api.test.{FakeRequest, NoMaterializer, PlaySpecification}

import akka.stream.Materializer
import org.specs2.mock.Mockito
import org.specs2.specification.core.{Fragment, Fragments}
import org.thp.scalligraph.AppBuilder
import org.thp.scalligraph.auth.UserSrv
import org.thp.scalligraph.models.{Database, DatabaseProviders}
import org.thp.thehive.TestAppBuilder
import org.thp.thehive.dto.v1.{InputOrganisation, OutputOrganisation}
import org.thp.thehive.models._
import org.thp.thehive.services.OrganisationSrv

class OrganisationCtrlTest extends PlaySpecification with Mockito {
  implicit val mat: Materializer = NoMaterializer

  Fragments.foreach(new DatabaseProviders().list) { dbProvider =>
    val app: AppBuilder = TestAppBuilder(dbProvider)
    step(setupDatabase(app)) ^ specs(dbProvider.name, app) ^ step(teardownDatabase(app))
  }

  def setupDatabase(app: AppBuilder): Try[Unit] =
    app.instanceOf[DatabaseBuilder].build()(app.instanceOf[Database], app.instanceOf[UserSrv].getSystemAuthContext)

  def teardownDatabase(app: AppBuilder): Unit = app.instanceOf[Database].drop()

  def specs(name: String, app: AppBuilder): Fragment = {
    val organisationCtrl: OrganisationCtrl = app.instanceOf[OrganisationCtrl]

    s"[$name] organisation controller" should {

      "create a new organisation" in {
        val request = FakeRequest("POST", "/api/v1/organisation")
          .withJsonBody(Json.toJson(InputOrganisation(name = "orga1", "no description")))
          .withHeaders("user" -> "admin@thehive.local")
        val result = organisationCtrl.create(request)
        status(result) must_=== 201
        val resultOrganisation = contentAsJson(result).as[OutputOrganisation]
        resultOrganisation.name must_=== "orga1"
      }

      "refuse to create an user if the permission doesn't contain ManageOrganisation right" in {
        val request = FakeRequest("POST", "/api/v1/organisation")
          .withJsonBody(Json.toJson(InputOrganisation(name = "orga2", "no description")))
          .withHeaders("user" -> "user1@thehive.local")
        val result = organisationCtrl.create(request)
        status(result) must_=== 403
      }

      "get an organisation" in {
        val request = FakeRequest("GET", s"/api/v1/organisation/cert").withHeaders("user" -> "user1@thehive.local")
        val result  = organisationCtrl.get("cert")(request)
        status(result) must_=== 200
        val resultOrganisation = contentAsJson(result).as[OutputOrganisation]
        resultOrganisation.name must_=== "cert"
      }

      "get a visible organisation" in {
        val request = FakeRequest("GET", s"/api/v1/organisation/cert").withHeaders("user" -> "user3@thehive.local")
        val result  = organisationCtrl.get("cert")(request)
        status(result) must_=== 200
        val resultOrganisation = contentAsJson(result).as[OutputOrganisation]
        resultOrganisation.name must_=== "cert"
      }

      "refuse to get a invisible organisation" in {
        val request = FakeRequest("GET", s"/api/v1/organisation/${OrganisationSrv.administration.name}").withHeaders("user" -> "user1@thehive.local")
        val result  = organisationCtrl.get(OrganisationSrv.administration.name)(request)
        status(result) must_=== 404
      }
    }
  }
}
