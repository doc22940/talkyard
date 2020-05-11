/// <reference path="./model.ts" />
/// <reference path="./constants.ts" />
/// <reference path="./magic-time.ts" />


// Docs: https://developer.mozilla.org/en-US/docs/Web/API/Service_Worker_API
// Read?: https://www.kollegorna.se/en/2017/06/service-worker-gotchas/
// Caching, see:
// https://developers.google.com/web/ilt/pwa/caching-files-with-service-worker
// https://github.com/mdn/sw-test/blob/gh-pages/sw.js

// detect updates to previous service worker registrations, & tell users to refresh the page.

//  !! https://gist.github.com/Rich-Harris/fd6c3c73e6e707e312d7c5d7d0f3b2f9

// + what? many ways the browser might post the reply ??
// view-source:https://jakearchibald.github.io/isserviceworkerready/demos/postMessage/
// (found via:  https://jakearchibald.github.io/isserviceworkerready/#postmessage-to-&-from-worker )


// Service worker global scope, see:
// https://developer.mozilla.org/en-US/docs/Web/API/ServiceWorkerGlobalScope
// https://developers.google.com/web/fundamentals/primers/service-workers/lifecycle
declare var oninstall: any;
declare var onactivate: any;
declare var onfetch: any;
declare var registration: any;
declare var skipWaiting: any;
declare var clients;


const enum WebSocketMessageTypes {
  IdleSeconds = 2,  // works as keep-alive
  Bye = 3,
}


/*

WebSocket partial "state machine"
==================================

Human logs in  —>  Connects  —> Connected

Connected  ——> Human gone ——> Stay connected
  |                           (since already connected, anyway)
  |
   `——> Disconnected —> Browser window open?
                           \
                            \———[no]——> Stay disconnected (don't reconnect
                             \                     the service worker alone)
                              \
                              [yes]——> Human there? ———[yes] ———> Try to reconnect
                                            \
                                             [no, gone] —–—> Stay disonnected
                                                           (since already disconnected)
*/



