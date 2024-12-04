package nl.rogro82.pipup

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.File


class PipUpService : Service(), WebServer.Handler {
    private val mHandler: Handler = Handler(Looper.getMainLooper())
    private var mOverlay: FrameLayout? = null
    private var mPopup: PopupView? = null
    private lateinit var mWebServer: WebServer

    override fun onCreate() {
        super.onCreate()

        initNotificationChannel("service_channel", "Service channel", "Service channel")

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(this, "service_channel")
            .setContentTitle("PiPup")
            .setContentText("Service running")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setAutoCancel(false)
            .setOngoing(true)

        startForeground(PIPUP_NOTIFICATION_ID, notificationBuilder.build())

        mWebServer = WebServer(PIPUP_SERVER_PORT, this).apply {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }

        Log.d(LOG_TAG, "WebServer started")
    }

    override fun onDestroy() {
        super.onDestroy()

        mWebServer.stop()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    @Suppress("SameParameterValue")
    private fun initNotificationChannel(id: String, name: String, description: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(id, name,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.description = description
        notificationManager.createNotificationChannel(channel)
    }

    private fun removePopup() {
        mHandler.removeCallbacksAndMessages(null)
        mPopup?.destroy()
        mPopup = null
        mOverlay?.let { overlay ->
            overlay.removeAllViews()
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(overlay)
            mOverlay = null
        }
    }

    private fun createOverlay(): FrameLayout {
        return mOverlay ?: FrameLayout(this).apply {
            setPadding(20, 20, 20, 20)
            val layoutFlags = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlags,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(this, params)
            mOverlay = this
        }
    }

    private fun inflatePopupView(popup: PopupProps): PopupView {
        return PopupView.build(this, popup)
    }

    private fun positionPopup(popupView: PopupView, popupProps: PopupProps) {
        val layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = when (popupProps.position) {
                PopupProps.Position.TopRight -> Gravity.TOP or Gravity.END
                PopupProps.Position.TopLeft -> Gravity.TOP or Gravity.START
                PopupProps.Position.BottomRight -> Gravity.BOTTOM or Gravity.END
                PopupProps.Position.BottomLeft -> Gravity.BOTTOM or Gravity.START
                PopupProps.Position.Center -> Gravity.CENTER
            }
        }
        mOverlay?.addView(popupView, layoutParams)
    }

    private fun createPopup(popup: PopupProps) {
        try {
            Log.d(LOG_TAG, "Create popup: $popup")
            removePopup()
            createOverlay()
            mPopup = inflatePopupView(popup)
            positionPopup(mPopup!!, popup)
            mHandler.postDelayed({ removePopup() }, (popup.duration * 1000).toLong())
        } catch (ex: Throwable) {
            ex.printStackTrace()
        }
    }

    private fun parseJsonPopupProps(session: NanoHTTPD.IHTTPSession): PopupProps? {
        return try {
            val contentLength = session.headers["content-length"]?.toInt() ?: 0
            val content = ByteArray(contentLength)
            session.inputStream.read(content, 0, contentLength)
            Json.readValue(content, PopupProps::class.java)
        } catch (ex: Exception) {
            Log.e(LOG_TAG, "Failed to parse JSON: ${ex.message}")
            null
        }
    }

    private fun parseMultipartPopupProps(session: NanoHTTPD.IHTTPSession): PopupProps? {
        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)

            // flatten parameters
            val params = session.parameters.mapValues { it.value.firstOrNull() }

            val duration = params["duration"]?.toIntOrNull()
                ?: PopupProps.DEFAULT_DURATION

            val position = PopupProps.Position.values()[params["position"]?.toIntOrNull() ?: 0]

            val backgroundColor = params["backgroundColor"]
                ?: PopupProps.DEFAULT_BACKGROUND_COLOR

            val title = params["title"]

            val titleSize = params["titleSize"]?.toFloatOrNull()
                ?: PopupProps.DEFAULT_TITLE_SIZE

            val titleColor = params["titleColor"]
                ?: PopupProps.DEFAULT_TITLE_COLOR

            val message = params["message"]

            val messageSize = params["messageSize"]?.toFloatOrNull()
                ?: PopupProps.DEFAULT_TITLE_SIZE

            val messageColor = params["messageColor"]
                ?: PopupProps.DEFAULT_TITLE_COLOR

            val media = when(val image = files["image"]) {
                is String -> {
                    File(image).absoluteFile.let {
                        val bitmap = BitmapFactory.decodeStream(it.inputStream())
                        val imageWidth = params["imageWidth"]?.toIntOrNull() ?: PopupProps.DEFAULT_MEDIA_WIDTH

                        PopupProps.Media.Bitmap(image = bitmap, width = imageWidth)
                    }
                }
                else -> null
            }

            PopupProps(
                duration = duration,
                position = position,
                backgroundColor =  backgroundColor,
                title = title,
                titleSize = titleSize,
                titleColor = titleColor,
                message = message,
                messageSize = messageSize,
                messageColor = messageColor,
                media = media
            )

        } catch (ex: Exception) {
            Log.e(LOG_TAG, "Failed to parse multipart data: ${ex.message}")
            null
        }
    }

    override fun handleHttpRequest(session: NanoHTTPD.IHTTPSession?): NanoHTTPD.Response {
        return session?.let {
            when (session.method) {
                NanoHTTPD.Method.POST -> {
                    when (session.uri) {
                        "/cancel" -> {
                            mHandler.post { removePopup() }
                            ok()
                        }
                        "/notify" -> {
                            val contentType = session.headers["content-type"] ?: APPLICATION_JSON
                            val popup = when {
                                contentType.startsWith(APPLICATION_JSON) -> parseJsonPopupProps(session)
                                contentType.startsWith(MULTIPART_FORM_DATA) -> parseMultipartPopupProps(session)
                                else -> {
                                    Log.e(LOG_TAG, "Invalid content-type: $contentType")
                                    null
                                }
                            }
                            popup?.let {
                                Log.d(LOG_TAG, "Received popup: $it")
                                mHandler.post { createPopup(it) }
                                ok("$it")
                            } ?: invalidRequest("Failed to parse popup data")
                        }
                        else -> invalidRequest("Unknown URI: ${session.uri}")
                    }
                }
                else -> invalidRequest("Invalid method")
            }
        } ?: invalidRequest()
    }

    companion object {
        const val LOG_TAG = "PiPupService"
        const val PIPUP_SERVER_PORT = 7979
        const val PIPUP_NOTIFICATION_ID = 123
        const val MULTIPART_FORM_DATA = "multipart/form-data"
        const val APPLICATION_JSON = "application/json"

        fun ok(message: String? = null): NanoHTTPD.Response = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", message)
        fun invalidRequest(message: String? = null): NanoHTTPD.Response = newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", "invalid request: $message")
    }
}
