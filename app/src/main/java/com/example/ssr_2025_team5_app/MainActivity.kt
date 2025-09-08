package com.example.ssr_2025_team5_app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.content.Intent
import android.widget.Button
import android.view.View
import androidx.core.content.ContextCompat
import com.example.ssr_2025_team5_app.R

import com.example.ssr_2025_team5_app.BleHandler

class MainActivity : AppCompatActivity(), BleHandler.BleConnectionCallback {

    private lateinit var sensorHandler: SensorHandler
    private lateinit var bleHandler: BleHandler
    private lateinit var bleStatusIndicator: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        bleStatusIndicator = findViewById(R.id.bleStatusIndicator)

        // センサーとBLEハンドラを初期化
        sensorHandler = SensorHandler(this)
        bleHandler = BleHandler.getInstance(this)
        bleHandler.setConnectionCallback(this)

        // BLEスキャンを開始
        bleHandler.startScan()
        Log.d("Bluetooth", "BLE Scan Started")

        // センサーの更新をBLEに送信
        sensorHandler.setOnSensorDataListener { roll, pitch, azimuth, px, py, pz ->
            bleHandler.sendToFData(roll, pitch, azimuth)
            Log.d(
              "IMU",
             "Roll=$roll, Pitch=$pitch, Azimuth=$azimuth"
            )
        }

        //Bluetooth接続ボタンが押された時の処理
        val btnConnect = findViewById<Button>(R.id.buttonBeginAdvertisement)
        btnConnect.setOnClickListener {
            bleHandler.startScan()
            Log.d("Bluetooth", "BLE Scan Started")
        }

        //LineTraceボタンが押された時の処理
        val btnLineTrace = findViewById<Button>(R.id.buttonLineTrace)
        btnLineTrace.setOnClickListener {
            val intent = Intent(this, LineTrace::class.java)
            startActivity(intent)
        }
    }

    override fun onConnectionStateChanged(isConnected: Boolean) {
        if (isConnected) {
            bleStatusIndicator.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        } else {
            bleStatusIndicator.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorHandler.cleanup()
        bleHandler.cleanup()
        bleHandler.quitSafety()
    }
}

private fun View.setBackgroundColor(color: Any) {}
