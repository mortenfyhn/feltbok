package io.github.mortenfyhn.feltbok

import kotlinx.coroutines.flow.MutableStateFlow

/** A [Platform] whose web answers are set per URL fragment, recording requests and toasts. */
class FakePlatform : Platform {
    override val storage = object : Storage {
        val files = HashMap<String, String>()
        override fun exists(name: String) = name in files
        override fun read(name: String) = files[name]
        override fun write(name: String, text: String) {
            check(!failWrites) { "disk full" }
            files[name] = text
        }
        override fun append(name: String, text: String) { files[name] = files[name].orEmpty() + text }
        override fun delete(name: String) { files.remove(name) }
        override fun list() = files.keys.toList()
        override fun readAsset(name: String): String? = null
    }
    override val location = object : LocationSource {
        override val fix = MutableStateFlow<GpsFix?>(null)
        override fun start() {}
        override fun stop() {}
    }

    var cookie: String? = null
    var failWrites = false

    /** URL fragment -> answer (null: the request fails). The first matching fragment wins. */
    val answers = LinkedHashMap<String, (String) -> String?>()
    val requests = java.util.Collections.synchronizedList(ArrayList<Pair<String, Map<String, String>>>())
    val toasts = java.util.Collections.synchronizedList(ArrayList<String>())

    fun requestsTo(fragment: String) = synchronized(requests) { requests.filter { fragment in it.first } }

    override fun toast(message: String, long: Boolean) { toasts += message }

    override suspend fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int): String? {
        requests += url to headers
        return answers.entries.firstOrNull { it.key in url }?.value?.invoke(url)
    }

    override suspend fun siteCookie() = cookie

    companion object {
        fun site(id: Int, name: String, private: Boolean, lat: Double = 59.905, lon: Double = 10.71) =
            """{"id":$id,"name":"$name","latitude":$lat,"longitude":$lon,"isPrivate":$private,"accuracy":10,""" +
                """"municipalityName":"Oslo","parentSiteId":null,"isPolygon":false,"polygonCoordinates":null}"""

        fun loggedInClaims(seconds: Long = 3600) = """[{"type":"bff:session_expires_in","value":$seconds}]"""
    }
}
