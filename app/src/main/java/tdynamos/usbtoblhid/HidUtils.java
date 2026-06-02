package tdynamos.usbtoblhid;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import java.util.concurrent.Executors;

public class HidUtils {
    public static final String TAG = "Hid-Utils";
    public static boolean _connected = false;
    public static boolean isRegister = false;
    public static ConnectionStateChangeListener connectionStateChangeListener;

    static BluetoothProfile bluetoothProfile;
    static BluetoothDevice mDevice;
    static BluetoothHidDevice mHidDevice;

    public interface ConnectionStateChangeListener {
        void onConnecting();

        void onConnected();

        void onDisConnected();
    }

    public static byte getSubclass() {
        boolean useKeyboard = HidConsts.INSTANCE.getUseKeyboardEnabled();
        boolean useMouse = HidConsts.INSTANCE.getUseMouseEnabled();
        boolean useJoystick = HidConsts.INSTANCE.getUseJoystickEnabled();

        if (useMouse && useKeyboard) {
            return (byte) 0xC0; // Combo Keyboard/Pointing Device (highly compatible)
        } else if (useMouse) {
            return (byte) 0x80; // Pointing Device (Mouse) only
        } else if (useKeyboard) {
            return (byte) 0x40; // Keyboard only
        } else if (useJoystick) {
            return (byte) 0x08; // Gamepad only
        }
        return (byte) 0xC0; // Default combo fallback
    }

    public static void registerApp(Context context) {
        HidConsts.INSTANCE.buildDescriptor(context);
        if (mHidDevice != null) {
            if (isRegister) {
                mHidDevice.unregisterApp();
                isRegister = false;
            }
            byte subclass = getSubclass();
            BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(HidConsts.NAME, HidConsts.DESCRIPTION, HidConsts.PROVIDER, subclass, HidConsts.Descriptor);
            mHidDevice.registerApp(sdp, null, null, Executors.newCachedThreadPool(), mCallback);
        } else {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            try {
                if (HidConsts.INSTANCE.getUseJoystickEnabled() && !HidConsts.INSTANCE.getUseKeyboardEnabled() && !HidConsts.INSTANCE.getUseMouseEnabled()) {
                    bluetoothAdapter.setName("Wireless Controller");
                } else {
                    bluetoothAdapter.setName("USBtoBLHid");
                }
            } catch (SecurityException e) {
                // Ignore if no permission
            }
            bluetoothAdapter.getProfileProxy(context, mProfileServiceListener, BluetoothProfile.HID_DEVICE);
        }
    }

    public static boolean isConnected() {
        return HidUtils._connected;
    }

    private static void isConnected(boolean _connected) {
        HidUtils._connected = _connected;
    }

    public static BluetoothProfile.ServiceListener mProfileServiceListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceDisconnected(int profile) {
            Log.e(TAG, "hid onServiceDisconnected");
            if (profile == BluetoothProfile.HID_DEVICE) {
                mHidDevice.unregisterApp();
            }
        }

        @SuppressLint("NewApi")
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            Log.e(TAG, "hid onServiceConnected");
            bluetoothProfile = proxy;
            if (profile == BluetoothProfile.HID_DEVICE) {
                mHidDevice = (BluetoothHidDevice) proxy;
                HidConsts.HidDevice = mHidDevice;
                byte subclass = getSubclass();
                BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(HidConsts.NAME, HidConsts.DESCRIPTION, HidConsts.PROVIDER, subclass, HidConsts.Descriptor);
                mHidDevice.registerApp(sdp, null, null, Executors.newCachedThreadPool(), mCallback);
            }
        }
    };
    public static final BluetoothHidDevice.Callback mCallback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
            Log.e(TAG, "onAppStatusChanged: " + registered);
            isRegister = registered;
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            Log.e(TAG, "onConnectionStateChanged:" + state);
            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                HidUtils.isConnected(false);
                if (connectionStateChangeListener != null) {
                    connectionStateChangeListener.onDisConnected();
                    mDevice = null;
                }
            } else if (state == BluetoothProfile.STATE_CONNECTED) {
                HidUtils.isConnected(true);
                mDevice = device;
                if (connectionStateChangeListener != null) {
                    connectionStateChangeListener.onConnected();
                }
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                if (connectionStateChangeListener != null) {
                    connectionStateChangeListener.onConnecting();
                }
            }
        }
    };
}
