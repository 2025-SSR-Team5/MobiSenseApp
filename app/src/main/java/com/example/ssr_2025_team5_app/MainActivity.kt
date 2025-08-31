package com.example.ssr_2025_team5_app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.util.Log

class MainActivity : AppCompatActivity() {

    private lateinit var sensorHandler: SensorHandler
    private lateinit var bleHandler: BleHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // センサーとBLEハンドラを初期化
        sensorHandler = SensorHandler(this)
        bleHandler = BleHandler(this)

        // BLEスキャンを開始
        bleHandler.startScan()
        Log.d("Bluetooth", "BLE Scan Started")

        // センサーの更新をBLEに送信
        sensorHandler.setOnSensorDataListener { roll, pitch, azimuth, px, py, pz ->
            bleHandler.sendSensorData(roll, pitch, azimuth)
            Log.d(
              "IMU",
             "Roll=$roll, Pitch=$pitch, Azimuth=$azimuth"
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorHandler.cleanup()
        bleHandler.cleanup()
    }
}