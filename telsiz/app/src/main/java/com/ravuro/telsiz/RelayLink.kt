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

    /**
     * Sunucudan en son ne zaman cevap alındı (epoch ms, hiç alınmadıysa 0).
     *
     * [connected] yetmiyor: mobil veride uyuyan telsiz soketi sessizce
     * ölüyor, iş parçacığı okumada asılı kalıyor ve bayrak "bağlı" olarak
     * donup kalıyor. Bekçi bu damgaya bakıp gerçekten canlı mı diye
     * karar veriyor.
     */
    val lastRxAt: Long

    /** Ölmüş bağlantıyı sıfırdan kurar; bekçi çağırıyor. */
    fun restart()

    /** Tanı ekranına tek satır. */
    fun diag(): String
}
