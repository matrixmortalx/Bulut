package tr.bulut.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tr.bulut.ag.CloudflareProtokol
import tr.bulut.ag.GorselIstemci
import tr.bulut.ag.GorselKomutu
import tr.bulut.ag.Istemci
import tr.bulut.ag.Protokol
import tr.bulut.veri.AnahtarKasasi
import tr.bulut.veri.Ayarlar
import tr.bulut.veri.Depo
import tr.bulut.veri.GorselDepo
import tr.bulut.veri.GorselKaydi
import tr.bulut.veri.GorselModel
import tr.bulut.veri.GorselModelleri
import tr.bulut.veri.Mesaj
import tr.bulut.veri.Rol
import tr.bulut.veri.Saglayici
import tr.bulut.veri.Sohbet
import java.io.File
import java.util.UUID
import kotlin.random.Random
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Tek ViewModel: üretim burada yaşadığı için ekran döndürme ya da ekranlar
 * arası geçiş yanıtı kesmez.
 */
class BulutViewModel(uygulama: Application) : AndroidViewModel(uygulama) {

    private val depo = Depo(uygulama)
    private val kasa = AnahtarKasasi(uygulama)
    private val istemci = Istemci()

    private val _ayarlar = MutableStateFlow(Ayarlar())
    val ayarlar: StateFlow<Ayarlar> = _ayarlar.asStateFlow()

    private val _sohbetler = MutableStateFlow<List<Sohbet>>(emptyList())
    val sohbetler: StateFlow<List<Sohbet>> = _sohbetler.asStateFlow()

    /** Yanıtı hâlâ akmakta olan sohbetlerin kimlikleri. */
    private val _uretenler = MutableStateFlow<Set<String>>(emptySet())
    val uretenler: StateFlow<Set<String>> = _uretenler.asStateFlow()

    /** Anahtarı kayıtlı sağlayıcılar; anahtarın kendisi arayüze çıkmaz. */
    private val _anahtarlilar = MutableStateFlow<Set<String>>(emptySet())
    val anahtarlilar: StateFlow<Set<String>> = _anahtarlilar.asStateFlow()

    private val isler = mutableMapOf<String, Job>()

    private val gorselDepo = GorselDepo(uygulama)
    private val gorselIstemci = GorselIstemci()

    private val _gorseller = MutableStateFlow<List<GorselKaydi>>(emptyList())
    val gorseller: StateFlow<List<GorselKaydi>> = _gorseller.asStateFlow()

    data class StudyoDurumu(val uretiyor: Boolean = false, val sonuc: String? = null, val hata: String? = null)

