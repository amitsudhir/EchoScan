package com.aitd.qrscannertts;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.camera.core.AspectRatio;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static final String TAG = "MainActivity";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private static final long SCAN_TIMEOUT_MS = 8000; // 8 seconds

    private PreviewView previewView;
    private TextView statusTextView;
    private Button scanAgainButton;
    private ImageButton flashlightButton;
    private ImageButton importButton;
    private ImageButton flipCameraButton;

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ExecutorService cameraExecutor;
    private ImageAnalysis imageAnalysis;
    private final AtomicBoolean isScanning = new AtomicBoolean(false);
    private boolean isFlashlightOn = false;
    private int cameraLensFacing = CameraSelector.LENS_FACING_BACK;

    private TextToSpeech textToSpeech;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;

    private final ActivityResultLauncher<Intent> galleryLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();
                    try {
                        Bitmap bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
                        scanBarcodes(InputImage.fromBitmap(bitmap, 0), null, true);
                    } catch (IOException e) {
                        Log.e(TAG, "Error getting bitmap from gallery", e);
                        Toast.makeText(this, "Error loading image", Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scanner);

        previewView = findViewById(R.id.previewView);
        statusTextView = findViewById(R.id.statusTextView);
        scanAgainButton = findViewById(R.id.scanAgainButton);
        flashlightButton = findViewById(R.id.flashlightButton);
        importButton = findViewById(R.id.importButton);
        flipCameraButton = findViewById(R.id.flipCameraButton);

        cameraExecutor = Executors.newSingleThreadExecutor();
        textToSpeech = new TextToSpeech(this, this);

        scanAgainButton.setOnClickListener(v -> startScanningSession());
        flashlightButton.setOnClickListener(v -> toggleFlashlight());
        importButton.setOnClickListener(v -> openGallery());
        flipCameraButton.setOnClickListener(v -> toggleCamera());

        if (isCameraPermissionGranted()) {
            startCamera();
        } else {
            requestCameraPermission();
        }
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        galleryLauncher.launch(intent);
    }

    private void toggleFlashlight() {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            isFlashlightOn = !isFlashlightOn;
            camera.getCameraControl().enableTorch(isFlashlightOn);
        } else {
             Toast.makeText(this, "Flashlight not available", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void toggleCamera() {
        if (cameraLensFacing == CameraSelector.LENS_FACING_BACK) {
            cameraLensFacing = CameraSelector.LENS_FACING_FRONT;
        } else {
            cameraLensFacing = CameraSelector.LENS_FACING_BACK;
        }
        startCamera();
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void startScanningSession() {
        isScanning.set(true);
        statusTextView.setText(getString(R.string.scanning_for_qr_code_20s));
        scanAgainButton.setVisibility(View.GONE);

        speakOut("Align the code inside the frame");

        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer(); // Stop any previous analyzer
            imageAnalysis.setAnalyzer(cameraExecutor, getAnalyzer());
        }

        // 15-second timeout
        timeoutRunnable = () -> {
            if (isScanning.getAndSet(false)) {
                Log.d(TAG, "Scan timed out.");
                statusTextView.setText(R.string.error_qr_code_not_found);
                speakOut("Error: Code not found");
                scanAgainButton.setVisibility(View.VISIBLE);
                if (imageAnalysis != null) imageAnalysis.clearAnalyzer();
            }
        };
        mainHandler.postDelayed(timeoutRunnable, SCAN_TIMEOUT_MS);
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private ImageAnalysis.Analyzer getAnalyzer() {
        return imageProxy -> {
            if (!isScanning.get()) {
                imageProxy.close();
                return;
            }

            if (imageProxy.getImage() != null) {
                InputImage image = InputImage.fromMediaImage(
                        imageProxy.getImage(),
                        imageProxy.getImageInfo().getRotationDegrees()
                );
                scanBarcodes(image, imageProxy, false);
            } else {
                imageProxy.close();
            }
        };
    }

    private void startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error starting camera: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(@NonNull ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(cameraLensFacing)
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        imageAnalysis = new ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(previewView.getDisplay().getRotation())
                .build();

        cameraProvider.unbindAll();
        camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
    }

    private void scanBarcodes(InputImage image, ImageProxy imageProxy, boolean fromGallery) {
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build();
        BarcodeScanner scanner = BarcodeScanning.getClient(options);

        scanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    boolean shouldProcess = fromGallery || isScanning.getAndSet(false);
                    
                    if (!barcodes.isEmpty() && shouldProcess) {
                        mainHandler.removeCallbacks(timeoutRunnable);
                        if (fromGallery) isScanning.set(false);

                        String rawValue = barcodes.get(0).getRawValue();
                        Log.d(TAG, "Code Found: " + rawValue);
                        
                        handleSuccess(rawValue, fromGallery);
                    } else if (fromGallery && barcodes.isEmpty()) {
                         Toast.makeText(this, "No barcode found in image", Toast.LENGTH_SHORT).show();
                    } else {
                        // For camera scans, if nothing found, keep scanning by NOT setting isScanning to false
                         if (!fromGallery) isScanning.set(true);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Scanning failed: " + e.getMessage());
                    if (fromGallery) Toast.makeText(this, "Failed to scan image", Toast.LENGTH_SHORT).show();
                })
                .addOnCompleteListener(task -> {
                    if(imageProxy != null) imageProxy.close();
                });
    }

    private void handleSuccess(String resultText, boolean fromGallery) {
        vibrateDevice();
        statusTextView.setText(resultText);
        
        if (!fromGallery) {
            scanAgainButton.setVisibility(View.VISIBLE);
        }
        if (imageAnalysis != null) imageAnalysis.clearAnalyzer();

        showResultDialog(resultText);
    }

    private void vibrateDevice() {
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                // Deprecated in API 26
                vibrator.vibrate(500);
            }
        }
    }

    private void showResultDialog(String resultText) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_scan_result, null);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        TextView resultTextView = dialogView.findViewById(R.id.resultTextView);
        Button closeButton = dialogView.findViewById(R.id.closeButton);
        Button openLinkButton = dialogView.findViewById(R.id.openLinkButton);
        
        String speakText;
        String displayText;

        if (resultText.startsWith("upi://")) {
            try {
                Uri uri = Uri.parse(resultText);
                String payeeName = uri.getQueryParameter("pn");
                if (payeeName != null && !payeeName.isEmpty()) {
                    displayText = "UPI QR Code of: " + URLDecoder.decode(payeeName, "UTF-8");
                    speakText = "This is a UPI QR code of " + URLDecoder.decode(payeeName, "UTF-8") + ". Please be cautious while making payment.";
                } else {
                    displayText = "UPI QR Code";
                    speakText = "This is a UPI QR code. Please be cautious while making payment.";
                }
            } catch (Exception e) {
                Log.e(TAG, "Error parsing UPI QR code", e);
                displayText = "Invalid UPI QR Code";
                speakText = "Invalid UPI QR Code";
            }
            openLinkButton.setVisibility(View.GONE);
        } else if (android.util.Patterns.WEB_URL.matcher(resultText).matches()) {
            displayText = resultText;
            speakText = "The Code redirects to an external website, please click on the URL to explore more.";
            openLinkButton.setVisibility(View.VISIBLE);
            openLinkButton.setOnClickListener(v -> {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(resultText));
                startActivity(browserIntent);
            });
        } else {
            displayText = resultText;
            speakText = resultText;
            openLinkButton.setVisibility(View.GONE);
        }
        
        resultTextView.setText(displayText);
        speakOut(speakText);

        closeButton.setOnClickListener(v -> {
            dialog.dismiss();
            if (textToSpeech != null) textToSpeech.stop();
        });

        dialog.show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            Locale indianEnglish = new Locale("en", "IN");
            int result = textToSpeech.setLanguage(indianEnglish);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Indian English not supported, falling back to default");
                textToSpeech.setLanguage(Locale.getDefault());
            }
        } else {
            Log.e(TAG, "TextToSpeech initialization failed");
        }
    }

    private void speakOut(String text) {
        if (text != null && !text.isEmpty()) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "UTTERANCE_ID");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        mainHandler.removeCallbacks(timeoutRunnable);
    }

    private boolean isCameraPermissionGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA},
                CAMERA_PERMISSION_REQUEST_CODE);
    }
}
