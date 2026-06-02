package tdynamos.usbtoblhid

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.hardware.input.InputManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import tdynamos.usbtoblhid.databinding.ActivityMainBinding
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.roundToInt

import android.util.Log;
import tdynamos.usbtoblhid.SettingsActivity




@OptIn(DelicateCoroutinesApi::class)
class MainActivity : AppCompatActivity(), HidUtils.ConnectionStateChangeListener {
    private lateinit var binding: ActivityMainBinding
    private var lastMouseX: Float? = null
    private var lastMouseY: Float? = null
    private var pointerCaptured = false
    private val scrollScale = 3
    private lateinit var inputManager: InputManager
    
    // Touchpad state fields
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchDownTime = 0L
    private var isStarted = false

    private fun checkBluetoothAndStart() {
        if (Build.VERSION.SDK_INT >= 31 && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            connectPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        if (!isEnableBluetooth()) {
            bluetoothPermission.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        start()
    }

    var bluetoothPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (isEnableBluetooth()) {
            showToast(R.string.toast_bluetooth_on)
            checkBluetoothAndStart()
            discoverPermission.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE))
        } else {
            showToast(R.string.toast_bluetooth_off)
        }
    }

    var discoverPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checkBluetoothAndStart()
    }

    var connectPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            checkBluetoothAndStart()
            discoverPermission.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE))
        } else {
            showToast(R.string.toast_permission)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        inputManager = getSystemService(InputManager::class.java)
        inputManager.registerInputDeviceListener(inputDeviceListener, null)

        binding.root.isFocusableInTouchMode = true
        binding.root.requestFocus()

        // Initialize state & attempt registration
        checkBluetoothAndStart()
        
        // request discoverability separately if permissions and BT already enabled
        if (isEnableBluetooth() && (Build.VERSION.SDK_INT < 31 || hasPermission(Manifest.permission.BLUETOOTH_CONNECT))) {
            discoverPermission.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.root.setOnCapturedPointerListener { _, event ->
                handleCapturedPointer(event)
                true
            }
        }

        // Handle Touchpad gestures inside the dedicated touchpad area!
        binding.touchpadArea.setOnTouchListener { _, event ->
            // Let physical mouse events pass down to click listener
            if (event.isFromSource(InputDevice.SOURCE_MOUSE) || event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
                return@setOnTouchListener false
            }

            if (!HidUtils.isConnected() || !HidConsts.useMouseEnabled) {
                return@setOnTouchListener false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    touchDownTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val currX = event.x
                    val currY = event.y
                    val dx = currX - lastTouchX
                    val dy = currY - lastTouchY
                    lastTouchX = currX
                    lastTouchY = currY
                    if (dx != 0f || dy != 0f) {
                        HidConsts.mouseMove(dx, dy, 0f, false, false, false)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - touchDownTime
                    val dist = kotlin.math.hypot(event.x - lastTouchX, event.y - lastTouchY)
                    if (dist < 15f) {
                        if (duration < 250) {
                            // Tap -> Left Click down, then up
                            HidConsts.mouseMove(0f, 0f, 0f, true, false, false)
                            binding.touchpadArea.postDelayed({
                                HidConsts.mouseMove(0f, 0f, 0f, false, false, false)
                            }, 50)
                        } else if (duration >= 600) {
                            // Long Press -> Right Click down, then up
                            HidConsts.mouseMove(0f, 0f, 0f, false, true, false)
                            binding.touchpadArea.postDelayed({
                                HidConsts.mouseMove(0f, 0f, 0f, false, false, false)
                            }, 50)
                        }
                    }
                }
            }
            true
        }

        binding.root.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasMouseDevice() && HidConsts.useMouseEnabled) {
                binding.root.requestPointerCapture()
            }
        }
        
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        inputManager.registerInputDeviceListener(inputDeviceListener, null)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val oldMouse = HidConsts.useMouseEnabled
        val oldKbd = HidConsts.useKeyboardEnabled
        val oldJoy = HidConsts.useJoystickEnabled

        HidConsts.useMouseEnabled = prefs.getBoolean("pref_enable_mouse", true)
        HidConsts.useKeyboardEnabled = prefs.getBoolean("pref_enable_keyboard", true)
        HidConsts.useJoystickEnabled = prefs.getBoolean("pref_enable_joystick", true)

        if (oldMouse != HidConsts.useMouseEnabled || oldKbd != HidConsts.useKeyboardEnabled || oldJoy != HidConsts.useJoystickEnabled) {
            android.widget.Toast.makeText(this, "Настройки изменены. Пожалуйста, перезапустите приложение.", android.widget.Toast.LENGTH_LONG).show()
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasMouseDevice() && HidConsts.useMouseEnabled) {
            binding.root.post { binding.root.requestPointerCapture() }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.root.post { binding.root.releasePointerCapture() }
        }
        applyLatencyTweak()
    }

    private fun applyLatencyTweak() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean("pref_root_latency", false)) {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val p = Runtime.getRuntime().exec("su")
                    val os = java.io.DataOutputStream(p.outputStream)
                    os.writeBytes("renice -20 -p \$(pidof com.android.bluetooth)\n")
                    os.writeBytes("renice -20 -p \$(pidof tdynamos.usbtoblhid)\n")
                    os.writeBytes("chrt -f -p 99 \$(pidof com.android.bluetooth)\n")
                    os.writeBytes("chrt -f -p 99 \$(pidof tdynamos.usbtoblhid)\n")
                    os.writeBytes("echo performance > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor\n")
                    os.writeBytes("setprop persist.bluetooth.disableinbandringing 1\n")
                    os.writeBytes("exit\n")
                    os.flush()
                    p.waitFor()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        inputManager.unregisterInputDeviceListener(inputDeviceListener)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasFocus && hasMouseDevice() && HidConsts.useMouseEnabled) {
            binding.root.requestPointerCapture()
        }
    }

    private fun start() {
        if (isStarted) return
        isStarted = true
        HidUtils.registerApp(applicationContext)
        HidConsts.reporters(applicationContext)
        HidUtils.connectionStateChangeListener = this
    }


    override fun onConnecting() {
    }

    override fun onConnected() {
        if (Build.VERSION.SDK_INT >= 31 && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        GlobalScope.launch(Dispatchers.Main) {
            binding.tvConnectStatus.text = "${getString(R.string.connected)}: ${HidUtils.mDevice!!.name}"
        }

    }

    override fun onDisConnected() {
        GlobalScope.launch(Dispatchers.Main) {
            binding.tvConnectStatus.text = getString(R.string.ununited)
        }

    }


    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!HidUtils.isConnected()) {
            return super.onGenericMotionEvent(event)
        }
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            val bs = event.buttonState
            val left   = bs and MotionEvent.BUTTON_PRIMARY   != 0
            val right  = bs and MotionEvent.BUTTON_SECONDARY != 0
            val middle = bs and MotionEvent.BUTTON_TERTIARY  != 0

            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                    val currX = event.x
                    val currY = event.y
                    val prevX = lastMouseX ?: currX
                    val prevY = lastMouseY ?: currY
                    val dx = currX - prevX
                    val dy = currY - prevY
                    lastMouseX = currX
                    lastMouseY = currY
                    if (HidConsts.useMouseEnabled && (dx != 0f || dy != 0f)) {
                        HidConsts.mouseMove(dx, dy, 0f, left, right, middle)
                    }
                }
                MotionEvent.ACTION_SCROLL -> {
                    val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                    val wheel = if (vScroll != 0f) vScroll else hScroll
                    if (wheel != 0f && HidConsts.useMouseEnabled) {
                        HidConsts.mouseMove(0f, 0f, wheel, left, right, middle)
                    }
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    lastMouseX = null
                    lastMouseY = null
                }
            }
            return true
        }
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.isFromSource(InputDevice.SOURCE_GAMEPAD)) {
            val cx = event.getAxisValue(MotionEvent.AXIS_X)
            val cy = event.getAxisValue(MotionEvent.AXIS_Y)
            val cz = event.getAxisValue(MotionEvent.AXIS_Z)
            val crz = event.getAxisValue(MotionEvent.AXIS_RZ)
            val lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
            val rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            
            // Buttons logic can be caught combining dispatchKeyEvent for gamepad buttons
            
            HidConsts.joystickMove(cx, cy, cz, crz, lt, rt, hatX, hatY)
            return true
        }
        return super.onGenericMotionEvent(event)
    }
    
    // Add gamepad buttons parsing to dispatchKeyEvent?
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!HidUtils.isConnected()) {
            return super.dispatchKeyEvent(event)
        }

        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            val buttonIndex = getGamepadButtonIndex(event.keyCode)
            if (buttonIndex >= 0) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    HidConsts.joystickButtonUpdate(buttonIndex, true)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    HidConsts.joystickButtonUpdate(buttonIndex, false)
                }
                return true
            }
        }

        // Send to HID keyboard handler
        if (handleKeyboardEvent(event)) {
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    private fun getGamepadButtonIndex(keyCode: Int): Int {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> 0
            KeyEvent.KEYCODE_BUTTON_B -> 1
            KeyEvent.KEYCODE_BUTTON_X -> 2
            KeyEvent.KEYCODE_BUTTON_Y -> 3
            KeyEvent.KEYCODE_BUTTON_L1 -> 4
            KeyEvent.KEYCODE_BUTTON_R1 -> 5
            KeyEvent.KEYCODE_BUTTON_L2 -> 6
            KeyEvent.KEYCODE_BUTTON_R2 -> 7
            KeyEvent.KEYCODE_BUTTON_SELECT -> 8
            KeyEvent.KEYCODE_BUTTON_START -> 9
            KeyEvent.KEYCODE_BUTTON_THUMBL -> 10
            KeyEvent.KEYCODE_BUTTON_THUMBR -> 11
            KeyEvent.KEYCODE_DPAD_UP -> 12
            KeyEvent.KEYCODE_DPAD_DOWN -> 13
            KeyEvent.KEYCODE_DPAD_LEFT -> 14
            KeyEvent.KEYCODE_DPAD_RIGHT -> 15
            else -> -1
        }
    }

    private fun handleKeyboardEvent(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        if (device.isVirtual) return false

        // Ignore repeats; multi-key presses will be tracked in the buffer
        if (event.repeatCount > 0) return true

        // Handle modifier keys first
        val modifierMask = InputHidMapper.keyCodeToModifierMask(event.keyCode)
        if (modifierMask != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                HidConsts.kbdKeyDown("M$modifierMask")
            } else if (event.action == KeyEvent.ACTION_UP) {
                HidConsts.kbdKeyUp("M$modifierMask")
            }
            return true
        }

        // Handle regular keys
        val usage = InputHidMapper.keyCodeToHidUsage(event.keyCode) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            HidConsts.kbdKeyDown(usage.toString())  // Adds key to KeyBuffer
        } else if (event.action == KeyEvent.ACTION_UP) {
            HidConsts.kbdKeyUp(usage.toString())    // Removes key from KeyBuffer
        }

        return true
    }

    private fun handleCapturedPointer(event: MotionEvent): Boolean {

        // Current button states
        val bs = event.buttonState
        val left   = bs and MotionEvent.BUTTON_PRIMARY   != 0
        val right  = bs and MotionEvent.BUTTON_SECONDARY != 0
        val middle = bs and MotionEvent.BUTTON_TERTIARY  != 0
        
        when (event.actionMasked) {

            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_HOVER_MOVE -> {
                // Use relative motion for smooth fractional accumulation
                var dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                var dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                if (dx == 0f && dy == 0f) {
                    dx = event.x
                    dy = event.y
                }
                // Send via optimized mouseMove function
                if (!HidUtils.isConnected()) return true
                HidConsts.mouseMove(dx, dy, 0f, left, right, middle)
            }

            MotionEvent.ACTION_SCROLL -> {
                // Vertical and horizontal scroll (round to int)
                val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                val wheel = if (vScroll != 0f) vScroll else hScroll

                if (wheel != 0f) {
                    if (!HidUtils.isConnected()) return true
                    HidConsts.mouseMove(0f, 0f, wheel, left, right, middle)
                }
            }

            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE,
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_UP -> {
                // Only button state changed
                if (!HidUtils.isConnected()) return true
                HidConsts.mouseMove(0f, 0f, 0f, left, right, middle)
            }
        }

        return true
    }
    
    // mouse requestPointerCapture
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            Log.i("MDEBUG", "MOUSE_ID: " + deviceId.toString());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (isMouseDevice(deviceId)) {
                    binding.root.post { binding.root.requestPointerCapture() }
                }
            }
        }
        override fun onInputDeviceRemoved(deviceId: Int) {
        }
        override fun onInputDeviceChanged(deviceId: Int) {
        }
    }

    private fun hasMouseDevice(): Boolean {
        return inputManager.inputDeviceIds.any { isMouseDevice(it) }
    }

    private fun isMouseDevice(deviceId: Int): Boolean {
        val device = inputManager.getInputDevice(deviceId) ?: return false
        val isMouse = device.sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE
        return isMouse && !device.isVirtual
    }
}
