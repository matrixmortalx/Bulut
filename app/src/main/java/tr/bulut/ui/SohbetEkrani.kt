package tr.bulut.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UnfoldMore
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tr.bulut.ag.GorselKomutu
import tr.bulut.veri.GorselKaydi
import tr.bulut.veri.Mesaj
import java.io.File
import tr.bulut.veri.Rol

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SohbetEkrani(
    vm: BulutViewModel,
    sohbetId: String,
    geri: () -> Unit,
    ayarlarAc: () -> Unit,
    gorselAc: (String) -> Unit,
) {
    val sohbetler by vm.sohbetler.collectAsStateWithLifecycle()
    val ayarlar by vm.ayarlar.collectAsStateWithLifecycle()
    val anahtarlilar by vm.anahtarlilar.collectAsStateWithLifecycle()
    val uretenler by vm.uretenler.collectAsStateWithLifecycle()
    val gorseller by vm.gorseller.collectAsStateWithLifecycle()
    val sohbet = sohbetler.firstOrNull { it.id == sohbetId }
    val uretiyor = sohbetId in uretenler

    var girdi by rememberSaveable { mutableStateOf("") }
    /** Düzenlenen kullanıcı mesajının sırası; -1 ise yeni mesaj yazılıyor. */
    var duzenlenen by rememberSaveable { mutableIntStateOf(-1) }
    var modelSecici by remember { mutableStateOf(false) }
    val liste = rememberLazyListState()

    DisposableEffect(sohbetId) { onDispose { vm.bosSohbetiAt(sohbetId) } }
    LaunchedEffect(sohbet == null) { if (sohbet == null && sohbetler.isNotEmpty()) geri() }
    // Yeni mesaj eklenince en alta dön (ters düzende en alt = 0).
    LaunchedEffect(sohbet?.mesajlar?.size) { liste.animateScrollToItem(0) }
    BackHandler(duzenlenen >= 0) { duzenlenen = -1; girdi = "" }

    if (sohbet == null) return
    val saglayici = ayarlar.saglayicilar.firstOrNull { it.id == sohbet.saglayiciId }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = geri) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
                title = {
                    Row(
                        Modifier.clickable { modelSecici = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(
                                sohbet.baslik.ifBlank { "Yeni sohbet" },
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "${saglayici?.ad ?: sohbet.saglayiciId} · ${sohbet.model}",
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Default.UnfoldMore, "Model seç", Modifier.padding(start = 4.dp).size(18.dp))
                    }
                },
            )
        },
    ) { ic ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = ic.calculateTopPadding())
                .imePadding()
        ) {
            val mesajlar = sohbet.mesajlar
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = liste,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (mesajlar.isEmpty()) {
                    item {
                        Text(
                            "Bir şey sor. Model başlıktan değiştirilebilir.",
                            Modifier.fillMaxWidth().padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val ters = mesajlar.withIndex().reversed()
                itemsIndexed(ters, key = { _, it -> "${it.index}-${it.value.zaman}" }) { _, (indeks, mesaj) ->
                    val sonMu = indeks == mesajlar.lastIndex
                    when (mesaj.rol) {
                        Rol.KULLANICI -> KullaniciBalonu(
                            mesaj,
                            duzenle = if (!uretiyor) ({ duzenlenen = indeks; girdi = mesaj.icerik }) else null,
                        )
                        Rol.ASISTAN -> AsistanYaniti(
                            mesaj,
                            akiyor = uretiyor && sonMu,
                            yenile = if (sonMu && !uretiyor) ({ vm.yenidenUret(sohbetId) }) else null,
                            gorsel = mesaj.gorsel.takeIf { it.isNotEmpty() }?.let { id -> gorseller.firstOrNull { it.id == id } }
                                ?.let { it to vm.gorselDosyasi(it) },
                            gorselAc = gorselAc,
                        )
                    }
                }
            }

            if (duzenlenen >= 0) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Mesaj düzenleniyor; sonrası yeniden üretilecek",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    IconButton(onClick = { duzenlenen = -1; girdi = "" }) { Icon(Icons.Default.Close, "Düzenlemeyi bırak") }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 8.dp, bottom = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = girdi,
                    onValueChange = { girdi = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Mesaj ya da /görsel …") },
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp),
                )
                if (uretiyor) {
                    FilledIconButton(onClick = { vm.durdur(sohbetId) }, Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                        Icon(Icons.Default.Stop, "Durdur")
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            if (duzenlenen >= 0) vm.duzenleVeGonder(sohbetId, duzenlenen, girdi)
                            else vm.gonder(sohbetId, girdi)
                            girdi = ""
                            duzenlenen = -1
                        },
                        enabled = girdi.isNotBlank(),
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                    ) {
                        Icon(if (duzenlenen >= 0) Icons.Default.Check else Icons.AutoMirrored.Filled.Send, "Gönder")
                    }
                }
            }
        }
    }

    if (modelSecici) {
        ModalBottomSheet(onDismissRequest = { modelSecici = false }) {
            val kullanilabilir = ayarlar.saglayicilar.filter { it.kullanilabilir(anahtarlilar) }
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                kullanilabilir.forEach { s ->
                    item(key = "b-${s.id}") {
                        Text(
                            s.ad,
                            Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (s.gorunenModeller.isEmpty()) {
                        item(key = "y-${s.id}") {
                            Text(
                                "Model yok; sağlayıcı ayarından listeyi getir.",
                                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    s.gorunenModeller.forEach { m ->
                        item(key = "m-${s.id}-$m") {
                            val secili = s.id == sohbet.saglayiciId && m == sohbet.model
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.modelSec(sohbetId, s.id, m); modelSecici = false }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(m, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (secili) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                item {
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                    TextButton(onClick = { modelSecici = false; ayarlarAc() }, Modifier.padding(12.dp)) {
                        Text("Sağlayıcıları düzenle")
                    }
                }
            }
        }
    }
}

@Composable
private fun KullaniciBalonu(mesaj: Mesaj, duzenle: (() -> Unit)?) {
    val pano = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            SelectionContainer {
                Text(
                    mesaj.icerik,
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        Row {
            IconButton(onClick = { pano.setText(AnnotatedString(mesaj.icerik)) }, Modifier.size(36.dp)) {
                Icon(Icons.Default.ContentCopy, "Kopyala", Modifier.size(16.dp))
            }
            if (duzenle != null) {
                IconButton(onClick = duzenle, Modifier.size(36.dp)) {
                    Icon(Icons.Default.Edit, "Düzenle", Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun AsistanYaniti(
    mesaj: Mesaj,
    akiyor: Boolean,
    yenile: (() -> Unit)?,
    gorsel: Pair<GorselKaydi, File>?,
    gorselAc: (String) -> Unit,
) {
    val pano = LocalClipboardManager.current
    var dusunceAcik by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        if (mesaj.dusunce.isNotBlank()) {
            Row(
                Modifier.clickable { dusunceAcik = !dusunceAcik }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (akiyor && mesaj.icerik.isEmpty()) "Düşünüyor…" else "Düşünce",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(if (dusunceAcik) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
            }
            if (dusunceAcik) {
                SelectionContainer {
                    Text(
                        mesaj.dusunce.trim(),
                        Modifier.padding(start = 8.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val gorunen = if (akiyor) GorselKomutu.gizle(mesaj.icerik) else mesaj.icerik
        if (gorunen.isNotEmpty()) {
            SelectionContainer {
                MarkdownMetin(gorunen, MaterialTheme.colorScheme.onSurface)
            }
        } else if (akiyor && mesaj.dusunce.isBlank() && mesaj.gorselIstemi.isEmpty()) {
            Box(Modifier.padding(8.dp)) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
        }
        if (mesaj.gorselIstemi.isNotEmpty()) {
            SohbetGorseli(mesaj, akiyor, gorsel, gorselAc)
        }
        mesaj.hata?.let { hata ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                SelectionContainer {
                    Text(hata, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (!akiyor) {
            val baglam = LocalContext.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mesaj.icerik.isNotEmpty()) {
                    IconButton(onClick = { pano.setText(AnnotatedString(mesaj.icerik)) }, Modifier.size(36.dp)) {
                        Icon(Icons.Default.ContentCopy, "Kopyala", Modifier.size(16.dp))
                    }
                    IconButton(onClick = {
                        val paylas = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, mesaj.icerik)
                        }
                        baglam.startActivity(Intent.createChooser(paylas, "Paylaş"))
                    }, Modifier.size(36.dp)) {
                        Icon(Icons.Default.Share, "Paylaş", Modifier.size(16.dp))
                    }
                }
                if (yenile != null) {
                    IconButton(onClick = yenile, Modifier.size(36.dp)) {
                        Icon(Icons.Default.Refresh, "Yeniden üret", Modifier.size(16.dp))
                    }
                }
                Text(
                    "${mesaj.model} · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(mesaj.zaman))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SohbetGorseli(mesaj: Mesaj, akiyor: Boolean, gorsel: Pair<GorselKaydi, File>?, gorselAc: (String) -> Unit) {
    val sekil = RoundedCornerShape(12.dp)
    when {
        gorsel != null -> {
            val (k, dosya) = gorsel
            val bmp by rememberGorsel(dosya, 1024)
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .widthIn(max = 320.dp)
                    .fillMaxWidth()
                    .aspectRatio(k.genislik.toFloat() / k.yukseklik)
                    .clip(sekil)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { gorselAc(k.id) },
                contentAlignment = Alignment.Center,
            ) {
                bmp?.let { Image(it, k.istem, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        akiyor && mesaj.hata == null -> Row(
            Modifier
                .padding(top = 6.dp)
                .clip(sekil)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("  Görsel üretiliyor…", style = MaterialTheme.typography.bodyMedium)
        }
        mesaj.gorsel.isNotEmpty() -> Text(
            "Görsel galeriden silinmiş.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    Text(
        mesaj.gorselIstemi,
        Modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}
