package com.blockstream.green.gdk

import com.blockstream.gdk.GreenWallet
import com.blockstream.gdk.GreenWallet.Companion.JsonDeserializer
import com.blockstream.green.settings.SettingsManager
import com.blockstream.green.database.Wallet
import com.blockstream.green.database.WalletId
import com.blockstream.green.utils.AssetManager
import com.blockstream.libgreenaddress.GASession
import com.greenaddress.greenapi.Session
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import mu.KLogging

class SessionManager(
    private val settingsManager: SettingsManager,
    private val assetManager: AssetManager,
    private val greenWallet: GreenWallet
) {

    private val sessions = mutableMapOf<GASession, GreenSession>()
    private val walletSessions = mutableMapOf<WalletId, GreenSession>()
    private var onBoardingSession: GreenSession? = null
    private var jadeSession: GreenSession? = null

    private var hardwareSessionV3: GreenSession? = null

    init {
        greenWallet.setNotificationHandler { gaSession, jsonObject ->
            sessions[gaSession]?.apply {

                // Pass notification to to GDKSession
                Session.getSession().also {
                    if(it.nativeSession == gaSession){
                        it.notificationModel.onNewNotification(gaSession, jsonObject)
                    }
                }

                onNewNotification(JsonDeserializer.decodeFromJsonElement(jsonObject as JsonElement))
            }
        }
    }

    fun getWalletSession(wallet: Wallet): GreenSession {
        if (walletSessions[wallet.id] == null) {
            val session = createSession()
            walletSessions[wallet.id] = session
        }

        return walletSessions[wallet.id]!!
    }

    // Used to find a GreenSession from v3 gaSession
    fun getWalletSession(gaSession: GASession?): GreenSession? {
        return sessions[gaSession]
    }

    // Used to find the Wallet Id from v3 gaSession
    fun getWalletIdFromSession(gaSession: GASession?): WalletId {
        getWalletSession(gaSession)?.let { greenSession ->
            for (key in walletSessions.keys){
                if(walletSessions[key] == greenSession){
                    return key
                }
            }
        }

        return -1
    }

    fun destroyWalletSession(wallet: Wallet){
        walletSessions[wallet.id]?.let{
            it.destroy()
            sessions.remove(it.gaSession)
        }

        walletSessions.remove(wallet.id)
    }

    fun getHardwareSessionV3(): GreenSession {
        if (hardwareSessionV3 == null) {
            hardwareSessionV3 = createSession()
        }

        return hardwareSessionV3!!
    }

    fun getOnBoardingSession(wallet: Wallet? = null): GreenSession {
        wallet?.let {
            return getWalletSession(it)
        }

        // OnBoardingSession waits petiently to be upgraded to a proper wallet session

        if (onBoardingSession == null) {
            onBoardingSession = createSession()
        }

        return onBoardingSession!!
    }

    // Is this really needed
    fun getJadeSession(): GreenSession {
        if (jadeSession == null) {
            jadeSession = createSession()
        }

        return jadeSession!!
    }

    fun upgradeOnBoardingSessionToWallet(wallet: Wallet) {
        onBoardingSession?.let {
            walletSessions[wallet.id] = it
            onBoardingSession = null
        }
    }

    // Always use this method to create a Session as it keeps track of gaSession & GreenSession
    private fun createSession(): GreenSession {
        val session = GreenSession(settingsManager, assetManager, greenWallet)

        sessions[session.gaSession] = session

        return session
    }

    companion object: KLogging()
}