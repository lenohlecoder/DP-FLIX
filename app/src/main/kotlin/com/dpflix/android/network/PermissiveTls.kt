package com.dpflix.android.network

import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Confiance HTTPS permissive pour les panels/flux IPTV, en complément de
 * `network_security_config.xml` (§res/xml).
 *
 * Pourquoi ce fichier en plus de network_security_config.xml : ce dernier ne couvre
 * que (1) le cleartext HTTP et (2) les certificats explicitement installés à la main
 * par l'utilisateur (`src="user"`). Il ne couvre PAS les certificats auto-signés ou
 * invalides servis directement par le panel sans action de l'utilisateur — cas très
 * fréquent sur des serveurs IPTV bricolés. Sans ce TrustManager, ces panels
 * échoueraient avec une `SSLHandshakeException` même si le flux est par ailleurs
 * valide. C'est probablement une des causes du comportement observé avec Televizo
 * (aucun flux jamais rejeté, y compris sur des panels à la config HTTPS douteuse).
 *
 * Compromis assumé : ceci désactive la vérification du certificat serveur sur les
 * connexions HTTPS de l'app (donc la protection contre l'interception/l'usurpation
 * de serveur). Acceptable ici pour un usage personnel où les identifiants Xtream
 * transitent déjà dans l'URL plutôt que dans un header d'auth séparé, et où
 * l'alternative concrète est simplement "le flux ne se lit pas du tout".
 *
 * Fix (2026-07-25) : au-delà de la confiance du certificat, [sslSocketFactory] force
 * désormais sur chaque socket TOUS les protocoles TLS supportés par la plateforme
 * (jusqu'à TLSv1/TLSv1.1 inclus) et toutes les suites de chiffrement disponibles,
 * plutôt que le sous-ensemble "moderne" activé par défaut. Un panel tournant sur une
 * vieille pile OpenSSL/PHP qui ne parle que TLSv1.1, par exemple, échouait sinon la
 * négociation TLS avant même d'atteindre le TrustManager permissif ci-dessus.
 */
object PermissiveTls {

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /** Accepte tout nom d'hôte, y compris quand le certificat ne correspond pas au domaine appelé. */
    val hostnameVerifier = HostnameVerifier { _, _ -> true }

    private val baseSslSocketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAllManager), SecureRandom())
        }.socketFactory
    }

    val sslSocketFactory: SSLSocketFactory by lazy {
        AllProtocolsSslSocketFactory(baseSslSocketFactory)
    }

    val trustManager: X509TrustManager get() = trustAllManager

    /**
     * Délègue toute la création de sockets à [delegate], mais active systématiquement
     * l'ensemble des protocoles et suites de chiffrement *supportés* par la plateforme
     * (pas seulement ceux activés par défaut) sur chaque [SSLSocket] produit — voir la
     * doc de classe ci-dessus.
     */
    private class AllProtocolsSslSocketFactory(
        private val delegate: SSLSocketFactory
    ) : SSLSocketFactory() {

        private fun widenProtocols(socket: Socket): Socket {
            if (socket is SSLSocket) {
                socket.supportedProtocols?.let { socket.enabledProtocols = it }
                socket.supportedCipherSuites?.let { socket.enabledCipherSuites = it }
            }
            return socket
        }

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            widenProtocols(delegate.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String?, port: Int): Socket =
            widenProtocols(delegate.createSocket(host, port))

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            widenProtocols(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            widenProtocols(delegate.createSocket(host, port))

        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int
        ): Socket = widenProtocols(delegate.createSocket(address, port, localAddress, localPort))
    }
}
