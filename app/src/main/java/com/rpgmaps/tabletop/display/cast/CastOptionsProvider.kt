package com.rpgmaps.tabletop.display.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.rpgmaps.tabletop.R

/**
 * Tells the Cast framework which receiver application to launch on the
 * Chromecast. Instantiated by name from the manifest, so it must stay public
 * with a no-arg constructor (and is kept in proguard-rules.pro).
 *
 * The application ID comes from `cast_app_id`, defined in app/build.gradle.kts.
 * Until you register your own receiver it is Google's Default Media Receiver,
 * which will connect but cannot draw fog of war -- see docs/CASTING.md.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(context.getString(R.string.cast_app_id))
            // The map is not media; leave the notification and lock screen
            // controls out of it entirely.
            .setEnableReconnectionService(true)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
