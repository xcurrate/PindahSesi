package com.example.cgptswitcher

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        const val PKG  = "com.openai.chatgpt"
        const val ROOT = "/data/adb/chatgpt_switcher"

        private val NAME_PATTERN = Regex("[A-Za-z0-9 ._()-]+")
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private val profiles = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("switcher", MODE_PRIVATE)
        status = findViewById(R.id.status)
        listView = findViewById(R.id.list)

        adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, profiles) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val v = super.getView(pos, cv, parent)
                (v as TextView).apply {
                    text = if (profiles[pos] == activeProfile())
                        "● ${profiles[pos]}  (aktif)" else "○ ${profiles[pos]}"
                    setTextColor(0xFFE5E5E5.toInt())
                    setPadding(24, 24, 24, 24)
                }
                return v
            }
        }
        listView.adapter = adapter

        findViewById<Button>(R.id.btnSaveNew).setOnClickListener { promptSaveNew() }
        findViewById<Button>(R.id.btnNewSession).setOnClickListener { confirmNewSession() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshList() }

        listView.setOnItemClickListener { _, _, pos, _ ->
            val name = profiles[pos]
            if (name == activeProfile()) toast("Sudah aktif")
            else confirmSwitch(name)
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            confirmDelete(profiles[pos]); true
        }

        refreshList()
    }

    // ---------- helpers ----------

    private fun activeProfile(): String? =
        prefs.getString("active", null)?.takeIf { isValidName(it) }
    private fun setActive(name: String?) =
        prefs.edit().putString("active", name).apply()

    /** Nama profil dipakai langsung di skrip root, jadi batasi karakternya. */
    private fun isValidName(name: String) =
        name.length in 1..40 && name[0] != '_' && NAME_PATTERN.matches(name)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun uiBusy(s: String) = runOnUiThread {
        status.text = s
        findViewById<Button>(R.id.btnSaveNew).isEnabled = false
        findViewById<Button>(R.id.btnNewSession).isEnabled = false
        findViewById<Button>(R.id.btnRefresh).isEnabled = false
    }
    private fun uiIdle() = runOnUiThread {
        findViewById<Button>(R.id.btnSaveNew).isEnabled = true
        findViewById<Button>(R.id.btnNewSession).isEnabled = true
        findViewById<Button>(R.id.btnRefresh).isEnabled = true
    }

    private fun refreshList() {
        uiBusy("Memuat daftar profil…")
        Thread {
            val r = RootShell.run("""
                mkdir -p $ROOT
                ls -1 $ROOT 2>/dev/null | grep -v '^_' || true
            """.trimIndent(), timeoutSec = 10)
            val names = if (r.ok) r.output.lines().map { it.trim() }.filter { isValidName(it) }
                        else emptyList()
            runOnUiThread {
                profiles.clear(); profiles.addAll(names.sorted())
                adapter.notifyDataSetChanged()
                status.text = "Aktif: ${activeProfile() ?: "— (sesi baru)"}  •  ${names.size} profil"
                uiIdle()
            }
        }.start()
    }

    // ---------- 1. Save current session as profile ----------

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
            val script = """
                set -e
                PKG=$PKG
                ROOT=$ROOT
                NAME="$name"
                DATA="/data/data/${'$'}PKG"
                [ -d "${'$'}DATA" ] || { echo "app tidak terpasang"; exit 1; }
                am force-stop "${'$'}PKG"
                sleep 1
                mkdir -p "${'$'}ROOT"
                rm -rf "${'$'}ROOT/_tmp"
                cp -a "${'$'}DATA" "${'$'}ROOT/_tmp"
                chmod -R u+rwX "${'$'}ROOT/_tmp"
                rm -rf "${'$'}ROOT/${'$'}NAME"
                mv "${'$'}ROOT/_tmp" "${'$'}ROOT/${'$'}NAME"
                echo OK
            """.trimIndent()
            val r = RootShell.run(script, timeoutSec = 120)
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
                "(atau ke _last kalau belum ada profil aktif), lalu data app ChatGPT " +
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
            val script = """
                set -e
                PKG=$PKG
                ROOT=$ROOT
                ACTIVE="${activeProfile() ?: ""}"
                DATA="/data/data/${'$'}PKG"
                [ -d "${'$'}DATA" ] || { echo "app tidak terpasang"; exit 1; }

                am force-stop "${'$'}PKG"
                sleep 1

                UID_ORIG=${'$'}(stat -c %u "${'$'}DATA")
                GID_ORIG=${'$'}(stat -c %g "${'$'}DATA")

                # auto-save: state yang sedang login balik ke profil aktifnya
                rm -rf "${'$'}ROOT/_tmp"
                cp -a "${'$'}DATA" "${'$'}ROOT/_tmp"
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
            """.trimIndent()
            val r = RootShell.run(script, timeoutSec = 180)
            runOnUiThread {
                if (r.ok) {
                    setActive(null)
                    toast("Sesi baru siap — login dengan akun lain")
                } else toast("Gagal: ${r.output}\nState lama ada di ${activeProfile() ?: "_last"}")
                refreshList()
            }
        }.start()
    }

    // ---------- 3. Switch profile ----------

    private fun confirmSwitch(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Pindah ke profil \"$name\"?")
            .setMessage(
                "App ChatGPT akan di-force stop, perubahan pada akun yang sedang aktif " +
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
            val script = """
                set -e
                PKG=$PKG
                ROOT=$ROOT
                NAME="$name"
                ACTIVE="${activeProfile() ?: ""}"
                DATA="/data/data/${'$'}PKG"
                SRC="${'$'}ROOT/${'$'}NAME"
                [ -d "${'$'}SRC" ] || { echo "profil tidak ditemukan"; exit 1; }
                [ -d "${'$'}DATA" ] || { echo "app tidak terpasang"; exit 1; }

                am force-stop "${'$'}PKG"
                sleep 1

                # auto-save: state sekarang disimpan ke profil yang sedang aktif
                rm -rf "${'$'}ROOT/_tmp"
                cp -a "${'$'}DATA" "${'$'}ROOT/_tmp"
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
            """.trimIndent()
            val r = RootShell.run(script, timeoutSec = 180)
            runOnUiThread {
                if (r.ok) {
                    setActive(name)
                    toast("Beralih ke $name")
                } else toast("Gagal: ${r.output}\nState lama ada di ${activeProfile() ?: "_last"}")
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
                        set -e
                        rm -rf "$ROOT/$name"
                        echo OK
                    """.trimIndent(), timeoutSec = 10)
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
