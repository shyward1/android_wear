package com.smashedlabs.torchwear;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TorchService extends WearableListenerService implements SurfaceHolder.Callback
{
    private String TAG = TorchService.class.getSimpleName().toString();
    private GoogleApiClient mGoogleApiClient;
    private boolean mTorchOn = false;
    private Camera.Parameters parameters;
    Camera cam;
    private SurfaceHolder mHolder;
    private SurfaceView preview;
    private WindowManager windowManager;
    private boolean isCameraInitialized = false;
    private boolean sendToggleLedCommand = false;
    private Ringtone mRingtone;



    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate");

        preview=new SurfaceView(getApplicationContext());
        windowManager = (WindowManager) this.getSystemService(Context.WINDOW_SERVICE);

        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
        );
        layoutParams.gravity = Gravity.LEFT | Gravity.TOP;
        windowManager.addView(preview, layoutParams);
        mHolder = preview.getHolder();
        mHolder.addCallback(this);


        //  Needed for communication between watch and device.
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(Bundle connectionHint) {
                        Log.d(TAG, "onConnected: " + connectionHint);
                        tellWatchConnectedState("connected");
                        //  "onConnected: null" is normal.
                        //  There's nothing in our bundle.
                    }
                    @Override
                    public void onConnectionSuspended(int cause) {
                        Log.d(TAG, "onConnectionSuspended: " + cause);
                    }
                })
                .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(ConnectionResult result) {
                        Log.d(TAG, "onConnectionFailed: " + result);
                    }
                })
                .addApi(Wearable.API)
                .build();

        mGoogleApiClient.connect();

    }

    @Override public void onDestroy()
    {
        super.onDestroy();
        Log.v(TAG, "onDestroy");
        cam.stopPreview();
        cam.release();
        isCameraInitialized = false;
        mRingtone.stop();
        mRingtone = null;
    }

    /**
     * Here, the device actually receives the message that the phone sent, as a path.
     * We simply check that path's last segment and act accordingly.
     * @param messageEvent
     */
    @Override
    public void onMessageReceived(MessageEvent messageEvent) {

        Log.v(TAG, "msg rcvd");
        Log.v(TAG, messageEvent.getPath());
        if(messageEvent.getPath().equalsIgnoreCase("LED")) {
            if (isCameraInitialized)
                toggleLed();
            else {
                //we need to wait for the surface created callback
                sendToggleLedCommand = true;
            }
        }
        else if(messageEvent.getPath().equalsIgnoreCase("PING")) {
            playRingtone();
        }


    }

    private void tellWatchConnectedState(final String state){

        new AsyncTask<Void, Void, List<Node>>(){

            @Override
            protected List<Node> doInBackground(Void... params) {
                return getNodes();
            }

            @Override
            protected void onPostExecute(List<Node> nodeList) {
                for(Node node : nodeList) {
                    Log.v(TAG, "telling " + node.getId() + " i am " + state);

                    PendingResult<MessageApi.SendMessageResult> result = Wearable.MessageApi.sendMessage(
                            mGoogleApiClient,
                            node.getId(),
                            "/listener/lights/" + state,
                            null
                    );

                    result.setResultCallback(new ResultCallback<MessageApi.SendMessageResult>() {
                        @Override
                        public void onResult(MessageApi.SendMessageResult sendMessageResult) {
                            Log.v(TAG, "Phone: " + sendMessageResult.getStatus().getStatusMessage());
                        }
                    });
                }
            }
        }.execute();

    }

    private List<Node> getNodes() {
        List<Node> nodes = new ArrayList<Node>();
        NodeApi.GetConnectedNodesResult rawNodes =
                Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).await();
        for (Node node : rawNodes.getNodes()) {
            nodes.add(node);
        }
        return nodes;
    }

    private void toggleLed() {
        //has the surface been created and camera initialized? If so go ahead. Otherwise we need to wait
        if(isCameraInitialized) {
            if (!mTorchOn) {
                parameters.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
                cam.setParameters(parameters);
                cam.startPreview();
                mTorchOn = true;
                Log.i(TAG, "Light is on");
            } else {
                mTorchOn = false;
                parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                cam.setParameters(parameters);
                cam.stopPreview();
                Log.i(TAG, "Light is off");
            }
        }
    }

    private void playRingtone() {
        Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        mRingtone = RingtoneManager.getRingtone(getApplicationContext(), notification);
        mRingtone.play();
    }

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {

        mHolder = surfaceHolder;
        try {
            Log.i(TAG, "surface created");
            if(cam == null)
            {
                //lets open the camera
                cam = Camera.open();
                parameters= cam.getParameters();
                Log.v(TAG, "camera is ready for use");
            }
            cam.setPreviewDisplay(mHolder);
            isCameraInitialized = true;
        } catch (IOException e){
            e.printStackTrace();
        }
        if(sendToggleLedCommand)
        {
            sendToggleLedCommand = false;
            toggleLed();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i2, int i3) {
        Log.i(TAG, "surfaceChanged");

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        Log.i(TAG, "surfaceDestroyed");
        cam.stopPreview();
        cam.release();
        mHolder = null;
    }
}
