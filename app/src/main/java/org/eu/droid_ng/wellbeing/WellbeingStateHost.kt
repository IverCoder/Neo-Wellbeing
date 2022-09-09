package org.eu.droid_ng.wellbeing

import android.app.*
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Build
import android.os.IBinder

// Fancy class holding GlobalWellbeingState & a notification
class WellbeingStateHost : Service() {
    @JvmField
	var state: GlobalWellbeingState? = null
    private var lateNotify = false

    // Unique Identification Number for the Notification.
    private val NOTIFICATION = 325563
    private val CHANNEL_ID = "service_notif"

    /**
     * Class for clients to access.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with
     * IPC.
     */
    inner class LocalBinder : Binder() {
        val service: WellbeingStateHost
            get() = this@WellbeingStateHost
    }

    override fun onCreate() {
        state = GlobalWellbeingState(applicationContext, this)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val notificationManager = getSystemService(
            NotificationManager::class.java
        )
        if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            val name: CharSequence = getString(R.string.channel_name)
            val description = getString(R.string.channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance)
            channel.description = description
            notificationManager.createNotificationChannel(channel)
        }
        lateNotify = intent.getBooleanExtra("lateNotify", lateNotify)
        val n = buildDefaultNotification()

        // Notification ID cannot be 0.
        startForeground(NOTIFICATION, n)
        return START_STICKY
    }

    fun buildAction(
        actionText: Int,
        actionIcon: Int,
        actionIntent: Intent?,
        isBroadcast: Boolean
    ): Notification.Action {
        val pendingIntent = if (isBroadcast) {
            PendingIntent.getBroadcast(this, 0, actionIntent!!, PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(this, 0, actionIntent, PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = Notification.Action.Builder(
            Icon.createWithResource(applicationContext, actionIcon),
            getText(actionText),
            pendingIntent
        )
            .setAllowGeneratedReplies(false).setContextual(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAuthenticationRequired(true)
        }
        return builder.build()
    }

    private fun buildNotification(
        title: Int,
        text: String,
        icon: Int,
        actions: Array<Notification.Action>,
        notificationIntent: Intent
    ): Notification {
        val pendingIntent =
            PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon) // the status icon
            .setTicker(text) // the status text
            .setWhen(System.currentTimeMillis()) // the time stamp
            .setContentTitle(getText(title)) // the label of the entry
            .setContentText(text) // the contents of the entry
            .setContentIntent(pendingIntent) // The intent to send when the entry is clicked
            .setOnlyAlertOnce(true) // dont headsup/bling twice
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !lateNotify) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) // do not wait with showing the notification
        }
        if (lateNotify) lateNotify = false
        for (action in actions) {
            b.addAction(action)
        }
        return b.build()
    }

    private fun buildDefaultNotification(): Notification {
        val text = R.string.notification_desc
        val title = R.string.notification_title
        val icon = R.drawable.ic_stat_name
        val notificationIntent = Intent(this, MainActivity::class.java)
        return buildNotification(title, getString(text), icon, arrayOf(), notificationIntent)
    }

    private fun updateNotification(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, n)
    }

    fun updateNotification(
        title: Int,
        text: String,
        icon: Int,
        actions: Array<Notification.Action>,
        notificationIntent: Intent
    ) {
        updateNotification(buildNotification(title, text, icon, actions, notificationIntent))
    }

    fun updateNotification(
        title: Int,
        text: Int,
        icon: Int,
        actions: Array<Notification.Action>,
        notificationIntent: Intent
    ) {
        updateNotification(title, getString(text), icon, actions, notificationIntent)
    }

    fun updateDefaultNotification() {
        updateNotification(buildDefaultNotification())
    }

    fun stop() {
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        state!!.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        return mBinder
    }

    // This is the object that receives interactions from clients.  See
    // RemoteService for a more complete example.
    private val mBinder: IBinder = LocalBinder()
}