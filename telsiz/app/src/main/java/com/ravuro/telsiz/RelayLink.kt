package com.ravuro.telsiz

/**
 * İnternet üzerinden taşıma. İki uygulaması var:
 *
 *  - [RelayTransport]      WebSocket (ws://, wss://) — düşük gecikme,
 *                          sürekli çalışan bir sunucu ister.
 *  - [HttpRelayTransport]  düz HTTP (http://, https://) — paylaşımlı
 *                          hosting'de çalışan PHP rölesi için.
 *
 * Servis hangisini kullanacağını adresin şemasına bakarak seçiyor.
 */
interface RelayLink {
    fun start()
    fun stop()
    fun send(data: ByteArray, len: Int)
    val connected: Boolean
    val status: String
}
