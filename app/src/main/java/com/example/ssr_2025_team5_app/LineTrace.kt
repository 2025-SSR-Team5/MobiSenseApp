package com.example.ssr_2025_team5_app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.media.ImageReader
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.TextView
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import android.hardware.camera2.*

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.CvType
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.imgproc.Imgproc
import org.opencv.android.OpenCVLoader

import android.graphics.Bitmap

import android.graphics.Color
import android.graphics.Rect


class LineTrace : AppCompatActivity() {
    private lateinit var textureView: TextureView
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private lateinit var tvLineStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_line_trace)

        // OpenCV 初期化
        if (!OpenCVLoader.initDebug()) {
            Log.e("LineTrace", "OpenCV initialization failed")
        } else {
            Log.d("LineTrace", "OpenCV initialized successfully")
        }

        textureView = findViewById(R.id.textureView)
        tvLineStatus = findViewById(R.id.tvLineStatus)

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                openCamera()
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun openCamera() {
        Log.d("LineTrace", "Attempting to open camera...")
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                lensFacing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList[0]

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
                return
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d("LineTrace", "Camera opened successfully")
                    cameraDevice = camera
                    startPreview()
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, null)
        } catch (e: Exception) {
            Log.e("LineTrace", "Camera open error: ${e.message}")
        }
    }

    private fun startPreview() {
        val surfaceTexture = textureView.surfaceTexture ?: return
        surfaceTexture.setDefaultBufferSize(640, 480)
        val previewSurface = Surface(surfaceTexture)

        imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            processFrame(image)
            image.close()
        }, null)

        cameraDevice?.createCaptureSession(
            // listOf(previewSurface, imageReader!!.surface), // 修正前
            listOf(imageReader!!.surface), // 修正後: previewSurface を削除
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

                    // ▼▼▼ 修正点 2 ▼▼▼
                    // requestのターゲットも ImageReader の surface のみにします
                    // request.addTarget(previewSurface) // 修正後: この行を削除
                    request.addTarget(imageReader!!.surface)

                    session.setRepeatingRequest(request.build(), null, null)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, null
        )
    }


    private fun processFrame(image: android.media.Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        // rowStrideとwidthが同じ場合はそのまま使える
        // 違う場合は、1行ずつ正しいデータ幅(width)でコピーする必要がある
        val yMat = Mat(height, width, CvType.CV_8UC1)
        if (pixelStride == 1 && rowStride == width) {
            // パディングがないラッキーなケース
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            yMat.put(0, 0, bytes)
        } else {
            // パディングがある一般的なケース
            val bytes = ByteArray(width)
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(bytes, 0, width)
                yMat.put(row, 0, bytes)
            }
        }

        val binaryMat = Mat()
        Imgproc.threshold(yMat, binaryMat, 128.0, 255.0, Imgproc.THRESH_BINARY_INV)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binaryMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val colorMat = Mat()
        Imgproc.cvtColor(yMat, colorMat, Imgproc.COLOR_GRAY2BGR)

        for (cnt in contours) {
            val cnt2f = MatOfPoint2f(*cnt.toArray())
            val approx = MatOfPoint2f()
            val epsilon = 0.01 * Imgproc.arcLength(cnt2f, true)
            Imgproc.approxPolyDP(cnt2f, approx, epsilon, true)

            val points = MatOfPoint(*approx.toArray().map { Point(it.x, it.y) }.toTypedArray())
            Imgproc.polylines(colorMat, listOf(points), true, Scalar(0.0, 0.0, 255.0), 2)
        }

        val bmp = Bitmap.createBitmap(colorMat.cols(), colorMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(colorMat, bmp)

        runOnUiThread {
            val canvas = textureView.lockCanvas()
            canvas?.let {
                it.drawBitmap(bmp, 0f, 0f, null)
                textureView.unlockCanvasAndPost(it)
            }
            tvLineStatus.text = if (contours.isNotEmpty()) "ライン検出!" else "ラインなし"
        }

        colorMat.release()
        binaryMat.release()
        hierarchy.release()
    }
}