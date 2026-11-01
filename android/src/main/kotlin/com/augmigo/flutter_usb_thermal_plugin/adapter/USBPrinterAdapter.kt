package com.augmigo.flutter_usb_thermal_plugin.adapter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.util.Base64
import android.util.Log
import android.widget.Toast
import java.nio.charset.Charset
import java.util.*


class USBPrinterAdapter {

    private var mInstance: USBPrinterAdapter? = null
    private val LOG_TAG = "Flutter USB Printer"
    private var mContext: Context? = null
    private var mUSBManager: UsbManager? = null
    private var mPermissionIntent: PendingIntent? = null
    private var mUsbDevice: UsbDevice? = null
    private var mUsbDeviceConnection: UsbDeviceConnection? = null
    private var mUsbInterface: UsbInterface? = null
    private var mEndPoint: UsbEndpoint? = null

    private val ACTION_USB_PERMISSION = "com.augmigo.flutter_usb_thermal_plugin.USB_PERMISSION"

    // LOCK OBJECT to prevent close() from interrupting print()
    private val printLock = Any()

    fun getInstance(): USBPrinterAdapter? {
        if (mInstance == null) {
            mInstance = this;
        }
        return mInstance
    }

    private val mUsbDeviceReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            var action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized(this) {
                    val usbDevice =
                        intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.i(LOG_TAG, "Success to grant permission for device " + usbDevice!!.deviceId + ", vendor_id: " + usbDevice.vendorId + " product_id: " + usbDevice.productId)
                        mUsbDevice = usbDevice
                    } else {
                        Toast.makeText(context, "User refused to give USB device permissions" + usbDevice!!.deviceName, Toast.LENGTH_LONG).show()
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                if (mUsbDevice != null) {
                    Toast.makeText(context, "USB device has been turned off", Toast.LENGTH_LONG).show()
                    closeConnectionIfExists()
                }
            }
        }
    }

    fun init(reactContext: Context?) {
        mContext = reactContext
        mUSBManager = mContext!!.getSystemService(Context.USB_SERVICE) as UsbManager
        
        val permissionIntent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(mContext!!.packageName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mPermissionIntent = PendingIntent.getBroadcast(mContext, 0, permissionIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        } else {
            mPermissionIntent = PendingIntent.getBroadcast(mContext, 0, permissionIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        }
        
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mContext!!.registerReceiver(mUsbDeviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            mContext!!.registerReceiver(mUsbDeviceReceiver, filter)
        }
        Log.v(LOG_TAG, "USB Printer initialized")
    }

    fun closeConnectionIfExists() {
        // SYNCHRONIZED: Waits for print job to finish before closing
        synchronized(printLock) {
            if (mUsbDeviceConnection != null) {
                mUsbDeviceConnection!!.releaseInterface(mUsbInterface)
                mUsbDeviceConnection!!.close()
                mUsbInterface = null
                mEndPoint = null
                mUsbDeviceConnection = null
            }
        }
    }

    fun getDeviceList(): List<UsbDevice> {
        if (mUSBManager == null) return emptyList()
        return ArrayList(mUSBManager!!.deviceList.values)
    }

    fun selectDevice(vendorId: Int, productId: Int): Boolean {
        if (mUsbDevice == null || mUsbDevice!!.vendorId != vendorId || mUsbDevice!!.productId != productId) {
            closeConnectionIfExists()
            val usbDevices = getDeviceList()
            for (usbDevice in usbDevices) {
                if (usbDevice.vendorId == vendorId && usbDevice.productId == productId) {
                    closeConnectionIfExists()
                    if (mPermissionIntent == null && mContext != null) {
                        val permissionIntent = Intent(ACTION_USB_PERMISSION).apply { setPackage(mContext!!.packageName) }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            mPermissionIntent = PendingIntent.getBroadcast(mContext, 0, permissionIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                        } else {
                            mPermissionIntent = PendingIntent.getBroadcast(mContext, 0, permissionIntent, PendingIntent.FLAG_UPDATE_CURRENT)
                        }
                    }
                    mUSBManager!!.requestPermission(usbDevice, mPermissionIntent)
                    return true
                }
            }
            return false
        }
        return true
    }

    private fun openConnection(): Boolean {
        synchronized(printLock) {
            if (mUsbDevice == null) return false
            if (mUSBManager == null) return false
            if (mUsbDeviceConnection != null) return true
            
            val usbInterface = mUsbDevice!!.getInterface(0)
            for (i in 0 until usbInterface.endpointCount) {
                val ep = usbInterface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) {
                    val usbDeviceConnection = mUSBManager!!.openDevice(mUsbDevice)
                    if (usbDeviceConnection == null) return false
                    return if (usbDeviceConnection.claimInterface(usbInterface, true)) {
                        mEndPoint = ep
                        mUsbInterface = usbInterface
                        mUsbDeviceConnection = usbDeviceConnection
                        true
                    } else {
                        usbDeviceConnection.close()
                        false
                    }
                }
            }
            return false
        }
    }

    fun printText(text: String): Boolean {
        val isConnected = openConnection()
        return if (isConnected) {
            Thread {
                val bytes = text.toByteArray(Charset.forName("UTF-8"))
                sendBytesSafe(bytes)
            }.start()
            true
        } else {
            false
        }
    }

    fun printRawText(data: String): Boolean {
        val isConnected = openConnection()
        return if (isConnected) {
            Thread {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                sendBytesSafe(bytes)
            }.start()
            true
        } else {
            false
        }
    }

    fun write(bytes: ByteArray): Boolean {
        val isConnected = openConnection()
        return if (isConnected) {
            Thread {
                sendBytesSafe(bytes)
            }.start()
            true
        } else {
            false
        }
    }

    private fun sendBytesSafe(byteData: ByteArray) {
        synchronized(printLock) {
            if (mEndPoint == null || mUsbDeviceConnection == null) return
            
            // 1. SEND ESC @ (INITIALIZE PRINTER) TO CLEAR ANY ERRORS
            val initCmd = byteArrayOf(0x1B, 0x40)
            mUsbDeviceConnection!!.bulkTransfer(mEndPoint, initCmd, initCmd.size, 100000)
            
            // 2. SEND ALL DATA AT ONCE
            val b = mUsbDeviceConnection!!.bulkTransfer(mEndPoint, byteData, byteData.size, 100000)
            Log.i(LOG_TAG, "Return Status: $b")
        }
    }
}
