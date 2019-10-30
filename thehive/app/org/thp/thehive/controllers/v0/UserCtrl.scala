package org.thp.thehive.controllers.v0

import scala.concurrent.ExecutionContext
import scala.util.Failure

import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, Results}

import javax.inject.{Inject, Singleton}
import org.thp.scalligraph.auth.AuthSrv
import org.thp.scalligraph.controllers.{EntryPoint, FieldsParser}
import org.thp.scalligraph.models.Database
import org.thp.scalligraph.query.{ParamQuery, PropertyUpdater, PublicProperty, Query}
import org.thp.scalligraph.steps.PagedResult
import org.thp.scalligraph.steps.StepsOps._
import org.thp.scalligraph.{AuthorizationError, RichOptionTry}
import org.thp.thehive.controllers.v0.Conversion._
import org.thp.thehive.dto.v0.InputUser
import org.thp.thehive.models._
import org.thp.thehive.services._

@Singleton
class UserCtrl @Inject()(
    entryPoint: EntryPoint,
    db: Database,
    properties: Properties,
    userSrv: UserSrv,
    profileSrv: ProfileSrv,
    authSrv: AuthSrv,
    organisationSrv: OrganisationSrv,
    auditSrv: AuditSrv,
    implicit val ec: ExecutionContext,
    roleSrv: RoleSrv
) extends QueryableCtrl {
  lazy val logger                                           = Logger(getClass)
  override val entityName: String                           = "user"
  override val publicProperties: List[PublicProperty[_, _]] = properties.user ::: metaProperties[UserSteps]
  override val initialQuery: Query =
    Query.init[UserSteps]("listUser", (graph, authContext) => organisationSrv.get(authContext.organisation)(graph).users)
  override val getQuery: ParamQuery[IdOrName] = Query.initWithParam[IdOrName, UserSteps](
    "getUser",
    FieldsParser[IdOrName],
    (param, graph, authContext) => userSrv.get(param.idOrName)(graph).visible(authContext)
  )
  override val pageQuery: ParamQuery[OutputParam] = Query.withParam[OutputParam, UserSteps, PagedResult[RichUser]](
    "page",
    FieldsParser[OutputParam],
    (range, userSteps, authContext) => userSteps.richUser(authContext.organisation).page(range.from, range.to, withTotal = true)
  )
  override val outputQuery: Query = Query.output[RichUser]()
  override val extraQueries: Seq[ParamQuery[_]] = Seq(
    Query[UserSteps, List[RichUser]]("toList", (userSteps, authContext) => userSteps.richUser(authContext.organisation).toList)
  )

  def current: Action[AnyContent] =
    entryPoint("current user")
      .authRoTransaction(db) { implicit request => implicit graph =>
        userSrv
          .get(request.userId)
          .richUser(request.organisation)
          .getOrFail()
          .orElse(
            userSrv
              .get(request.userId)
              .richUser(OrganisationSrv.administration.name)
              .getOrFail()
          )
          .map(user => Results.Ok(user.toJson))
      }

  def create: Action[AnyContent] =
    entryPoint("create user")
      .extract("user", FieldsParser[InputUser])
      .auth { implicit request =>
        val inputUser: InputUser = request.body("user")
        db.tryTransaction { implicit graph =>
            val organisationName = inputUser.organisation.getOrElse(request.organisation)
            for {
              _            <- userSrv.current.organisations(Permissions.manageUser).get(organisationName).existsOrFail()
              organisation <- organisationSrv.getOrFail(organisationName)
              profile <- if (inputUser.roles.contains("admin")) profileSrv.getOrFail(ProfileSrv.admin.name)
              else if (inputUser.roles.contains("write")) profileSrv.getOrFail(ProfileSrv.analyst.name)
              else if (inputUser.roles.contains("read")) profileSrv.getOrFail(ProfileSrv.readonly.name)
              else profileSrv.getOrFail(ProfileSrv.readonly.name)
              user <- userSrv.create(inputUser.toUser, inputUser.avatar, organisation, profile)
            } yield user
          }
          .flatMap { user =>
            inputUser
              .password
              .map(password => authSrv.setPassword(user._id, password))
              .flip
              .map(_ => Results.Created(user.toJson))
          }
      }

  // FIXME delete = lock or remove from organisation ?
  // lock make user unusable for all organisation
  // remove from organisation make the user disappear from organisation admin, and his profile is removed
  def delete(userId: String): Action[AnyContent] =
    entryPoint("delete user")
      .authTransaction(db) { implicit request => implicit graph =>
        for {
          user        <- userSrv.get(userId).getOrFail()
          _           <- userSrv.current.organisations(Permissions.manageUser).users.get(user).getOrFail()
          updatedUser <- userSrv.get(user).update("locked" -> true)
          _           <- auditSrv.user.delete(updatedUser)
        } yield Results.NoContent
      }

  def get(userId: String): Action[AnyContent] =
    entryPoint("get user")
      .authRoTransaction(db) { implicit request => implicit graph =>
        userSrv
          .get(userId)
          .visible
          .richUser(request.organisation)
          .getOrFail()
          .map(user => Results.Ok(user.toJson))
      }

  def update(userId: String): Action[AnyContent] =
    entryPoint("update user")
      .extract("user", FieldsParser.update("user", properties.user))
      .authTransaction(db) { implicit request => implicit graph =>
        val propertyUpdaters: Seq[PropertyUpdater] = request.body("user")
        for {
          user <- userSrv
            .update(userSrv.get(userId), propertyUpdaters) // Authorisation is managed in public properties
            .flatMap { case (user, _) => user.richUser(request.organisation).getOrFail() }
        } yield Results.Ok(user.toJson)

      }

  def setPassword(userId: String): Action[AnyContent] =
    entryPoint("set password")
      .extract("password", FieldsParser[String].on("password"))
      .auth { implicit request =>
        for {
          user <- db.roTransaction { implicit graph =>
            userSrv
              .get(userId)
              .getOrFail()
              .flatMap { u =>
                userSrv
                  .current
                  .organisations(Permissions.manageUser)
                  .users
                  .get(u)
                  .getOrFail()
              }
          }
          _ <- authSrv.setPassword(userId, request.body("password"))
          _ <- db.tryTransaction(implicit graph => auditSrv.user.update(user, Json.obj("password" -> "<hidden>")))
        } yield Results.NoContent
      }

  def changePassword(userId: String): Action[AnyContent] =
    entryPoint("change password")
      .extract("password", FieldsParser[String].on("password"))
      .extract("currentPassword", FieldsParser[String].on("currentPassword"))
      .auth { implicit request =>
        if (userId == request.userId) {
          for {
            user <- db.roTransaction(implicit graph => userSrv.get(userId).getOrFail())
            _    <- authSrv.changePassword(userId, request.body("currentPassword"), request.body("password"))
            _    <- db.tryTransaction(implicit graph => auditSrv.user.update(user, Json.obj("password" -> "<hidden>")))
          } yield Results.NoContent
        } else Failure(AuthorizationError(s"You are not authorized to change password of $userId"))
      }

  def getKey(userId: String): Action[AnyContent] =
    entryPoint("get key")
      .auth { implicit request =>
        for {
          user <- db.roTransaction { implicit graph =>
            userSrv
              .get(userId)
              .getOrFail()
              .flatMap { u =>
                userSrv
                  .current
                  .organisations(Permissions.manageUser)
                  .users
                  .get(u)
                  .getOrFail()
              }
          }
          key <- authSrv
            .getKey(user._id)
        } yield Results.Ok(key)
      }

  def removeKey(userId: String): Action[AnyContent] =
    entryPoint("remove key")
      .auth { implicit request =>
        for {
          user <- db.roTransaction { implicit graph =>
            userSrv
              .get(userId)
              .getOrFail()
              .flatMap { u =>
                userSrv
                  .current
                  .organisations(Permissions.manageUser)
                  .users
                  .get(u)
                  .getOrFail()
              }
          }
          _ <- authSrv.removeKey(userId)
          _ <- db.tryTransaction(implicit graph => auditSrv.user.update(user, Json.obj("key" -> "<hidden>")))
        } yield Results.NoContent
//          Failure(AuthorizationError(s"User $userId doesn't exist or permission is insufficient"))
      }

  def renewKey(userId: String): Action[AnyContent] =
    entryPoint("renew key")
      .auth { implicit request =>
        for {
          user <- db.roTransaction { implicit graph =>
            userSrv
              .get(userId)
              .getOrFail()
              .flatMap { u =>
                userSrv
                  .current
                  .organisations(Permissions.manageUser)
                  .users
                  .get(u)
                  .getOrFail()
              }
          }
          key <- authSrv.renewKey(userId)
          _   <- db.tryTransaction(implicit graph => auditSrv.user.update(user, Json.obj("key" -> "<hidden>")))
        } yield Results.Ok(key)
      }
}
