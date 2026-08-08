package dev.tradescanner.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

class MediaProjectionPermissionActivity : Activity() {
    companion object {
        const val REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CODE)
        } catch (e: Exception) {
            Log.e("MPermActivity", "Error", e)
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE) {
            val resultIntent = Intent()
            resultIntent.putExtra("resultCode", resultCode)
            resultIntent.putExtra("data", data)
            setResult(resultCode, resultIntent)
        }
        finish()
        super.onActivityResult(requestCode, resultCode, data)
    }
}
