package com.example.cameracomposition;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.util.SparseArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

public class MainActivity extends AppCompatActivity {


    private static final int REQUEST_CAMERA_PERMISSION = 0;
    private static final int REQUEST_WRITE_EXTERNAL_STORAGE = 0;
    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAIT_LOCK = 1;
    private int captureState = STATE_PREVIEW;
    private TextureView texture;
    private TextureView.SurfaceTextureListener surface = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            setUpCamera(width, height);
            connectCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };


    private CameraDevice cameraD;
    private CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraD = camera;
//            Toast.makeText(getApplicationContext(), "Camera connection made", Toast.LENGTH_SHORT).show();
            startPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraD = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraD = null;
        }
    };
    private String cameraID;
    private Size previousSize;
    private HandlerThread backgroundHandlerThread;  // for the thread itself
    private Handler backgroundHandler;  // thread's handler
    private static SparseArray Orientation = new SparseArray();
    private static final int CAMERA_PERMISSION = 0;
    private CaptureRequest.Builder captureRequest;

    private ImageButton cameraImageButton;

    private File imageFolder;
    private String imageFileName;



    private CameraCaptureSession previewCaptureSession;
    private CameraCaptureSession.CaptureCallback previewCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        private void process(CaptureResult captureResult){
            switch (captureState){
                case STATE_PREVIEW:
                //do nothing;
                    break;
                case STATE_WAIT_LOCK:
                    captureState = STATE_PREVIEW;
                    Integer state = captureResult.get(CaptureResult.CONTROL_AF_STATE);
                    if(state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED){
                        Toast.makeText(getApplicationContext(), "AF LOCKED", Toast.LENGTH_SHORT).show();
                        startCaptureRequest();
                    }

                    break;
                }
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            process(result);
        }
    };
    private Size imageSize;
    private ImageReader imageReader;
    private final ImageReader.OnImageAvailableListener onImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            backgroundHandler.post(new ImageSaver(reader.acquireLatestImage()));
        }
    };

    private int totalRotation;


    //we initialise the array with a static block
    static {
        Orientation.append(Surface.ROTATION_0, 0);
        Orientation.append(Surface.ROTATION_90, 90);
        Orientation.append(Surface.ROTATION_180, 180);
        Orientation.append(Surface.ROTATION_270, 270);

    }   // translate the orientation devince into a number


    private static class CompareSize implements Comparator<Size> {
        @Override
        public int compare(Size o1, Size o2) {
            return Long.signum((long) o1.getWidth() * o1.getHeight() /
                    (long) o2.getWidth() * o2.getHeight());  // signum method compares the two sizes
        }
    }
    //helper class that does the comparisons between the different resolutions from the preview


    private class ImageSaver implements Runnable{
        private final Image image;

        public ImageSaver(Image image) {
            this.image = image;
        }

        @Override
        public void run() {
            ByteBuffer byteBuffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(bytes);

            FileOutputStream  fileOutputStream = null;
            try {
                fileOutputStream = new FileOutputStream(imageFileName);
                fileOutputStream.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            }finally {
                image.close();
                if(fileOutputStream != null){
                    try {
                        fileOutputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }


        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

         createFolder();

        texture = findViewById(R.id.textureView);
        cameraImageButton = findViewById(R.id.cameraimageButton);
        cameraImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lockFocus();
            }
        });

    }


    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (texture.isAvailable()) {
            setUpCamera(texture.getWidth(), texture.getHeight());
            connectCamera();
        } else {
            texture.setSurfaceTextureListener(surface);
        }
    }


    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroudThread();
        super.onPause();
    }


    @Override
    public void onWindowFocusChanged(boolean hasFocas) {
        super.onWindowFocusChanged(hasFocas);
        View decorView = getWindow().getDecorView();
        if (hasFocas) {
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE //a stable transition
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY // sticky, but we can swipe up and swipe down to see the other components(navigation bar)
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN // remove any artifacts
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    }


    private void setUpCamera(int width, int height) {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE); // camera manager object
        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }
                StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                int deviceOrientation = getWindowManager().getDefaultDisplay().getRotation(); //get the current value of orientation
                int totalRotation = rotation(cameraCharacteristics, deviceOrientation);
                boolean swapRotation = totalRotation == 90 || totalRotation == 270;   // portrait mode
                int rotatedWidth = width;
                int rotatedHeight = height;
                if (swapRotation) {
                    rotatedWidth = height;
                    rotatedHeight = width;
                }
                previousSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), rotatedWidth, rotatedHeight);
                imageSize = chooseOptimalSize(map.getOutputSizes(ImageFormat.JPEG), rotatedWidth, rotatedHeight);
                imageReader = ImageReader.newInstance(imageSize.getWidth(), imageSize.getHeight(), ImageFormat.JPEG,1);
                imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);
                cameraID = cameraId;
                return;

            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }


    private void closeCamera() {
        if (cameraD != null) {
            cameraD.close();
            cameraD = null;
        }
    }


    private void startBackgroundThread() {
        backgroundHandlerThread = new HandlerThread("CameraComposition");  // must add some inner text
        backgroundHandlerThread.start();
        backgroundHandler = new Handler(backgroundHandlerThread.getLooper()); // set up a handler pointing to the thread
//        getLooper() -> returns a lopp object which is a run message loop for a thread
    }


    private void stopBackgroudThread() {
        backgroundHandlerThread.quitSafely();
        try {
            backgroundHandlerThread.join(); //will stop anything else from interrupting it
            backgroundHandlerThread = null;
            backgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static int rotation(CameraCharacteristics cameraCharacteristics, int deviceOrientation) {
        int sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION); // takes the value of the sensor orientation(if I move my phone, it takes this value)
        deviceOrientation = (int) Orientation.get(deviceOrientation); // the current value of orientation
        return (sensorOrientation + deviceOrientation + 360) % 360;  // e.g : 90 + 90 = 180 -> 180 % 360 = 180 --- will set the orientation at 180 degrees
    }


    private static Size chooseOptimalSize(Size[] choice, int width, int height) {
        List<Size> bigEnough = new ArrayList<Size>(); // stores all acceptable values
        for (Size option : choice) {
            if (option.getHeight() == option.getWidth() * height / width
                    && option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add((option));
                //aspect ration correct matches the textureview
            }
        }
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSize());
        } else {
            return choice[0];
        }

    }

    private void connectCamera() {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    cameraManager.openCamera(cameraID, cameraStateCallback, backgroundHandler);
                } else {
                    if(shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)){
                        Toast.makeText(this, "Required acces to camera", Toast.LENGTH_SHORT).show();
                    }
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION );
                }
            }else{
                cameraManager.openCamera(cameraID, cameraStateCallback, backgroundHandler);
            }
        }catch(CameraAccessException e){
                e.printStackTrace();
            }
        }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == CAMERA_PERMISSION){
            if(grantResults[0] != PackageManager.PERMISSION_GRANTED){
                Toast.makeText(getApplicationContext(), "Application will not run without camera services", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startPreview() {
        SurfaceTexture surfaceTexture = texture.getSurfaceTexture();
        surfaceTexture.setDefaultBufferSize(previousSize.getWidth(), previousSize.getHeight());
        final Surface previewSurface = new Surface(surfaceTexture);

        try {
            captureRequest = cameraD.createCaptureRequest(cameraD.TEMPLATE_PREVIEW);
            captureRequest.addTarget(previewSurface);

            cameraD.createCaptureSession(Arrays.asList(previewSurface, imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    previewCaptureSession = session;
                    try {
                        previewCaptureSession.setRepeatingRequest(captureRequest.build(),null, backgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Toast.makeText(getApplicationContext(),"Unable to setup the camera", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void lockFocus(){
        captureState = STATE_WAIT_LOCK;
        captureRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
        try {
            previewCaptureSession.capture(captureRequest.build(), previewCaptureCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void createFolder(){
        File file = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        imageFolder = new File(file, "CameraComposition");
        if(!imageFolder.exists()){
            imageFolder.mkdirs();
        }
    }

    private File createfileName() throws IOException{
        String timestamp = new SimpleDateFormat("yyyyMMdd HHmmss").format(new Date());
        String prepend = "IMG_" +timestamp + "_";
        File file = File.createTempFile(prepend, ".jpg", imageFolder);
        imageFileName = file.getAbsolutePath();
        return file;
    }

    private void startCaptureRequest(){
        try {
            captureRequest = cameraD.createCaptureRequest(cameraD.TEMPLATE_STILL_CAPTURE);
            captureRequest.addTarget(imageReader.getSurface());
            captureRequest.set(CaptureRequest.JPEG_ORIENTATION, totalRotation);
            CameraCaptureSession.CaptureCallback stillCaptureCallback = new CameraCaptureSession.CaptureCallback(){
                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                    super.onCaptureStarted(session, request, timestamp, frameNumber);
                    try {
                        createfileName();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            };
            previewCaptureSession.capture(captureRequest.build(), stillCaptureCallback, null);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }


}


