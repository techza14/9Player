package moe.tekuza.m9player

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebView

class NinePlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Handler(Looper.getMainLooper()).post {
            Looper.myQueue().addIdleHandler {
                runCatching {
                    WebView(this).apply {
                        loadUrl("about:blank")
                        stopLoading()
                        destroy()
                    }
                }
                false
            }
        }
    }
}
