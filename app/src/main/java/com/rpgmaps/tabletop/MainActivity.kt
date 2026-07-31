package com.rpgmaps.tabletop

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.rpgmaps.tabletop.ui.AppNavigation
import com.rpgmaps.tabletop.ui.theme.RpgMapsTheme

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // A map sitting on the table between encounters must not put the
        // tablet to sleep -- the LAN server and the Cast session both go with
        // it, and the players' screen freezes.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        askForNotificationPermission()

        setContent {
            RpgMapsTheme {
                AppNavigation()
            }
        }
    }

    /**
     * Android 13+ hides notifications until asked, including the one belonging
     * to a foreground service. The service runs either way, so this is not
     * about permission to keep the map alive -- it is so the DM can see that it
     * is alive, read the player-view address off it, and stop it without coming
     * back into the app.
     */
    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
