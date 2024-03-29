package code.restService

import zio._
import zio.json._
import zio.macros._
import zio.http._

import code.model.Entities._
import code.model.Entities.ErrorTypes._

// RestServer layer
@accessible
trait RestServer {
  val runServer: ZIO[Any, Throwable, ExitCode]
  def contributorsByOrganization(organization: Organization, groupLevel: String, minContribs: Int):
  ZIO[Client, ErrorTypeE, List[Contributor]]
  def groupContributors(organization: Organization,
                        groupLevel: String,
                        minContribs: Int,
                        contributorsDetailed: List[Contributor]): List[Contributor]
}

final case class RestServerLive(restClient: RestClient, restServerCache: RestServerCache) extends RestServer {

  // retrieve contributors by repo using ZIO-http and a Redis cache
  def contributorsByOrganization(organization: Organization, groupLevel: String, minContribs: Int):
  ZIO[Client, ErrorTypeE, List[Contributor]] = for {
    repos <- restClient.reposByOrganization(organization)
    contributorsDetailed <- contributorsDetailedZIOWithCache(organization, repos)
    contributors = groupContributors(organization, groupLevel, minContribs, contributorsDetailed)
  } yield contributors

  private def contributorsDetailedZIOWithCache(organization: Organization, repos: List[Repository]):
    ZIO[Client, ErrorTypeE, List[Contributor]] = {

    val (reposUpdatedInCache, reposNotUpdatedInCache) = repos.partition(restServerCache.repoUpdatedInCache(organization, _))
    val contributorsDetailed_L_1: List[List[Contributor]] =
      reposUpdatedInCache.map { repo =>
        restServerCache.retrieveContributorsFromCache(organization, repo)
      }
    val contributorsDetailed_L_Z_2 = {
      reposNotUpdatedInCache.map { repo =>
        restClient.contributorsByRepo(organization, repo)
      }
    }

    // retrieve contributors by repo in parallel
    val contributorsDetailed_Z_L_2 = ZIO.collectAllPar(contributorsDetailed_L_Z_2).withParallelism(8)

    for {
      contributorsDetailed_L_2 <- contributorsDetailed_Z_L_2
      _ <- ZIO.succeed(restServerCache.updateCache(organization, reposNotUpdatedInCache, contributorsDetailed_L_2))
    } yield (contributorsDetailed_L_1 ++ contributorsDetailed_L_2).flatten

  }

  // group - sort list of contributors
  def groupContributors(organization: Organization,
                        groupLevel: String,
                        minContribs: Int,
                        contributorsDetailed: List[Contributor]): List[Contributor] = {
    val (contributorsGroupedAboveMin, contributorsGroupedBelowMin) = contributorsDetailed.
      map(c => if (groupLevel == "repo") c else c.copy(repo = s"All $organization repos")).
      groupBy(c => (c.repo, c.contributor)).
      view.mapValues(_.foldLeft(0)((acc, elt) => acc + elt.contributions)).
      map(p => Contributor(p._1._1, p._1._2, p._2)).
      partition(_.contributions >= minContribs)
    val contributorsGrouped = {
      (
        contributorsGroupedAboveMin
          ++
          contributorsGroupedBelowMin.
            map(c => c.copy(contributor = "Other contributors")).
            groupBy(c => (c.repo, c.contributor)).
            view.mapValues(_.foldLeft(0)((acc, elt) => acc + elt.contributions)).
            map(p => Contributor(p._1._1, p._1._2, p._2))
        ).toList.sortWith { (c1: Contributor, c2: Contributor) =>
        if (c1.repo != c2.repo) c1.repo < c2.repo
        else if (c1.contributor == "Other contributors") false
        else if (c1.contributions != c2.contributions) c1.contributions >= c2.contributions
        else c1.contributor < c2.contributor
      }
    }
    contributorsGrouped
  }

  // ZIO-HTTP definition of the endpoint for the REST service
  private val handlerContribsGH2Z: Handler[Client, ErrorTypeE, (String, Request), Response] =
    handler { (organization: String, request: Request) =>
      val glS = request.url.queryParams.get("group-level").getOrElse(Chunk[String]()).toString
      val gl = if (glS.trim == "") "organization" else glS
      val mcS = request.url.queryParams.get("min-contribs").getOrElse(Chunk[String]()).toString
      val mc = mcS.toIntOption.getOrElse(0)
      contributorsByOrganization(organization, gl, mc).map(l => Response.json(l.toJson))
    }
  private val routesContribsGH2Z: Routes[Client, ErrorTypeE] =
    Routes(Method.GET / "org" / string("organization") / "contributors" -> handlerContribsGH2Z)
  private val routesContribsGH2ZErrorsHandled: Routes[Client, Nothing] =
    routesContribsGH2Z.handleError(_ match {
      case OrganizationNotFound => Response.notFound("GitHub API - non-existent organization")
      case LimitExceeded => Response.forbidden("GitHub API - rate limit exceeded")
      case UnexpectedError => Response.internalServerError("GitHub API - unexpected StatusCode")
    })
  private val appContribsGH2Z: HttpApp[Client] = routesContribsGH2ZErrorsHandled.sandbox.toHttpApp

  // ZIO-HTTP definition of the server for the REST service
  private val port: Int = 8080
  val runServer: ZIO[Any, Throwable, ExitCode] = for {
    _ <- Console.printLine(s"Starting server on http://0.0.0.0:$port")
    _ <- Server.serve(appContribsGH2Z).provide(Server.defaultWithPort(port), Client.default)
  } yield ExitCode.success

}

object RestServerLive {
  val layer =
    ZLayer.fromFunction(RestServerLive(_, _))
}

// REST service implementation as a running instance of a ZIO-Http server, with all dependencies provided as ZIO layers
object ContribsGH2Z extends ZIOAppDefault {

  override val run = {
    ZIO.serviceWithZIO[RestServer](_.runServer).
      provide(
        RestClientLive.layer,
        RestServerLive.layer,
        RestServerCacheLive.layer,
        RedisServerClientLive.layer
      )
  }

}