//------------------------------------------------------------------------------
   namespace debiki2 {
//------------------------------------------------------------------------------


console.log(`SW: Service worker ${TalkyardVersion} loading [TyMSWVLDNG]`);  // [sw]

let wsUserId: UserId | U;
let wsConnection: WebSocket | U;
// Nice if debugging?
let lastWsUserId: UserId | U;
let lastWsConnection: WebSocket | U;

let wsMessageNr = 0;
let hasAuthenticated: boolean;

let nextKeepAliveScheduled: boolean | U;
let nextKeepAliveMessageIdleSecs: number | U;
const KeepAliveIntervalSeconds = 30;



oninstall = function(event) {
  // Later: Here, can start populating an IndexedDB, and caching site assets — making
  // things available for offline use.
  console.log("SW: Installing... [TyMSWINSTLD]");

  // Make this the active service worker, for all clients, including any other
  // already "since long ago" open browser tabs. (Otherwise, would need to wait
  // for them to close — just refreshing, apparently isn't enough — then they'll
  // continue using the old service worker: """refreshing the page isn't enough
  // to let the new version take over""", see:
  // https://developers.google.com/web/fundamentals/primers/service-workers/lifecycle.)
  event.waitUntil(skipWaiting());
};



onactivate = function(event) {
  // Here, can cleanup resources used by previous sw versions that are no longer needed,
  // and e.g. migrate any database. Which cannot be done in oninstall, since then an
  // old service worker might still be running. Note that an old database that's to
  // be migrated, might be many versions old, not always the previous version.
  console.log("SW: Activating... [TyMSWACTIVD]");

  // On the very very first Talkyard page load, the browser page tab loads without any
  // service worker, and thus won't get any live notifications, because it'll have no
  // service worker (until after tab reload), since it was loaded outside any service worker.
  // Unless we claim() it. Then, 1) subsequent fetches (http requests) will be via this
  // service worker, and we can send messages to that tab.
  // Or, 2) if there's an old service worker already installed, the page is currently
  // using that one, and when we claim() that page, it'll start using this new
  // service worker instad. [SWCLMTBS]
  // Nice: https://serviceworke.rs/immediate-claim_service-worker_doc.html
  if (!clients.claim) return;
  event.waitUntil(clients.claim().then(() => {
    console.log("SW: I claimed all clients. [TyMSWCLDCLS]");
  }));
};



if (registration.onupdatefound) registration.onupdatefound = function() {
  console.log("SW: I'm about to get replaced by a newer version. [TyMSWUPDFND]");
  wsConnection?.close();
  wsConnection = null;
};



onmessage = function(event: any) {
  console.debug(`SW: Win says:  ${JSON.stringify(event.data)}  ` +
    ` [TyMSWWINMSG]`);  // from ${event.origin}

  const untypedMessage: MessageToServiceWorker = event.data;

  switch (untypedMessage.doWhat) {
    case SwDo.TellMeYourVersion: {
      event.source.postMessage({ // <MyVersionIsMessageFromSw> {
        type: 'MyVersionIs',  // old, start using ...
        saysWhat: SwSays.MyVersionIs,  // ... <— this instead
        talkyardVersion: TalkyardVersion,
      });
      break;
    }
    case SwDo.SubscribeToEvents: {
      const message = <SubscribeToEventsSwMessage> untypedMessage;
      if (!message.myId) {
        // We've logged out. Don't ask for any events — if everyone did that,
        // that could put the server under an a bit high load? And not much interesting
        // to be notified about anyway, when haven't joined the site yet / not logged in.
        console.debug(`SW: Not logged in. [TyMSWLOGOUT]`);
        if (wsConnection) {
          console.debug(`SW: Closing connection. [TyMSWEND01]`);
          wsConnection.close();
          wsConnection = null;
          wsUserId = null;
        }
        return;
      }

      if (message.myId <= MaxGuestId) {
        console.error(`Guest account: ${message.myId}`);
        return;
      }

      if (wsUserId === message.myId) {
        console.trace(`SW: Already connected w WebSocket as user ${wsUserId}. [TyMSWALRCON]`);
        return;
      }

      // Could incl the req nr in the URL, for debugging, so knows which lines in
      // chrome://net-internals/#events and in the Nginx logs are for which browser request.
      const wsUrl = (this.location.protocol === 'http:' ? 'ws:' : 'wss:') +
          this.location.host + '/-/websocket';

      console.debug(`SW: Opening WebSocket to:  ${wsUrl}  [TyMSWNEW]`);

      wsUserId = message.myId;
      wsConnection = new WebSocket(wsUrl);
      hasAuthenticated = false;

      wsConnection.onerror = function(event: Event) {
        // Which one of toString() or stringify? Try both.
        const errorText = event.toString?.() || JSON.stringify(event);
        console.warn(`SW: WebSocket error:  ${errorText}  [TyMSWERR]`);
        // Apparently there's always a close event after onerror, see:
        // https://stackoverflow.com/a/40084550/694469
        // https://html.spec.whatwg.org/multipage/web-sockets.html#feedback-from-the-protocol%3Aconcept-websocket-closed
        // — let's notify the browsers from the onclose() event, not here.
      };

      wsConnection.onopen = function(event: Event) {
        // Todo: Remove any "No internet" message. [NOINETMSG]  already done?
        // sendToAllBrowserTabs({ type: 'connected', data: longPollingState.nextReqNr });
        //   —>  $h.removeClasses(document.documentElement, 's_NoInet');

        console.debug(`SW: WebSocket connection open [TyMSWSOPN]`);
        // Double quotes — the server wants json.
        wsConnection.send(`"${message.xsrfToken}"`);
      };

      wsConnection.onmessage = function(event: MessageEvent) {
        // We just got authenticated? Then the server says: "OkHi @username".
        if (event.data.indexOf('"OkHi ') === 0) {  // double quotes because is json
          if (hasAuthenticated) {
            // Should get only one 'OkHi'.
            console.warn(`SW: Unexpected 'OkHi' [TyESWUNEXPOKHI]`);
            return;
          }
          hasAuthenticated = true;
          console.debug(`SW: Server: 'OkHi' [TyMSWOKHI]`);
          sendToAllBrowserTabs({ type: 'connected', data: wsMessageNr });
          return;
        }

        if (!hasAuthenticated) {
          console.warn(`SW: Server, before 'OkHi': ${event.data} [TyESW0OKHI]`);
          return;
        }

        console.debug(`SW: Server: ${event.data} [TyMSWSVSAYS]`);
        const message = JSON.parse(event.data)
        sendToAllBrowserTabs(message);
      }

      wsConnection.onclose = function(event: CloseEvent) {
        // CloseEvent codes: (for event.code)
        //  https://developer.mozilla.org/en-US/docs/Web/API/CloseEvent
        const logFn = event.wasClean ? console.debug : console.error;
        logFn(`SW: Connection closed, code: ${event.code}, ` +
            `reason: ${event.reason}, clean: ${event.wasClean}  [TyMSWEND02]`);

        sendToAllBrowserTabs({ type: event.wasClean ? 'disconnected' : 'eventsBroken' });
        lastWsConnection = wsConnection;
        lastWsUserId = wsUserId;
        wsUserId = null;
        wsConnection = null;

        // ?  [NOINETMSG]
      }
      break;
    }
    case SwDo.KeepWebSocketAlive: {
      // One or more browser windows are open — so keep any WebSocket connection alive,
      // by sending regular short messages so proxy servers notice the connection is
      // actually in use.

      const message = <WebSocketKeepAliveSwMessage> untypedMessage;

      // If active in different browser windows, the idle time is the shortest one,
      // from the most recently active window.
      nextKeepAliveMessageIdleSecs = Math.min(
          message.idleSecs, nextKeepAliveMessageIdleSecs);

      if (!isConnected()) {
        // This timeout must be higher than the startKeepAliveMessages()
        // intervl [KEEPALVINTV] [5AR20ZJ], otherwise we might never reconnect.
        if (message.idleSecs < 60) {
          tryToReconnect();
        }
        else {
          // Noop. A browser window is open — but the human isn't there. Don't
          // reconnect, until maybe later when hen is back.
        }
      }
      else if (!nextKeepAliveScheduled) {
        if (message.myId !== wsUserId) {
          console.warn(`SW: message.myId: ${message.myId} !== wsUserId: ${wsUserId} [TyE06KTH3]`);
        }
        nextKeepAliveScheduled = true;
        magicTimeout(KeepAliveIntervalSeconds * 1000, function() {
          nextKeepAliveScheduled = false;
          trySendWebSocketMessage(
              WebSocketMessageTypes.IdleSeconds, nextKeepAliveMessageIdleSecs);
        });
      }

      break;
    }
    case SwDo.StartMagicTime: {
      const message = <StartMagicTimeSwMessage> untypedMessage;
      startMagicTime(message.startTimeMs);
      break;
    }
    case SwDo.PlayTime: {
      const message = <PlayTimeSwMessage> untypedMessage;
      addTestExtraMillis(message.extraTimeMs);
      break;
    }
  }
};



function sendToAllBrowserTabs(message) {
  clients.matchAll({ type: 'window' }).then(function (cs) {
    cs.forEach(function(c) {
      c.postMessage(message);
    });
  });
}


function tryToReconnect() {
  if (isConnected()) {
    return;
  }
  // See (OLDRECON) below.
}



function isConnected(): boolean {
  return wsConnection?.readyState === WebSocket.OPEN;
}



function trySendWebSocketMessage(messageType: WebSocketMessageTypes, data: any) {
  if (!isConnected()) {
    console.debug(`SW: Not connected, cannot send [TyMSW0CON]:  ` +
      `${messageType}  ${JSON.stringify(data)}`);
    return;
  }

  // Each message has its own sequence number, so the server can tell us which message
  // it replies to (if it replies to a specific message).
  wsMessageNr += 1;

  // Also, tell the server what message we're replying to. 0 means not replying.
  const replyingToServerMessageNr = 0;

  // Talkyard's WebSocket protocol.
  const jsonText = JSON.stringify([
      wsMessageNr, replyingToServerMessageNr, messageType, data]);

  console.debug(`SW: Sending to server: ${jsonText}`)
  wsConnection.send(jsonText);
}



const RetryAfterMsDefault = 5000;
const GiveUpAfterTotalMs = 7 * 60 * 1000; // 7 minutes [5AR20ZJ]  No! Don't give up that soon?
let retryAfterMs = RetryAfterMsDefault;
let startedFailingAtMs;



/* Old reconnect code, from long ago when Long Polling was used:  (OLDRECON)

  sendLongPollingRequest(channelId, (response) => {
    console.debug("Long polling request done, sending another... [TyMSWLPDONE]");
    subscribeToServerEvents(channelId);

    // Reset backoff, since all seems fine.
    retryAfterMs = RetryAfterMsDefault;
    startedFailingAtMs = undefined;


    sendToAllBrowserTabs(response);
  }, (errorStatusCode?: number) => {
    // Error. Don't retry immediately — that could result in super many error log messages,
    // if the problem persists. Also, do a bit exponential backoff; eventually give up.
    retryAfterMs = retryAfterMs * 1.3;
    if (!startedFailingAtMs) {
      startedFailingAtMs = getNowMs();
    }
    const totalFailTimeMs = getNowMs() - startedFailingAtMs;

    if (totalFailTimeMs > GiveUpAfterTotalMs) {
      // TESTS_MISSING how make Nginx "break" so all requests fail? If a script temporarily  [5YVBAR2]
      // does 'docker-compose kill web' and then 'start web' — then, other e2e tests won't be
      // able to run in parallel with this, hmm.
      console.error("Long polling broken, maybe events lost, giving up. [TySWDISCND");
      sendToAllBrowserTabs({ type: 'eventsBroken' });
    }
    else {
      // If the server couldn't reply with an error code, there's likely no internet connection?
      if (!errorStatusCode) {
        sendToAllBrowserTabs({ type: 'disconnected' });
      }
      console.warn(`Long polling error, will retry in ${Math.floor(retryAfterMs / 1000)} seconds...`);
      setTimeout(() => {
        if (!isLongPollingNow()) {
          subscribeToServerEvents(channelId);
        }
      }, retryAfterMs);
    }
  }, () => {
    console.debug("Long polling aborted, will send a new if needed [TyMLPRMBYE]");
    // No error has happened — we aborted the request intentionally. All fine then? Reset the backoff:
    retryAfterMs = RetryAfterMsDefault;
    if (!isLongPollingNow()) {
      subscribeToServerEvents(channelId);
    }
  });
}
*/



//------------------------------------------------------------------------------
   }
//------------------------------------------------------------------------------
// vim: fdm=marker et ts=2 sw=2 tw=0 fo=r list