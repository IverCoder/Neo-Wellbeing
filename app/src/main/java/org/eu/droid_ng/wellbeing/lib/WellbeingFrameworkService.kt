package org.eu.droid_ng.wellbeing.lib

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import org.eu.droid_ng.wellbeing.framework.IWellbeingFrameworkService

class WellbeingFrameworkService internal constructor(
	private val context: Context,
	private val wellbeingService: WellbeingService
) : IWellbeingFrameworkService {
	private val serviceConnection: ServiceConnection
	private var wellbeingFrameworkService: IWellbeingFrameworkService? = null
	private var binder: IBinder? = null
	private var versionCode = 0
	private var initial = true

	init {
		serviceConnection = object : ServiceConnection {
			override fun onServiceConnected(name: ComponentName, service: IBinder) {
				wellbeingFrameworkService = IWellbeingFrameworkService.Stub.asInterface(
					service.also {
						binder = it
					})
				try {
					versionCode = wellbeingFrameworkService!!.versionCode()
				} catch (e: Exception) {
					Log.e("WellbeingFrameworkService", "Failed to get framework version", e)
					invalidateConnection()
					context.unbindService(this)
				}
				if (binder != null || initial) {
					notifyWellbeingService()
				}
			}

			override fun onServiceDisconnected(name: ComponentName) {
				invalidateConnection()
				HANDLER.post { tryConnect() }
			}

			override fun onBindingDied(name: ComponentName) {
				invalidateConnection()
				context.unbindService(this)
			}

			override fun onNullBinding(name: ComponentName) {
				invalidateConnection()
				context.unbindService(this)
				if (initial) {
					notifyWellbeingService()
				}
			}
		}
	}

	private fun invalidateConnection() {
		wellbeingFrameworkService = DEFAULT
		versionCode = 0
		binder = null
	}

	private fun notifyWellbeingService() {
		initial.let {
			initial = false
			wellbeingService.onWellbeingFrameworkConnected(it)
		}
	}

	fun tryConnect() {
		if (versionCode == -1) return
		if (binder == null || !(binder!!.isBinderAlive && binder!!.pingBinder())) {
			versionCode = -1
			try {
				context.bindService(
					FRAMEWORK_SERVICE_INTENT, serviceConnection,
					Context.BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES
				)
			} catch (e: Exception) {
				Log.e("WellbeingFrameworkService", "Failed to bind framework service", e)
				if (versionCode == -1) {
					versionCode = 0
					if (initial) {
						notifyWellbeingService()
					}
				}
			}
		}
	}

	override fun versionCode(): Int {
		if (binder != null && !binder!!.isBinderAlive) {
			invalidateConnection()
		}
		return versionCode
	}

	@Throws(RemoteException::class)
	override fun setAirplaneMode(value: Boolean) {
		if (versionCode < 1) return
		wellbeingFrameworkService!!.setAirplaneMode(value)
	}

	override fun asBinder(): IBinder {
		return binder!!
	}

	companion object {
		private val HANDLER = Handler(Looper.getMainLooper())
		private val FRAMEWORK_SERVICE_INTENT =
			Intent("org.eu.droid_ng.wellbeing.framework.FRAMEWORK_SERVICE")
				.setPackage("org.eu.droid_ng.wellbeing.framework")
		private var DEFAULT: IWellbeingFrameworkService? = null

		init {
			IWellbeingFrameworkService.Stub.setDefaultImpl(
				IWellbeingFrameworkService.Default().also { DEFAULT = it })
		}
	}
}