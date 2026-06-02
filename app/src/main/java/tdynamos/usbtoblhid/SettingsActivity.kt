package tdynamos.usbtoblhid

import android.os.Bundle
import android.hardware.input.InputManager
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.Preference
import android.app.AlertDialog

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            val listDevicesPref: Preference? = findPreference("pref_list_devices")
            listDevicesPref?.setOnPreferenceClickListener {
                val inputManager = requireContext().getSystemService(Context.INPUT_SERVICE) as InputManager
                val deviceIds = inputManager.inputDeviceIds
                val sb = java.lang.StringBuilder()
                for (id in deviceIds) {
                    val device = inputManager.getInputDevice(id)
                    if (device != null && !device.isVirtual) {
                        sb.append("ID: $id - ${device.name}\n")
                        sb.append("  Sources: 0x${Integer.toHexString(device.sources)}\n")
                    }
                }
                if (sb.isEmpty()) {
                    sb.append("No physical devices found.")
                }

                AlertDialog.Builder(requireContext())
                    .setTitle("Connected Input Devices")
                    .setMessage(sb.toString())
                    .setPositiveButton("OK", null)
                    .show()

                true
            }
        }
    }
}
