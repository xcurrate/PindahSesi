package com.example.cgptswitcher

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        const val DEFAULT_PKG = "com.openai.chatgpt"
        const val BASE = "/data/adb/pindah_sesi"
        const val OLD_ROOT = "/data/adb/chatgpt_switcher"

        private val NAME_PATTERN = Regex("[A-Za-z0-9 ._()-]+")
        private val PKG_PATTERN = Regex("[A-Za-z0-9._]+")
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var btnPickApp: Button
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private val profiles = mutableListOf<String>()
    private val sizes = mutableMapOf<String, Long>()

    /** Paket yang sedang dikelola. Nama paket masuk ke skrip root, jadi divalidasi. */
    private val pkg: String
        get() = prefs.getString("pkg", DEFAULT_PKG)
            ?.takeIf { PKG_PATTERN.matches(it) } ?: DEFAULT_PKG

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("switcher", MODE_PRIVATE)
        status = findViewById(R.id.status)
        btnPickApp = findViewById(R.id.btnPickApp)
        listView = findViewById(R.id.list)

        migratePrefs()

        adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, profiles) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val v = super.getView(pos, cv, parent)
                val name = profiles[pos]
                val active = name == activeProfile()
                val size = sizes[name]?.let { "  ·  ${fmtSize(it)}" } ?: ""
                (v as TextView).apply {
                    text = (if (active) "● " else "○ ") + name + size
                    setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFFE5E5E5.toInt())
                    setPadding(24, 24, 24, 24)
                }
                return v
            }
        }
        listView.adapter = adapter

        btnPickApp.setOnClickListener { pickApp() }
        findViewById<Button>(R.id.btnSaveNew).setOnClickListener { promptSaveNew() }
        findViewById<Button>(R.id.btnNewSession).setOnClickListener { confirmNewSession() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshList() }

        listView.setOnItemClickListener { _, _, pos, _ ->
            val name = profiles[pos]
            if (name == activeProfile()) toast("Sudah aktif")
            else confirmSwitch(name)
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            showProfileMenu(profiles[pos]); true
        }

        updateAppButton()
        refreshList()
    }

    // ---------- helpers ----------

    private fun activeProfile(): String? =
        prefs.getString("active_$pkg", null)?.takeIf { isValidName(it) }

    private fun setActive(name: String?) =
        prefs.edit().putString("active_$pkg", name).apply()

    /** Nama profil dipakai langsung di skrip root, jadi batasi karakternya. */
    private fun isValidName(name: String) =
        name.length in 1..40 && name[0] != '_' && NAME_PATTERN.matches(name)

    private fun migratePrefs() {
        val old = prefs.getString("active", null) ?: return
        if (!prefs.contains("active_$DEFAULT_PKG"))
            prefs.edit().putString("active_$DEFAULT_PKG", old).apply()
        prefs.edit().remove("active").apply()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun fmtSize(kb: Long): String = when {
        kb >= 1024 * 1024 -> String.format("%.1f GB", kb / 1024.0 / 1024.0)
        kb >= 1024 -> String.format("%.1f MB", kb / 1024.0)
        else -> "$kb KB"
    }

    private fun appLabel(p: String): String = try {
        val ai = packageManager.getApplicationInfo(p, 0)
        "${packageManager.getApplicationLabel(ai)} · $p"
    } catch (e: Exception) {
        "$p (tidak terpasang)"
    }

    private fun updateAppButton() {
        btnPickApp.text = "Target: ${appLabel(pkg)}"
    }

    private fun uiBusy(s: String) = runOnUiThread {
        status.text = s
        btnPickApp.isEnabled = false
        findViewById<Button>(R.id.btnSaveNew).isEnabled = false
        findViewById<Button>(R.id.btnNewSession).isEnabled = false
        findViewById<Button>(R.id.btnRefresh).isEnabled = false
    }

    private fun uiIdle() = runOnUiThread {
        btnPickApp.isEnabled = true
        findViewById<Button>(R.id.btnSaveNew).isEnabled = true
        findViewById<Button>(R.id.btnNewSession).isEnabled = true
        findViewById<Button>(R.id.btnRefresh).isEnabled = true
    }

    /**
     * Bagian awal semua skrip: path per paket, plus fungsi copy_data yang
     * melewati folder cache supaya profil tidak membengkak.
     */
    private fun scriptHead(): String = """
        set -e
        BASE=$BASE
        PKG="$pkg"
        ROOT="${'$'}BASE/${'$'}PKG"
        DATA="/data/data/${'$'}PKG"
        if [ -d "$OLD_ROOT" ] && [ ! -e "${'$'}BASE/$DEFAULT_PKG" ]; then
            mkdir -p "${'$'}BASE"
            mv "$OLD_ROOT" "${'$'}BASE/$DEFAULT_PKG"
        fi
        mkdir -p "${'$'}ROOT"
        copy_data() {
            rm -rf "${'$'}2"
            mkdir -p "${'$'}2"
            find "${'$'}1" -mindepth 1 -maxdepth 1 ! -name cache ! -name code_cache \
                -exec cp -a {} "${'$'}2/" \;
            find "${'$'}2" -type d \( -name GPUCache -o -name "Code Cache" \
                -o -name CacheStorage \) -prune -exec rm -rf {} \; 2>/dev/null || true
            chmod -R u+rwX "${'$'}2" 2>/dev/null || true
        }
    """.trimIndent()

    // ---------- daftar profil ----------

    private fun refreshList() {
        uiBusy("Memuat daftar profil…")
        Thread {
            val r = RootShell.run("""
                BASE=$BASE
                PKG="$pkg"
                ROOT="${'$'}BASE/${'$'}PKG"
                if [ -d "$OLD_ROOT" ] && [ ! -e "${'$'}BASE/$DEFAULT_PKG" ]; then
                    mkdir -p "${'$'}BASE"
                    mv "$OLD_ROOT" "${'$'}BASE/$DEFAULT_PKG"
                fi
                mkdir -p "${'$'}ROOT"
                for d in "${'$'}ROOT"/*; do
                    [ -d "${'$'}d" ] || continue
                    n=${'$'}{d##*/}
                    case "${'$'}n" in _*) continue;; esac
                    k=${'$'}(du -sk "${'$'}d" 2>/dev/null | cut -f1)
                    printf '%s\t%s\n' "${'$'}n" "${'$'}{k:-0}"
                done
            """.trimIndent(), timeoutSec = 30)

            val rows = if (r.ok) r.output.lines().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 2) return@mapNotNull null
                val name = parts[0].trim()
                if (!isValidName(name)) return@mapNotNull null
                name to (parts[1].trim().toLongOrNull() ?: 0L)
            } else emptyList()

            runOnUiThread {
                profiles.clear(); profiles.addAll(rows.map { it.first }.sorted())
                sizes.clear(); sizes.putAll(rows.toMap())
                adapter.notifyDataSetChanged()
                status.text = "Aktif: ${activeProfile() ?: "—"}  •  " +
                    "${profiles.size} profil  •  ${fmtSize(rows.sumOf { it.second })}"
                uiIdle()
            }
        }.start()
    }

    // ---------- pilih app ----------

    private fun pickApp() {
        val pm = packageManager
        val pairs = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
            .sortedBy { it.second.lowercase() }

        val items = pairs.map { "${it.second} · ${it.first}" }.toMutableList()
        val pkgs = pairs.map { it.first }.toMutableList()
        if (!pkgs.contains(pkg)) {
            items.add(0, appLabel(pkg))
            pkgs.add(0, pkg)
        }

        AlertDialog.Builder(this)
            .setTitle("Pilih app")
            .setMessage("Data app ini yang akan disimpan dan ditukar. Hati-hati dengan app sistem.")
            .setItems(items.toTypedArray()) { _, which ->
                prefs.edit().putString("pkg", pkgs[which]).apply()
                updateAppButton()
                refreshList()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---------- 1. Simpan sesi sekarang ----------

    private fun promptSaveNew() {
        val input = EditText(this).apply {
            hint = "Nama profil (mis. kerja)"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("Simpan sesi yang sedang login")
            .setMessage("Pastikan kamu sudah login sebagai akun yang mau disimpan. Setelah ini, kamu bisa klik \"Sesi baru\" untuk login akun lain tanpa logout.")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val name = input.text.toString().trim()
                when {
                    name.isEmpty() -> toast("Nama kosong")
                    !isValidName(name) ->
                        toast("Pakai huruf, angka, spasi, titik, minus, garis bawah, atau kurung (maks 40)")
                    profiles.contains(name) -> confirmOverwrite(name)
                    else -> saveNew(name)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun confirmOverwrite(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Profil \"$name\" sudah ada")
            .setMessage("Simpan sesi sekarang sebagai \"$name\"? Isi profil lama akan diganti.")
            .setPositiveButton("Ganti") { _, _ -> saveNew(name) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun saveNew(name: String) {
        uiBusy("Menyimpan profil \"$name\"…")
        Thread {
            val r = RootShell.run("""
                ${scriptHead()}
                NAME="$name"
                [ -d "${'$'}DATA" ] || { echo "app tidak terpasang"; exit 1; }
                am force-stop "${'$'}PKG"
                sleep 1
                copy_data "${'$'}DATA" "${'$'}ROOT/_tmp"
                rm -rf "${'$'}ROOT/${'$'}NAME"
                mv "${'$'}ROOT/_tmp" "${'$'}ROOT/${'$'}NAME"
                echo OK
            """.trimIndent(), timeoutSec = 180)
            runOnUiThread {
                if (r.ok) {
                    setActive(name)
                    toast("Tersimpan: $name")
                } else toast("Gagal: ${r.output}")
                refreshList()
            }
        }.start()
    }

    // ---------- 2. "Sesi baru" — tanpa logout ----------

    private fun confirmNewSession() {
        AlertDialog.Builder(this)
            .setTitle("Mulai sesi baru?")
            .setMessage(
                "State akun yang sedang login disimpan dulu ke profilnya " +
                "(atau ke _last kalau belum ada profil aktif), lalu data app " +
                "dikosongkan sehingga app terasa baru install dan menampilkan layar login.\n\n" +
                "TIDAK ada request logout ke server — token akun sebelumnya tetap hidup. " +
                "Kamu bisa login akun lain, lalu simpan sebagai profil baru."
            )
            .setPositiveButton("Mulai") { _, _ -> newSession() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun newSession() {
        uiBusy("Menyiapkan sesi baru…")
        Thread {
            val r = RootShell.run("""
                ${scriptHead()}
                ACTIVE="${activeProfile() ?: ""}"
                [ -d "${'$'}DATA" ] || { echo "app tidak terpasang"; exit 1; }
                am force-stop "${'$'}PKG"
                sleep 1

                UID_ORIG=${'$'}(stat -c %u "${'$'}DATA")
                GID_ORIG=${'$'}(stat -c %g "${'$'}DATA")

                copy_data "${'$'}DATA" "${'$'}ROOT/_tmp"
                if [ -n "${'$'}ACTIVE" ]; then
                    rm -rf "${'$'}ROOT/${'$'}ACTIVE"
                    mv "${'$'}ROOT/_tmp" "${'$'}ROOT/${'$'}ACTIVE"
                else
                    rm -rf "${'$'}ROOT/_last"
                    mv "${'$'}ROOT/_tmp" "${'$'}ROOT/_last"
                fi

                rm -rf "${'$'}DATA"
                mkdir -p "${'$'}DATA"
                chown "${'$'}UID_ORIG:${'$'}GID_ORIG" "${'$'}DATA"
                chmod 771 "${'$'}DATA"

                if command -v restorecon >/dev/null 2>&1; then
                    restorecon -R "${'$'}DATA"
                fi

                monkey -p "${'$'}PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
                echo OK
            """.trimIndent(), timeoutSec = 240)
            runOnUiThread {
                if (r.ok) {
                    setActive(null)
                    toast("Sesi baru siap — login dengan akun lain")
                } else toast("Gagal: ${r.output}\nState lama ada di ${activeProfile() ?: "_last"}")
                refreshList()
            }
        }.start()
    }

    // ---------- 3. Pindah profil ----------

    private fun confirmSwitch(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Pindah ke profil \"$name\"?")
            .setMessage(
                "App akan di-force stop, perubahan pada akun yang sedang aktif " +
                "disimpan dulu ke profilnya, lalu data profil \"$name\" dipulihkan dan " +
                "app dibuka ulang.\n\n" +
                "Tidak ada request logout ke server."
            )
            .setPositiveButton("Pindah") { _, _ -> switchTo(name) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun switchTo(name: String) {
        uiBusy("Beralih ke \"$name\"…")
        Thread {
            val r = RootShell.run("""
                ${scriptHead()}
                NAME="$name"
                ACTIVE="${activeProfile() ?: ""}"
                SRC="${'$'}ROOT/${'$'}NAME"
                [ -d "${'$'}SRC" ] || { echo "profil tidak ditemukan"; exit 1; }
                [ -d "${'$'}DATA" ] || { echo "app tidak terpasang"; exit 1; }

                am force-stop "${'$'}PKG"
                sleep 1

                copy_data "${'$'}DATA" "${'$'}ROOT/_tmp"
                if [ -n "${'$'}ACTIVE" ] && [ "${'$'}ACTIVE" != "${'$'}NAME" ]; then
                    rm -rf "${'$'}ROOT/${'$'}ACTIVE"
                    mv "${'$'}ROOT/_tmp" "${'$'}ROOT/${'$'}ACTIVE"
                else
                    rm -rf "${'$'}ROOT/_last"
                    mv "${'$'}ROOT/_tmp" "${'$'}ROOT/_last"
                fi

                UID_ORIG=${'$'}(stat -c %u "${'$'}DATA")
                GID_ORIG=${'$'}(stat -c %g "${'$'}DATA")

                rm -rf "${'$'}DATA"
                cp -a "${'$'}SRC" "${'$'}DATA"
                chown -R "${'$'}UID_ORIG:${'$'}GID_ORIG" "${'$'}DATA"

                if command -v restorecon >/dev/null 2>&1; then
                    restorecon -R "${'$'}DATA"
                fi

                monkey -p "${'$'}PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
                echo OK
            """.trimIndent(), timeoutSec = 240)
            runOnUiThread {
                if (r.ok) {
                    setActive(name)
                    toast("Beralih ke $name")
                } else toast("Gagal: ${r.output}\nState lama ada di ${activeProfile() ?: "_last"}")
                refreshList()
            }
        }.start()
    }

    // ---------- 4. Menu profil ----------

    private fun showProfileMenu(name: String) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf("Hapus cache profil", "Hapus profil")) { _, which ->
                if (which == 0) cleanCache(name) else confirmDelete(name)
            }
            .show()
    }

    private fun cleanCache(name: String) {
        uiBusy("Membersihkan cache \"$name\"…")
        Thread {
            val r = RootShell.run("""
                ${scriptHead()}
                NAME="$name"
                [ -d "${'$'}ROOT/${'$'}NAME" ] || { echo "profil tidak ditemukan"; exit 1; }
                rm -rf "${'$'}ROOT/${'$'}NAME/cache" "${'$'}ROOT/${'$'}NAME/code_cache"
                find "${'$'}ROOT/${'$'}NAME" -type d \( -name GPUCache -o -name "Code Cache" \
                    -o -name CacheStorage \) -prune -exec rm -rf {} \; 2>/dev/null || true
                echo OK
            """.trimIndent(), timeoutSec = 120)
            runOnUiThread {
                toast(if (r.ok) "Cache dibersihkan" else "Gagal: ${r.output}")
                refreshList()
            }
        }.start()
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Hapus profil \"$name\"?")
            .setPositiveButton("Hapus") { _, _ ->
                uiBusy("Menghapus…")
                Thread {
                    val r = RootShell.run("""
                        ${scriptHead()}
                        NAME="$name"
                        rm -rf "${'$'}ROOT/${'$'}NAME"
                        echo OK
                    """.trimIndent(), timeoutSec = 30)
                    runOnUiThread {
                        if (name == activeProfile()) setActive(null)
                        toast(if (r.ok) "Dihapus" else "Gagal: ${r.output}")
                        refreshList()
                    }
                }.start()
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}
