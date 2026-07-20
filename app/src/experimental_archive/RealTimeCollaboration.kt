package com.viewshed.app.collaboration

object RealTimeCollaboration {
    fun connectToSession(sessionId: String) { /* WebSocket or Firebase */ }
    fun sendUpdate(update: Any) {}
    fun receiveUpdates(onUpdate: (Any) -> Unit) {}
}
