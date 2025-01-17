package com.github.shadowsocks.utils

import SpeedUpVPN.VpnEncrypt
import android.app.Application
import android.text.TextUtils
import android.util.Log
import com.github.shadowsocks.Core
import com.github.shadowsocks.database.AppConfig
import com.github.shadowsocks.database.V2rayConfig
import com.github.shadowsocks.database.VmessBean
import com.github.shadowsocks.preference.DataStore
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object V2rayConfigUtil {
    private val requestObj: JsonObject by lazy {
        Gson().fromJson("""{"version":"1.1","method":"GET","path":["/"],"headers":{"User-Agent":["Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/55.0.2883.75 Safari/537.36","Mozilla/5.0 (iPhone; CPU iPhone OS 10_0_2 like Mac OS X) AppleWebKit/601.1 (KHTML, like Gecko) CriOS/53.0.2785.109 Mobile/14A456 Safari/601.1.46"],"Accept-Encoding":["gzip, deflate"],"Connection":["keep-alive"],"Pragma":"no-cache"}}""", JsonObject::class.java)
    }

//    private val responseObj: JSONObject by lazy {
//        JSONObject("""{"version":"1.1","status":"200","reason":"OK","headers":{"Content-Type":["application/octet-stream","video/mpeg"],"Transfer-Encoding":["chunked"],"Connection":["keep-alive"],"Pragma":"no-cache"}}""")
//    }

    data class Result(var status: Boolean, var content: String)

    /**
     * 生成v2ray的客户端配置文件
     */
    fun getV2rayConfig(app: Application, vmess: VmessBean,isTest:Boolean=false): Result {
        var result = Result(false, "")
        try {
            //检查设置
//            if (config.index < 0
//                    || config.vmess.count() <= 0
//                    || config.index > config.vmess.count() - 1
//            ) {
//                return result
//            }

            if (vmess.configType == AppConfig.EConfigType.vmess || vmess.configType == AppConfig.EConfigType.vless) {
                result = getV2rayConfigType1(app, vmess,isTest)
            } else if (vmess.configType == AppConfig.EConfigType.custom) {
                result = getV2rayConfigType2(app, vmess)
            } else if (vmess.configType == AppConfig.EConfigType.shadowsocks) {
                result = getV2rayConfigType1(app, vmess)
            } else if (vmess.configType == AppConfig.EConfigType.socks) {
                result = getV2rayConfigType1(app, vmess)
            }

            val domainName = parseDomainName(result.content)
            if (!TextUtils.isEmpty(domainName)) {
                Core.defaultDPreference.setPrefString(AppConfig.PREF_CURR_CONFIG_DOMAIN, domainName)
            }

            Log.d("V2rayConfigUtilGoLog", result.content)
            return result
        } catch (e: Exception) {
            e.printStackTrace()
            return result
        }
    }

    /**
     * 生成v2ray的客户端配置文件
     */
    private fun getV2rayConfigType1(app: Application, vmess: VmessBean,isTest:Boolean=false): Result {
        val result = Result(false, "")
        try {
            //取得默认配置
            val assets = readTextFromAssets(app, "v2ray_config.json")
            if (TextUtils.isEmpty(assets)) {
                return result
            }

            //转成Json
            val theInstance=V2rayConfig()
            val v2rayConfig = Gson().fromJson(assets, theInstance::class.java) ?: return result
//            if (v2rayConfig == null) {
//                return result
//            }

            inbounds(vmess, v2rayConfig, app)

            outbounds(vmess, v2rayConfig, app)

            routing(vmess, v2rayConfig, app,isTest)

            //if (VpnEncrypt.enableLocalDns) {customLocalDns(vmess, v2rayConfig, app) } else {
                customRemoteDns(vmess, v2rayConfig, app)
            //}


            v2rayConfig.outbounds.forEach {
                if("freedom" == it.protocol){
                    it.mux=null
                    it.settings=null
                }
                else if ("blackhole" == it.protocol){
                    it.mux=null
                    it.settings?.servers=null
                    it.settings?.vnext=null
                }
                else{
                    it.settings?.response=null
                }
            }
            val finalConfig = GsonBuilder().setPrettyPrinting().create().toJson(v2rayConfig)

            result.status = true
            result.content = finalConfig
            return result

        } catch (e: Exception) {
            e.printStackTrace()
            return result
        }
    }

    /**
     * 生成v2ray的客户端配置文件
     */
    private fun getV2rayConfigType2(app: Application, vmess: VmessBean): Result {
        val result = Result(false, "")
        try {
            val guid = vmess.guid
            val jsonConfig = Core.defaultDPreference.getPrefString(AppConfig.ANG_CONFIG + guid, "")
            result.status = true
            result.content = jsonConfig
            return result

        } catch (e: Exception) {
            e.printStackTrace()
            return result
        }
    }

    /**
     *
     */
    private fun inbounds(vmess: VmessBean, v2rayConfig: V2rayConfig, app: Application): Boolean {
        try {
            v2rayConfig.inbounds.forEach { curInbound ->
                if (!DataStore.publicStore.getBoolean(Key.shareOverLan, false)) {
                    //bind all inbounds to localhost if the user requests
                    curInbound.listen = "127.0.0.1"
                }
            }
            v2rayConfig.inbounds[0].port = DataStore.portProxy
            //v2rayConfig.inbounds[1].port = DataStore.portHttpProxy

//            val socksPort = Utils.parseInt(app.defaultDPreference.getPrefString(SettingsActivity.PREF_SOCKS_PORT, "10808"))
//            val lanconnPort = Utils.parseInt(app.defaultDPreference.getPrefString(SettingsActivity.PREF_HTTP_PORT, ""))

//            if (socksPort > 0) {
//                v2rayConfig.inbounds[0].port = socksPort
//            }
//            if (lanconnPort > 0) {
//                val httpCopy = v2rayConfig.inbounds[0].copy()
//                httpCopy.port = lanconnPort
//                httpCopy.protocol = "http"
//                v2rayConfig.inbounds.add(httpCopy)
//            }
            v2rayConfig.inbounds[0].sniffing?.enabled = VpnEncrypt.enableSniffing

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * vmess协议服务器配置
     */
    private fun outbounds(vmess: VmessBean, v2rayConfig: V2rayConfig, app: Application): Boolean {
        try {
            val outbound = v2rayConfig.outbounds[0]

            when (vmess.configType) {
                AppConfig.EConfigType.vless -> {
                    outbound.settings?.servers = null
                    val vnext = v2rayConfig.outbounds[0].settings?.vnext?.get(0)
                    vnext?.address = vmess.address
                    vnext?.port = vmess.port
                    val user = vnext?.users?.get(0)
                    user?.id = vmess.id
                    user?.encryption = vmess.security
                    user?.flow = vmess.flow
                    user?.alterId = 0
                    user?.security = "auto"
                    user?.level = 8

                    //Mux
                    val muxEnabled = false//app.defaultDPreference.getPrefBoolean(SettingsActivity.PREF_MUX_ENABLED, false)
                    outbound.mux?.enabled = muxEnabled

                    //远程服务器底层传输配置
                    outbound.streamSettings = boundStreamSettings(vmess)

                    outbound.protocol = vmess.configType
                }
                AppConfig.EConfigType.vmess -> {
                    outbound.settings?.servers = null

                    val vnext = v2rayConfig.outbounds[0].settings?.vnext?.get(0)
                    vnext?.address = vmess.address
                    vnext?.port = vmess.port
                    val user = vnext?.users?.get(0)
                    user?.id = vmess.id
                    user?.alterId = vmess.alterId
                    user?.security = vmess.security
                    user?.level = 8

                    //Mux
                    val muxEnabled = false//app.defaultDPreference.getPrefBoolean(SettingsActivity.PREF_MUX_ENABLED, false)
                    outbound.mux?.enabled = muxEnabled

                    //远程服务器底层传输配置
                    outbound.streamSettings = boundStreamSettings(vmess)

                    outbound.protocol = vmess.configType
                }
                AppConfig.EConfigType.shadowsocks -> {
                    outbound.settings?.vnext = null

                    val server = outbound.settings?.servers?.get(0)
                    server?.address = vmess.address
                    server?.method = vmess.security
                    server?.ota = false
                    server?.password = vmess.id
                    server?.port = vmess.port
                    server?.level = 8

                    //Mux
                    outbound.mux?.enabled = false

                    outbound.protocol = "shadowsocks"
                }
                AppConfig.EConfigType.socks -> {
                    outbound.settings?.vnext = null

                    val server = outbound.settings?.servers?.get(0)
                    server?.address = vmess.address
                    server?.port = vmess.port

                    //Mux
                    outbound.mux?.enabled = false

                    outbound.protocol = "socks"
                }
                else -> {
                }
            }

            var serverDomain: String
            if(isIpv6Address(vmess.address)) {
                serverDomain = String.format("[%s]:%s", vmess.address, vmess.port)
            } else {
                serverDomain = String.format("%s:%s", vmess.address, vmess.port)
            }
            Core.defaultDPreference.setPrefString(AppConfig.PREF_CURR_CONFIG_DOMAIN, serverDomain)

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * 远程服务器底层传输配置
     */
    private fun boundStreamSettings(vmess: VmessBean): V2rayConfig.OutboundBean.StreamSettingsBean {
        val streamSettings = V2rayConfig.OutboundBean.StreamSettingsBean("", "", null, null, null, null, null, null)
        try {
            //远程服务器底层传输配置
            streamSettings.network = vmess.network
            streamSettings.security = vmess.streamSecurity

            val tlssettings = V2rayConfig.OutboundBean.StreamSettingsBean.TlssettingsBean()
            tlssettings.allowInsecure = vmess.allowInsecure.toBoolean()
            tlssettings.serverName=vmess.SNI
            if ("xtls" ==  vmess.streamSecurity)
                streamSettings.xtlsSettings = tlssettings
            else
                streamSettings.tlsSettings = tlssettings

            //streamSettings
            when (streamSettings.network) {
                "kcp" -> {
                    val kcpsettings = V2rayConfig.OutboundBean.StreamSettingsBean.KcpsettingsBean()
                    kcpsettings.mtu = 1350
                    kcpsettings.tti = 50
                    kcpsettings.uplinkCapacity = 12
                    kcpsettings.downlinkCapacity = 100
                    kcpsettings.congestion = false
                    kcpsettings.readBufferSize = 1
                    kcpsettings.seed = vmess.path.trim()
                    kcpsettings.writeBufferSize = 1
                    kcpsettings.header = V2rayConfig.OutboundBean.StreamSettingsBean.KcpsettingsBean.HeaderBean()
                    kcpsettings.header.type = vmess.headerType
                    streamSettings.kcpSettings = kcpsettings
                }
                "ws" -> {
                    val wssettings = V2rayConfig.OutboundBean.StreamSettingsBean.WssettingsBean()
                    val host = vmess.requestHost.trim()
                    val path = vmess.path.trim()

                    if (!TextUtils.isEmpty(host)) {
                        wssettings.headers = V2rayConfig.OutboundBean.StreamSettingsBean.WssettingsBean.HeadersBean()
                        wssettings.headers.Host = host
                    }
                    if (!TextUtils.isEmpty(path)) {
                        wssettings.path = path
                    }
                    streamSettings.wsSettings = wssettings
                }
                "h2" -> {
                    val httpsettings = V2rayConfig.OutboundBean.StreamSettingsBean.HttpsettingsBean()
                    val host = vmess.requestHost.trim()
                    val path = vmess.path.trim()

                    if (!TextUtils.isEmpty(host)) {
                        httpsettings.host = host.split(",").map { it.trim() }
                    }
                    httpsettings.path = path
                    streamSettings.httpsettings = httpsettings
                }
                "quic" -> {
                    val quicsettings = V2rayConfig.OutboundBean.StreamSettingsBean.QuicsettingBean()
                    val host = vmess.requestHost.trim()
                    val path = vmess.path.trim()

                    quicsettings.security = host
                    quicsettings.key = path

                    quicsettings.header = V2rayConfig.OutboundBean.StreamSettingsBean.QuicsettingBean.HeaderBean()
                    quicsettings.header.type = vmess.headerType

                    streamSettings.quicsettings = quicsettings
                }
                else -> {
                    //tcp带http伪装
                    if (vmess.headerType == "http") {
                        val tcpSettings = V2rayConfig.OutboundBean.StreamSettingsBean.TcpsettingsBean()
                        tcpSettings.connectionReuse = true
                        tcpSettings.header = V2rayConfig.OutboundBean.StreamSettingsBean.TcpsettingsBean.HeaderBean()
                        tcpSettings.header.type = vmess.headerType

//                        if (requestObj.has("headers")
//                                || requestObj.optJSONObject("headers").has("Pragma")) {
//                            val arrHost = ArrayList<String>()
//                            vmess.requestHost
//                                    .split(",")
//                                    .forEach {
//                                        arrHost.add(it)
//                                    }
//                            requestObj.optJSONObject("headers")
//                                    .put("Host", arrHost)
//
//                        }
                        if (!TextUtils.isEmpty(vmess.requestHost)) {
                            val arrHost = ArrayList<String>()
                            vmess.requestHost
                                    .split(",")
                                    .forEach {
                                        arrHost.add("\"$it\"")
                                    }
                            requestObj.getAsJsonObject("headers")
                                    .add("Host", Gson().fromJson(arrHost.toString(), JsonArray::class.java))
                        }
                        if (!TextUtils.isEmpty(vmess.path)) {
                            val arrPath = ArrayList<String>()
                            vmess.path
                                    .split(",")
                                    .forEach {
                                        arrPath.add("\"$it\"")
                                    }
                            requestObj.add("path", Gson().fromJson(arrPath.toString(), JsonArray::class.java))
                        }
                        tcpSettings.header.request = requestObj
                        //tcpSettings.header.response = responseObj
                        streamSettings.tcpSettings = tcpSettings
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return streamSettings
        }
        return streamSettings
    }

    /**
     * routing
     */
    private fun routing(vmess: VmessBean, v2rayConfig: V2rayConfig, app: Application,isTest:Boolean=false): Boolean {
        try {
            routingUserRule(Core.defaultDPreference.getPrefString(AppConfig.PREF_V2RAY_ROUTING_AGENT, ""), AppConfig.TAG_AGENT, v2rayConfig)
            routingUserRule(Core.defaultDPreference.getPrefString(AppConfig.PREF_V2RAY_ROUTING_DIRECT, ""), AppConfig.TAG_DIRECT, v2rayConfig)
            routingUserRule(Core.defaultDPreference.getPrefString(AppConfig.PREF_V2RAY_ROUTING_BLOCKED, ""), AppConfig.TAG_BLOCKED, v2rayConfig)

            v2rayConfig.routing.domainStrategy = VpnEncrypt.PREF_ROUTING_DOMAIN_STRATEGY
            //val routingMode = Core.defaultDPreference.getPrefString(vmess.route, "0")
            var routingMode = vmess.route  //强制全局模式，"3"时启动很慢
            if (isTest)routingMode="0" //测试时强制全局模式，"3"时启动很慢

            // Hardcode googleapis.cn
            val googleapisRoute = V2rayConfig.RoutingBean.RulesBean(
                type = "field",
                outboundTag = AppConfig.TAG_AGENT,
                domain = arrayListOf("domain:googleapis.cn")
            )

            when (routingMode) {
                "0" -> {
                }
                "1" -> {
                    routingGeo("ip", "private", AppConfig.TAG_DIRECT, v2rayConfig)
                }
                "2" -> {
                    routingGeo("", "cn", AppConfig.TAG_DIRECT, v2rayConfig)
                    v2rayConfig.routing.rules.add(0, googleapisRoute)
                }
                "3" -> {
                    routingGeo("ip", "private", AppConfig.TAG_DIRECT, v2rayConfig)
                    routingGeo("", "cn", AppConfig.TAG_DIRECT, v2rayConfig)
                    v2rayConfig.routing.rules.add(0, googleapisRoute)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    private fun routingGeo(ipOrDomain: String, code: String, tag: String, v2rayConfig: V2rayConfig) {
        try {
            if (!TextUtils.isEmpty(code)) {
                //IP
                if (ipOrDomain == "ip" || ipOrDomain == "") {
                    val rulesIP = V2rayConfig.RoutingBean.RulesBean()
                    rulesIP.type = "field"
                    rulesIP.outboundTag = tag
                    rulesIP.ip = ArrayList<String>()
                    rulesIP.ip?.add("geoip:$code")
                    v2rayConfig.routing.rules.add(rulesIP)
                }

                if (ipOrDomain == "domain" || ipOrDomain == "") {
                    //Domain
                    val rulesDomain = V2rayConfig.RoutingBean.RulesBean()
                    rulesDomain.type = "field"
                    rulesDomain.outboundTag = tag
                    rulesDomain.domain = ArrayList<String>()
                    rulesDomain.domain?.add("geosite:$code")
                    v2rayConfig.routing.rules.add(rulesDomain)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun routingUserRule(userRule: String, tag: String, v2rayConfig: V2rayConfig) {
        try {
            if (!TextUtils.isEmpty(userRule)) {
                //Domain
                val rulesDomain = V2rayConfig.RoutingBean.RulesBean()
                rulesDomain.type = "field"
                rulesDomain.outboundTag = tag
                rulesDomain.domain = ArrayList<String>()

                //IP
                val rulesIP = V2rayConfig.RoutingBean.RulesBean()
                rulesIP.type = "field"
                rulesIP.outboundTag = tag
                rulesIP.ip = ArrayList<String>()

                userRule.trim().replace("\n", "")
                        .split(",")
                        .forEach {
                            if (isIpAddress(it) || it.startsWith("geoip:")) {
                                rulesIP.ip?.add(it)
                            } else if (it.isNotBlank() || it.isNotEmpty())
//                                if (Utils.isValidUrl(it)
//                                    || it.startsWith("geosite:")
//                                    || it.startsWith("regexp:")
//                                    || it.startsWith("domain:")
//                                    || it.startsWith("full:"))
                            {
                                rulesDomain.domain?.add(it)
                            }
                        }
                if (rulesDomain.domain?.size!! > 0) {
                    v2rayConfig.routing.rules.add(rulesDomain)
                }
                if (rulesIP.ip?.size!! > 0) {
                    v2rayConfig.routing.rules.add(rulesIP)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun userRule2Domian(userRule: String): ArrayList<String> {
        val domain = ArrayList<String>()
        userRule.trim().replace("\n", "").split(",").forEach {
            if ((it.startsWith("geosite:") || it.startsWith("domain:")) &&
                    it.isNotBlank() && it.isNotEmpty()) {
                domain.add(it)
            }
        }
        return domain
    }

    /**
     * Custom Remote Dns
     */
    private fun customRemoteDns(vmess: VmessBean, v2rayConfig: V2rayConfig, app: Application): Boolean {
        try {
            val servers = ArrayList<Any>()
            servers.add(vmess.remoteDns)
            v2rayConfig.dns = V2rayConfig.DnsBean(servers = servers)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * is valid config
     */
    fun isValidConfig(conf: String): Boolean {
        try {
            val jObj = JSONObject(conf)
            var hasBound = false
            //hasBound = (jObj.has("outbounds") and jObj.has("inbounds")) or (jObj.has("outbound") and jObj.has("inbound"))
            hasBound = (jObj.has("outbounds")) or (jObj.has("outbound"))
            return hasBound
        } catch (e: JSONException) {
            return false
        }
    }

    private fun parseDomainName(jsonConfig: String): String {
        try {
            val jObj = JSONObject(jsonConfig)
            var domainName: String
            if (jObj.has("outbound")) {
                domainName = parseDomainName(jObj.optJSONObject("outbound"))
                if (!TextUtils.isEmpty(domainName)) {
                    return domainName
                }
            }
            if (jObj.has("outbounds")) {
                for (i in 0..(jObj.optJSONArray("outbounds").length() - 1)) {
                    domainName = parseDomainName(jObj.optJSONArray("outbounds").getJSONObject(i))
                    if (!TextUtils.isEmpty(domainName)) {
                        return domainName
                    }
                }
            }
            if (jObj.has("outboundDetour")) {
                for (i in 0..(jObj.optJSONArray("outboundDetour").length() - 1)) {
                    domainName = parseDomainName(jObj.optJSONArray("outboundDetour").getJSONObject(i))
                    if (!TextUtils.isEmpty(domainName)) {
                        return domainName
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

    private fun parseDomainName(outbound: JSONObject): String {
        try {
            if (outbound.has("settings")) {
                var vnext: JSONArray?
                if (outbound.optJSONObject("settings").has("vnext")) {
                    // vmess
                    vnext = outbound.optJSONObject("settings").optJSONArray("vnext")
                } else if (outbound.optJSONObject("settings").has("servers")) {
                    // shadowsocks or socks
                    vnext = outbound.optJSONObject("settings").optJSONArray("servers")
                } else {
                    return ""
                }
                for (i in 0..(vnext.length() - 1)) {
                    val item = vnext.getJSONObject(i)
                    val address = item.getString("address")
                    val port = item.getString("port")
                    if(isIpv6Address(address)) {
                        return String.format("[%s]:%s", address, port)
                    } else {
                        return String.format("%s:%s", address, port)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }
}