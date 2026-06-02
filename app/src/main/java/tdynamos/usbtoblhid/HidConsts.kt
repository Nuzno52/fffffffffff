package tdynamos.usbtoblhid

import android.bluetooth.BluetoothHidDevice
import android.content.Context
import android.os.Handler
import android.text.TextUtils
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.experimental.and
import kotlin.experimental.inv
import kotlin.experimental.or

import android.util.Log;

import kotlinx.coroutines.launch

object HidConsts {
    const val TAG = "u-HidConsts"
    const val NAME = "BS-HID-Peripheral"
    const val DESCRIPTION = "fac"
    const val PROVIDER = "funny"

    @JvmField
    var HidDevice: BluetoothHidDevice? = null

    private var handler: Handler? = null
    private val mouseChannel = kotlinx.coroutines.channels.Channel<HidReport>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val keyboardChannel = kotlinx.coroutines.channels.Channel<HidReport>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val joystickChannel = kotlinx.coroutines.channels.Channel<HidReport>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val senderScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)

    init {
        senderScope.launch {
            for (report in mouseChannel) {
                if (HidUtils.isConnected()) {
                    HidDevice?.sendReport(HidUtils.mDevice, report.ReportId.toInt(), report.ReportData)
                }
            }
        }
        senderScope.launch {
            for (report in keyboardChannel) {
                if (HidUtils.isConnected()) {
                    HidDevice?.sendReport(HidUtils.mDevice, report.ReportId.toInt(), report.ReportData)
                }
            }
        }
        senderScope.launch {
            for (report in joystickChannel) {
                if (HidUtils.isConnected()) {
                    HidDevice?.sendReport(HidUtils.mDevice, report.ReportId.toInt(), report.ReportData)
                }
            }
        }
    }

    private fun addInputReport(inputReport: HidReport?) {
        if (inputReport != null) {
            when (inputReport.deviceType) {
                HidReport.DeviceType.Mouse -> mouseChannel.trySend(inputReport)
                HidReport.DeviceType.Keyboard -> keyboardChannel.trySend(inputReport)
                HidReport.DeviceType.Joystick -> joystickChannel.trySend(inputReport)
                else -> mouseChannel.trySend(inputReport) // Ping or none
            }
        }
    }

    var scheperoid: Long = 5
    fun reporters(context: Context) {
        handler = Handler(context.mainLooper)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        Timer().scheduleAtFixedRate(object : TimerTask() {
            var pingCounter = 0
            override fun run() {
                if (HidUtils.isConnected() && prefs.getBoolean("pref_enable_ping", false)) {
                    pingCounter++
                    if (pingCounter >= 4) { // Ping every 20ms (4 * 5ms) to prevent Sniff mode
                        pingCounter = 0
                        // Only send ping if channel is empty to avoid flooding the queue if BT is slow
                        if (useMouseEnabled && mouseChannel.isEmpty) {
                            mouseChannel.trySend(HidReport(HidReport.DeviceType.Mouse, 0x01.toByte(), byteArrayOf(0, 0, 0, 0)))
                        } else if (useKeyboardEnabled && keyboardChannel.isEmpty) {
                            keyboardChannel.trySend(HidReport(HidReport.DeviceType.Keyboard, 0x02.toByte(), ByteArray(8)))
                        } else if (useJoystickEnabled && joystickChannel.isEmpty) {
                            joystickChannel.trySend(HidReport(HidReport.DeviceType.Joystick, 0x03.toByte(), ByteArray(9)))
                        }
                    }
                }
            }
        }, 0, scheperoid)
    }

    private fun postReport(report: HidReport) {
        // Obsolete
    }

    fun sendMouseReport(reportData: ByteArray?) {
        val report = HidReport(HidReport.DeviceType.Mouse, 0x01.toByte(), reportData!!)
        addInputReport(report)
    }

    private var mouseAccumulatorX = 0f
    private var mouseAccumulatorY = 0f
    private var mouseAccumulatorWheel = 0f

    private val MouseReport = HidReport(HidReport.DeviceType.Mouse, 0x01.toByte(), byteArrayOf(0, 0, 0, 0))
    fun mouseMove(dx: Float, dy: Float, wheel: Float, leftButton: Boolean, rightButton: Boolean, middleButton: Boolean) {
        if (!useMouseEnabled) return

        mouseAccumulatorX += dx
        mouseAccumulatorY += dy
        mouseAccumulatorWheel += wheel

        var ix = mouseAccumulatorX.toInt()
        var iy = mouseAccumulatorY.toInt()
        var iwheel = mouseAccumulatorWheel.toInt()

        mouseAccumulatorX -= ix
        mouseAccumulatorY -= iy
        mouseAccumulatorWheel -= iwheel

        if (ix > 127) ix = 127
        if (ix < -127) ix = -127
        if (iy > 127) iy = 127
        if (iy < -127) iy = -127
        if (iwheel > 127) iwheel = 127
        if (iwheel < -127) iwheel = -127
        if (leftButton) {
            MouseReport.ReportData[0] = (MouseReport.ReportData[0].toInt() or 1).toByte()
        } else {
            MouseReport.ReportData[0] = (MouseReport.ReportData[0].toInt() and 1.inv()).toByte()
        }
        if (rightButton) {
            MouseReport.ReportData[0] = (MouseReport.ReportData[0].toInt() or 2).toByte()
        } else {
            MouseReport.ReportData[0] = (MouseReport.ReportData[0].toInt() and 2.inv()).toByte()
        }
        if (middleButton) {
            MouseReport.ReportData[0] = (MouseReport.ReportData[0].toInt() or 4).toByte()
        } else {
            MouseReport.ReportData[0] = (MouseReport.ReportData[0].toInt() and 4.inv()).toByte()
        }
        MouseReport.ReportData[1] = ix.toByte()
        MouseReport.ReportData[2] = iy.toByte()
        MouseReport.ReportData[3] = iwheel.toByte()

        val reportData = ByteArray(4)
        System.arraycopy(MouseReport.ReportData, 0, reportData, 0, 4)
        addInputReport(HidReport(HidReport.DeviceType.Mouse, 0x01.toByte(), reportData))
    }

    private val JoyReport = HidReport(HidReport.DeviceType.Joystick, 0x03.toByte(), ByteArray(9))
    // Report layout:
    // 0: X, 1: Y, 2: Z, 3: Rz, 4: Brake, 5: Accelerator, 6: Buttons Low, 7: Buttons High, 8: D-Pad

    fun joystickMove(x: Float, y: Float, z: Float, rz: Float, lt: Float, rt: Float, hatX: Float, hatY: Float) {
        if (!useJoystickEnabled) return

        // Values are -1.0 to +1.0, mapped to 0 to 255.
        val ix = ((x + 1f) / 2f * 255f).toInt().coerceIn(0, 255)
        val iy = ((y + 1f) / 2f * 255f).toInt().coerceIn(0, 255)
        val iz = ((z + 1f) / 2f * 255f).toInt().coerceIn(0, 255)
        val irz = ((rz + 1f) / 2f * 255f).toInt().coerceIn(0, 255)

        // Triggers are 0.0 to 1.0
        val ilt = (lt * 255f).toInt().coerceIn(0, 255)
        val irt = (rt * 255f).toInt().coerceIn(0, 255)

        var dpad = 0 // Null State
        if (hatY < -0.5f && hatX == 0f) dpad = 1 // Up
        else if (hatY < -0.5f && hatX > 0.5f) dpad = 2 // Up-Right
        else if (hatY == 0f && hatX > 0.5f) dpad = 3 // Right
        else if (hatY > 0.5f && hatX > 0.5f) dpad = 4 // Down-Right
        else if (hatY > 0.5f && hatX == 0f) dpad = 5 // Down
        else if (hatY > 0.5f && hatX < -0.5f) dpad = 6 // Down-Left
        else if (hatY == 0f && hatX < -0.5f) dpad = 7 // Left
        else if (hatY < -0.5f && hatX < -0.5f) dpad = 8 // Up-Left

        JoyReport.ReportData[0] = ix.toByte()
        JoyReport.ReportData[1] = iy.toByte()
        JoyReport.ReportData[2] = iz.toByte()
        JoyReport.ReportData[3] = irz.toByte()
        JoyReport.ReportData[4] = ilt.toByte()
        JoyReport.ReportData[5] = irt.toByte()
        JoyReport.ReportData[8] = dpad.toByte()
        
        val reportData = ByteArray(9)
        System.arraycopy(JoyReport.ReportData, 0, reportData, 0, 9)
        addInputReport(HidReport(HidReport.DeviceType.Joystick, 0x03.toByte(), reportData))
    }

    private var joyButtons16: Int = 0

    fun joystickButtonUpdate(buttonIndex: Int, isDown: Boolean) {
        if (!useJoystickEnabled) return

        if (isDown) {
            joyButtons16 = joyButtons16 or (1 shl buttonIndex)
        } else {
            joyButtons16 = joyButtons16 and (1 shl buttonIndex).inv()
        }
        JoyReport.ReportData[6] = (joyButtons16 and 0xFF).toByte()
        JoyReport.ReportData[7] = ((joyButtons16 shr 8) and 0xFF).toByte()

        val reportData = ByteArray(9)
        System.arraycopy(JoyReport.ReportData, 0, reportData, 0, 9)
        addInputReport(HidReport(HidReport.DeviceType.Joystick, 0x03.toByte(), reportData))
    }

    private var ModifierByte: Byte = 0
    // 6-key rollover buffer
    private val keyBuffer = ByteArray(6) { 0 }


    private fun addKey(key: Byte) {
        for (i in keyBuffer.indices) {
            if (keyBuffer[i] == 0.toByte()) {
                keyBuffer[i] = key
                return
            }
        }
    }

    private fun removeKey(key: Byte) {
        for (i in keyBuffer.indices) {
            if (keyBuffer[i] == key) {
                keyBuffer[i] = 0
            }
        }
    }


    fun modifierDown(usageId: Byte): Byte {
        synchronized(HidConsts::class.java) {
            ModifierByte = ModifierByte or usageId
        }
        return ModifierByte
    }

    fun modifierUp(usageId: Byte): Byte {
        val inv = usageId.inv().toByte()
        synchronized(HidConsts::class.java) {
            ModifierByte = (ModifierByte and inv).toByte()
        }
        return ModifierByte
    }

    fun kbdKeyDown(usageStr: String) {
        if (!useKeyboardEnabled) return
        if (usageStr.isEmpty()) return

        synchronized(HidConsts::class.java) {

            if (usageStr.startsWith("M")) {
                val mod = modifierDown(
                    usageStr.removePrefix("M").toInt().toByte()
                )
                sendFullKeyReport()
            } else {
                val key = usageStr.toInt().toByte()
                addKey(key)
                sendFullKeyReport()
            }
        }
    }


    fun kbdKeyUp(usageStr: String) {
        if (!useKeyboardEnabled) return
        if (usageStr.isEmpty()) return

        synchronized(HidConsts::class.java) {

            if (usageStr.startsWith("M")) {
                modifierUp(
                    usageStr.removePrefix("M").toInt().toByte()
                )
            } else {
                val key = usageStr.toInt().toByte()
                removeKey(key)
            }

            sendFullKeyReport()
        }
    }

    private fun sendFullKeyReport() {

        val reportData = ByteArray(8)

        reportData[0] = ModifierByte
        reportData[1] = 0 // reserved
        
        for (i in 0 until 6) {
            reportData[2 + i] = keyBuffer[i]
        }

        val report = HidReport(
            HidReport.DeviceType.Keyboard,
            0x02.toByte(),
            reportData
        )

        addInputReport(report)
    }


    fun intArrayToByteArray(vararg values: Int): ByteArray = ByteArray(values.size) { i -> values[i].toByte() }

    var useMouseEnabled = true
    var useKeyboardEnabled = true
    var useJoystickEnabled = true

    @JvmField
    var Descriptor: ByteArray = byteArrayOf()
    fun buildDescriptor(context: Context) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        useMouseEnabled = prefs.getBoolean("pref_enable_mouse", true)
        useKeyboardEnabled = prefs.getBoolean("pref_enable_keyboard", true)
        useJoystickEnabled = prefs.getBoolean("pref_enable_joystick", true)

        var list = mutableListOf<Byte>()

        if (useMouseEnabled) {
            val mouseDesc = intArrayToByteArray(
                0x05, 0x01,       // Usage Page (Generic Desktop)
                0x09, 0x02,       // Usage (Mouse)
                0xA1, 0x01,       // Collection (Application)
                0x09, 0x01,       //   Usage (Pointer)
                0xA1, 0x00,       //   Collection (Physical)
                0x85, 0x01,       //   Report ID = 1

                // Mouse buttons
                0x05, 0x09,       //     Usage Page (Button)
                0x19, 0x01,       //     Usage Minimum (Button 1)
                0x29, 0x03,       //     Usage Maximum (Button 3)
                0x15, 0x00,       //     Logical Minimum 0
                0x25, 0x01,       //     Logical Maximum 1
                0x95, 0x03,       //     Report Count = 3 (3 buttons)
                0x75, 0x01,       //     Report Size = 1 bit
                0x81, 0x02,       //     Input (Data, Variable, Absolute)

                // Padding for buttons (to fill 1 byte)
                0x95, 0x01,       //     Report Count = 1
                0x75, 0x05,       //     Report Size = 5 bits
                0x81, 0x03,       //     Input (Constant, Variable, Absolute)

                // X/Y/Wheel movement
                0x05, 0x01,       //     Usage Page (Generic Desktop)
                0x09, 0x30,       //     Usage X
                0x09, 0x31,       //     Usage Y
                0x09, 0x38,       //     Usage Wheel
                0x15, 0x81,       //     Logical Minimum -127
                0x25, 0x7F,       //     Logical Maximum 127
                0x75, 0x08,       //     Report Size = 8 bits
                0x95, 0x03,       //     Report Count = 3
                0x81, 0x06,       //     Input (Data, Variable, Relative)

                0xC0,             //   End Physical Collection
                0xC0              // End Application Collection
            )
            list.addAll(mouseDesc.toList())
        }

        if (useKeyboardEnabled) {
            val keybDesc = intArrayToByteArray(
                0x05, 0x01,       // Usage Page (Generic Desktop)
                0x09, 0x06,       // Usage (Keyboard)
                0xA1, 0x01,       // Collection (Application)
                0x85, 0x02,       // Report ID = 2

                // Modifiers (Ctrl/Shift/Alt/GUI)
                0x05, 0x07,       // Usage Page (Keyboard/Keypad)
                0x19, 0xE0,       // Usage Minimum (Left Ctrl)
                0x29, 0xE7,       // Usage Maximum (Right GUI)
                0x15, 0x00,       // Logical Minimum 0
                0x25, 0x01,       // Logical Maximum 1
                0x75, 0x01,       // Report Size = 1 bit per modifier
                0x95, 0x08,       // Report Count = 8 bits (all modifiers)
                0x81, 0x02,       // Input (Data, Variable, Absolute)

                // Reserved byte
                0x75, 0x08,       // Report Size = 8 bits
                0x95, 0x01,       // Report Count = 1
                0x81, 0x01,       // Input (Constant) – reserved for alignment

                // Keycodes (6-key rollover)
                0x05, 0x07,       // Usage Page (Keyboard/Keypad)
                0x19, 0x00,       // Usage Minimum = 0
                0x29, 0x65,       // Usage Maximum = 101
                0x15, 0x00,       // Logical Minimum = 0
                0x25, 0x65,       // Logical Maximum = 101
                0x75, 0x08,       // Report Size = 8 bits per key
                0x95, 0x06,       // Report Count = 6 keys
                0x81, 0x00,       // Input (Data, Array, Absolute)

                // LED Output (CapsLock/NumLock/etc)
                0x05, 0x08,       // Usage Page (LEDs)
                0x19, 0x01,       // Usage Minimum = Num Lock
                0x29, 0x05,       // Usage Maximum = Kana
                0x75, 0x01,       // Report Size = 1 bit per LED
                0x95, 0x05,       // Report Count = 5 LEDs
                0x91, 0x02,       // Output (Data, Variable, Absolute)

                // Padding for LEDs
                0x75, 0x03,       // Report Size = 3 bits
                0x95, 0x01,       // Report Count = 1
                0x91, 0x03,       // Output (Constant, Variable, Absolute)
                0xC0              // End Keyboard Collection
            )
            list.addAll(keybDesc.toList())
        }
        
        if (useJoystickEnabled) {
            val joyDesc = intArrayToByteArray(
                0x05, 0x01,        // Usage Page (Generic Desktop Ctrls)
                0x09, 0x05,        // Usage (Gamepad)
                0xA1, 0x01,        // Collection (Application)
                0x85, 0x03,        //   Report ID = 3
                
                // Axes (X, Y, Z, Rz)
                0x05, 0x01,        //   Usage Page (Generic Desktop Ctrls)
                0x09, 0x30,        //   Usage (X)
                0x09, 0x31,        //   Usage (Y)
                0x09, 0x32,        //   Usage (Z)
                0x09, 0x35,        //   Usage (Rz)
                0x15, 0x00,        //   Logical Minimum (0)
                0x26, 0xFF, 0x00,  //   Logical Maximum (255)
                0x75, 0x08,        //   Report Size (8)
                0x95, 0x04,        //   Report Count (4)
                0x81, 0x02,        //   Input (Data,Var,Abs)

                // Triggers (L2, R2 as Brake / Accelerator)
                0x05, 0x02,        //   Usage Page (Simulation Controls)
                0x09, 0xC5,        //   Usage (Brake)
                0x09, 0xC4,        //   Usage (Accelerator)
                0x15, 0x00,        //   Logical Minimum (0)
                0x26, 0xFF, 0x00,  //   Logical Maximum (255)
                0x75, 0x08,        //   Report Size (8)
                0x95, 0x02,        //   Report Count (2)
                0x81, 0x02,        //   Input (Data,Var,Abs)
                
                // Joystick Buttons (16 buttons)
                0x05, 0x09,        //   Usage Page (Button)
                0x19, 0x01,        //   Usage Minimum (1)
                0x29, 0x10,        //   Usage Maximum (16)
                0x15, 0x00,        //   Logical Minimum (0)
                0x25, 0x01,        //   Logical Maximum (1)
                0x95, 0x10,        //   Report Count (16)
                0x75, 0x01,        //   Report Size (1 bit)
                0x81, 0x02,        //   Input (Data, Variable, Absolute)
                
                // D-Pad (Hat Switch)
                0x05, 0x01,        //   Usage Page (Generic Desktop)
                0x09, 0x39,        //   Usage (Hat switch)
                0x15, 0x01,        //   Logical Minimum (1)
                0x25, 0x08,        //   Logical Maximum (8)
                0x35, 0x00,        //   Physical Minimum (0)
                0x46, 0x3B, 0x01,  //   Physical Maximum (315)
                0x65, 0x14,        //   Unit (Eng Rot:Angular Pos)
                0x75, 0x04,        //   Report Size (4 bits)
                0x95, 0x01,        //   Report Count (1)
                0x81, 0x42,        //   Input (Data, Variable, Absolute, Null State)
                
                // Padding for D-Pad
                0x75, 0x04,        //   Report Size (4 bits)
                0x95, 0x01,        //   Report Count (1)
                0x81, 0x03,        //   Input (Constant, Variable, Absolute)
                
                0xC0               // End Application Collection
            )
            list.addAll(joyDesc.toList())
        }

        Descriptor = list.toByteArray()
    }
}
