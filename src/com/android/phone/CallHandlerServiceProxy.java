/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;

import com.android.phone.AudioRouter.AudioModeListener;
import com.android.services.telephony.common.AudioMode;
import com.android.services.telephony.common.Call;
import com.android.services.telephony.common.ICallHandlerService;
import com.google.common.collect.Lists;

import java.util.List;

/**
 * This class is responsible for passing through call state changes to the CallHandlerService.
 */
public class CallHandlerServiceProxy extends Handler
        implements CallModeler.Listener, AudioModeListener {

    private static final String TAG = CallHandlerServiceProxy.class.getSimpleName();
    private static final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 1) && (SystemProperties.getInt(
            "ro.debuggable", 0) == 1);

    public static final int RETRY_DELAY_MILLIS = 2000;
    private static final int BIND_RETRY_MSG = 1;
    private static final int MAX_RETRY_COUNT = 5;

    private AudioRouter mAudioRouter;
    private CallCommandService mCallCommandService;
    private CallModeler mCallModeler;
    private Context mContext;

    private ICallHandlerService mCallHandlerServiceGuarded;  // Guarded by mServiceAndQueueLock
    // Single queue to guarantee ordering
    private List<QueueParams> mQueue;                        // Guarded by mServiceAndQueueLock

    private final Object mServiceAndQueueLock = new Object();
    private int mBindRetryCount = 0;

    @Override
    public void handleMessage(Message msg) {
        super.handleMessage(msg);

        switch (msg.what) {
            case BIND_RETRY_MSG:
                setupServiceConnection();
                break;
        }
    }

    public CallHandlerServiceProxy(Context context, CallModeler callModeler,
            CallCommandService callCommandService, AudioRouter audioRouter) {
        if (DBG) {
            Log.d(TAG, "init CallHandlerServiceProxy");
        }
        mContext = context;
        mCallCommandService = callCommandService;
        mCallModeler = callModeler;
        mAudioRouter = audioRouter;

        mAudioRouter.addAudioModeListener(this);
        mCallModeler.addListener(this);
    }

    @Override
    public void onDisconnect(Call call) {
        synchronized (mServiceAndQueueLock) {
            if (mCallHandlerServiceGuarded == null) {
                if (DBG) {
                    Log.d(TAG, "CallHandlerService not connected.  Enqueue disconnect");
                }
                enqueueDisconnect(call);
                setupServiceConnection();
                return;
            }
        }
        processDisconnect(call);
    }

    private void processDisconnect(Call call) {
        try {
            if (DBG) {
                Log.d(TAG, "onDisconnect: " + call);
            }
            synchronized (mServiceAndQueueLock) {
                if (mCallHandlerServiceGuarded != null) {
                    mCallHandlerServiceGuarded.onDisconnect(call);
                }
            }
            if (!mCallModeler.hasLiveCall()) {
                unbind();
            }
        } catch (Exception e) {
            Log.e(TAG, "Remote exception handling onDisconnect ", e);
        }
    }

    @Override
    public void onIncoming(Call call) {
        synchronized (mServiceAndQueueLock) {
            if (mCallHandlerServiceGuarded == null) {
                if (DBG) {
                    Log.d(TAG, "CallHandlerService not connected.  Enqueue incoming.");
                }
                enqueueIncoming(call);
                setupServiceConnection();
                return;
            }
        }
        processIncoming(call);
    }

    private void processIncoming(Call call) {
        if (DBG) {
            Log.d(TAG, "onIncoming: " + call);
        }
        try {
            // TODO(klp): check RespondViaSmsManager.allowRespondViaSmsForCall()
            // must refactor call method to accept proper call object.
            synchronized (mServiceAndQueueLock) {
                if (mCallHandlerServiceGuarded != null) {
                    mCallHandlerServiceGuarded.onIncoming(call,
                            RejectWithTextMessageManager.loadCannedResponses());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Remote exception handling onUpdate", e);
        }
    }

    @Override
    public void onUpdate(List<Call> calls) {
        synchronized (mServiceAndQueueLock) {
            if (mCallHandlerServiceGuarded == null) {
                if (DBG) {
                    Log.d(TAG, "CallHandlerService not connected.  Enqueue update.");
                }
                enqueueUpdate(calls);
                setupServiceConnection();
                return;
            }
        }
        processUpdate(calls);
    }

    private void processUpdate(List<Call> calls) {
        if (DBG) {
            Log.d(TAG, "onUpdate: " + calls.toString());
        }
        try {
            synchronized (mServiceAndQueueLock) {
                if (mCallHandlerServiceGuarded != null) {
                    mCallHandlerServiceGuarded.onUpdate(calls);
                }
            }
            if (!mCallModeler.hasLiveCall()) {
                // TODO: unbinding happens in both onUpdate and onDisconnect because the ordering
                // is not deterministic.  Unbinding in both ensures that the service is unbound.
                // But it also makes this in-efficient because we are unbinding twice, which leads
                // to the CallHandlerService performing onCreate() and onDestroy() twice for each
                // disconnect.
                unbind();
            }
        } catch (Exception e) {
            Log.e(TAG, "Remote exception handling onUpdate", e);
        }
    }


    @Override
    public void onPostDialWait(int callId, String remainingChars) {
        try {
            synchronized (mServiceAndQueueLock) {
                if (mCallHandlerServiceGuarded == null) {
                    if (DBG) {
                        Log.d(TAG, "CallHandlerService not conneccted. Skipping "
                                + "onPostDialWait().");
                    }
                    return;
                }
            }

            mCallHandlerServiceGuarded.onPostDialWait(callId, remainingChars);
        } catch (Exception e) {
            Log.e(TAG, "Remote exception handling onUpdate", e);
        }
    }

    @Override
    public void onAudioModeChange(int newMode, boolean muted) {
        try {
            synchronized (mServiceAndQueueLock) {
                if (mCallHandlerServiceGuarded == null) {
                    if (DBG) {
                        Log.d(TAG, "CallHandlerService not conneccted. Skipping "
                                + "onAudioModeChange().");
                    }
                    return;
                }
            }

            // Just do a simple log for now.
            Log.i(TAG, "Updating with new audio mode: " + AudioMode.toString(newMode) +
                    " with mute " + muted);

            mCallHandlerServiceGuarded.onAudioModeChange(newMode, muted);
        } catch (Exception e) {
            Log.e(TAG, "Remote exception handling onAudioModeChange", e);
        }
    }

    @Override
    public void onSupportedAudioModeChange(int modeMask) {
        try {
            synchronized (mServiceAndQueueLock) {
                if (mCallHandlerServiceGuarded == null) {
                    if (DBG) {
                        Log.d(TAG, "CallHandlerService not conneccted. Skipping"
                                + "onSupportedAudioModeChange().");
                    }
                    return;
                }
            }

            if (DBG) {
                Log.d(TAG, "onSupportAudioModeChange: " + AudioMode.toString(modeMask));
            }

            mCallHandlerServiceGuarded.onSupportedAudioModeChange(modeMask);
        } catch (Exception e) {
            Log.e(TAG, "Remote exception handling onAudioModeChange", e);
        }

    }

    private ServiceConnection mConnection = null;

    private class InCallServiceConnection implements ServiceConnection {
        @Override public void onServiceConnected (ComponentName className, IBinder service){
            if (DBG) {
                Log.d(TAG, "Service Connected");
            }
            onCallHandlerServiceConnected(ICallHandlerService.Stub.asInterface(service));
            mBindRetryCount = 0;
        }

        @Override public void onServiceDisconnected (ComponentName className){
            Log.i(TAG, "Disconnected from UI service.");
            synchronized (mServiceAndQueueLock) {
                // Technically, unbindService is un-necessary since the framework will schedule and
                // restart the crashed service.  But there is a exponential backoff for the restart.
                // Unbind explicitly and setup again to avoid the backoff since it's important to
                // always have an in call ui.
                unbind();

                // TODO(klp): hang up all calls.
            }
        }
    }

    public void bringToForeground() {
        // only support this call if the service is already connected.
        synchronized (mServiceAndQueueLock) {
            if (mCallHandlerServiceGuarded != null && mCallModeler.hasLiveCall()) {
                try {
                    if (DBG) Log.d(TAG, "bringToForeground");
                    mCallHandlerServiceGuarded.bringToForeground();
                } catch (RemoteException e) {
                    Log.e(TAG, "Exception handling bringToForeground", e);
                }
            }
        }
    }

    private static Intent getInCallServiceIntent(Context context) {
        final Intent serviceIntent = new Intent(ICallHandlerService.class.getName());
        final ComponentName component = new ComponentName(context.getResources().getString(
                R.string.incall_ui_default_package), context.getResources().getString(
                R.string.incall_ui_default_class));
        serviceIntent.setComponent(component);
        return serviceIntent;
    }

    /**
     * Sets up the connection with ICallHandlerService
     */
    private void setupServiceConnection() {
        if (!PhoneGlobals.sVoiceCapable) {
            return;
        }

        final Intent serviceIntent = getInCallServiceIntent(mContext);
        if (DBG) {
            Log.d(TAG, "binding to service " + serviceIntent);
        }

        synchronized (mServiceAndQueueLock) {
            if (mConnection == null) {
                mConnection = new InCallServiceConnection();

                final PackageManager packageManger = mContext.getPackageManager();
                final List<ResolveInfo> services = packageManger.queryIntentServices(serviceIntent,
                        0);
                if (services.size() == 0) {
                    // Service not found, retry again after some delay
                    // This can happen if the service is being installed by the package manager.
                    // Between deletes and installs, bindService could get a silent service not
                    // found error.
                    mBindRetryCount++;
                    if (mBindRetryCount < MAX_RETRY_COUNT) {
                        Log.w(TAG, "InCallUI service not found. " + serviceIntent
                                + ". This happens if the service is being installed and should be"
                                + " transient. Retrying" + RETRY_DELAY_MILLIS + " ms.");
                        sendMessageDelayed(Message.obtain(this, BIND_RETRY_MSG),
                                RETRY_DELAY_MILLIS);
                    } else {
                        Log.e(TAG, "Tried to bind to in-call UI " + MAX_RETRY_COUNT + " times."
                                + " Giving up.");
                    }
                    return;
                }

                if (DBG) {
                    Log.d(TAG, "binding to service " + serviceIntent);
                }
                if (!mContext.bindService(serviceIntent, mConnection, Context.BIND_AUTO_CREATE)) {
                    // This happens when the in-call package is in the middle of being
                    // installed.
                    // Delay the retry.
                    mBindRetryCount++;
                    if (mBindRetryCount < MAX_RETRY_COUNT) {
                        Log.e(TAG, "bindService failed on " + serviceIntent + ".  Retrying in "
                                + RETRY_DELAY_MILLIS + " ms.");
                        sendMessageDelayed(Message.obtain(this, BIND_RETRY_MSG),
                                RETRY_DELAY_MILLIS);
                    } else {
                        Log.wtf(TAG, "Tried to bind to in-call UI " + MAX_RETRY_COUNT + " times."
                                + " Giving up.");
                    }
                }

            } else {
                Log.d(TAG, "Service connection to in call service already started.");
            }
        }
    }

    private void unbind() {
        synchronized (mServiceAndQueueLock) {
            if (mCallHandlerServiceGuarded != null) {
                Log.d(TAG, "Unbinding service.");
                mCallHandlerServiceGuarded = null;
                mContext.unbindService(mConnection);
            }
            mConnection = null;
        }
    }

    /**
     * Called when the in-call UI service is connected.  Send command interface to in-call.
     */
    private void onCallHandlerServiceConnected(ICallHandlerService callHandlerService) {

        synchronized (mServiceAndQueueLock) {
            mCallHandlerServiceGuarded = callHandlerService;

            // Before we send any updates, we need to set up the initial service calls.
            makeInitialServiceCalls();

            processQueue();
        }
    }

    /**
     * Makes initial service calls to set up callcommandservice and audio modes.
     */
    private void makeInitialServiceCalls() {
        try {
            mCallHandlerServiceGuarded.setCallCommandService(mCallCommandService);

            onSupportedAudioModeChange(mAudioRouter.getSupportedAudioModes());
            onAudioModeChange(mAudioRouter.getAudioMode(), mAudioRouter.getMute());
        } catch (RemoteException e) {
            Log.e(TAG, "Remote exception calling CallHandlerService::setCallCommandService", e);
        }
    }

    private List<QueueParams> getQueue() {
        if (mQueue == null) {
            mQueue = Lists.newArrayList();
        }
        return mQueue;
    }

    private void enqueueDisconnect(Call call) {
        getQueue().add(new QueueParams(QueueParams.METHOD_DISCONNECT, new Call(call)));
    }

    private void enqueueIncoming(Call call) {
        getQueue().add(new QueueParams(QueueParams.METHOD_INCOMING, new Call(call)));
    }

    private void enqueueUpdate(List<Call> calls) {
        final List<Call> copy = Lists.newArrayList();
        for (Call call : calls) {
            copy.add(new Call(call));
        }
        getQueue().add(new QueueParams(QueueParams.METHOD_UPDATE, copy));
    }

    private void processQueue() {
        synchronized (mServiceAndQueueLock) {
            if (mQueue != null) {
                for (QueueParams params : mQueue) {
                    switch (params.mMethod) {
                        case QueueParams.METHOD_INCOMING:
                            processIncoming((Call) params.mArg);
                            break;
                        case QueueParams.METHOD_UPDATE:
                            processUpdate((List<Call>) params.mArg);
                            break;
                        case QueueParams.METHOD_DISCONNECT:
                            processDisconnect((Call) params.mArg);
                            break;
                        default:
                            throw new IllegalArgumentException("Method type " + params.mMethod +
                                    " not recognized.");
                    }
                }
                mQueue.clear();
                mQueue = null;
            }
        }
    }

    /**
     * Holds method parameters.
     */
    private static class QueueParams {
        private static final int METHOD_INCOMING = 1;
        private static final int METHOD_UPDATE = 2;
        private static final int METHOD_DISCONNECT = 3;

        private final int mMethod;
        private final Object mArg;

        private QueueParams(int method, Object arg) {
            mMethod = method;
            this.mArg = arg;
        }
    }
}
