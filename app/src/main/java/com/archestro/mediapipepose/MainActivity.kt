package com.archestro.mediapipepose

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.mediapipe.components.CameraHelper.CameraFacing
import com.google.mediapipe.components.CameraHelper.OnCameraStartedListener
import com.google.mediapipe.components.CameraXPreviewHelper
import com.google.mediapipe.components.ExternalTextureConverter
import com.google.mediapipe.components.FrameProcessor
import com.google.mediapipe.components.PermissionHelper
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList
import com.google.mediapipe.framework.AndroidAssetUtil
import com.google.mediapipe.framework.Packet
import com.google.mediapipe.framework.PacketCallback
import com.google.mediapipe.framework.PacketGetter
import com.google.mediapipe.glutil.EglManager
import com.google.protobuf.InvalidProtocolBufferException
import net.majorkernelpanic.streaming.SessionBuilder
import net.majorkernelpanic.streaming.gl.SurfaceView
import net.majorkernelpanic.streaming.rtsp.RtspServer
import pub.devrel.easypermissions.AppSettingsDialog
import pub.devrel.easypermissions.EasyPermissions


class MainActivity : AppCompatActivity(),
    EasyPermissions.PermissionCallbacks {



    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            // Do something if permission granted
            if (isGranted) {
                startCamera()
            }else{
                getPermission()
            }
        }


    private val TAG = "MainActivity"
    private val BINARY_GRAPH_NAME = "pose_tracking_gpu.binarypb"
    private val INPUT_VIDEO_STREAM_NAME = "input_video"
    private val OUTPUT_VIDEO_STREAM_NAME = "output_video"
    private val OUTPUT_LANDMARKS_STREAM_NAME = "pose_landmarks"
    private val NUM_HANDS = 2
    private var cameraFacing = CameraFacing.BACK
    // Flips the camera-preview frames vertically before sending them into FrameProcessor to be
    // processed in a MediaPipe graph, and flips the processed frames back when they are displayed.
    // This is needed because OpenGL represents images assuming the image origin is at the bottom-left
    // corner, whereas MediaPipe in general assumes the image origin is at top-left.
    private val FLIP_FRAMES_VERTICALLY = true

    companion object {
        init {
            // Load all native libraries needed by the app.
            System.loadLibrary("mediapipe_jni")
            System.loadLibrary("opencv_java3")
        }
    }

    // {@link SurfaceTexture} where the camera-preview frames can be accessed.
    private var previewFrameTexture: SurfaceTexture? = null

    // {@link SurfaceView} that displays the camera-preview frames processed by a MediaPipe graph.
    private lateinit var previewDisplayView: SurfaceView

    // Creates and manages an {@link EGLContext}.
    private var eglManager: EglManager? = null

    // Sends camera-preview frames into a MediaPipe graph for processing, and displays the processed
    // frames onto a {@link Surface}.
    private var processor: FrameProcessor? = null

    // Converts the GL_TEXTURE_EXTERNAL_OES texture from Android camera into a regular texture to be
    // consumed by {@link FrameProcessor} and the underlying MediaPipe graph.
    private var converter: ExternalTextureConverter? = null

    // Handles camera access via the {@link CameraX} Jetpack support library.
    private var cameraHelper: CameraXPreviewHelper? = null

    private lateinit var switchButton:FloatingActionButton

    private lateinit var textView:TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        switchButton=findViewById(R.id.switch_camera)
        textView=findViewById(R.id.no_camera_access_view)

       switchButton.setOnClickListener {
           cameraFacing = if (cameraFacing==CameraFacing.BACK){
               CameraFacing.FRONT
           }else {
               CameraFacing.BACK
           }
           converter!!.close()

           // Hide preview display until we re-open the camera again.
           previewDisplayView.visibility = View.GONE
           converter = ExternalTextureConverter(
               eglManager!!.context, 2
           )
           converter?.setFlipY(FLIP_FRAMES_VERTICALLY)
           converter?.setConsumer(processor)
           startCamera()
       }

        //check if camera permission is granted
        checkPermissions()



        previewDisplayView = findViewById(R.id.surface)
        setupPreviewDisplayView()

        // Initialize asset manager so that MediaPipe native libraries can access the app assets, e.g.,
        // binary graphs.
        AndroidAssetUtil.initializeNativeAssetManager(this)
        eglManager = EglManager(null)
        processor = eglManager?.getNativeContext()?.let {
            FrameProcessor(
                this,
                it,
                BINARY_GRAPH_NAME,
                INPUT_VIDEO_STREAM_NAME,
                OUTPUT_VIDEO_STREAM_NAME
            )
        }
        processor
            ?.videoSurfaceOutput
            ?.setFlipY(FLIP_FRAMES_VERTICALLY)

        processor?.addPacketCallback(
            OUTPUT_LANDMARKS_STREAM_NAME,
            PacketCallback { packet: Packet ->
                Log.v(TAG, "Received Pose landmarks packet.")
                try {
//                        NormalizedLandmarkList poseLandmarks = PacketGetter.getProto(packet, NormalizedLandmarkList.class);
                    val landmarksRaw = PacketGetter.getProtoBytes(packet)
                    val poseLandmarks =
                        NormalizedLandmarkList.parseFrom(landmarksRaw)
                    Log.v(
                        TAG,
                        "[TS:" + packet.timestamp + "] " + getPoseLandmarksDebugString(
                            poseLandmarks
                        )
                    )
                } catch (exception: InvalidProtocolBufferException) {
                    Log.e(TAG, "failed to get proto.", exception)
                }
            }
        )
        SessionBuilder.getInstance()
            .setSurfaceView(previewDisplayView)
            .setPreviewOrientation(90)
            .setContext(applicationContext)
            .setAudioEncoder(SessionBuilder.AUDIO_NONE).videoEncoder = SessionBuilder.VIDEO_H264

        // Starts the RTSP server

        // Starts the RTSP server
        startService(Intent(this, RtspServer::class.java))
    }

    override fun onResume() {
        super.onResume()
        converter = ExternalTextureConverter(
            eglManager!!.context, 2
        )
        converter?.setFlipY(FLIP_FRAMES_VERTICALLY)
        converter?.setConsumer(processor)
        if (PermissionHelper.cameraPermissionsGranted(this)) {
            startCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        converter!!.close()

        // Hide preview display until we re-open the camera again.
        previewDisplayView.visibility = View.GONE
    }

    private fun onCameraStarted(surfaceTexture: SurfaceTexture) {
        previewFrameTexture = surfaceTexture

        // Make the display view visible to start showing the preview. This triggers the
        // SurfaceHolder.Callback added to (the holder of) previewDisplayView.
        previewDisplayView.visibility = View.VISIBLE
    }

     private fun cameraTargetResolution(): Size? {
        return null // No preference and let the camera (helper) decide.
    }

    private fun startCamera() {
        cameraHelper = CameraXPreviewHelper()
        cameraHelper?.setOnCameraStartedListener(
            OnCameraStartedListener { surfaceTexture: SurfaceTexture? -> onCameraStarted((surfaceTexture)!!) })
        cameraHelper?.startCamera(
            this, cameraFacing,  /*unusedSurfaceTexture=*/null, cameraTargetResolution()
        )
    }

    private fun computeViewSize(width: Int, height: Int): Size {
        return Size(width, height)
    }

    fun onPreviewDisplaySurfaceChanged(
        holder: SurfaceHolder?, format: Int, width: Int, height: Int
    ) {
        // (Re-)Compute the ideal size of the camera-preview display (the area that the
        // camera-preview frames get rendered onto, potentially with scaling and rotation)
        // based on the size of the SurfaceView that contains the display.
        val viewSize = computeViewSize(width, height)
        val displaySize = cameraHelper!!.computeDisplaySizeFromViewSize(viewSize)
        val isCameraRotated = cameraHelper!!.isCameraRotated

        //displaySize.getHeight(); cell phone display size
        //displaySize.getWidth();


        // Connect the converter to the camera-preview frames as its input (via
        // previewFrameTexture), and configure the output width and height as the computed
        // display size.
        converter!!.setSurfaceTextureAndAttachToGLContext(
            previewFrameTexture,
            if (isCameraRotated) displaySize.height else displaySize.width,
            if (isCameraRotated) displaySize.width else displaySize.height
        )
    }

    private fun setupPreviewDisplayView() {
        previewDisplayView.visibility = View.GONE
        val viewGroup = findViewById<ViewGroup>(R.id.preview_display_layout)
        viewGroup.addView(previewDisplayView)
        previewDisplayView
            .holder
            .addCallback(
                object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        processor!!.videoSurfaceOutput.setSurface(holder.surface)
                        Log.d("Surface", "Surface Created")
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {
                        onPreviewDisplaySurfaceChanged(holder, format, width, height)
                        // 이곳에서 width , height 가 720,1280
                        Log.d("Surface", "Surface Changed")
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        processor!!.videoSurfaceOutput.setSurface(null)
                        Log.d("Surface", "Surface destroy")
                    }
                })
    }

    //You can extract the landmark's coordinates from the code.
    //[0.0 , 1.0] by normalized coordinate -> image width, height
    private fun getPoseLandmarksDebugString(poseLandmarks: NormalizedLandmarkList): String? {
        val poseLandmarkStr = "Pose landmarks: " + poseLandmarks.landmarkCount + "\n"
        val poseMarkers = ArrayList<PoseLandMark>()
        var landmarkIndex = 0
        for (landmark: NormalizedLandmark in poseLandmarks.landmarkList) {
            val marker = PoseLandMark(landmark.x, landmark.y, landmark.visibility)
            //          poseLandmarkStr += "\tLandmark ["+ landmarkIndex+ "]: ("+ (landmark.getX()*720)+ ", "+ (landmark.getY()*1280)+ ", "+ landmark.getVisibility()+ ")\n";
            ++landmarkIndex
            poseMarkers.add(marker)
        }
        // Get Angle of Positions
        val rightAngle = getAngle(poseMarkers[16], poseMarkers[14], poseMarkers[12])
        val leftAngle = getAngle(poseMarkers[15], poseMarkers[13], poseMarkers[11])
        val rightKnee = getAngle(poseMarkers[24], poseMarkers[26], poseMarkers[28])
        val leftKnee = getAngle(poseMarkers[23], poseMarkers[25], poseMarkers[27])
        val rightShoulder = getAngle(poseMarkers[14], poseMarkers[12], poseMarkers[24])
        val leftShoulder = getAngle(poseMarkers[13], poseMarkers[11], poseMarkers[23])
        Log.v(
            TAG, "======Degree Of Position]======\n" +
                    "rightAngle :" + rightAngle + "\n" +
                    "leftAngle :" + leftAngle + "\n" +
                    "rightHip :" + rightKnee + "\n" +
                    "leftHip :" + leftKnee + "\n" +
                    "rightShoulder :" + rightShoulder + "\n" +
                    "leftShoulder :" + leftShoulder + "\n"
        )
        return poseLandmarkStr
        /*
           16 Right wrist 14 Right elbow 12 Right shoulder --> Right arm angle
           15 Left wrist 13 Left elbow 11 Left shoulder --> Left arm angle
           24 Right hip 26 Right knee 28 Right ankle --> Right knee angle
           23 Left hip 25 Left knee 27 Left ankle --> Left knee angle
           14 Right elbow 12 Right shoulder 24 Right pelvis --> Right armpit angle
           13 Left elbow 11 Left shoulder 23 Left pelvis --> Left armpit angle
        */
    }

    private fun getAngle(
        firstPoint: PoseLandMark,
        midPoint: PoseLandMark,
        lastPoint: PoseLandMark
    ): Double {
        var result = Math.toDegrees(
            Math.atan2(
                (lastPoint.y - midPoint.y).toDouble(),
                (lastPoint.x - midPoint.x).toDouble()
            ) - Math.atan2(
                (firstPoint.y - midPoint.y).toDouble(),
                (firstPoint.x - midPoint.x).toDouble()
            )
        )
        result = Math.abs(result) // Angle should never be negative
        if (result > 180) {
            result = (360.0 - result) // Always get the acute representation of the angle
        }
        return result
    }

    private fun checkPermissions(){
        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) -> {
                textView.visibility=View.GONE
                startCamera()
            }
            else -> {
                requestPermission.launch(Manifest.permission.CAMERA)
            }
        }
    }

    override fun onPermissionsDenied(requestCode: Int, perms: List<String>) {
        if (EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
            AppSettingsDialog.Builder(this).build().show()
        } else {
            getPermission()
        }
    }

    override fun onPermissionsGranted(requestCode: Int, perms: List<String>) {
        startCamera()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    private fun getPermission() {

        EasyPermissions.requestPermissions(
            this,
            "This application requires Camera Permissions",
            0,
            Manifest.permission.CAMERA
        )
    }

}