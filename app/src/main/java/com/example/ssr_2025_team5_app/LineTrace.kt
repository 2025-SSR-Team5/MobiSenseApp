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
    private var lineDataListener: ((Float, Float) -> Unit)? = null
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
            listOf(imageReader!!.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    request.addTarget(imageReader!!.surface)

                    session.setRepeatingRequest(request.build(), null, null)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, null
        )
    }
    fun processFrame(image: android.media.Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val yMat = Mat(height, width, CvType.CV_8UC1) // Y成分を抽出

        //グレースケール変換
        if (pixelStride == 1 && rowStride == width) {
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            yMat.put(0, 0, bytes)
        } else {
            val bytes = ByteArray(width)
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(bytes, 0, width)
                yMat.put(row, 0, bytes)
            }
        }

        //2値化
        val binaryMat = Mat()
        Imgproc.threshold(yMat, binaryMat, 200.0, 255.0, Imgproc.THRESH_BINARY_INV)

        val centerRowIndex = binaryMat.rows() / 2
        val rowBytes = ByteArray(width)
        binaryMat.get(centerRowIndex, 0, rowBytes)

        val rowFloats = FloatArray(width) { i -> (rowBytes[i].toInt() and 0xFF).toFloat() }

        val dRow = FloatArray(width)
        for (i in 1 until width - 1) {
            dRow[i] = (rowFloats[i + 1] - rowFloats[i - 1]) / 2f
        }

        dRow[0] = rowFloats[1] - rowFloats[0]
        dRow[width - 1] = rowFloats[width - 1] - rowFloats[width - 2]

        val rowMax = dRow.indices.maxByOrNull { dRow[it] } ?: -1
        val rowMin = dRow.indices.minByOrNull { dRow[it] } ?: -1
        val centerLine = (rowMax + rowMin) / 2.0

        val colorMat = Mat()
        Imgproc.cvtColor(yMat, colorMat, Imgproc.COLOR_GRAY2BGR)

        val lineX = centerLine.toInt()

        Imgproc.line(
            colorMat,
            Point(lineX.toDouble(), 0.0),
            Point(lineX.toDouble(), colorMat.rows().toDouble()),
            Scalar(0.0, 0.0, 255.0), 2
        )

        Core.rotate(colorMat, colorMat, Core.ROTATE_90_CLOCKWISE)

        val bmp = Bitmap.createBitmap(colorMat.cols(), colorMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(colorMat, bmp)

        val bleHandler = BleHandler.getInstance(this)
        bleHandler.sendLineData(centerLine.toFloat(), width.toFloat())


        runOnUiThread {
            val canvas = textureView.lockCanvas()
            canvas?.let {
                val destRect = Rect(0, 0, width, height)
                it.drawBitmap(bmp, null, destRect, null)
                textureView.unlockCanvasAndPost(it)
            }
            tvLineStatus.text = if(centerLine >= width/2) "右：${centerLine} ${width}" else "左：${centerLine} ${width}"
        }

        binaryMat.release()
        colorMat.release()
    }

    fun setOnLineDataListener(listener: (centerLine: Float, width: Float) -> Unit) {
        this.lineDataListener = listener
    }
}