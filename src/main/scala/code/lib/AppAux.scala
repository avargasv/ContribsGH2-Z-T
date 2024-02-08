package code.lib

object AppAux {

  val gh_token_S = System.getenv("GH_TOKEN")
  val gh_token =
    if (gh_token_S != null) {
      gh_token_S
    } else {
      null
    }

}
