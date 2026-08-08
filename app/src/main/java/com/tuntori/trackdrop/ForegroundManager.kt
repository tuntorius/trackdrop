package com.tuntori.trackdrop

/**
 * Acts as a bridge between FirebaseMessagingService and the UI.
 * When the app is in the foreground, the service sends fetched results here.
 */
object ForegroundManager {
    var listener: ((FetchResult?) -> Unit)? = null
}