    private val _studyo = MutableStateFlow(StudyoDurumu())
    val studyo: StateFlow<StudyoDurumu> = _studyo.asStateFlow()
    private var studyoIsi: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _ayarlar.value = depo.ayarlariOku()
            _sohbetler.value = depo.sohbetleriOku()
            _gorseller.value = gorselDepo.hepsi()
            anahtarlariYenile()
        }
    }

    // ---- Sağlayıcılar ----

    fun kullanilabilirMi(s: Saglayici) = s.kullanilabilir(_anahtarlilar.value)

    fun anahtarOku(id: String): String = kasa.oku(id)

    fun saglayiciKaydet(saglayici: Saglayici, anahtar: String) {
        kasa.yaz(saglayici.id, anahtar)
        anahtarlariYenile()
        saglayiciGuncelle(saglayici)
    }

    /** Anahtara dokunmadan yalnızca tanımı (adres, model listesi) değiştirir. */
    fun saglayiciGuncelle(saglayici: Saglayici) {
        ayarlariDegistir { a ->
            a.copy(saglayicilar = a.saglayicilar.map { if (it.id == saglayici.id) saglayici else it })
        }
    }

    /** Sunucudan model listesini çeker ve kaydeder; hata metnini döndürür. */
    suspend fun modelleriGetir(saglayici: Saglayici, anahtar: String): Result<List<String>> =
        runCatching {
            istemci.modeller(saglayici.tabanUrl, saglayici.modelListesiUrl, anahtar, saglayici.modelSoneki)
        }.onSuccess { liste ->
            if (liste.isNotEmpty()) ayarlariDegistir { a ->
                a.copy(saglayicilar = a.saglayicilar.map { if (it.id == saglayici.id) it.copy(modeller = liste) else it })
            }
        }

    fun ayarlariDegistir(degisim: (Ayarlar) -> Ayarlar) {
        _ayarlar.update(degisim)
        val yeni = _ayarlar.value
        viewModelScope.launch(Dispatchers.IO) { depo.ayarlariYaz(yeni) }
    }

    private fun anahtarlariYenile() {
        _anahtarlilar.value = (_ayarlar.value.saglayicilar.map { it.id } + CLOUDFLARE).filter { kasa.varMi(it) }.toSet()
    }

    // ---- Sohbetler ----

    /** Yeni sohbeti son kullanılan modelle açar; hiçbir sağlayıcı hazır değilse null. */
    fun yeniSohbet(): String? {
        val a = _ayarlar.value
        val hazirlar = a.saglayicilar.filter { kullanilabilirMi(it) && it.gorunenModeller.isNotEmpty() }
        val saglayici = hazirlar.firstOrNull { it.id == a.sonSaglayici } ?: hazirlar.firstOrNull() ?: return null
        val model = a.sonModel.takeIf { saglayici.id == a.sonSaglayici && it.isNotBlank() }
            ?: saglayici.gorunenModeller.first()
        val sohbet = Sohbet(id = UUID.randomUUID().toString(), baslik = "", saglayiciId = saglayici.id, model = model)
        _sohbetler.update { listOf(sohbet) + it }
        return sohbet.id
    }

    fun sohbet(id: String): Sohbet? = _sohbetler.value.firstOrNull { it.id == id }

    fun modelSec(sohbetId: String, saglayiciId: String, model: String) {
        sohbetGuncelle(sohbetId, kaydet = true) { it.copy(saglayiciId = saglayiciId, model = model) }
        ayarlariDegistir { it.copy(sonSaglayici = saglayiciId, sonModel = model) }
    }

    fun sohbetSil(id: String) {
        durdur(id)
        _sohbetler.update { liste -> liste.filterNot { it.id == id } }
        viewModelScope.launch(Dispatchers.IO) { depo.sohbetSil(id) }
    }

    fun tumSohbetleriSil() {
        _sohbetler.value.forEach { durdur(it.id) }
        _sohbetler.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            _sohbetler.value.forEach { depo.sohbetSil(it.id) }
        }
    }

    suspend fun sohbetleriDisariAktar(): String = withContext(Dispatchers.IO) {
        val json = kotlinx.serialization.json.Json { prettyPrint = true; encodeDefaults = true }
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Sohbet.serializer()), _sohbetler.value)
    }

    fun sohbetleriIceAktar(jsonMetin: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val liste = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Sohbet.serializer()), jsonMetin)
                if (liste.isNotEmpty()) {
                    val mevcut = _sohbetler.value.associateBy { it.id }
                    val birlesik = (liste.map { it.copy(guncellendi = System.currentTimeMillis()) } + _sohbetler.value)
                        .distinctBy { it.id }
                        .sortedByDescending { it.guncellendi }
                    _sohbetler.value = birlesik
                    birlesik.forEach { depo.sohbetKaydet(it) }
                }
            }
        }
    }

    /** Hiç mesajı olmayan sohbet diske yazılmaz; ekrandan çıkınca listeden de düşer. */
    fun bosSohbetiAt(id: String) {
        if (sohbet(id)?.mesajlar?.isEmpty() == true) _sohbetler.update { l -> l.filterNot { it.id == id } }
    }

    fun gonder(sohbetId: String, metin: String) {
        val temiz = metin.trim()
        if (temiz.isEmpty() || sohbetId in _uretenler.value) return
        val tarif = GorselKomutu.komut(temiz)
        sohbetGuncelle(sohbetId, kaydet = true) { s ->
            s.copy(
                baslik = s.baslik.ifBlank { baslikUret(tarif?.ifBlank { null } ?: temiz) },
                mesajlar = s.mesajlar + Mesaj(Rol.KULLANICI, temiz),
            )
        }
        when {
            tarif == null -> uret(sohbetId)
            tarif.isEmpty() -> sohbetGuncelle(sohbetId, kaydet = true) {
                it.copy(mesajlar = it.mesajlar + Mesaj(Rol.ASISTAN, "", hata = "Kullanım: /görsel <ne çizilsin>"))
            }
            else -> {
                val m = Mesaj(Rol.ASISTAN, "", model = GorselModelleri.bul(_ayarlar.value.gorsel.model).ad, gorselIstemi = tarif)
                sohbetGuncelle(sohbetId, kaydet = false) { it.copy(mesajlar = it.mesajlar + m) }
                gorselIsi(sohbetId, m.zaman, tarif)
            }
        }
    }

    /** Son asistan yanıtını (ya da hatasını) atıp aynı soruyu yeniden sorar. */
    fun yenidenUret(sohbetId: String) {
        if (sohbetId in _uretenler.value) return
        val son = sohbet(sohbetId)?.mesajlar?.lastOrNull()
        if (son != null && son.rol == Rol.ASISTAN && son.icerik.isEmpty() && son.gorselIstemi.isNotEmpty()) {
            gorselYenile(sohbetId, son.zaman)
            return
        }
        sohbetGuncelle(sohbetId, kaydet = false) { s ->
            s.copy(mesajlar = s.mesajlar.dropLastWhile { it.rol == Rol.ASISTAN })
        }
        uret(sohbetId)
    }

    /** Kullanıcının bir mesajını düzenleyip ondan sonrasını yeniden üretir. */
    fun duzenleVeGonder(sohbetId: String, indeks: Int, yeniMetin: String) {
        if (sohbetId in _uretenler.value || yeniMetin.isBlank()) return
        sohbetGuncelle(sohbetId, kaydet = false) { s -> s.copy(mesajlar = s.mesajlar.take(indeks)) }
        gonder(sohbetId, yeniMetin)
    }

    fun durdur(sohbetId: String) {
        isler.remove(sohbetId)?.cancel()
    }

    private fun uret(sohbetId: String) {
        val sohbet = sohbet(sohbetId) ?: return
        val a = _ayarlar.value
        val saglayici = a.saglayicilar.firstOrNull { it.id == sohbet.saglayiciId }
        val otomatikGorsel = a.gorsel.sohbetteOtomatik && cloudflareHazir()
        val gecmis = sohbet.mesajlar
            .filter { it.hata == null && (it.icerik.isNotEmpty() || it.gorselIstemi.isNotEmpty()) }
            .takeLast(a.gecmisSiniri)
        val istek = buildList {
            val sistem = a.sistemIstemi + if (otomatikGorsel) GorselKomutu.sistemEki() else ""
            if (sistem.isNotBlank()) add(Protokol.IstekMesaji("system", sistem.trim()))
            gecmis.forEach { m ->
                // Görseli model görmüyor; ne çizildiğini bilsin diye tarifi metin olarak geçiyoruz.
                val metin = if (m.gorselIstemi.isEmpty()) m.icerik else (m.icerik + "\n[Görsel üretildi: ${m.gorselIstemi}]").trim()
                add(Protokol.IstekMesaji(if (m.rol == Rol.KULLANICI) "user" else "assistant", metin))
            }
        }
        val yanit = Mesaj(Rol.ASISTAN, "", model = sohbet.model)
        sohbetGuncelle(sohbetId, kaydet = false) { it.copy(mesajlar = it.mesajlar + yanit) }
        _uretenler.update { it + sohbetId }

        isler[sohbetId] = viewModelScope.launch {
            val icerik = StringBuilder()
            val dusunce = StringBuilder()
            var hata: String? = null
            var gorselTarifi: String? = null
            try {
                if (saglayici == null) throw IllegalStateException("Sağlayıcı bulunamadı; modeli yeniden seçin.")
                val anahtar = withContext(Dispatchers.IO) { kasa.oku(saglayici.id) }
                if (anahtar.isBlank() && !saglayici.anahtarIstege) {
                    throw IllegalStateException("${saglayici.ad} için API anahtarı girilmemiş.")
                }
                var sonYayin = 0L
                istemci.akis(saglayici.tabanUrl, anahtar, sohbet.model, istek, a.sicaklik) { parca ->
                    icerik.append(parca.icerik)
                    dusunce.append(parca.dusunce)
                    // Her parçada arayüzü yeniden çizmek uzun yanıtta takılma yapıyor.
                    val simdi = System.currentTimeMillis()
                    if (simdi - sonYayin > 50) {
                        sonYayin = simdi
                        sonMesajiYaz(sohbetId, icerik.toString(), dusunce.toString(), null)
                    }
                }
                if (icerik.isEmpty() && dusunce.isEmpty()) hata = "Model boş yanıt döndürdü."
                if (otomatikGorsel) {
                    val (temiz, tarif) = GorselKomutu.ayikla(icerik.toString())
                    if (tarif != null) {
                        gorselTarifi = tarif
                        icerik.setLength(0)
                        icerik.append(temiz)
                        sonMesajiYaz(sohbetId, temiz, dusunce.toString(), null)
                        mesajGuncelle(sohbetId, yanit.zaman) { it.copy(gorselIstemi = tarif) }
                        gorselAdimi(sohbetId, yanit.zaman, tarif)
                    }
                }
            } catch (e: CancellationException) {
                // Kullanıcı durdurdu: gelen kadarı kalır.
            } catch (e: Exception) {
                hata = e.message ?: e.javaClass.simpleName
            } finally {
                sonMesajiYaz(sohbetId, icerik.toString(), dusunce.toString(), hata)
                // Durdurulup hiç metin gelmemişse boş balon bırakma.
                if (hata == null && icerik.isEmpty() && dusunce.isEmpty() && gorselTarifi == null) {
                    sohbetGuncelle(sohbetId, kaydet = false) { it.copy(mesajlar = it.mesajlar.dropLast(1)) }
                }
                sohbet(sohbetId)?.let { s -> withContext(Dispatchers.IO + NonCancellable) { depo.sohbetYaz(s) } }
                isler.remove(sohbetId)
                _uretenler.update { it - sohbetId }
            }
        }
    }

    // ---- Görsel ----

    fun cloudflareHazir() = _ayarlar.value.gorsel.hesapId.isNotBlank() && CLOUDFLARE in _anahtarlilar.value

    fun cloudflareKaydet(hesapId: String, belirtec: String) {
        kasa.yaz(CLOUDFLARE, belirtec)
        ayarlariDegistir { it.copy(gorsel = it.gorsel.copy(hesapId = hesapId.trim())) }
        anahtarlariYenile()
    }

    suspend fun cloudflareDene(hesapId: String, belirtec: String): Result<Int> =
        runCatching { gorselIstemci.dene(hesapId, belirtec) }

    fun gorsel(id: String): GorselKaydi? = _gorseller.value.firstOrNull { it.id == id }

    fun gorselDosyasi(kayit: GorselKaydi): File = gorselDepo.dosya(kayit)

    fun gorselSil(kayit: GorselKaydi) {
        _gorseller.update { l -> l.filterNot { it.id == kayit.id } }
        viewModelScope.launch(Dispatchers.IO) { gorselDepo.sil(kayit) }
    }

    fun studyoUret(istem: String, negatif: String, model: GorselModel, adim: Int, genislik: Int, yukseklik: Int, tohum: Long?) {
        if (_studyo.value.uretiyor || istem.isBlank()) return
        ayarlariDegistir {
            it.copy(gorsel = it.gorsel.copy(model = model.id, adim = adim, genislik = genislik, yukseklik = yukseklik))
        }
        _studyo.value = StudyoDurumu(uretiyor = true)
        studyoIsi = viewModelScope.launch {
            _studyo.value = try {
                StudyoDurumu(sonuc = gorselUret(istem.trim(), negatif.trim(), model, adim, genislik, yukseklik, tohum).id)
            } catch (e: CancellationException) {
                _studyo.value = StudyoDurumu()
                throw e
            } catch (e: Exception) {
                StudyoDurumu(hata = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun studyoDurdur() {
        studyoIsi?.cancel()
        _studyo.value = StudyoDurumu()
    }

    fun studyoSonucunuTemizle() {
        if (!_studyo.value.uretiyor) _studyo.value = StudyoDurumu()
    }

    /** Tarifi sohbet modeline İngilizce, ayrıntılı bir istem olarak yeniden yazdırır. */
    suspend fun istemiGelistir(metin: String): Result<String> = runCatching {
        val a = _ayarlar.value
        val hazirlar = a.saglayicilar.filter { kullanilabilirMi(it) && it.gorunenModeller.isNotEmpty() }
        val s = hazirlar.firstOrNull { it.id == a.sonSaglayici } ?: hazirlar.firstOrNull()
            ?: throw IllegalStateException("Geliştirmek için bir sohbet sağlayıcısı gerekli.")
        val model = a.sonModel.takeIf { s.id == a.sonSaglayici && it.isNotBlank() } ?: s.gorunenModeller.first()
        val anahtar = withContext(Dispatchers.IO) { kasa.oku(s.id) }
        val sonuc = StringBuilder()
        istemci.akis(
            s.tabanUrl, anahtar, model,
            listOf(Protokol.IstekMesaji("system", GorselKomutu.GELISTIRME_ISTEMI), Protokol.IstekMesaji("user", metin)),
            null,
        ) { sonuc.append(it.icerik) }
        sonuc.toString().trim().trim('"').ifBlank { throw IllegalStateException("Model boş yanıt döndürdü.") }
    }

    fun gorselYenile(sohbetId: String, zaman: Long) {
        if (sohbetId in _uretenler.value) return
        val m = sohbet(sohbetId)?.mesajlar?.lastOrNull { it.rol == Rol.ASISTAN && it.zaman == zaman } ?: return
        if (m.gorselIstemi.isEmpty()) return
        mesajGuncelle(sohbetId, zaman) { it.copy(gorsel = "", hata = null) }
        gorselIsi(sohbetId, zaman, m.gorselIstemi)
    }

    /** Sohbetteki bir görsel mesajı için ayrı iş; durdur düğmesi bunu da keser. */
    private fun gorselIsi(sohbetId: String, zaman: Long, tarif: String) {
        _uretenler.update { it + sohbetId }
        isler[sohbetId] = viewModelScope.launch {
            try {
                gorselAdimi(sohbetId, zaman, tarif)
            } finally {
                sohbet(sohbetId)?.let { s -> withContext(Dispatchers.IO + NonCancellable) { depo.sohbetYaz(s) } }
                isler.remove(sohbetId)
                _uretenler.update { it - sohbetId }
            }
        }
    }

    private suspend fun gorselAdimi(sohbetId: String, zaman: Long, tarif: String) {
        val g = _ayarlar.value.gorsel
        val model = GorselModelleri.bul(g.model)
        try {
            val k = gorselUret(tarif, "", model, g.adim.takeIf { it > 0 } ?: model.varsayilanAdim, g.genislik, g.yukseklik, null)
            mesajGuncelle(sohbetId, zaman) { it.copy(gorsel = k.id, hata = null) }
        } catch (e: CancellationException) {
            mesajGuncelle(sohbetId, zaman) { it.copy(hata = "Görsel üretimi durduruldu.") }
            throw e
        } catch (e: Exception) {
            mesajGuncelle(sohbetId, zaman) { it.copy(hata = "Görsel üretilemedi: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    private suspend fun gorselUret(
        istem: String, negatif: String, model: GorselModel, adim: Int, genislik: Int, yukseklik: Int, tohum: Long?,
    ): GorselKaydi {
        val hesap = _ayarlar.value.gorsel.hesapId
        val belirtec = withContext(Dispatchers.IO) { kasa.oku(CLOUDFLARE) }
        if (hesap.isBlank() || belirtec.isBlank()) {
            throw IllegalStateException("Cloudflare hesabı ayarlanmamış (Ayarlar → Cloudflare görsel).")
        }
        val g = if (model.ayarlanabilir) genislik else 1024
        val y = if (model.ayarlanabilir) yukseklik else 1024
        val kullanilan = if (model.ayarlanabilir) tohum ?: Random.nextLong(0, Int.MAX_VALUE.toLong()) else null
        val adimSiniri = adim.coerceIn(1, model.enFazlaAdim)
        val bayt = gorselIstemci.uret(
            hesap, belirtec,
            CloudflareProtokol.Istek(model, istem, if (model.ayarlanabilir) negatif else "", adimSiniri, g, y, kullanilan),
        )
        val id = UUID.randomUUID().toString()
        val kayit = GorselKaydi(
            id = id, dosya = "$id.${CloudflareProtokol.uzanti(bayt)}", istem = istem,
            negatif = if (model.ayarlanabilir) negatif else "", model = model.id, adim = adimSiniri,
            genislik = g, yukseklik = y, tohum = kullanilan,
        )
        withContext(Dispatchers.IO) { gorselDepo.yaz(kayit, bayt) }
        _gorseller.update { listOf(kayit) + it }
        return kayit
    }

    private fun mesajGuncelle(sohbetId: String, zaman: Long, degisim: (Mesaj) -> Mesaj) {
        sohbetGuncelle(sohbetId, kaydet = false) { s ->
            val i = s.mesajlar.indexOfLast { it.rol == Rol.ASISTAN && it.zaman == zaman }
            if (i < 0) s else s.copy(mesajlar = s.mesajlar.toMutableList().also { it[i] = degisim(it[i]) })
        }
    }

    private fun sonMesajiYaz(sohbetId: String, icerik: String, dusunce: String, hata: String?) {
        sohbetGuncelle(sohbetId, kaydet = false) { s ->
            val son = s.mesajlar.lastOrNull()?.takeIf { it.rol == Rol.ASISTAN } ?: return@sohbetGuncelle s
            s.copy(mesajlar = s.mesajlar.dropLast(1) + son.copy(icerik = icerik, dusunce = dusunce, hata = hata))
        }
    }

    private fun sohbetGuncelle(id: String, kaydet: Boolean, degisim: (Sohbet) -> Sohbet) {
        var yeni: Sohbet? = null
        _sohbetler.update { liste ->
            liste.map { if (it.id == id) degisim(it).copy(guncellendi = System.currentTimeMillis()).also { s -> yeni = s } else it }
                .sortedByDescending { it.guncellendi }
        }
        val kayit = yeni
        if (kaydet && kayit != null && kayit.mesajlar.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) { depo.sohbetYaz(kayit) }
        }
    }

    companion object {
        const val CLOUDFLARE = "cloudflare"
    }

    private fun baslikUret(metin: String): String {
        val tek = metin.replace(Regex("\\s+"), " ")
        return if (tek.length <= 40) tek else tek.take(40).trimEnd() + "…"
    }
}
