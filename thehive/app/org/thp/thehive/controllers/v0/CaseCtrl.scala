package org.thp.thehive.controllers.v0

import javax.inject.{Inject, Singleton}
import org.thp.scalligraph.RichSeq
import org.thp.scalligraph.controllers.{EntryPoint, FieldsParser}
import org.thp.scalligraph.models.{Database, Entity, PagedResult}
import org.thp.scalligraph.query.{PropertyUpdater, Query}
import org.thp.thehive.dto.v0.InputCase
import org.thp.thehive.models.{Permissions, RichCaseTemplate, User}
import org.thp.thehive.services._
import play.api.Logger
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.{Action, AnyContent, Results}

import scala.util.{Success, Try}

@Singleton
class CaseCtrl @Inject()(
    entryPoint: EntryPoint,
    db: Database,
    caseSrv: CaseSrv,
    caseTemplateSrv: CaseTemplateSrv,
    taskSrv: TaskSrv,
    userSrv: UserSrv,
    organisationSrv: OrganisationSrv,
    val queryExecutor: TheHiveQueryExecutor
) extends QueryCtrl
    with CaseConversion {

  lazy val logger = Logger(getClass)

  def create: Action[AnyContent] =
    entryPoint("create case")
      .extract('case, FieldsParser[InputCase])
      .extract('caseTemplate, FieldsParser[String].optional.on("caseTemplate"))
      .authTransaction(db) { implicit request ⇒ implicit graph ⇒
        val caseTemplateName: Option[String] = request.body('caseTemplate)
        for {
          caseTemplate ← caseTemplateName
            .fold[Try[Option[RichCaseTemplate]]](Success(None)) { templateName ⇒
              caseTemplateSrv
                .get(templateName)
                .visible
                .richCaseTemplate
                .getOrFail()
                .map(Some.apply)
            }
          inputCase: InputCase = request.body('case)
          case0                = fromInputCase(inputCase, caseTemplate)
          user         ← inputCase.user.fold[Try[Option[User with Entity]]](Success(None))(u ⇒ userSrv.getOrFail(u).map(Some.apply))
          organisation ← organisationSrv.getOrFail(request.organisation)
          customFields = inputCase.customFieldValue.map(fromInputCustomField).toMap
          richCase ← caseSrv.create(case0, user, organisation, customFields, caseTemplate)

          _ = caseTemplate.foreach { ct ⇒
            caseTemplateSrv.get(ct.caseTemplate).tasks.toList().foreach { task ⇒
              taskSrv.create(task, richCase.`case`)
            }
          }
        } yield Results.Created(richCase.toJson)
      }

  def get(caseIdOrNumber: String): Action[AnyContent] =
    entryPoint("get case")
      .authTransaction(db) { implicit request ⇒ implicit graph ⇒
        caseSrv
          .get(caseIdOrNumber)
          .visible
          .richCase
          .getOrFail()
          .map(richCase ⇒ Results.Ok(richCase.toJson))
      }

  def list: Action[AnyContent] =
    entryPoint("list case")
      .authTransaction(db) { implicit request ⇒ implicit graph ⇒
        val cases = caseSrv
          .initSteps
          .visible
          .richCase
          .map(_.toJson)
          .toList()
        Success(Results.Ok(Json.toJson(cases)))
      }

  def search: Action[AnyContent] =
    entryPoint("search case")
      .extract('query, searchParser("listCase"))
      .authTransaction(db) { implicit request ⇒ graph ⇒
        val query: Query = request.body('query)
        val result       = queryExecutor.execute(query, graph, request.authContext)
        val resp         = Results.Ok((result.toJson \ "result").as[JsValue])
        result.toOutput match {
          case PagedResult(_, Some(size)) ⇒ Success(resp.withHeaders("X-Total" → size.toString))
          case _                          ⇒ Success(resp)
        }
      }

  def stats: Action[AnyContent] =
    entryPoint("case stats")
      .extract('query, statsParser("listCase"))
      .authTransaction(db) { implicit request ⇒ graph ⇒
        val queries: Seq[Query] = request.body('query)
        val results = queries
          .map(query ⇒ queryExecutor.execute(query, graph, request.authContext).toJson)
          .foldLeft(JsObject.empty) {
            case (acc, o: JsObject) ⇒ acc ++ o
            case (acc, r) ⇒
              logger.warn(s"Invalid stats result: $r")
              acc
          }
        Success(Results.Ok(results))
      }

  def update(caseIdOrNumber: String): Action[AnyContent] =
    entryPoint("update case")
      .extract('case, FieldsParser.update("case", caseProperties))
      .authTransaction(db) { implicit request ⇒ implicit graph ⇒
        val propertyUpdaters: Seq[PropertyUpdater] = request.body('case)
        caseSrv
          .get(caseIdOrNumber)
          .can(Permissions.manageCase)
          .updateProperties(propertyUpdaters)
          .flatMap(_ ⇒ {
            caseSrv
              .get(caseIdOrNumber)
              .visible
              .richCase
              .getOrFail()
              .map(richCase ⇒ Results.Ok(richCase.toJson))
          })
      }

  def delete(caseIdOrNumber: String): Action[AnyContent] =
    entryPoint("delete case")
      .authTransaction(db) { implicit request ⇒ implicit graph ⇒
        caseSrv
          .get(caseIdOrNumber)
          .can(Permissions.manageCase)
          .update("status" → "deleted")
          .map(_ ⇒ Results.NoContent)
      }

  def merge(caseIdsOrNumbers: String): Action[AnyContent] =
    entryPoint("merge cases")
      .authTransaction(db) { implicit request ⇒ implicit graph ⇒
        caseIdsOrNumbers
          .split(',')
          .toSeq
          .toTry(
            caseSrv
              .get(_)
              .visible
              .getOrFail()
          )
          .map { cases ⇒
            val mergedCase = caseSrv.merge(cases)
            Results.Ok(mergedCase.toJson)
          }
      }

  def linkedCases(caseIdsOrNumber: String): Action[AnyContent] =
    entryPoint("case link")
      .authTransaction(db) { _ ⇒ implicit graph ⇒
        val relatedCases = caseSrv
          .get(caseIdsOrNumber)
          .linkedCases
          .richCase
          .map(_.toJson)
          .toList()
        Success(Results.Ok(Json.toJson(relatedCases)))
      }
}
