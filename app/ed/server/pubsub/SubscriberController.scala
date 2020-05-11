/**
 * Copyright (c) 2015, 2020 Kaj Magnus Lindberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ed.server.pubsub

import akka.Done
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, SourceQueueWithComplete}
import com.debiki.core._
import com.debiki.core.Prelude._
import debiki.EdHttp._
import debiki._
import ed.server.{EdContext, EdController}
import ed.server.http._
import ed.server.security.SidAbsent
import javax.inject.Inject
import org.scalactic.{Bad, ErrorMessage, Good, Or}
import play.{api => p}
import p.libs.json.{JsString, JsValue, Json}
import p.mvc.{Action, ControllerComponents, RequestHeader, Result}
import scala.concurrent.Future
import scala.util.{Failure, Success}
import talkyard.server.TyLogging
import talkyard.server.RichResult


/** Authorizes and subscribes a user to pubsub messages.
  */
class SubscriberController @Inject()(cc: ControllerComponents, tyCtx: EdContext)
  extends EdController(cc, tyCtx) with TyLogging {

  import context.globals


  def webSocket: p.mvc.WebSocket = p.mvc.WebSocket.acceptOrResult[JsValue, JsValue] {
        request: RequestHeader =>
    webSocketImpl(request)
  }


  private def webSocketImpl(request: RequestHeader): Future[Either[
        // Either an error response, if we reject the connection.
        Result,
        // Or an In and Out stream, for talking with the client.
        Flow[JsValue, JsValue, _]]] = {
    // CR THIS WHOLE FILE
    val site = globals.lookupSiteOrThrow(request)

    val authnReq = authenticateWebSocket(site, request) getOrIfBad { result =>
      // Reject the connection. However, the browser won't really find out why —
      // Chrome etc just shows the status code, e.g. 403 Forbidden, nothing more,
      // apparently to make it harder to use WebSocket for port scanning or
      // DoS-attack generating many connections, see:
      //   https://stackoverflow.com/a/19305172/694469
      // So, could log this to some site admin accessible Talkyard log? So a
      // site admin, at least, can find out what's wrong.
      logger.debug(s"s${site.id}: Rejecting WebSocket with status ${
          result.statusCode}: ${result.bodyAsUtf8String}")
      return Future.successful(Left(result))
    }

    globals.pubSub.mayConnectClient(authnReq.theSiteUserId) map {
      case Some(problem: ErrorMessage) =>
        Left(ForbiddenResult("TyEWSTOOMANY", s"You cannot connect: $problem"))
      case None =>

        // Later: If accepting new topics and replies via WebSocket, then, need to
        // remember a SpamRelReqStuff here? [WSSPAM]

        // Rate limit connections per ip: set user = None.
        // (We rate limit *messages* too, per user, see RateLimits.SendWebSocketMessage.)
        context.rateLimiter.rateLimit(
              RateLimits.ConnectWebSocket, authnReq.copy(user = None))

        val flow: Flow[JsValue, JsValue, _] = acceptWebSocket(authnReq)
        Right(flow)
    }
  }



  private def authenticateWebSocket(site: SiteBrief, request: RequestHeader)
        : AuthnReqHeaderImpl Or Result = {
    import tyCtx.security

    val dao = globals.siteDao(site.id)
    val requestOrigin = request.headers.get(play.api.http.HeaderNames.ORIGIN)
    val siteCanonicalOrigin = globals.originOfSiteId(site.id)

    // If the Origin header isn't the server's origin, then, maybe this is a
    // xsrf attack request — reply Forbidden. (We look for a xsrf token in the first
    // WebSocket message too, [WSXSRF].)
    // Could allow site-NNN origins, in dev mode, maybe?
    TESTS_MISSING // TyTWSAUTH
    if (requestOrigin != siteCanonicalOrigin) {
      logger.debug(o"""s${site.id}: Rejecting WebSocket: Request origin: $requestOrigin
          but site canon orig: $siteCanonicalOrigin  [TyM305RKDJ6]""")
      return Bad(ForbiddenResult(
          "TyEWSBADORIGIN", s"Bad Origin header: $requestOrigin"))
    }

    // A bit dupl code — the same as for normal HTTP requests. [WSHTTPREQ]
    val expireIdleAfterMins = dao.getWholeSiteSettings().expireIdleAfterMins

    // We check the xsrf token later — since a WebSocket upgrade request
    // cannot have a request body or custom header with xsrf token.
    // (checkSidAndXsrfToken() won't throw for GET requests. [GETNOTHROW])
    val (actualSidStatus, xsrfOk, _ /* newCookies */) =
        security.checkSidAndXsrfToken(
            request, anyRequestBody = None, siteId = site.id,
            expireIdleAfterMins = expireIdleAfterMins, maySetCookies = false)

    // Needs to be a cookie already set. [WSXSRF]
    throwForbiddenIf(xsrfOk.value.isEmpty,
          "TyEWS0XSRFCO", "No xsrf cookie")

    val (mendedSidStatus, _ /* deleteSidCookie */) =
      if (actualSidStatus.canUse) (actualSidStatus, false)
      else (SidAbsent, true)

    // For now, let's require a browser id cookie — then, *in some cases* simpler
    // to detect WebSocket abuse or attacks? (Also see: [GETLOGIN] — an id cookie
    // needs to be set also via GET requests, if they're for logging in.)
    val anyBrowserId = security.getAnyBrowserId(request)
    //if (anyBrowserId.isEmpty)
    //  return Bad(ForbiddenResult("TyEWS0BRID", "No browser id"))

    dao.perhapsBlockRequest(request, mendedSidStatus, anyBrowserId)

    val requesterMaybeSuspended: User = dao.getUserBySessionId(mendedSidStatus) getOrElse {
      return Bad(ForbiddenResult("TyEWS0LGDIN", "Not logged in"))
    } match {
      case user: User => user
      case other =>
        return Bad(ForbiddenResult("TyEWS0USR", s"Not a user account, but a ${
              other.accountType} account: ${other.nameHashId}"))
    }

    if (requesterMaybeSuspended.isDeleted)
      return Bad(ForbiddenResult("TyEWSUSRDLD", "User account deleted")
          .discardingCookies(security.DiscardingSessionCookie))  // + discard browser id co too

    val isSuspended = requesterMaybeSuspended.isSuspendedAt(new java.util.Date)
    if (isSuspended)
      return Bad(ForbiddenResult("TyEWSSUSPENDED", "Your account has been suspended")
          .discardingCookies(security.DiscardingSessionCookie))

    val requester = requesterMaybeSuspended

    val authnReq = AuthnReqHeaderImpl(site, sid = mendedSidStatus, xsrfOk,
          anyBrowserId, Some(requester), dao, request)

    Good(authnReq)
  }


  private def acceptWebSocket(req: AuthnReqHeader): Flow[JsValue, JsValue, _] = {

    // Use Sink and Source directly.
    // Could have used this Actor thing:
    play.api.libs.streams.ActorFlow
    // However, seems it'd use more memory and resources (creates an unneeded
    // actor), and is more complicated: 1) Needs to wait until an Actor
    // onStart() overridable fn has been called, to get an ActorrRef.
    // And 2) seems the Actor does some buffering of outgoing messages, but
    // I want to do that myself, to be able to re-send if the user disconnects
    // and reconnects shortly thereafter — but if the messages were buffered
    // in a per WebSocket actor, they'd be lost?
    // And 3) there's a memory retention leak bug risk? From the docs (May 2020):
    // """Note that terminating the actor without first completing it, either
    // with a success or a failure, will prevent the actor triggering downstream
    // completion and the stream will continue* to run even though
    // the source actor is dead. Therefore you should **not** attempt to
    // manually terminate the actor such as with a [[akka.actor.PoisonPill]]
    // """
    // — having to think about that is just unnecessary? No need for extra
    // actors in Talkyard's case anyway.

    import akka.stream.scaladsl.{Flow, Sink, Source}
    import req.{site, theRequester => requester}

    @volatile
    var authenticatedViaWebSocket = false

    @volatile
    var anyWebSocketClient: Option[UserConnected] = None

    val foreachSink = Sink.foreach[JsValue](jsValue => {
      anyWebSocketClient match {
        case None =>
          logger.error(s"WS: Got message but no ws client [TyEWSMSG0CLNT]: $jsValue")
        case Some(client) =>
          val prefix = s"s${client.siteId}: WS:"
          val who = client.user.nameParaId
          if (!authenticatedViaWebSocket) {
            // This should be an xsrf token.
            jsValue match {
              case JsString(value) =>
                if (value != req.xsrfToken.value) {
                  // Close — bad xsrf token. [WSXSRF]
                  logger.debug(s"$prefix $who sent bad xsrf token: '$value' [TyEWSXSRF]")
                  client.wsOut.offer(JsString(
                      s"Bad xsrf token: '$value'. Bye. [TyEWSXSRF]"))
                  client.wsOut.complete()
                }
                else {
                  // Let's talk.
                  logger.debug(o"""$prefix $who connected, telling PubSubActor, it'll
                      watch page ids: ${client.watchedPageIds}  [TyMWSCONN]""")
                  client.wsOut.offer(JsString(s"OkHi @${client.user.usernameOrGuestName}"))
                  authenticatedViaWebSocket = true

                  RACE // [WATCHBRACE]
                  val dao = globals.siteDao(site.id)
                  val watchbar = dao.getOrCreateWatchbar(requester.id)
                  val clientWithPages = client.copy(watchedPageIds = watchbar.watchedPageIds)

                  globals.pubSub.userSubscribed(clientWithPages)
                }
              case other =>
                // Close — got no xsrf token.
                logger.debug(s"$prefix $who skipped xsrf token [TyEWS0XSRFTKN]")
                client.wsOut.offer(JsString("Send xsrf token. Bye. [TyEWS0XSRFTKN]"))
                client.wsOut.complete()
            }
          }
          else {
            logger.trace(s"$prefix $who sent: $jsValue [TyEWSGOTMSG]")

            val rateLimitData = SomethingToRateLimitImpl(
              siteId = site.id,
              user = Some(client.user),
              ip = client.browserIdData.ip,
              ctime = globals.now.toJavaDate,
              shallSkipRateLimitsBecauseIsTest = false,  // for now
              hasOkE2eTestPassword = false)  // for now

            // New connections are also rate limited, see: RateLimits.ConnectWebSocket
            context.rateLimiter.rateLimit(
                  RateLimits.SendWebSocketMessage, rateLimitData)

            // ? maybe use:
            //   https://github.com/circe/circe
            //     val decodedFoo = decode[Foo](json)
            //     no runtime reflection
            //     Wow! It's like 3-4x faster than Play, for parsing? and 2-10x for writing?
            //     https://github.com/circe/circe-benchmarks
            //     Argonaut, Json4s, Play, Spray are all slower.
            //     https://github.com/circe/circe-derivation
            //       macro-supported derivation of circe's type class instances

            //   https://github.com/tototoshi/play-json4s — from Lift (don't like)
            //     "Case classes can be used to extract values from parsed JSON"
            //     json.extract[Person]
            //     Apparently uses reflection — so avoid.
            //       https://stackoverflow.com/a/41333676/694469
            //       jon4s: "you provide a Scala Manifest for it". Because Manifest
            //       is a Scala trait used for reflection"

            // globals.pubSub.onMessage( ... )  ?
            // — no. Instead, dispatch the message as if it was a normal http request?
            // it's just that now we know already who the user is, no authentication needed
            // (only authorization).

            // And, if the message means the human was active:
            // globals.pubSub.userIsActive(
            //     client.siteId, client.user, client.browserIdData)
            // — but that'd be done by the message/request handler.
          }
      }
    })

    //val inSink: Sink[JsValue, _] = Flow[JsValue].alsoTo(onCompleteSink).to(foreachSink)
    val inSink: Sink[JsValue, _] = foreachSink

    val outSource: Source[JsValue, SourceQueueWithComplete[JsValue]] = Source
      .queue[JsValue](bufferSize = 50, OverflowStrategy.fail)
    ///       .queue[Int](bufferSize = 100, akka.stream.OverflowStrategy.backpressure)
    ///       // .throttle(elementsToProcess, 3.second)

    type SourceQueue = SourceQueueWithComplete[JsValue]


    var flow: Flow[JsValue, JsValue, Unit] =
      Flow.fromSinkAndSourceMat(inSink,  outSource) { (_, outboundMat: SourceQueue) =>
        // The WebSocket is now active. Wait for the client to send its
        // session id — because checking the cookie and Origin header, might
        // not be enough, if the client is weird.

        anyWebSocketClient = Some(
            UserConnected(
              site.id, requester, req.theBrowserIdData, Set.empty, outboundMat))

        logger.debug(s"WS conn: ${requester.nameParaId} [TyMWSCON]")
      }

    // https://stackoverflow.com/a/54137778/694469
    flow = flow.watchTermination() { (_, doneFuture: Future[Done]) =>
      doneFuture onComplete { result =>
        val who = anyWebSocketClient.map(_.user.nameParaId) getOrElse "who?"
        var nothingToDo = ""

        if (globals.isInitialized) {
          globals.pubSub.unsubscribeUser(site.id, requester, req.theBrowserIdData)
        }
        else {
          // We're debugging and reloading the app?
          nothingToDo = " — but no Globals.state, nothing to do"
        }

        result match {
          case Success(_) =>
            logger.debug(s"WS closed: $who [TyMWSEND]$nothingToDo")
          case Failure(throwable) =>
            logger.warn(s"WS failed: $who [TyMWSFAIL]$nothingToDo, error: ${
                throwable.getMessage}")
        }
      }
    }

    flow
  }


  def loadOnlineUsers(): Action[Unit] = GetActionRateLimited(RateLimits.ExpensiveGetRequest) {
        request =>
    val stuff = request.dao.loadUsersOnlineStuff()
    OkSafeJson(
      Json.obj(
        "numOnlineStrangers" -> stuff.numStrangers,
        "onlineUsers" -> stuff.usersJson))
  }

}

