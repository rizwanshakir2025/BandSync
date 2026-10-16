package com.bandsync.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * mDNS discovery for BandSync hosts ("_bandsync._tcp.").
 * Note: some venue routers block mDNS — the Join screen also offers
 * manual IP entry as a fallback, which needs no discovery at all.
 */
class Discovery(context: Context) {
    companion object {
        const val SERVICE_TYPE = "_bandsync._tcp."
    }

    private val nsd: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    interface Listener {
        fun onFound(service: NsdServiceInfo)
        fun onLost(service: NsdServiceInfo)
    }

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun startDiscovery(listener: Listener) {
        stopDiscovery()
        val dl = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType == SERVICE_TYPE) listener.onFound(service)
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                listener.onLost(service)
            }
        }
        discoveryListener = dl
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, dl)
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try { nsd.stopServiceDiscovery(it) } catch (_: Exception) { }
        }
        discoveryListener = null
    }

    fun resolve(service: NsdServiceInfo, callback: (host: String?, port: Int) -> Unit) {
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                callback(null, -1)
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                callback(serviceInfo.host?.hostAddress, serviceInfo.port)
            }
        })
    }

    fun register(name: String, port: Int) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = "BandSync-$name"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val rl = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) {}
            override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = rl
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, rl)
    }

    fun unregister() {
        registrationListener?.let {
            try { nsd.unregisterService(it) } catch (_: Exception) { }
        }
        registrationListener = null
    }
}
