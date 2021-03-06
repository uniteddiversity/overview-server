package controllers.filters

import javax.inject.Inject
import play.api.mvc.{EssentialAction,EssentialFilter,RequestHeader}
import scala.concurrent.ExecutionContext

/** Adds Access-Control-Allow-Origin headers to successful /api/ responses.
  */
class CorsFilter @Inject() (implicit ec: ExecutionContext) extends EssentialFilter {
  private val Headers = Seq(
    "Access-Control-Allow-Origin" -> "*",
    "Access-Control-Allow-Headers" -> "origin, authorization, content-type, accept"
  )

  def apply(nextFilter: EssentialAction) = new EssentialAction {
    def apply(requestHeader: RequestHeader) = {
      if (requestHeader.path.startsWith("/api/")) {
        nextFilter(requestHeader).map(_.withHeaders(Headers: _*))
      } else {
        nextFilter(requestHeader)
      }
    }
  }
}
