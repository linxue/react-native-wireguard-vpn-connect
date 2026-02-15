package com.wireguardvpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import com.facebook.react.bridge.*
import com.wireguard.android.backend.GoBackend
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.android.backend.Tunnel
import java.net.InetAddress
import com.wireguard.config.InetNetwork
import com.wireguard.config.ParseException
import com.wireguard.crypto.Key

class WireGuardVpnModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    private var backend: GoBackend? = null
    private var tunnel: Tunnel? = null
    private var config: Config? = null
    private var vpnPermissionGranted = false
    
    companion object {
        private const val VPN_REQUEST_CODE = 1000
    }

    override fun getName() = "WireGuardVpnModule"

    @ReactMethod
    fun initialize(promise: Promise) {
        try {
            // Check VPN permission first
            val intent = VpnService.prepare(reactApplicationContext)
            if (intent != null) {
                // VPN permission not granted, need to request it
                val activity = currentActivity
                if (activity != null) {
                    activity.startActivityForResult(intent, VPN_REQUEST_CODE)
                    // We'll handle the result in onActivityResult
                    promise.reject("VPN_PERMISSION_REQUIRED", "VPN permission is required. Please grant permission and try again.")
                    return
                } else {
                    promise.reject("NO_ACTIVITY", "No current activity available to request VPN permission")
                    return
                }
            } else {
                vpnPermissionGranted = true
            }
            
            backend = GoBackend(reactApplicationContext)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("INIT_ERROR", "Failed to initialize WireGuard: ${e.message}")
        }
    }

    @ReactMethod
    fun requestVpnPermission(promise: Promise) {
        try {
            val intent = VpnService.prepare(reactApplicationContext)
            if (intent != null) {
                val activity = currentActivity
                if (activity != null) {
                    activity.startActivityForResult(intent, VPN_REQUEST_CODE)
                    promise.resolve("PERMISSION_REQUESTED")
                } else {
                    promise.reject("NO_ACTIVITY", "No current activity available")
                }
            } else {
                vpnPermissionGranted = true
                promise.resolve("PERMISSION_ALREADY_GRANTED")
            }
        } catch (e: Exception) {
            promise.reject("PERMISSION_ERROR", "Failed to request VPN permission: ${e.message}")
        }
    }

    @ReactMethod
    fun connect(configMap: ReadableMap, promise: Promise) {
        try {
            println("Starting VPN connection process...")
            println("Received config: $configMap")
            
            // Check VPN permission first
            if (!vpnPermissionGranted) {
                val intent = VpnService.prepare(reactApplicationContext)
                if (intent != null) {
                    promise.reject("VPN_PERMISSION_REQUIRED", "VPN permission not granted. Call requestVpnPermission() first.")
                    return
                } else {
                    vpnPermissionGranted = true
                }
            }
            
            if (backend == null) {
                println("Backend is null, initializing...")
                backend = GoBackend(reactApplicationContext)
            }
            
            val interfaceBuilder = Interface.Builder()
            
            // Parse private key
            val privateKey = configMap.getString("privateKey") ?: throw Exception("Private key is required")
            try {
                println("Parsing private key...")
                interfaceBuilder.parsePrivateKey(privateKey)
                println("Private key parsed successfully")
            } catch (e: ParseException) {
                println("Failed to parse private key: ${e.message}")
                throw Exception("Invalid private key format: ${e.message}")
            }
            
            // Parse allowed IPs for interface
            val allowedIPs = configMap.getArray("allowedIPs")?.toArrayList()
                ?: throw Exception("allowedIPs array is required")
            
            try {
                println("Parsing interface addresses: $allowedIPs")
                // For interface, we typically use a single IP like "10.0.0.2/32"
                // We'll use the first allowed IP as the interface address if it's a specific IP
                allowedIPs.forEach { ip ->
                    (ip as? String)?.let { ipString ->
                        if (ipString.contains("/32") || ipString.contains("/128")) {
                            interfaceBuilder.addAddress(InetNetwork.parse(ipString))
                        }
                    }
                }
                // If no specific interface IP found, add a default one
                if (interfaceBuilder.build().addresses.isEmpty()) {
                    interfaceBuilder.addAddress(InetNetwork.parse("10.0.0.2/32"))
                }
                println("Interface addresses parsed successfully")
            } catch (e: ParseException) {
                println("Failed to parse interface addresses: ${e.message}")
                throw Exception("Invalid interface address format: ${e.message}")
            }

            // Parse DNS servers
            if (configMap.hasKey("dns")) {
                val dnsServers = configMap.getArray("dns")?.toArrayList()
                try {
                    println("Parsing DNS servers: $dnsServers")
                    dnsServers?.forEach { dns ->
                        (dns as? String)?.let { dnsString ->
                            interfaceBuilder.addDnsServer(InetAddress.getByName(dnsString))
                        }
                    }
                    println("DNS servers parsed successfully")
                } catch (e: Exception) {
                    println("Failed to parse DNS servers: ${e.message}")
                    throw Exception("Invalid DNS server format: ${e.message}")
                }
            }

            // Set MTU if provided
            if (configMap.hasKey("mtu")) {
                val mtu = configMap.getInt("mtu")
                if (mtu < 1280 || mtu > 65535) {
                    throw Exception("MTU must be between 1280 and 65535, got: $mtu")
                }
                interfaceBuilder.setMtu(mtu)
            } else {
                // Set default MTU
                interfaceBuilder.setMtu(1280)
            }

            val peerBuilder = Peer.Builder()
            
            // Parse public key
            val publicKey = configMap.getString("publicKey") ?: throw Exception("Public key is required")
            try {
                println("Parsing public key...")
                peerBuilder.parsePublicKey(publicKey)
                println("Public key parsed successfully")
            } catch (e: ParseException) {
                println("Failed to parse public key: ${e.message}")
                throw Exception("Invalid public key format: ${e.message}")
            }
            
            // Parse preshared key if provided
            if (configMap.hasKey("presharedKey")) {
                val presharedKey = configMap.getString("presharedKey")
                try {
                    println("Parsing preshared key...")
                    presharedKey?.let { keyString ->
                        if (keyString.isNotEmpty()) {
                            val key = Key.fromBase64(keyString)
                            peerBuilder.setPreSharedKey(key)
                        }
                    }
                    println("Preshared key parsed successfully")
                } catch (e: Exception) {
                    println("Failed to parse preshared key: ${e.message}")
                    throw Exception("Invalid preshared key format: ${e.message}")
                }
            }
            
            // Parse endpoint
            val serverAddress = configMap.getString("serverAddress") ?: throw Exception("Server address is required")
            val serverPort = configMap.getInt("serverPort")
            if (serverPort < 1 || serverPort > 65535) {
                throw Exception("Port must be between 1 and 65535, got: $serverPort")
            }
            val endpoint = "$serverAddress:$serverPort"
            try {
                println("Parsing endpoint: $endpoint")
                peerBuilder.parseEndpoint(endpoint)
                println("Endpoint parsed successfully")
            } catch (e: ParseException) {
                println("Failed to parse endpoint: ${e.message}")
                throw Exception("Invalid endpoint format: ${e.message}")
            }

            // Add allowed IPs to peer (these are the routes)
            try {
                println("Adding allowed IPs to peer: $allowedIPs")
                allowedIPs.forEach { ip ->
                    (ip as? String)?.let { ipString ->
                        peerBuilder.addAllowedIp(InetNetwork.parse(ipString))
                    }
                }
                println("Allowed IPs added to peer successfully")
            } catch (e: ParseException) {
                println("Failed to add allowed IPs to peer: ${e.message}")
                throw Exception("Invalid peer allowedIP format: ${e.message}")
            }

            // Set persistent keepalive
            peerBuilder.setPersistentKeepalive(25)

            println("Building WireGuard config...")
            val configBuilder = Config.Builder()
            configBuilder.setInterface(interfaceBuilder.build())
            configBuilder.addPeer(peerBuilder.build())

            this.config = configBuilder.build()
            println("WireGuard config built successfully")
            println("Config details: ${this.config}")

            // Create tunnel with proper implementation
            this.tunnel = object : Tunnel {
                override fun getName(): String = "WireGuardTunnel"
                
                override fun onStateChange(newState: Tunnel.State) {
                    println("WireGuard tunnel state changed to: $newState")
                    // You can emit events to React Native here if needed
                }
            }

            try {
                println("Attempting to set tunnel state to UP...")
                
                if (backend == null) {
                    throw Exception("Backend is null")
                }
                if (tunnel == null) {
                    throw Exception("Tunnel is null")
                }
                if (this.config == null) {
                    throw Exception("Config is null")
                }
                
                println("All components ready, connecting...")
                val state = backend!!.setState(tunnel!!, Tunnel.State.UP, this.config)
                println("Tunnel state set result: $state")
                println("Successfully connected to VPN")
                promise.resolve(null)
                
            } catch (e: Exception) {
                println("Failed to set tunnel state: ${e.message}")
                println("Exception stack trace:")
                e.printStackTrace()
                throw Exception("Failed to establish VPN connection: ${e.message}")
            }
            
        } catch (e: Exception) {
            println("Connection failed with error: ${e.message}")
            println("Exception stack trace:")
            e.printStackTrace()
            promise.reject("CONNECT_ERROR", "Failed to connect: ${e.message}")
        }
    }

    @ReactMethod
    fun disconnect(promise: Promise) {
        try {
            if (tunnel != null && backend != null) {
                val state = backend!!.setState(tunnel!!, Tunnel.State.DOWN, null)
                println("Tunnel disconnected, state: $state")
                promise.resolve(null)
            } else {
                promise.reject("DISCONNECT_ERROR", "Tunnel not initialized")
            }
        } catch (e: Exception) {
            println("Failed to disconnect: ${e.message}")
            promise.reject("DISCONNECT_ERROR", "Failed to disconnect: ${e.message}")
        }
    }

    @ReactMethod
    fun getStatus(promise: Promise) {
        try {
            val state = if (tunnel != null && backend != null) {
                backend!!.getState(tunnel!!)
            } else {
                Tunnel.State.DOWN
            }

            val status = Arguments.createMap().apply {
                putBoolean("isConnected", state == Tunnel.State.UP)
                putString("tunnelState", state?.name ?: "UNKNOWN")
                putBoolean("vpnPermissionGranted", vpnPermissionGranted)
            }
            promise.resolve(status)
        } catch (e: Exception) {
            val status = Arguments.createMap().apply {
                putBoolean("isConnected", false)
                putString("tunnelState", "ERROR")
                putString("error", e.message)
                putBoolean("vpnPermissionGranted", vpnPermissionGranted)
            }
            promise.resolve(status)
        }
    }

    @ReactMethod
    fun isSupported(promise: Promise) {
        promise.resolve(true)
    }
}