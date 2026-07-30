package com.rpgmaps.tabletop

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rpgmaps.tabletop.ui.AppNavigation
import com.rpgmaps.tabletop.ui.theme.RpgMapsTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // A map sitting on the table between encounters must not put the
        // tablet to sleep -- the LAN server and the Cast session both go with
        // it, and the players' screen freezes.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            RpgMapsTheme {
                AppNavigation()
            }
        }
    }
}
