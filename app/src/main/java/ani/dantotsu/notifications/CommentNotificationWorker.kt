package ani.dantotsu.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import ani.dantotsu.MainActivity
import ani.dantotsu.R
import ani.dantotsu.connections.comments.CommentsAPI
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.data.notification.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient


class CommentNotificationWorker(appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {
    override fun doWork(): Result {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            PrefManager.init(applicationContext) //make sure prefs are initialized
            val client = OkHttpClient()
            CommentsAPI.fetchAuthToken(client)
            val notificationResponse = CommentsAPI.getNotifications(client)
            var notifications = notificationResponse?.notifications?.toMutableList()
            //if we have at least one reply notification, we need to fetch the media titles
            var names = emptyMap<Int, MediaNameFetch.Companion.ReturnedData>()
            if (notifications?.any { it.type == 1 || it.type == null } == true) {
                val mediaIds =
                    notifications.filter { it.type == 1 || it.type == null }.map { it.mediaId }
                names = MediaNameFetch.fetchMediaTitles(mediaIds)
            }

            val recentGlobal = PrefManager.getVal<Int>(
                PrefName.RecentGlobalNotification
            )

            notifications =
                notifications?.filter { it.type != 3 || it.notificationId > recentGlobal }
                    ?.toMutableList()

            val newRecentGlobal =
                notifications?.filter { it.type == 3 }?.maxOfOrNull { it.notificationId }
            if (newRecentGlobal != null) {
                PrefManager.setVal(PrefName.RecentGlobalNotification, newRecentGlobal)
            }

            notifications?.forEach {
                val type: NotificationType = when (it.type) {
                    1 -> NotificationType.COMMENT_REPLY
                    2 -> NotificationType.COMMENT_WARNING
                    3 -> NotificationType.APP_GLOBAL
                    420 -> NotificationType.NO_NOTIFICATION
                    else -> NotificationType.UNKNOWN
                }
                val notification = when (type) {
                    NotificationType.COMMENT_WARNING -> {
                        val title = "You received a warning"
                        val message = it.content ?: "Be more thoughtful with your comments"
                        createNotification(
                            NotificationType.COMMENT_WARNING,
                            message,
                            title,
                            it.mediaId,
                            it.commentId,
                            "",
                            ""
                        )
                    }

                    NotificationType.COMMENT_REPLY -> {
                        val title = "New CommentNotificationWorker Reply"
                        val mediaName = names[it.mediaId]?.title ?: "Unknown"
                        val message = "${it.username} replied to your comment in $mediaName"
                        createNotification(
                            NotificationType.COMMENT_REPLY,
                            message,
                            title,
                            it.mediaId,
                            it.commentId,
                            names[it.mediaId]?.color ?: "#222222",
                            names[it.mediaId]?.coverImage ?: ""
                        )
                    }

                    NotificationType.APP_GLOBAL -> {
                        val title = "Update from Dantotsu"
                        val message = it.content ?: "New feature available"
                        createNotification(
                            NotificationType.APP_GLOBAL,
                            message,
                            title,
                            0,
                            0,
                            "",
                            ""
                        )
                    }

                    NotificationType.NO_NOTIFICATION -> {
                        PrefManager.removeCustomVal("genre_thumb")
                        PrefManager.removeCustomVal("banner_ANIME_time")
                        PrefManager.removeCustomVal("banner_MANGA_time")
                        PrefManager.setVal(PrefName.ImageUrl, it.content ?: "")
                        null
                    }

                    NotificationType.UNKNOWN -> {
                        null
                    }
                }

                if (ActivityCompat.checkSelfPermission(
                        applicationContext,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    if (notification != null) {
                        NotificationManagerCompat.from(applicationContext)
                            .notify(
                                type.id,
                                System.currentTimeMillis().toInt(),
                                notification
                            )
                    }
                }
            }
        }
        return Result.success()
    }

    private fun createNotification(
        notificationType: NotificationType,
        message: String,
        title: String,
        mediaId: Int,
        commentId: Int,
        color: String,
        imageUrl: String
    ): android.app.Notification? {
        Logger.log("Creating notification of type $notificationType" +
                ", message: $message, title: $title, mediaId: $mediaId, commentId: $commentId")
        val notification = when (notificationType) {
            NotificationType.COMMENT_WARNING -> {
                val intent = Intent(applicationContext, MainActivity::class.java).apply {
                    putExtra("FRAGMENT_TO_LOAD", "COMMENTS")
                    putExtra("mediaId", mediaId)
                    putExtra("commentId", commentId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                val pendingIntent = PendingIntent.getActivity(
                    applicationContext,
                    commentId,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val builder = NotificationCompat.Builder(applicationContext, notificationType.id)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(R.drawable.notification_icon)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                builder.build()
            }

            NotificationType.COMMENT_REPLY -> {
                val intent = Intent(applicationContext, MainActivity::class.java).apply {
                    putExtra("FRAGMENT_TO_LOAD", "COMMENTS")
                    putExtra("mediaId", mediaId)
                    putExtra("commentId", commentId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                val pendingIntent = PendingIntent.getActivity(
                    applicationContext,
                    commentId,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val builder = NotificationCompat.Builder(applicationContext, notificationType.id)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(R.drawable.notification_icon)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                if (imageUrl.isNotEmpty()) {
                    val bitmap = getBitmapFromUrl(imageUrl)
                    if (bitmap != null) {
                        builder.setLargeIcon(bitmap)
                    }
                }
                if (color.isNotEmpty()) {
                    builder.color = Color.parseColor(color)
                }
                builder.build()
            }

            NotificationType.APP_GLOBAL -> {
                val intent = Intent(applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                val pendingIntent = PendingIntent.getActivity(
                    applicationContext,
                    System.currentTimeMillis().toInt(),
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val builder = NotificationCompat.Builder(applicationContext, notificationType.id)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(R.drawable.notification_icon)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                builder.build()
            }

            else -> {
                null
            }
        }
        return notification
    }

    private fun getBitmapFromVectorDrawable(context: Context, drawableId: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, drawableId) ?: return null
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth,
            drawable.intrinsicHeight, Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun getBitmapFromUrl(url: String): Bitmap? {
        return try {
            val inputStream = java.net.URL(url).openStream()
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            null
        }
    }

    enum class NotificationType(val id: String) {
        COMMENT_REPLY(Notifications.CHANNEL_COMMENTS),
        COMMENT_WARNING(Notifications.CHANNEL_COMMENT_WARING),
        APP_GLOBAL(Notifications.CHANNEL_APP_GLOBAL),
        NO_NOTIFICATION("no_notification"),
        UNKNOWN("unknown")
    }

    companion object {
        val checkIntervals = arrayOf(0L, 720, 1440)
        const val WORK_NAME = "ani.dantotsu.notifications.CommentNotificationWorker"
    }
}