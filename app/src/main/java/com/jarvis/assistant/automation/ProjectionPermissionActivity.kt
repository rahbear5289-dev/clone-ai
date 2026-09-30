package com.jarvis.assistant.automation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

class ProjectionPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), 91)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 91) {
            if (resultCode == RESULT_OK && data != null) {
                ScreenCaptureHolder.resultCode = resultCode
                ScreenCaptureHolder.resultData = data
            } else {
                ScreenCaptureHolder.clear()
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        fun launch(context: Context) {
            val i = Intent(context, ProjectionPermissionActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            BackgroundActivityLauncher.launch(context, i)
        }
    }
}
