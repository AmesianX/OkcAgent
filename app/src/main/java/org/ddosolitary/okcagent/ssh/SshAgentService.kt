package org.ddosolitary.okcagent.ssh

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.app.NotificationCompat
import org.ddosolitary.okcagent.AgentService
import org.ddosolitary.okcagent.NOTIFICATION_CHANNEL_SERVICE
import org.ddosolitary.okcagent.R
import org.ddosolitary.okcagent.showError
import org.openintents.ssh.authentication.SshAuthenticationApi.EXTRA_ERROR
import org.openintents.ssh.authentication.SshAuthenticationApiError
import org.openintents.ssh.authentication.request.SigningRequest
import org.openintents.ssh.authentication.request.SshPublicKeyRequest
import org.openintents.ssh.authentication.response.SigningResponse
import org.openintents.ssh.authentication.response.SshPublicKeyResponse
import java.net.Socket

private const val NOTIFICATION_ID_SSH = 1

class SshAgentService : AgentService() {
	override fun getErrorMessage(intent: Intent): String? {
		return intent.getParcelableExtra<SshAuthenticationApiError>(EXTRA_ERROR)?.message
	}

	override fun runAgent(port: Int, intent: Intent) {
		var socket: Socket? = null
		try {
			socket = Socket("127.0.0.1", port)
			val input = socket.getInputStream()
			val output = socket.getOutputStream()
			val keyId = getSharedPreferences(getString(R.string.pref_main), Context.MODE_PRIVATE)
				.getString(getString(R.string.key_ssh_key), "") ?: ""
			val lock = Object()
			var connRes = false
			SshApi(this) { res ->
				if (!res) showError(this@SshAgentService, R.string.error_connect)
				connRes = res
				synchronized(lock) { lock.notify() }
			}.use { api ->
				api.connect()
				synchronized(lock) { lock.wait() }
				if (!connRes) throw IllegalStateException()
				val executeApi = { reqIntent: Intent -> api.executeApi(reqIntent) }
				while (true) {
					val req = SshAgentMessage.readFromStream(input) ?: break
					val resMsg = when (req.type) {
						SSH_AGENTC_REQUEST_IDENTITIES -> {
							val resIntent =
								callApi(executeApi, SshPublicKeyRequest(keyId).toIntent(), port)
							if (resIntent != null) {
								val pubKeyStr = SshPublicKeyResponse(resIntent).sshPublicKey
								SshAgentMessage(
									SSH_AGENT_IDENTITIES_ANSWER,
									SshIdentitiesResponse(
										Base64.decode(
											pubKeyStr.substring(pubKeyStr.indexOf(' ') + 1),
											Base64.DEFAULT
										)
									).toBytes()
								)
							} else null
						}
						SSH_AGENTC_SIGN_REQUEST -> {
							val signReq = SshSignRequest(req.contents!!)
							val resIntent = callApi(
								executeApi,
								SigningRequest(signReq.data, keyId, signReq.flags).toIntent(),
								port
							)
							if (resIntent != null) {
								SshAgentMessage(
									SSH_AGENT_SIGN_RESPONSE,
									SshSignResponse(SigningResponse(resIntent).signature).toBytes()
								)
							} else null
						}
						else -> null
					}
					(resMsg ?: SshAgentMessage(SSH_AGENT_FAILURE, null)).writeToStream(output)
				}
			}
		} catch (_: Exception) {
			socket?.setSoLinger(true, 0)
		} finally {
			socket?.close()
			checkThreadExit(port)
		}
	}

	override fun onCreate() {
		super.onCreate()
		val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_SERVICE)
			.setSmallIcon(R.mipmap.ic_launcher)
			.setContentTitle(getString(R.string.notification_ssh_title))
			.setContentText(getString(R.string.notification_ssh_content))
			.build()
		startForeground(NOTIFICATION_ID_SSH, notification)
	}
}