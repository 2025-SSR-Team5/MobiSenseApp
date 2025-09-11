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
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

class LineTrace : AppCompatActivity() {
    private var lineDataListener: ((Float, Float) -> Unit)? = null
    private lateinit var textureView: TextureView
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private lateinit var tvLineStatus: TextView
    private var thresholdValue = 200.0
    private var prevCenterLine: DoubleArray = DoubleArray(0)
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

        val mask = BooleanArray(n) { true }
        val threshold = 30.0

        if(y[0] != 0.0) mask[0] = true else mask[0] = false
        if(y[1] != 0.0) mask[1] = true else mask[1] = false

        for (j in 2 until n-1) {
            val ua = y[j] - y[j+1]
            val ub = y[j] - y[j-1]
            if (y[j] == 0.0 || (ua * ub > 0 && (abs(ua) > threshold && abs(ub) > threshold))){
                mask[j] = false
            }
        }

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
                val rmse = Math.sqrt(e.elementPower(2.0).elementSum() / n)
                if(70.0 < rmse  && rmse < 100.0) isCenterLineTrue = true
                Log.d("FitCheck", "RMSE = $rmse")
                val eData = e.ddrm.data
                val h = 0.5
                val variance = h * calculateVariance(eData)
                if (variance < 1e-9) {
                    break
                }
                val sdiag = SimpleMatrix.diag(
                    *DoubleArray(n) { j ->
                        if (!mask[j]) 0.0 else exp(-eData[j].pow(2.0) / variance)
                    }
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

    private fun autoROI(
        binaryMat: Mat,
        prevCenterLine: DoubleArray,
        stepper: Int = 15,
        margin: Int = 20
    ): List<Pair<Int, Int>> {
        val width = binaryMat.cols()
        val height = binaryMat.rows()
        val roiList = mutableListOf<Pair<Int, Int>>()

        // 行数（centerLine の要素数）を確定
        val rows = (height - 100) / stepper + 1
        if (rows <= 0) return roiList

        for (rowIdx in 0 until rows) {
            val y = rowIdx * stepper
            var xStart: Int
            var xEnd: Int

            if (!prevCenterLine.isNotEmpty() || rowIdx > prevCenterLine.size) {
                xStart = 0
                xEnd = width - 1
            } else {
                val prevX = prevCenterLine[rowIdx]
                xStart = ((prevX - margin).coerceAtLeast(0.0)).toInt()
                xEnd = ((prevX + margin).coerceAtMost(width - 1.0)).toInt()

                // ROI が狭すぎたら少し広げる（安全策）
                if (xEnd - xStart < 6) {
                    val half = 6
                    val s = (prevX - half).toInt().coerceAtLeast(0)
                    val e = (prevX + half).toInt().coerceAtMost(width - 1)
                    xStart = s
                    xEnd = e
                }
            }

            roiList.add(Pair(xStart, xEnd))
        }

        return roiList
    }


    private val margin = 50
    private var isCenterLineTrue = false

    fun processFrame(image: android.media.Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val yMat = Mat(height, width, CvType.CV_8UC1)

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

        val targetSize = Size(640.0, 360.0)
        Imgproc.resize(yMat, yMat, targetSize)

        //Imgproc.equalizeHist(yMat, yMat)

        val binaryMat = Mat()
        Imgproc.threshold(yMat, binaryMat, thresholdValue, 255.0, Imgproc.THRESH_BINARY_INV)

        val stepper = 10

        val roiList = autoROI(binaryMat, prevCenterLine, stepper, margin)

// centerLine と heightArray を roiList.size に揃える
        val centerLine = DoubleArray(roiList.size)
        val heightArray = DoubleArray(roiList.size)

        val colorMat = Mat()
        Imgproc.cvtColor(yMat, colorMat, Imgproc.COLOR_GRAY2BGR)

        var idx = 0
        for ((xStart, xEnd) in roiList) {
            val y = idx * stepper
            heightArray[idx] = y.toDouble()

            val rowBytes = ByteArray(width)
            binaryMat.get(y, 0, rowBytes)
            val rowFloats = FloatArray(width) { i -> (rowBytes[i].toInt() and 0xFF).toFloat() }

            val dRow = FloatArray(width)
            for (i in 1 until width - 1) {
                dRow[i] = (rowFloats[i + 1] - rowFloats[i - 1]) / 2f
            }
            dRow[0] = rowFloats[1] - rowFloats[0]
            dRow[width - 1] = rowFloats[width - 1] - rowFloats[width - 2]

            // --- ROI内で極値探索 ---
            val roiRange = xStart..xEnd
            val rowMax = roiRange.maxByOrNull { dRow[it] } ?: -1
            val rowMin = roiRange.minByOrNull { dRow[it] } ?: -1

            Imgproc.line(colorMat, Point(xStart.toDouble(),0.0), Point(xStart.toDouble(), height.toDouble()), Scalar(0.0, 0.0, 255.0), 2)
            Imgproc.line(colorMat, Point(xEnd.toDouble(),0.0), Point(xEnd.toDouble(), height.toDouble()), Scalar(0.0, 0.0, 255.0), 2)

            if (rowMax > 0 && rowMin > 0 && rowMax > rowMin) {
                centerLine[idx] = (rowMax + rowMin) / 2.0
            } else {
                centerLine[idx] = 0.0
            }

            idx++
        }

        // --- 5) prevCenterLine を更新（フレーム間で行ごとの値を持ち越す） ---
        prevCenterLine = centerLine.copyOf()
        isCenterLineTrue = centerLine.any { it > 0.0 }

        //val heightArray = (0 until height - 100 step stepper).map { it.toDouble() }.toDoubleArray()
        val coefficients = robust_regression(heightArray, centerLine, 1)

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

        runOnUiThread {
            val canvas = textureView.lockCanvas()
            canvas?.let {
                val destRect = Rect(0, 0, width, height)
                it.drawBitmap(bmp, null, destRect, null)
                textureView.unlockCanvasAndPost(it)
            }
            tvLineStatus.text = "a: $a, b: $b idx:$idx isCenterLine: $isCenterLineTrue"
        }

        binaryMat.release()
        colorMat.release()
    }

    fun setOnLineDataListener(listener: (centerLine: Float, width: Float) -> Unit) {
        this.lineDataListener = listener
    }
}