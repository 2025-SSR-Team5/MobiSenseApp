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
import android.widget.SeekBar
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
import org.opencv.core.Size
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import org.ejml.simple.SimpleMatrix
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.PI

class LineTrace : AppCompatActivity() {
    private var lineDataListener: ((Float, Float) -> Unit)? = null
    private lateinit var textureView: TextureView
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private lateinit var tvLineStatus: TextView
    private var thresholdValue = 200.0
    private lateinit var thresholdSeekBar: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_line_trace)

        // OpenCV 初期化
        if (!OpenCVLoader.initDebug()) {
            Log.e("LineTrace", "OpenCV initialization failed")
        } else {
            Log.d("LineTrace", "OpenCV initialized successfully")
        }

        thresholdSeekBar = findViewById(R.id.thresholdSeekBar)
        thresholdSeekBar.progress = thresholdValue.toInt()
        thresholdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                thresholdValue = progress.toDouble()
                tvLineStatus.text = "閾値: $thresholdValue"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

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

    private fun robust_regression(
        x: DoubleArray,
        y: DoubleArray,
        d: Int,
        iterations: Int = 20
    ): DoubleArray {
        val n = x.size
        if (n != y.size || n <= d) return DoubleArray(0)

        val x_ = SimpleMatrix(d + 1, n)
        for (i in 0..d) {
            for (j in 0 until n) {
                x_.set(i, j, x[j].pow(d - i))
            }
        }

        var coef = SimpleMatrix(d + 1, 1)
        val yVec = SimpleMatrix(n, 1, true, *y)

        for (i in 0 until iterations) {
            if (i == 0) {
                val xl = x_.mult(x_.transpose())
                val b = x_.mult(yVec)
                coef = xl.pseudoInverse().mult(b)
            } else {
                val yPred = coef.transpose().mult(x_).transpose()
                val e = yVec.minus(yPred)
                val eData = e.ddrm.data
                val h = 0.3
                val variance = h * calculateVariance(eData)
                if (variance < 1e-9) break
                val sdiag = SimpleMatrix.diag(
                    *DoubleArray(n) { j -> exp(-eData[j].pow(2.0) / variance) }
                )
                val xl = x_.mult(sdiag).mult(x_.transpose())
                val yl = x_.mult(sdiag).mult(yVec)
                coef = xl.pseudoInverse().mult(yl)
            }
        }

        return coef.ddrm.data
    }

    private fun calculateVariance(data: DoubleArray): Double {
        if (data.isEmpty()) return 0.0
        val mean = data.average()
        return data.sumOf { (it - mean).pow(2) } / data.size
    }

    fun processFrame(image: android.media.Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val yMat = Mat(height, width, CvType.CV_8UC1)
        val targetSize = Size(640.0, 360.0)
        Imgproc.resize(yMat, yMat, targetSize)

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

        //Imgproc.equalizeHist(yMat, yMat)
        val clahe = Imgproc.createCLAHE(3.0, Size(4.0, 4.0))//CLAHE
        clahe.apply(yMat, yMat)

        Imgproc.GaussianBlur(yMat, yMat, Size(7.0, 7.0), 0.0)//ガウシアンフィルタ

        val binaryMat = Mat()
        Imgproc.threshold(yMat, binaryMat, thresholdValue, 255.0, Imgproc.THRESH_BINARY_INV)

        val stepper = 15
        val centerLine = DoubleArray((height - 100) / stepper + 1)
        var idx = 0
        for (y in 0 until height - 100 step stepper) {
            val rowBytes = ByteArray(width)
            binaryMat.get(y, 0, rowBytes)
            val rowFloats = FloatArray(width) { i -> (rowBytes[i].toInt() and 0xFF).toFloat() }

            val dRow = FloatArray(width)
            for (i in 1 until width - 1) {
                dRow[i] = (rowFloats[i + 1] - rowFloats[i - 1]) / 2f
            }
            dRow[0] = rowFloats[1] - rowFloats[0]
            dRow[width - 1] = rowFloats[width - 1] - rowFloats[width - 2]

            val rowMax = dRow.indices.maxByOrNull { dRow[it] } ?: -1
            val rowMin = dRow.indices.minByOrNull { dRow[it] } ?: -1
            centerLine[idx] = (rowMax + rowMin) / 2.0
            idx++
        }

        val heightArray = (0 until height - 100 step stepper).map { it.toDouble() }.toDoubleArray()
        val coefficients = robust_regression(heightArray, centerLine, 1)

        val colorMat = Mat()
        Imgproc.cvtColor(binaryMat, colorMat, Imgproc.COLOR_GRAY2BGR)

        val a = coefficients[0]
        val b = coefficients[1]
        val y1 = 0.0
        val x1 = a * y1 + b
        val y2 = height.toDouble()
        val x2 = a * y2 + b

        val startPoint = Point(x1, y1)
        val endPoint = Point(x2, y2)

        for (i in centerLine.indices) {
            val x = centerLine[i]
            val y = heightArray[i]
            Imgproc.circle(colorMat, Point(x, y), 3, Scalar(0.0, 255.0, 0.0), -1)
        }

        Imgproc.line(colorMat, startPoint, endPoint, Scalar(0.0, 0.0, 255.0), 2)
        Core.rotate(colorMat, colorMat, Core.ROTATE_90_CLOCKWISE)

        val bmp = Bitmap.createBitmap(colorMat.cols(), colorMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(colorMat, bmp)

        val bleHandler = BleHandler.getInstance(this)
        // bleHandler.sendLineData(centerLine.toFloat(), width.toFloat())
        val theta = atan(a) *180.0/PI
        val linePos = a * height/2 + b
        bleHandler.sendLineData(theta.toFloat(), linePos.toFloat())

        runOnUiThread {
            val canvas = textureView.lockCanvas()
            canvas?.let {
                val destRect = Rect(0, 0, width, height)
                it.drawBitmap(bmp, null, destRect, null)
                textureView.unlockCanvasAndPost(it)
            }
            tvLineStatus.text = "Θ：${theta}  linePos：${linePos}"
        }

        binaryMat.release()
        colorMat.release()
    }

    fun setOnLineDataListener(listener: (theta: Float, linePos: Float) -> Unit) {
        this.lineDataListener = listener
    }
}