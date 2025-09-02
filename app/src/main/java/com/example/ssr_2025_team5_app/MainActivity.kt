package com.example.ssr_2025_team5_app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.content.Intent
import android.widget.Button

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

        //LineTraceボタンが押された時の処理
        val btn = findViewById<Button>(R.id.buttonLineTrace)
        btn.setOnClickListener {
            val intent = Intent(this, LineTrace::class.java)
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorHandler.cleanup()
        bleHandler.cleanup()
    }
}