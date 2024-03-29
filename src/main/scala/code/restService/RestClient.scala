package code.restService

import zio.{Scope, ZIO, ZLayer}
import zio.http._
import code.lib.AppAux._
import code.model.Entities._
import code.model.Entities.ErrorTypes._

import java.time.Instant

// RestClient layer
// retrieves repos by organization and contributors by repo by making calls to the GITHub REST service
trait RestClient {
  def reposByOrganization(organization: Organization): ZIO[Client, ErrorTypeE, List[Repository]]
  def contributorsByRepo(organization: Organization, repo: Repository): ZIO[Client, ErrorTypeE, List[Contributor]]
}

final case class RestClientLive() extends RestClient {

  def reposByOrganization(organization: Organization): ZIO[Client, ErrorTypeE, List[Repository]] = {
    val resp = processResponseBody(s"https://api.github.com/orgs/$organization/repos") { responsePage =>
      val full_name_RE = s""","full_name":"$organization/([^"]+)",""".r
      val full_name_I = for (full_name_RE(full_name) <- full_name_RE.findAllIn(responsePage)) yield full_name
      val updated_at_RE = s""","updated_at":"([^"]+)",""".r
      val updated_at_I = for (updated_at_RE(updated_at) <- updated_at_RE.findAllIn(responsePage)) yield updated_at
      full_name_I.zip(updated_at_I).map(p => Repository(p._1, Instant.parse(p._2))).toList
    }
    resp
  }

  def contributorsByRepo(organization: Organization, repo: Repository): ZIO[Client, ErrorTypeE, List[Contributor]] = {
    val resp = processResponseBody(s"https://api.github.com/repos/$organization/${repo.name}/contributors") { responsePage =>
      val login_RE = """"login":"([^"]+)"""".r
      val login_I = for (login_RE(login) <- login_RE.findAllIn(responsePage)) yield login
      val contributions_RE = """"contributions":([0-9]+)""".r
      val contributions_I = for (contributions_RE(contributions) <- contributions_RE.findAllIn(responsePage)) yield contributions
      val contributors_L = login_I.zip(contributions_I).map(p => Contributor(repo.name, p._1, p._2.toInt)).toList
      contributors_L
    }
    resp
  }

  private def processResponseBody[T](url: String)(processPage: BodyType => List[T]): ZIO[Client, ErrorTypeE, List[T]] = {

    def processResponsePage(processedPages: List[T], pageNumber: Int): ZIO[Client, ErrorTypeE, List[T]] = {
      getResponseBody(s"$url?page=$pageNumber&per_page=100").flatMap {
        case Right(pageBody) if pageBody.length > 2 =>
          val processedPage = processPage(pageBody)
          processResponsePage(processedPages ++ processedPage, pageNumber + 1)
        case Right(_) =>
          ZIO.succeed(processedPages)
        case Left(error) =>
          ZIO.fail(error)
      }
    }

    processResponsePage(List.empty[T], 1)
  }

  private def getResponseBody(urlS: String): ZIO[Any, Nothing, Either[ErrorTypeE, BodyType]] = {

    def responseBody(body: BodyType, status: Status): Either[ErrorTypeE, BodyType] =
      status match {
        case Status.Ok =>
          Right(body)
        case Status.NotFound =>
          Left(OrganizationNotFound) // Non-existent organization
        case Status.Forbidden =>
          Left(LimitExceeded) // GitHub API rate limit exceeded
        case _ =>
          Left(UnexpectedError) // GitHub API unexpected StatusCode
      }

    val token = if (gh_token != null) gh_token else ""
    val url = URL.decode(urlS).toOption.get
    val request = Request(Version.Default, Method.GET, url, Headers("Authorization" -> token))
    val bodyZ = for {
      response <- Client.request(request)
      bodyString <- response.body.asString
    } yield responseBody(bodyString, response.status)

    bodyZ.provide(Client.default, Scope.default).orDie

  }

}

object RestClientLive {
  val layer =
    ZLayer.succeed(new RestClientLive())
}
