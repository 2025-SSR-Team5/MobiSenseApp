package com.example.ssr_2025_team5_app

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class BleHandler(private val context: Context) {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null

    var isConnected = false

    private var servicesReady = false
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val bleHandlerThread: HandlerThread = HandlerThread("BleHandlerThread").apply { start() }
    private val bleHandler: Handler = Handler(bleHandlerThread.looper)

    interface BleConnectionCallback {
        fun onConnectionStateChanged(isConnected: Boolean)
    }

    private var connectionCallback: BleConnectionCallback? = null

    fun setConnectionCallback(callback: BleConnectionCallback) {
        this.connectionCallback = callback
    }
    companion object {
        @Volatile
        private var INSTANCE: BleHandler? = null

        fun getInstance(context: Context): BleHandler {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BleHandler(context.applicationContext).also { INSTANCE = it }
            }
        }
        private val SERVICE_UUID = UUID.fromString("ab907856-3412-3412-3412-1234567890ab")
        private val CHAR_UUID    = UUID.fromString("ab907856-efcd-ab90-7856-34123412cdab")
        private const val TARGET_DEVICE_ADDRESS = "EC:E3:34:90:D8:EE"
        private const val SCAN_TIMEOUT = 15000L
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            if (result.device.address == TARGET_DEVICE_ADDRESS) {
                if (isScanning) {
                    Log.d("Bluetooth", "Found target device: ${result.device.address}")
                    bleHandler.post {
                        stopScan()
                        connectToDevice(result.device)
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e("Bluetooth", "BLE Scan Failed: $errorCode")
            isScanning = false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("Bluetooth", "Successfully connected to ${gatt?.device?.address}")
                    isConnected = true
                    bluetoothGatt = gatt
                    // 権限チェックを追加
                    if (checkPermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
                        bluetoothGatt?.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d("Bluetooth", "Disconnected from ${gatt?.device?.address}")
                    isConnected = false
                    cleanup()
                }
            } else {
                Log.e("Bluetooth", "onConnectionStateChange error. Status: $status, Device: ${gatt?.device?.address}")
                isConnected = false
                cleanup()
            }

            handler.post { // 既存のメインルーパーハンドラを使用
                connectionCallback?.onConnectionStateChanged(isConnected)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("Bluetooth", "Services discovered successfully.")
                servicesReady = true

                bleHandler.post {
                    sendPendingData()
                }
            } else {
                Log.w("Bluetooth", "onServicesDiscovered failed with status: $status")
            }
        }
    }

    fun startScan() {
        if (isScanning) return
        if (isConnected){
            Log.d("Bluetooth", "Already connected")

            Toast.makeText(context, "既に接続されています", Toast.LENGTH_SHORT).show()
            return
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e("Bluetooth", "Bluetooth is not available or off")
            return
        }

        if (!checkPermissions(Manifest.permission.BLUETOOTH_SCAN)) {
            Log.e("Bluetooth", "BLE permissions missing for scan")

            Toast.makeText(context, "BLEスキャン権限がありません", Toast.LENGTH_SHORT).show()
            return
        }

        val scanner = bluetoothAdapter!!.bluetoothLeScanner
        if (scanner == null) {
            Log.e("Bluetooth", "BLE Scanner unavailable")
            return
        }

        handler.postDelayed({
            if (isScanning) {
                Log.w("Bluetooth", "Scan timed out. Target device not found.")
                Toast.makeText(context, "ターゲットデバイスが見つかりません", Toast.LENGTH_SHORT).show()
                stopScan()
            }
        }, SCAN_TIMEOUT)

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        isScanning = true
        scanner.startScan(null, settings, scanCallback)
        Log.d("Bluetooth", "Starting BLE scan for $TARGET_DEVICE_ADDRESS")
    }
    fun sendToFData(roll: Float, pitch: Float, azimuth: Float) {
        bleHandler.post {
            if (!servicesReady || !checkPermissions(Manifest.permission.BLUETOOTH_CONNECT)) return@post

            val gatt = bluetoothGatt ?: return@post
            val service = gatt.getService(SERVICE_UUID) ?: return@post
            val characteristic = service.getCharacteristic(CHAR_UUID) ?: return@post

            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

            val buffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putFloat(roll).putFloat(pitch).putFloat(azimuth)
            characteristic.value = buffer.array()
            gatt.writeCharacteristic(characteristic)
        }
    }

    private val pendingLineData = mutableListOf<Pair<Float, Float>>()
    fun sendLineData(theta: Float, linePos: Float) {
        bleHandler.post {
            if (!checkPermissions(Manifest.permission.BLUETOOTH_CONNECT)) return@post

            if (!servicesReady) {
                pendingLineData.add(Pair(theta, linePos))
                return@post
            }

            val gatt = bluetoothGatt ?: return@post
            val service = gatt.getService(SERVICE_UUID) ?: return@post
            val characteristic = service.getCharacteristic(CHAR_UUID) ?: return@post

            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

            val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putFloat(theta).putFloat(linePos)
            characteristic.value = buffer.array()
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun sendPendingData() {
        if (pendingLineData.isNotEmpty()) {
            Log.d("Bluetooth", "Sending ${pendingLineData.size} pending data items.")
            pendingLineData.forEach { (centerLine, width) ->
                // 再帰的に呼び出すことで、servicesReadyのチェックを再利用する
                sendLineData(centerLine, width)
            }
            pendingLineData.clear()
        }
    }

    fun cleanup() {
        bleHandler.post {
            if (!checkPermissions(Manifest.permission.BLUETOOTH_CONNECT)) return@post
            bluetoothGatt?.close()
            bluetoothGatt = null
            servicesReady = false
        }
        //bleHandlerThread.quitSafely()
    }

    fun quitSafety() {
        bleHandlerThread.quitSafely()
    }

    private fun stopScan() {
        if (checkPermissions(Manifest.permission.BLUETOOTH_SCAN)) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
        isScanning = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (checkPermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.d("Bluetooth", "Connecting to ${device.address}")
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    private fun checkPermissions(vararg permissions: String): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestPermissions(activity: Activity, requestCode: Int) {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, missing.toTypedArray(), requestCode)
        }
    }
}