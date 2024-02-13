package code.model

import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

object Entities {

  // entities of the REST service

  import java.time.Instant

  type Organization = String

  case class Repository(name: String, updatedAt: Instant)

  case class Contributor(repo: String, contributor: String, contributions: Int)
  object Contributor {
    implicit val decoder: JsonDecoder[Contributor] = DeriveJsonDecoder.gen[Contributor]
    implicit val encoder: JsonEncoder[Contributor] = DeriveJsonEncoder.gen[Contributor]
  }

  // auxiliary types for the REST client

  type BodyType = String

  object ErrorTypes extends Enumeration {
    type ErrorType = Value
    val OrganizationNotFound, LimitExceeded, UnexpectedError = Value
  }

}
