/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks

import android.app.Activity
import android.app.backup.BackupManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.core.view.updateLayoutParams
import androidx.drawerlayout.widget.DrawerLayout
import androidx.preference.PreferenceDataStore
import com.github.shadowsocks.acl.CustomRulesFragment
import com.github.shadowsocks.aidl.IShadowsocksService
import com.github.shadowsocks.aidl.ShadowsocksConnection
import com.github.shadowsocks.aidl.TrafficStats
import com.github.shadowsocks.bg.BaseService
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.preference.OnPreferenceDataStoreChangeListener
import com.github.shadowsocks.subscription.SubscriptionFragment
import com.github.shadowsocks.utils.Key
import com.github.shadowsocks.utils.SingleInstanceActivity
import com.github.shadowsocks.utils.printLog
import com.github.shadowsocks.widget.ListHolderListener
import com.github.shadowsocks.widget.ServiceButton
import com.github.shadowsocks.widget.StatsBar
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.InterstitialAd
import com.google.android.gms.ads.MobileAds
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import java.util.*

class MainActivity : AppCompatActivity(), ShadowsocksConnection.Callback, OnPreferenceDataStoreChangeListener,
        NavigationView.OnNavigationItemSelectedListener {
    companion object {
        private const val TAG = "ShadowsocksMainActivity"
        private const val REQUEST_CONNECT = 1

        var stateListener: ((BaseService.State) -> Unit)? = null
        @JvmStatic var newsClickCount = 1L
    }

    // UI
    private lateinit var fab: ServiceButton
    private lateinit var stats: StatsBar
    internal lateinit var drawer: DrawerLayout
    private lateinit var navigation: NavigationView

    lateinit var snackbar: CoordinatorLayout private set
    fun snackbar(text: CharSequence = "") = Snackbar.make(snackbar, text, Snackbar.LENGTH_LONG).apply {
        anchorView = fab
    }

    private val customTabsIntent by lazy {
        CustomTabsIntent.Builder().apply {
            setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
            setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_LIGHT, CustomTabColorSchemeParams.Builder().apply {
                setToolbarColor(ContextCompat.getColor(this@MainActivity, R.color.light_color_primary))
            }.build())
            setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, CustomTabColorSchemeParams.Builder().apply {
                setToolbarColor(ContextCompat.getColor(this@MainActivity, R.color.dark_color_primary))
            }.build())
        }.build()
    }
    fun launchUrl(uri: String) = try {
        customTabsIntent.launchUrl(this, uri.toUri())
    } catch (_: ActivityNotFoundException) {
        snackbar(uri).show()
    }

    // service
    var state = BaseService.State.Idle
    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) =
            changeState(state, msg, true)
    override fun trafficUpdated(profileId: Long, stats: TrafficStats) {
        if (profileId == 0L) this@MainActivity.stats.updateTraffic(
                stats.txRate, stats.rxRate, stats.txTotal, stats.rxTotal)
        if (state != BaseService.State.Stopping) {
            (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ProfilesFragment)
                    ?.onTrafficUpdated(profileId, stats)
        }
    }
    override fun trafficPersisted(profileId: Long) {
        ProfilesFragment.instance?.onTrafficPersisted(profileId)
    }

    private fun changeState(state: BaseService.State, msg: String? = null, animate: Boolean = false) {
        fab.changeState(state, this.state, animate)
        stats.changeState(state)
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
        this.state = state
        ProfilesFragment.instance?.profilesAdapter?.notifyDataSetChanged()  // refresh button enabled state
        stateListener?.invoke(state)
    }

    private fun toggle() = try {
        when {
            state.canStop -> Core.stopService()
            (DataStore.serviceMode == Key.modeVpn) -> {
                val intent = VpnService.prepare(this)
                if (intent != null) startActivityForResult(intent, REQUEST_CONNECT)
                else onActivityResult(REQUEST_CONNECT, Activity.RESULT_OK, null)
            }
            else -> Core.startService()
        }
    }
    catch (t:Throwable){
        printLog(t)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val connection = ShadowsocksConnection(handler, true)
    fun getShadowsocksConnection():ShadowsocksConnection{return connection}
    override fun onServiceConnected(service: IShadowsocksService) = changeState(try {
        BaseService.State.values()[service.state]
    } catch (_: RemoteException) {
        BaseService.State.Idle
    })
    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when {
            requestCode != REQUEST_CONNECT -> super.onActivityResult(requestCode, resultCode, data)
            resultCode == Activity.RESULT_OK -> Core.startService()
            else -> {
                snackbar().setText(R.string.vpn_permission_denied).show()
                printLog("Failed to start VpnService from onActivityResult: $data")
            }
        }
    }
    lateinit var mAdView : AdView
    lateinit var mInterstitialAd: InterstitialAd

    fun userActionAds(){
        if (newsClickCount%3==2L)mInterstitialAd.loadAd(AdRequest.Builder().build())
        if (newsClickCount%3==1L && mInterstitialAd.isLoaded) {
            Log.e("ads", "click count is $newsClickCount ,show ad.")
            mInterstitialAd.show()
        } else {
            Log.e("ads", "click count is $newsClickCount ,The interstitial wasn't loaded yet.")
        }
        newsClickCount++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;//会导致statusbar有时不能自动缩回，改为在AndroidMainfest.xml设置
        //如果rotation，会导致批量测试过程出错，view无法更新，最后报错
        SingleInstanceActivity.register(this) ?: return
        setContentView(R.layout.layout_main)

        MobileAds.initialize(this) {}
//        mAdView = findViewById(R.id.adView)
//        val adRequest = AdRequest.Builder().build()
//        mAdView.loadAd(adRequest)

        mInterstitialAd = InterstitialAd(this)
        mInterstitialAd.adUnitId = getString(R.string.chaye_adUnitId)
        mInterstitialAd.loadAd(AdRequest.Builder().build())

        snackbar = findViewById(R.id.snackbar)
        snackbar.setOnApplyWindowInsetsListener(ListHolderListener)
        stats = findViewById(R.id.stats)
        stats.setOnClickListener {
            userActionAds()
            if (state == BaseService.State.Connected) stats.testConnection()
        }
        drawer = findViewById(R.id.drawer)
        drawer.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        navigation = findViewById(R.id.navigation)
        navigation.setNavigationItemSelectedListener(this)
        if (savedInstanceState == null) {
            navigation.menu.findItem(R.id.profiles).isChecked = true
            displayFragment(ProfilesFragment())
        }

        fab = findViewById(R.id.fab)
        fab.setOnClickListener {
            if(state.canStop)userActionAds()
            toggle()
        }
        fab.setOnApplyWindowInsetsListener { view, insets ->
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = insets.systemWindowInsetBottom +
                        resources.getDimensionPixelOffset(R.dimen.mtrl_bottomappbar_fab_bottom_margin)
            }
            insets
        }

        changeState(BaseService.State.Idle) // reset everything to init state
        connection.connect(this, this)
        DataStore.connection=connection
        DataStore.publicStore.registerChangeListener(this)
        //updateBuiltinServers
        if(DataStore.isAutoUpdateServers){
            Core.updateBuiltinServers()
        }
        //Log.e("user-country", Locale.getDefault().country)
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.serviceMode -> handler.post {
                connection.disconnect(this)
                connection.connect(this, this)
            }
        }
    }

    private fun displayFragment(fragment: ToolbarFragment) {
        supportFragmentManager.beginTransaction().replace(R.id.fragment_holder, fragment).commitAllowingStateLoss()
        drawer.closeDrawers()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.isChecked) drawer.closeDrawers() else {
            when (item.itemId) {
                R.id.profiles -> {
                    //userActionAds()
                    displayFragment(ProfilesFragment())
                    connection.bandwidthTimeout = connection.bandwidthTimeout   // request stats update
                }
                R.id.globalSettings -> {
                    userActionAds()
                    displayFragment(GlobalSettingsFragment())
                }
                R.id.about -> {
                    userActionAds()
                    displayFragment(AboutFragment())
                }
                R.id.faq -> {
                    launchUrl(getString(R.string.faq_url))
                    return true
                }
                R.id.customRules -> {
                    userActionAds()
                    displayFragment(CustomRulesFragment())
                }
                R.id.subscriptions -> {
                    userActionAds()
                    displayFragment(SubscriptionFragment())
                }
                else -> return false
            }
            item.isChecked = true
        }
        return true
    }

    override fun onStart() {
        super.onStart()
        connection.bandwidthTimeout = 500
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawers() else {
            val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_holder) as ToolbarFragment
            if (!currentFragment.onBackPressed()) {
                if (currentFragment is ProfilesFragment) super.onBackPressed() else {
                    navigation.menu.findItem(R.id.profiles).isChecked = true
                    displayFragment(ProfilesFragment())
                }
            }
        }
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent) = when {
        keyCode == KeyEvent.KEYCODE_G && event.hasModifiers(KeyEvent.META_CTRL_ON) -> {
            toggle()
            true
        }
        keyCode == KeyEvent.KEYCODE_T && event.hasModifiers(KeyEvent.META_CTRL_ON) -> {
            stats.testConnection()
            true
        }
        else -> (supportFragmentManager.findFragmentById(R.id.fragment_holder) as ToolbarFragment).toolbar.menu.let {
            it.setQwertyMode(KeyCharacterMap.load(event.deviceId).keyboardType != KeyCharacterMap.NUMERIC)
            it.performShortcut(keyCode, event, 0)
        }
    }

    override fun onStop() {
        connection.bandwidthTimeout = 0
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        DataStore.publicStore.unregisterChangeListener(this)
        connection.disconnect(this)
        BackupManager(this).dataChanged()
        handler.removeCallbacksAndMessages(null)
    }
}
