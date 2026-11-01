package com.orion.assistant.service.telecom

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import com.orion.assistant.service.call.CallManager

class OrionConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val connection = OrionConnection()
        connection.setInitializing()
        connection.setActive()
        return connection
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val connection = OrionConnection()
        connection.setInitializing()
        connection.setActive()
        return connection
    }

    inner class OrionConnection : Connection() {
        init {
            connectionCapabilities = CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD
            audioModeIsVoip = true
        }

        override fun onAnswer() {
            super.onAnswer()
            setActive()
            CallManager.answerCall(applicationContext)
        }

        override fun onDisconnect() {
            super.onDisconnect()
            setDisconnected(android.telecom.DisconnectCause(android.telecom.DisconnectCause.LOCAL))
            destroy()
            CallManager.endCall(applicationContext)
        }
    }
}
