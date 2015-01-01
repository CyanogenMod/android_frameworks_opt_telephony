/*
 * Copyright (c) 2012-2013, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.telephony;

import com.android.internal.telephony.sip.SipPhone;

import android.content.Context;
import android.media.AudioManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.RegistrantList;
import android.os.Registrant;
import android.os.SystemProperties;
import android.telephony.MSimTelephonyManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.Rlog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;



/**
 * @hide
 *
 * CallManager class provides an abstract layer for PhoneApp to access
 * and control calls. It implements Phone interface.
 *
 * CallManager provides call and connection control as well as
 * channel capability.
 *
 * There are three categories of APIs CallManager provided
 *
 *  1. Call control and operation, such as dial() and hangup()
 *  2. Channel capabilities, such as CanConference()
 *  3. Register notification
 *
 *
 */
public class CallManager {

    private static final String LOG_TAG ="CallManager";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    private static final int EVENT_DISCONNECT = 100;
    private static final int EVENT_PRECISE_CALL_STATE_CHANGED = 101;
    protected static final int EVENT_NEW_RINGING_CONNECTION = 102;
    private static final int EVENT_UNKNOWN_CONNECTION = 103;
    private static final int EVENT_INCOMING_RING = 104;
    private static final int EVENT_RINGBACK_TONE = 105;
    private static final int EVENT_IN_CALL_VOICE_PRIVACY_ON = 106;
    private static final int EVENT_IN_CALL_VOICE_PRIVACY_OFF = 107;
    private static final int EVENT_CALL_WAITING = 108;
    private static final int EVENT_DISPLAY_INFO = 109;
    private static final int EVENT_SIGNAL_INFO = 110;
    private static final int EVENT_CDMA_OTA_STATUS_CHANGE = 111;
    private static final int EVENT_RESEND_INCALL_MUTE = 112;
    private static final int EVENT_MMI_INITIATE = 113;
    private static final int EVENT_MMI_COMPLETE = 114;
    private static final int EVENT_ECM_TIMER_RESET = 115;
    private static final int EVENT_SUBSCRIPTION_INFO_READY = 116;
    private static final int EVENT_SUPP_SERVICE_FAILED = 117;
    private static final int EVENT_SERVICE_STATE_CHANGED = 118;
    private static final int EVENT_POST_DIAL_CHARACTER = 119;
    private static final int EVENT_SUPP_SERVICE_NOTIFY = 120;
    private static final int EVENT_CALL_MODIFY = 121;
    private static final int EVENT_CALL_MODIFY_RESPONSE = 122;

    private static final String PROPERTY_QCHAT_ENABLED = "persist.atel.qchat_enabled";

    // Singleton instance
    protected static CallManager INSTANCE;

    // list of registered phones, which are PhoneBase objs
    protected final ArrayList<Phone> mPhones;

    // list of supported ringing calls
    protected final ArrayList<Call> mRingingCalls;

    // list of supported background calls
    protected final ArrayList<Call> mBackgroundCalls;

    // list of supported foreground calls
    protected final ArrayList<Call> mForegroundCalls;

    // empty connection list
    protected final ArrayList<Connection> mEmptyConnections = new ArrayList<Connection>();

    // default phone as the first phone registered, which is PhoneBase obj
    private Phone mDefaultPhone;

    // save a cached copy of Ims Phone
    private Phone mImsPhone;

    protected String mDialString;

    protected boolean mSpeedUpAudioForMtCall = false;

    protected CmHandler mHandler;

    // state registrants
    protected final RegistrantList mPreciseCallStateRegistrants
    = new RegistrantList();

    protected final RegistrantList mNewRingingConnectionRegistrants
    = new RegistrantList();

    protected final RegistrantList mIncomingRingRegistrants
    = new RegistrantList();

    protected final RegistrantList mDisconnectRegistrants
    = new RegistrantList();

    protected final RegistrantList mMmiRegistrants
    = new RegistrantList();

    protected final RegistrantList mUnknownConnectionRegistrants
    = new RegistrantList();

    protected final RegistrantList mRingbackToneRegistrants
    = new RegistrantList();

    protected final RegistrantList mInCallVoicePrivacyOnRegistrants
    = new RegistrantList();

    protected final RegistrantList mInCallVoicePrivacyOffRegistrants
    = new RegistrantList();

    protected final RegistrantList mCallWaitingRegistrants
    = new RegistrantList();

    protected final RegistrantList mDisplayInfoRegistrants
    = new RegistrantList();

    protected final RegistrantList mSignalInfoRegistrants
    = new RegistrantList();

    protected final RegistrantList mCdmaOtaStatusChangeRegistrants
    = new RegistrantList();

    protected final RegistrantList mResendIncallMuteRegistrants
    = new RegistrantList();

    protected final RegistrantList mMmiInitiateRegistrants
    = new RegistrantList();

    protected final RegistrantList mMmiCompleteRegistrants
    = new RegistrantList();

    protected final RegistrantList mEcmTimerResetRegistrants
    = new RegistrantList();

    protected final RegistrantList mSubscriptionInfoReadyRegistrants
    = new RegistrantList();

    protected final RegistrantList mSuppServiceFailedRegistrants
    = new RegistrantList();

    protected final RegistrantList mSuppServiceNotificationRegistrants
    = new RegistrantList();

    protected final RegistrantList mServiceStateChangedRegistrants
    = new RegistrantList();

    protected final RegistrantList mPostDialCharacterRegistrants
    = new RegistrantList();

    protected final RegistrantList mCallModifyRegistrants
    = new RegistrantList();

    protected final RegistrantList mModifyCallResponseRegistrants
    = new RegistrantList();

    protected CallManager() {
        mPhones = new ArrayList<Phone>();
        mRingingCalls = new ArrayList<Call>();
        mBackgroundCalls = new ArrayList<Call>();
        mForegroundCalls = new ArrayList<Call>();
        mDefaultPhone = null;
        mImsPhone = null;
        initHandler();
    }

    /**
     * get singleton instance of CallManager
     * @return CallManager
     */
    public static CallManager getInstance() {
        if (INSTANCE == null) {
            if (isUseExtCallManager()) {
                INSTANCE = new ExtCallManager();
            } else {
                INSTANCE = new CallManager();
            }
        }
        return INSTANCE;
    }

    private static boolean isUseExtCallManager() {
        if (MSimTelephonyManager.getDefault().isMultiSimEnabled() ||
                SystemProperties.getBoolean(PROPERTY_QCHAT_ENABLED, false)) {
            return true;
        }
        return false;
    }

    protected void initHandler() {
        if (mHandler == null) {
            mHandler = new CmHandler();
        }
    }

    /**
     * Get the corresponding PhoneBase obj
     *
     * @param phone a Phone object
     * @return the corresponding PhoneBase obj in Phone if Phone
     * is a PhoneProxy obj
     * or the Phone itself if Phone is not a PhoneProxy obj
     */
    protected static Phone getPhoneBase(Phone phone) {
        if (phone instanceof PhoneProxy) {
            return phone.getForegroundCall().getPhone();
        }
        return phone;
    }

    /**
     * Check if two phones refer to the same PhoneBase obj
     *
     * Note: PhoneBase, not PhoneProxy, is to be used inside of CallManager
     *
     * Both PhoneBase and PhoneProxy implement Phone interface, so
     * they have same phone APIs, such as dial(). The real implementation, for
     * example in GSM,  is in GSMPhone as extend from PhoneBase, so that
     * foregroundCall.getPhone() returns GSMPhone obj. On the other hand,
     * PhoneFactory.getDefaultPhone() returns PhoneProxy obj, which has a class
     * member of GSMPhone.
     *
     * So for phone returned by PhoneFacotry, which is used by PhoneApp,
     *        phone.getForegroundCall().getPhone() != phone
     * but
     *        isSamePhone(phone, phone.getForegroundCall().getPhone()) == true
     *
     * @param p1 is the first Phone obj
     * @param p2 is the second Phone obj
     * @return true if p1 and p2 refer to the same phone
     */
    public static boolean isSamePhone(Phone p1, Phone p2) {
        return (getPhoneBase(p1) == getPhoneBase(p2));
    }

    /**
     * Returns true if Android supports Csvt calls.
     */
    public static boolean isCallOnCsvtEnabled() {
        return SystemProperties.getBoolean(TelephonyProperties.PROPERTY_CSVT_ENABLED, false);
    }

    /**
     * Returns true if Android supports VoLTE/VT calls on IMS
     */
    public static boolean isCallOnImsEnabled() {
        return SystemProperties.getBoolean(
                TelephonyProperties.CALLS_ON_IMS_ENABLED_PROPERTY, false);
    }

    /**
     * Returns all the registered phone objects.
     * @return all the registered phone objects.
     */
    public List<Phone> getAllPhones() {
        return Collections.unmodifiableList(mPhones);
    }

    /**
     * Get current coarse-grained voice call state.
     * If the Call Manager has an active call and call waiting occurs,
     * then the phone state is RINGING not OFFHOOK
     *
     */
    public PhoneConstants.State getState() {
        PhoneConstants.State s = PhoneConstants.State.IDLE;

        for (Phone phone : mPhones) {
            if (phone.getState() == PhoneConstants.State.RINGING) {
                s = PhoneConstants.State.RINGING;
            } else if (phone.getState() == PhoneConstants.State.OFFHOOK) {
                if (s == PhoneConstants.State.IDLE) s = PhoneConstants.State.OFFHOOK;
            }
        }
        return s;
    }

    /**
     * @return the service state of CallManager, which represents the
     * highest priority state of all the service states of phones
     *
     * The priority is defined as
     *
     * STATE_IN_SERIVCE > STATE_OUT_OF_SERIVCE > STATE_EMERGENCY > STATE_POWER_OFF
     *
     */

    public int getServiceState() {
        int resultState = ServiceState.STATE_OUT_OF_SERVICE;

        for (Phone phone : mPhones) {
            int serviceState = phone.getServiceState().getState();
            if (serviceState == ServiceState.STATE_IN_SERVICE) {
                // IN_SERVICE has the highest priority
                resultState = serviceState;
                break;
            } else if (serviceState == ServiceState.STATE_OUT_OF_SERVICE) {
                // OUT_OF_SERVICE replaces EMERGENCY_ONLY and POWER_OFF
                // Note: EMERGENCY_ONLY is not in use at this moment
                if ( resultState == ServiceState.STATE_EMERGENCY_ONLY ||
                        resultState == ServiceState.STATE_POWER_OFF) {
                    resultState = serviceState;
                }
            } else if (serviceState == ServiceState.STATE_EMERGENCY_ONLY) {
                if (resultState == ServiceState.STATE_POWER_OFF) {
                    resultState = serviceState;
                }
            }
        }
        return resultState;
    }

    /**
     * Register phone to CallManager
     * @param phone to be registered
     * @return true if register successfully
     */
    public boolean registerPhone(Phone phone) {
        Phone basePhone = getPhoneBase(phone);

        if (phone != null && phone.getPhoneType() == PhoneConstants.PHONE_TYPE_IMS) {
            mImsPhone = phone;
        }

        if (basePhone != null && !mPhones.contains(basePhone)) {

            if (DBG) {
                Rlog.d(LOG_TAG, "registerPhone(" +
                        phone.getPhoneName() + " " + phone + ")");
            }

            if (mPhones.isEmpty()) {
                mDefaultPhone = basePhone;
            }
            mPhones.add(basePhone);
            mRingingCalls.add(basePhone.getRingingCall());
            mBackgroundCalls.add(basePhone.getBackgroundCall());
            mForegroundCalls.add(basePhone.getForegroundCall());
            registerForPhoneStates(basePhone);
            return true;
        }
        return false;
    }

    /**
     * unregister phone from CallManager
     * @param phone to be unregistered
     */
    public void unregisterPhone(Phone phone) {
        Phone basePhone = getPhoneBase(phone);

        if (basePhone != null && mPhones.contains(basePhone)) {

            if (DBG) {
                Rlog.d(LOG_TAG, "unregisterPhone(" +
                        phone.getPhoneName() + " " + phone + ")");
            }

            mPhones.remove(basePhone);
            mRingingCalls.remove(basePhone.getRingingCall());
            mBackgroundCalls.remove(basePhone.getBackgroundCall());
            mForegroundCalls.remove(basePhone.getForegroundCall());
            unregisterForPhoneStates(basePhone);
            if (basePhone == mDefaultPhone) {
                if (mPhones.isEmpty()) {
                    mDefaultPhone = null;
                } else {
                    mDefaultPhone = mPhones.get(0);
                }
            }
        }
    }

    /**
     * return the default phone or null if no phone available
     */
    public Phone getDefaultPhone() {
        return mDefaultPhone;
    }


    /**
     * return the IMS phone or null if not available
     */
    public Phone getImsPhone() {
        return mImsPhone;
    }

    /**
     * @return the phone associated with the foreground call
     */
    public Phone getFgPhone() {
        return getActiveFgCall().getPhone();
    }

    /**
     * @return the phone associated with the background call
     */
    public Phone getBgPhone() {
        return getFirstActiveBgCall().getPhone();
    }

    /**
     * @return the phone associated with the ringing call
     */
    public Phone getRingingPhone() {
        return getFirstActiveRingingCall().getPhone();
    }

    /**
     * @return the phone associated with any call
     */
    public Phone getPhoneInCall() {
        Phone phone = null;
        if (!getFirstActiveRingingCall().isIdle()) {
            phone = getFirstActiveRingingCall().getPhone();
        } else if (!getActiveFgCall().isIdle()) {
            phone = getActiveFgCall().getPhone();
        } else {
            // If BG call is idle, we return default phone
            phone = getFirstActiveBgCall().getPhone();
        }
        return phone;
    }

    public void setAudioMode() {
        Context context = getContext();
        if (context == null) return;
        AudioManager audioManager = (AudioManager)
                context.getSystemService(Context.AUDIO_SERVICE);

        // change the audio mode and request/abandon audio focus according to phone state,
        // but only on audio mode transitions
        switch (getState()) {
            case RINGING:
                int curAudioMode = audioManager.getMode();
                if (curAudioMode != AudioManager.MODE_RINGTONE) {
                    // only request audio focus if the ringtone is going to be heard
                    if (audioManager.getStreamVolume(AudioManager.STREAM_RING) > 0) {
                        if (VDBG) Rlog.d(LOG_TAG, "requestAudioFocus on STREAM_RING");
                        audioManager.requestAudioFocusForCall(AudioManager.STREAM_RING,
                                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                    }
                    if(!mSpeedUpAudioForMtCall) {
                        audioManager.setMode(AudioManager.MODE_RINGTONE);
                    }
                }

                if (mSpeedUpAudioForMtCall && (curAudioMode != AudioManager.MODE_IN_CALL)) {
                    audioManager.setMode(AudioManager.MODE_IN_CALL);
                }
                break;
            case OFFHOOK:
                Phone offhookPhone = getFgPhone();
                if (getActiveFgCallState() == Call.State.IDLE) {
                    // There is no active Fg calls, the OFFHOOK state
                    // is set by the Bg call. So set the phone to bgPhone.
                    offhookPhone = getBgPhone();
                }

                int newAudioMode = AudioManager.MODE_IN_CALL;
                if (offhookPhone instanceof SipPhone) {
                    Rlog.d(LOG_TAG, "setAudioMode Set audio mode for SIP call!");
                    // enable IN_COMMUNICATION audio mode instead for sipPhone
                    newAudioMode = AudioManager.MODE_IN_COMMUNICATION;
                }
                int currMode = audioManager.getMode();
                if (currMode != newAudioMode || mSpeedUpAudioForMtCall) {
                    // request audio focus before setting the new mode
                    if (VDBG) Rlog.d(LOG_TAG, "requestAudioFocus on STREAM_VOICE_CALL");
                    audioManager.requestAudioFocusForCall(AudioManager.STREAM_VOICE_CALL,
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                    Rlog.d(LOG_TAG, "setAudioMode Setting audio mode from "
                            + currMode + " to " + newAudioMode);
                    audioManager.setMode(newAudioMode);
                }
                mSpeedUpAudioForMtCall = false;
                break;
            case IDLE:
                if (audioManager.getMode() != AudioManager.MODE_NORMAL) {
                    audioManager.setMode(AudioManager.MODE_NORMAL);
                    if (VDBG) Rlog.d(LOG_TAG, "abandonAudioFocus");
                    // abandon audio focus after the mode has been set back to normal
                    audioManager.abandonAudioFocusForCall();
                }
                mSpeedUpAudioForMtCall = false;
                break;
        }
        Rlog.d(LOG_TAG, "setAudioMode state = " + getState());
    }

    protected Context getContext() {
        Phone defaultPhone = getDefaultPhone();
        return ((defaultPhone == null) ? null : defaultPhone.getContext());
    }

    private void registerForPhoneStates(Phone phone) {
        // for common events supported by all phones
        phone.registerForPreciseCallStateChanged(mHandler, EVENT_PRECISE_CALL_STATE_CHANGED, null);
        phone.registerForDisconnect(mHandler, EVENT_DISCONNECT, null);
        phone.registerForNewRingingConnection(mHandler, EVENT_NEW_RINGING_CONNECTION, null);
        phone.registerForUnknownConnection(mHandler, EVENT_UNKNOWN_CONNECTION, null);
        phone.registerForIncomingRing(mHandler, EVENT_INCOMING_RING, null);
        phone.registerForRingbackTone(mHandler, EVENT_RINGBACK_TONE, null);
        phone.registerForInCallVoicePrivacyOn(mHandler, EVENT_IN_CALL_VOICE_PRIVACY_ON, null);
        phone.registerForInCallVoicePrivacyOff(mHandler, EVENT_IN_CALL_VOICE_PRIVACY_OFF, null);
        phone.registerForDisplayInfo(mHandler, EVENT_DISPLAY_INFO, null);
        phone.registerForSignalInfo(mHandler, EVENT_SIGNAL_INFO, null);
        phone.registerForResendIncallMute(mHandler, EVENT_RESEND_INCALL_MUTE, null);
        phone.registerForMmiInitiate(mHandler, EVENT_MMI_INITIATE, null);
        phone.registerForMmiComplete(mHandler, EVENT_MMI_COMPLETE, null);
        phone.registerForSuppServiceFailed(mHandler, EVENT_SUPP_SERVICE_FAILED, null);
        phone.registerForServiceStateChanged(mHandler, EVENT_SERVICE_STATE_CHANGED, null);
        if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM ||
                phone.getPhoneType() == PhoneConstants.PHONE_TYPE_IMS) {
            phone.registerForSuppServiceNotification(mHandler, EVENT_SUPP_SERVICE_NOTIFY, null);
        }

        // for events supported only by GSM and CDMA phone
        if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM ||
                phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            phone.setOnPostDialCharacter(mHandler, EVENT_POST_DIAL_CHARACTER, null);
        }

        // for events supported only by GSM phone
        if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM) {
            phone.registerForSuppServiceNotification(mHandler, EVENT_SUPP_SERVICE_NOTIFY, null);
        }

        // for events supported only by CDMA phone
        if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA ){
            phone.registerForCdmaOtaStatusChange(mHandler, EVENT_CDMA_OTA_STATUS_CHANGE, null);
            phone.registerForSubscriptionInfoReady(mHandler, EVENT_SUBSCRIPTION_INFO_READY, null);
            phone.registerForCallWaiting(mHandler, EVENT_CALL_WAITING, null);
            phone.registerForEcmTimerReset(mHandler, EVENT_ECM_TIMER_RESET, null);
        }

        if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_IMS) {
            phone.registerForEcmTimerReset(mHandler, EVENT_ECM_TIMER_RESET, null);
            try {
                phone.registerForModifyCallRequest(mHandler, EVENT_CALL_MODIFY, null);
                phone.registerForModifyCallResponse(
                      mHandler, EVENT_CALL_MODIFY_RESPONSE, null);
            } catch (CallStateException e) {
                Rlog.e(LOG_TAG, "registerForModifyCallRequest: CallStateException:" + e);
            }
        }
    }

    private void unregisterForPhoneStates(Phone phone) {
        //  for common events supported by all phones
        phone.unregisterForPreciseCallStateChanged(mHandler);
        phone.unregisterForDisconnect(mHandler);
        phone.unregisterForNewRingingConnection(mHandler);
        phone.unregisterForUnknownConnection(mHandler);
        phone.unregisterForIncomingRing(mHandler);
        phone.unregisterForRingbackTone(mHandler);
        phone.unregisterForInCallVoicePrivacyOn(mHandler);
        phone.unregisterForInCallVoicePrivacyOff(mHandler);
        phone.unregisterForDisplayInfo(mHandler);
        phone.unregisterForSignalInfo(mHandler);
        phone.unregisterForResendIncallMute(mHandler);
        phone.unregisterForMmiInitiate(mHandler);
        phone.unregisterForMmiComplete(mHandler);
        phone.unregisterForSuppServiceFailed(mHandler);
        if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM ||
                phone.getPhoneType() == PhoneConstants.PHONE_TYPE_IMS) {
            phone.unregisterForSuppServiceNotification(mHandler);
        }
        phone.unregisterForServiceStateChanged(mHandler);

        // for events supported only by GSM and CDMA phone
        if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM ||
                phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            phone.setOnPostDialCharacter(null, EVENT_POST_DIAL_CHARACTER, null);
        }

        // for events supported only by GSM phone
        if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM) {
            phone.unregisterForSuppServiceNotification(mHandler);
        }

        // for events supported only by CDMA phone
        if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA ){
            phone.unregisterForCdmaOtaStatusChange(mHandler);
            phone.unregisterForSubscriptionInfoReady(mHandler);
            phone.unregisterForCallWaiting(mHandler);
            phone.unregisterForEcmTimerReset(mHandler);
        }

        if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_IMS) {
            phone.unregisterForEcmTimerReset(mHandler);
            try {
                phone.unregisterForModifyCallRequest(mHandler);
                phone.unregisterForModifyCallResponse(mHandler);
            } catch (CallStateException e) {
                Rlog.e(LOG_TAG, "unregisterForModifyCallRequest ", e);
            }
        }
    }

    /**
     * Answers a ringing or waiting call.
     *
     * Active call, if any, go on hold.
     * If active call can't be held, i.e., a background call of the same channel exists,
     * the active call will be hang up.
     *
     * Answering occurs asynchronously, and final notification occurs via
     * {@link #registerForPreciseCallStateChanged(android.os.Handler, int,
     * java.lang.Object) registerForPreciseCallStateChanged()}.
     *
     * @exception CallStateException when call is not ringing or waiting
     */
    public void acceptCall(Call ringingCall) throws CallStateException {
        acceptCall(ringingCall, Phone.CALL_TYPE_VOICE);
    }

    /**
     * Answers a ringing or waiting call, with an option to downgrade a Video
     * call Active call, if any, go on hold. If active call can't be held, i.e.,
     * a background call of the same channel exists, the active call will be
     * hang up. Answering occurs asynchronously, and final notification occurs
     * via
     * {@link #registerForPreciseCallStateChanged(android.os.Handler, int, java.lang.Object)
     * registerForPreciseCallStateChanged()}.
     *
     * @param ringingCall The call to answer
     * @param callType The call type to use to answer the call. Values from
     *            Phone.RIL_CALL_TYPE
     * @exception CallStateException when call is not ringing or waiting
     */
    public void acceptCall(Call ringingCall, int callType) throws CallStateException {
        Phone ringingPhone = ringingCall.getPhone();
        if (VDBG) {
            Rlog.d(LOG_TAG, "acceptCall api with calltype " + callType);
            Rlog.d(LOG_TAG, "acceptCall(" + ringingCall + " from " + ringingCall.getPhone() + ")");
            Rlog.d(LOG_TAG, toString());
        }

        if ( hasActiveFgCall() ) {
            Phone activePhone = getActiveFgCall().getPhone();
            boolean hasBgCall = ! (activePhone.getBackgroundCall().isIdle());
            boolean sameChannel = (activePhone == ringingPhone);

            if (VDBG) {
                Rlog.d(LOG_TAG, "hasBgCall: "+ hasBgCall + "sameChannel:" + sameChannel);
            }

            if (sameChannel && hasBgCall) {
                getActiveFgCall().hangup();
            } else if (!sameChannel && !hasBgCall) {
                activePhone.switchHoldingAndActive();
            } else if (!sameChannel && hasBgCall) {
                getActiveFgCall().hangup();
            }
        }

        Context context = getContext();
        if (context == null) {
            Rlog.d(LOG_TAG, "Speedup Audio Path enhancement: Context is null");
        } else if (context.getResources().getBoolean(
                com.android.internal.R.bool.config_speed_up_audio_on_mt_calls)) {
            Rlog.d(LOG_TAG, "Speedup Audio Path enhancement");
            AudioManager audioManager = (AudioManager)
                    context.getSystemService(Context.AUDIO_SERVICE);
            int currMode = audioManager.getMode();
            if ((currMode != AudioManager.MODE_IN_CALL) && !(ringingPhone instanceof SipPhone)) {
                Rlog.d(LOG_TAG, "setAudioMode Setting audio mode from " +
                                currMode + " to " + AudioManager.MODE_IN_CALL);
                audioManager.setMode(AudioManager.MODE_IN_CALL);
                mSpeedUpAudioForMtCall = true;
            }
        }

        if (ringingPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_IMS) {
            ringingPhone.acceptCall(callType);
        } else {
            ringingPhone.acceptCall();
        }

        if (VDBG) {
            Rlog.d(LOG_TAG, "End acceptCall(" +ringingCall + ")");
            Rlog.d(LOG_TAG, toString());
        }
    }

    /**
     * Reject (ignore) a ringing call. In GSM, this means UDUB
     * (User Determined User Busy). Reject occurs asynchronously,
     * and final notification occurs via
     * {@link #registerForPreciseCallStateChanged(android.os.Handler, int,
     * java.lang.Object) registerForPreciseCallStateChanged()}.
     *
     * @exception CallStateException when no call is ringing or waiting
     */
    public void rejectCall(Call ringingCall) throws CallStateException {
        if (VDBG) {
            Rlog.d(LOG_TAG, "rejectCall(" +ringingCall + ")");
            Rlog.d(LOG_TAG, toString());
        }

        Phone ringingPhone = ringingCall.getPhone();

        ringingPhone.rejectCall();

        if (VDBG) {
            Rlog.d(LOG_TAG, "End rejectCall(" +ringingCall + ")");
            Rlog.d(LOG_TAG, toString());
        }
    }

    /**
     * Places active call on hold, and makes held call active.
     * Switch occurs asynchronously and may fail.
     *
     * There are 4 scenarios
     * 1. only active call but no held call, aka, hold
     * 2. no active call but only held call, aka, unhold
     * 3. both active and held calls from same phone, aka, swap
     * 4. active and held calls from different phones, aka, phone swap
     *
     * Final notification occurs via
     * {@link #registerForPreciseCallStateChanged(android.os.Handler, int,
     * java.lang.Object) registerForPreciseCallStateChanged()}.
     *
     * @exception CallStateException if active call is ringing, waiting, or
     * dialing/alerting, or heldCall can't be active.
     * In these cases, this operation may not be performed.
     */
    public void switchHoldingAndActive(Call heldCall) throws CallStateException {
        Phone activePhone = null;
        Phone heldPhone = null;

        if (VDBG) {
            Rlog.d(LOG_TAG, "switchHoldingAndActive(" +heldCall + ")");
            Rlog.d(LOG_TAG, toString());
        }

        if (hasActiveFgCall()) {
            activePhone = getActiveFgCall().getPhone();
        }

        if (heldCall != null) {
            heldPhone = heldCall.getPhone();
        }

        if (activePhone != null) {
            activePhone.switchHoldingAndActive();
        }

        if (heldPhone != null && heldPhone != activePhone) {
            heldPhone.switchHoldingAndActive();
        }

        if (VDBG) {
            Rlog.d(LOG_TAG, "End switchHoldingAndActive(" +heldCall + ")");
            Rlog.d(LOG_TAG, toString());
        }
    }

    /**
     * Hangup foreground call and resume the specific background call
     *
     * Note: this is noop if there is no foreground call or the heldCall is null
     *
     * @param heldCall to become foreground
     * @throws CallStateException
     */
    public void hangupForegroundResumeBackground(Call heldCall) throws CallStateException {
        Phone foregroundPhone = null;
        Phone backgroundPhone = null;

        if (VDBG) {
            Rlog.d(LOG_TAG, "hangupForegroundResumeBackground(" +heldCall + ")");
            Rlog.d(LOG_TAG, toString());
        }

        if (hasActiveFgCall()) {
            foregroundPhone = getFgPhone();
            if (heldCall != null) {
                backgroundPhone = heldCall.getPhone();
                if (foregroundPhone == backgroundPhone) {
                    getActiveFgCall().hangup();
                } else {
                // the call to be hangup and resumed belongs to different phones
                    getActiveFgCall().hangup();
                    switchHoldingAndActive(heldCall);
                }
            }
        }

        if (VDBG) {
            Rlog.d(LOG_TAG, "End hangupForegroundResumeBackground(" +heldCall + ")");
            Rlog.d(LOG_TAG, toString());
        }
    }

    /**
     * Whether or not the phone can conference in the current phone
     * state--that is, one call holding and one call active.
     * @return true if the phone can conference; false otherwise.
     */
    public boolean canConference(Call heldCall) {
        Phone activePhone = null;
        Phone heldPhone = null;

        if (hasActiveFgCall()) {
            activePhone = getActiveFgCall().getPhone();
        }

        if (heldCall != null) {
            heldPhone = heldCall.getPhone();
        }

        return heldPhone.getClass().equals(activePhone.getClass());
    }

    /**
     * Conferences holding and active. Conference occurs asynchronously
     * and may fail. Final notification occurs via
     * {@link #registerForPreciseCallStateChanged(android.os.Handler, int,
     * java.lang.Object) registerForPreciseCallStateChanged()}.
     *
     * @exception CallStateException if canConference() would return false.
     * In these cases, this operation may not be performed.
     */
    public void conference(Call heldCall) throws CallStateException {

        if (VDBG) {
            Rlog.d(LOG_TAG, "conference(" +heldCall + ")");
            Rlog.d(LOG_TAG, toString());
        }


        Phone fgPhone = getFgPhone();
        if (fgPhone instanceof SipPhone) {
            ((SipPhone) fgPhone).conference(heldCall);
        } else if (canConference(heldCall)) {
            fgPhone.conference();
        } else {
            throw(new CallStateException("Can't conference foreground and selected background call"));
        }

        if (VDBG) {
            Rlog.d(LOG_TAG, "End conference(" +heldCall + ")");
            Rlog.d(LOG_TAG, toString());
        }

    }

    /**
     * Initiate a new voice connection. This happens asynchronously, so you
     * cannot assume the audio path is connected (or a call index has been
     * assigned) until PhoneStateChanged notification has occurred.
     *
     * @exception CallStateException if a new outgoing call is not currently
     * possible because no more call slots exist or a call exists that is
     * dialing, alerting, ringing, or waiting.  Other errors are
     * handled asynchronously.
     */
    public Connection dial(Phone phone, String dialString) throws CallStateException {
        return dial(phone, dialString, Phone.CALL_TYPE_VOICE, null);
    }

    /**
     * Initiate a new connection. This happens asynchronously, so you cannot
     * assume the audio path is connected (or a call index has been assigned)
     * until PhoneStateChanged notification has occurred.
     *
     * @exception CallStateException if a new outgoing call is not currently
     *                possible because no more call slots exist or a call exists
     *                that is dialing, alerting, ringing, or waiting. Other
     *                errors are handled asynchronously.
     * @param phone The phone to use to place the call
     * @param dialString The phone number or URI that identifies the remote
     *            party
     * @param calldetails
     */
    public Connection dial(Phone phone, String dialString, int callType, String[] extras)
            throws CallStateException {

        Phone basePhone = getPhoneBase(phone);
        Connection result;
        mDialString = dialString;

        if (VDBG) {
            Rlog.d(LOG_TAG, " dial(" + basePhone + ", "+ dialString + ")");
            Rlog.d(LOG_TAG, toString());
        }

        if (!canDial(phone)) {
            throw new CallStateException("cannot dial in current state");
        }

        if ( hasActiveFgCall() ) {
            Phone activePhone = getActiveFgCall().getPhone();
            boolean hasBgCall = !(activePhone.getBackgroundCall().isIdle());

            if (DBG) {
                Rlog.d(LOG_TAG, "hasBgCall: "+ hasBgCall + " sameChannel:" + (activePhone == basePhone));
            }

            if (activePhone != basePhone) {
                if (hasBgCall) {
                    Rlog.d(LOG_TAG, "Hangup");
                    getActiveFgCall().hangup();
                } else {
                    Rlog.d(LOG_TAG, "Switch");
                    activePhone.switchHoldingAndActive();
                }
            }
        }

        if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_IMS) {
            result = basePhone.dial(dialString, callType, extras);
        } else {
            result = basePhone.dial(dialString);
        }

        if (VDBG) {
            Rlog.d(LOG_TAG, "End dial(" + basePhone + ", "+ dialString + ")");
            Rlog.d(LOG_TAG, toString());
        }

        return result;
    }

    protected boolean isExplicitCallTransferMMI (String dialString) {
        boolean result = false;
        String newDialString = PhoneNumberUtils.stripSeparators(dialString);
        if ((newDialString != null) && (newDialString.length() == 1)) {
            char ch = newDialString.charAt(0);
            if (ch == '4') {
                result = true;
            }
        }
        return result;
    }

    /**
     * Initiate a new voice connection. This happens asynchronously, so you
     * cannot assume the audio path is connected (or a call index has been
     * assigned) until PhoneStateChanged notification has occurred.
     *
     * @exception CallStateException if a new outgoing call is not currently
     * possible because no more call slots exist or a call exists that is
     * dialing, alerting, ringing, or waiting.  Other errors are
     * handled asynchronously.
     */
    public Connection dial(Phone phone, String dialString, UUSInfo uusInfo) throws CallStateException {
        return phone.dial(dialString, uusInfo);
    }

    /**
     * clear disconnect connection for each phone
     */
    public void clearDisconnected() {
        for(Phone phone : mPhones) {
            phone.clearDisconnected();
        }
    }

    /**
     * Phone can make a call only if ALL of the following are true:
     *        - Phone is not powered off
     *        - There's no incoming or waiting call
     *        - The foreground call is ACTIVE or IDLE or DISCONNECTED.
     *          (We mainly need to make sure it *isn't* DIALING or ALERTING.)
     * @param phone
     * @return true if the phone can make a new call
     */
    protected boolean canDial(Phone phone) {
        int serviceState = phone.getServiceState().getState();
        boolean hasRingingCall = hasActiveRingingCall();
        Call.State fgCallState = getActiveFgCallState();

        boolean result = (serviceState != ServiceState.STATE_POWER_OFF
                && !hasRingingCall
                && ((fgCallState == Call.State.ACTIVE)
                    || (fgCallState == Call.State.IDLE)
                    /*As per 3GPP TS 51.010-1 section 31.13.1.4
                    call should be alowed when the foreground
                    call is in ALERTING state*/
                    || ((fgCallState == Call.State.ALERTING) &&
                            isExplicitCallTransferMMI(mDialString))
                    || (fgCallState == Call.State.DISCONNECTED)));

        if (result == false) {
            Rlog.d(LOG_TAG, "canDial serviceState=" + serviceState
                            + " hasRingingCall=" + hasRingingCall
                            + " fgCallState=" + fgCallState);
        }
        return result;
    }

    /**
     * Whether or not the phone can do explicit call transfer in the current
     * phone state--that is, one call holding and one call active.
     * @return true if the phone can do explicit call transfer; false otherwise.
     */
    public boolean canTransfer(Call heldCall) {
        Phone activePhone = null;
        Phone heldPhone = null;

        if (hasActiveFgCall()) {
            activePhone = getActiveFgCall().getPhone();
        }

        if (heldCall != null) {
            heldPhone = heldCall.getPhone();
        }

        return (heldPhone == activePhone && activePhone.canTransfer());
    }

    /**
     * Connects the held call and active call
     * Disconnects the subscriber from both calls
     *
     * Explicit Call Transfer occurs asynchronously
     * and may fail. Final notification occurs via
     * {@link #registerForPreciseCallStateChanged(android.os.Handler, int,
     * java.lang.Object) registerForPreciseCallStateChanged()}.
     *
     * @exception CallStateException if canTransfer() would return false.
     * In these cases, this operation may not be performed.
     */
    public void explicitCallTransfer(Call heldCall) throws CallStateException {
        if (VDBG) {
            Rlog.d(LOG_TAG, " explicitCallTransfer(" + heldCall + ")");
            Rlog.d(LOG_TAG, toString());
        }

        if (canTransfer(heldCall)) {
            heldCall.getPhone().explicitCallTransfer();
        }

        if (VDBG) {
            Rlog.d(LOG_TAG, "End explicitCallTransfer(" + heldCall + ")");
            Rlog.d(LOG_TAG, toString());
        }

    }

    /**
     * Returns a list of MMI codes that are pending for a phone. (They have initiated
     * but have not yet completed).
     * Presently there is only ever one.
     *
     * Use <code>registerForMmiInitiate</code>
     * and <code>registerForMmiComplete</code> for change notification.
     * @return null if phone doesn't have or support mmi code
     */
    public List<? extends MmiCode> getPendingMmiCodes(Phone phone) {
        Rlog.e(LOG_TAG, "getPendingMmiCodes not implemented");
        return null;
    }

    /**
     * Sends user response to a USSD REQUEST message.  An MmiCode instance
     * representing this response is sent to handlers registered with
     * registerForMmiInitiate.
     *
     * @param ussdMessge    Message to send in the response.
     * @return false if phone doesn't support ussd service
     */
    public boolean sendUssdResponse(Phone phone, String ussdMessge) {
        Rlog.e(LOG_TAG, "sendUssdResponse not implemented");
        return false;
    }

    /**
     * Mutes or unmutes the microphone for the active call. The microphone
     * is automatically unmuted if a call is answered, dialed, or resumed
     * from a holding state.
     *
     * @param muted true to mute the microphone,
     * false to activate the microphone.
     */

    public void setMute(boolean muted) {
        if (VDBG) {
            Rlog.d(LOG_TAG, " setMute(" + muted + ")");
            Rlog.d(LOG_TAG, toString());
        }

        if (hasActiveFgCall()) {
            getActiveFgCall().getPhone().setMute(muted);
        }

        if (VDBG) {
            Rlog.d(LOG_TAG, "End setMute(" + muted + ")");
            Rlog.d(LOG_TAG, toString());
        }
    }

    /**
     * Gets current mute status. Use
     * {@link #registerForPreciseCallStateChanged(android.os.Handler, int,
     * java.lang.Object) registerForPreciseCallStateChanged()}
     * as a change notifcation, although presently phone state changed is not
     * fired when setMute() is called.
     *
     * @return true is muting, false is unmuting
     */
    public boolean getMute() {
        if (hasActiveFgCall()) {
            return getActiveFgCall().getPhone().getMute();
        } else if (hasActiveBgCall()) {
            return getFirstActiveBgCall().getPhone().getMute();
        }
        return false;
    }

    /**
     * Enables or disables echo suppression.
     */
    public void setEchoSuppressionEnabled(boolean enabled) {
        if (VDBG) {
            Rlog.d(LOG_TAG, " setEchoSuppression(" + enabled + ")");
            Rlog.d(LOG_TAG, toString());
        }

        if (hasActiveFgCall()) {
            getActiveFgCall().getPhone().setEchoSuppressionEnabled(enabled);
        }

        if (VDBG) {
            Rlog.d(LOG_TAG, "End setEchoSuppression(" + enabled + ")");
            Rlog.d(LOG_TAG, toString());
        }
    }

    /**
     * Play a DTMF tone on the active call.
     *
     * @param c should be one of 0-9, '*' or '#'. Other values will be
     * silently ignored.
     * @return false if no active call or the active call doesn't support
     *         dtmf tone
     */
    public boolean sendDtmf(char c) {
        boolean result = false;

        if (VDBG) {
            Rlog.d(LOG_TAG, " sendDtmf(" + c + ")");
            Rlog.d(LOG_TAG, toString());
        }

        if (hasActiveFgCall()) {
            getActiveFgCall().getPhone().sendDtmf(c);
            result = true;
        }

        if (VDBG) {
            Rlog.d(LOG_TAG, "End sendDtmf(" + c + ")");
            Rlog.d(LOG_TAG, toString());
        }
        return result;
    }

    /**
     * Start to paly a DTMF tone on the active call.
     * or there is a playing DTMF tone.
     * @param c should be one of 0-9, '*' or '#'. Other values will be
     * silently ignored.
     *
     * @return false if no active call or the active call doesn't support
     *         dtmf tone
     */
    public boolean startDtmf(char c) {
        boolean result = false;

        if (VDBG) {
            Rlog.d(LOG_TAG, " startDtmf(" + c + ")");
            Rlog.d(LOG_TAG, toString());
        }

        if (hasActiveFgCall()) {
            getActiveFgCall().getPhone().startDtmf(c);
            result = true;
        }

        if (VDBG) {
            Rlog.d(LOG_TAG, "End startDtmf(" + c + ")");
            Rlog.d(LOG_TAG, toString());
        }

        return result;
    }

    /**
     * Stop the playing DTMF tone. Ignored if there is no playing DTMF
     * tone or no active call.
     */
    public void stopDtmf() {
        if (VDBG) {
            Rlog.d(LOG_TAG, " stopDtmf()" );
            Rlog.d(LOG_TAG, toString());
        }

        if (hasActiveFgCall()) getFgPhone().stopDtmf();

        if (VDBG) {
            Rlog.d(LOG_TAG, "End stopDtmf()");
            Rlog.d(LOG_TAG, toString());
        }
    }

    /**
     * send burst DTMF tone, it can send the string as single character or multiple character
     * ignore if there is no active call or not valid digits string.
     * Valid digit means only includes characters ISO-LATIN characters 0-9, *, #
     * The difference between sendDtmf and sendBurstDtmf is sendDtmf only sends one character,
     * this api can send single character and multiple character, also, this api has response
     * back to caller.
     *
     * @param dtmfString is string representing the dialing digit(s) in the active call
     * @param on the DTMF ON length in milliseconds, or 0 for default
     * @param off the DTMF OFF length in milliseconds, or 0 for default
     * @param onComplete is the callback message when the action is processed by BP
     *
     */
    public boolean sendBurstDtmf(String dtmfString, int on, int off, Message onComplete) {
        if (hasActiveFgCall()) {
            getActiveFgCall().getPhone().sendBurstDtmf(dtmfString, on, off, onComplete);
            return true;
        }
        return false;
    }

    /**
     * Notifies when a voice connection has disconnected, either due to local
     * or remote hangup or error.
     *
     *  Messages received from this will have the following members:<p>
     *  <ul><li>Message.obj will be an AsyncResult</li>
     *  <li>AsyncResult.userObj = obj</li>
     *  <li>AsyncResult.result = a Connection object that is
     *  no longer connected.</li></ul>
     */
    public void registerForDisconnect(Handler h, int what, Object obj) {
        mDisconnectRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregisters for voice disconnection notification.
     * Extraneous calls are tolerated silently
     */
    public void unregisterForDisconnect(Handler h){
        mDisconnectRegistrants.remove(h);
    }

    /**
     * Register for getting notifications for change in the Call State {@link Call.State}
     * This is called PreciseCallState because the call state is more precise than what
     * can be obtained using the {@link PhoneStateListener}
     *
     * Resulting events will have an AsyncResult in <code>Message.obj</code>.
     * AsyncResult.userData will be set to the obj argument here.
     * The <em>h</em> parameter is held only by a weak reference.
     */
    public void registerForPreciseCallStateChanged(Handler h, int what, Object obj){
        mPreciseCallStateRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregisters for voice call state change notifications.
     * Extraneous calls are tolerated silently.
     */
    public void unregisterForPreciseCallStateChanged(Handler h){
        mPreciseCallStateRegistrants.remove(h);
    }

    /**
     * Notifies when a previously untracked non-ringing/waiting connection has appeared.
     * This is likely due to some other entity (eg, SIM card application) initiating a call.
     */
    public void registerForUnknownConnection(Handler h, int what, Object obj){
        mUnknownConnectionRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregisters for unknown connection notifications.
     */
    public void unregisterForUnknownConnection(Handler h){
        mUnknownConnectionRegistrants.remove(h);
    }


    /**
     * Notifies when a new ringing or waiting connection has appeared.<p>
     *
     *  Messages received from this:
     *  Message.obj will be an AsyncResult
     *  AsyncResult.userObj = obj
     *  AsyncResult.result = a Connection. <p>
     *  Please check Connection.isRinging() to make sure the Connection
     *  has not dropped since this message was posted.
     *  If Connection.isRinging() is true, then
     *   Connection.getCall() == Phone.getRingingCall()
     */
    public void registerForNewRingingConnection(Handler h, int what, Object obj){
        mNewRingingConnectionRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregisters for new ringing connection notification.
     * Extraneous calls are tolerated silently
     */

    public void unregisterForNewRingingConnection(Handler h){
        mNewRingingConnectionRegistrants.remove(h);
    }

    /**
     * Notifies when an incoming call rings.<p>
     *
     *  Messages received from this:
     *  Message.obj will be an AsyncResult
     *  AsyncResult.userObj = obj
     *  AsyncResult.result = a Connection. <p>
     */
    public void registerForIncomingRing(Handler h, int what, Object obj){
        mIncomingRingRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregisters for ring notification.
     * Extraneous calls are tolerated silently
     */

    public void unregisterForIncomingRing(Handler h){
        mIncomingRingRegistrants.remove(h);
    }

    /**
     * Notifies when out-band ringback tone is needed.<p>
     *
     *  Messages received from this:
     *  Message.obj will be an AsyncResult
     *  AsyncResult.userObj = obj
     *  AsyncResult.result = boolean, true to start play ringback tone
     *                       and false to stop. <p>
     */
    public void registerForRingbackTone(Handler h, int what, Object obj){
        mRingbackToneRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregisters for ringback tone notification.
     */

    public void unregisterForRingbackTone(Handler h){
        mRingbackToneRegistrants.remove(h);
    }

    /**
     * Registers the handler to reset the uplink mute state to get
     * uplink audio.
     */
    public void registerForResendIncallMute(Handler h, int what, Object obj){
        mResendIncallMuteRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregisters for resend incall mute notifications.
     */
    public void unregisterForResendIncallMute(Handler h){
        mResendIncallMuteRegistrants.remove(h);
    }

    /**
     * Register for notifications of initiation of a new MMI code request.
     * MMI codes for GSM are discussed in 3GPP TS 22.030.<p>
     *
     * Example: If Phone.dial is called with "*#31#", then the app will
     * be notified here.<p>
     *
     * The returned <code>Message.obj</code> will contain an AsyncResult.
     *
     * <code>obj.result</code> will be an "MmiCode" object.
     */
    public void registerForMmiInitiate(Handler h, int what, Object obj){
        mMmiInitiateRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregisters for new MMI initiate notification.
     * Extraneous calls are tolerated silently
     */
    public void unregisterForMmiInitiate(Handler h){
        mMmiInitiateRegistrants.remove(h);
    }

    /**
     * Register for notifications that an MMI request has completed
     * its network activity and is in its final state. This may mean a state
     * of COMPLETE, FAILED, or CANCELLED.
     *
     * <code>Message.obj</code> will contain an AsyncResult.
     * <code>obj.result</code> will be an "MmiCode" object
     */
    public void registerForMmiComplete(Handler h, int what, Object obj){
        mMmiCompleteRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregisters for MMI complete notification.
     * Extraneous calls are tolerated silently
     */
    public void unregisterForMmiComplete(Handler h){
        mMmiCompleteRegistrants.remove(h);
    }

    /**
     * Registration point for Ecm timer reset
     * @param h handler to notify
     * @param what user-defined message code
     * @param obj placed in Message.obj
     */
    public void registerForEcmTimerReset(Handler h, int what, Object obj){
        mEcmTimerResetRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregister for notification for Ecm timer reset
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForEcmTimerReset(Handler h){
        mEcmTimerResetRegistrants.remove(h);
    }

    /**
     * Register for ServiceState changed.
     * Message.obj will contain an AsyncResult.
     * AsyncResult.result will be a ServiceState instance
     */
    public void registerForServiceStateChanged(Handler h, int what, Object obj){
        mServiceStateChangedRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregisters for ServiceStateChange notification.
     * Extraneous calls are tolerated silently
     */
    public void unregisterForServiceStateChanged(Handler h){
        mServiceStateChangedRegistrants.remove(h);
    }

    /**
     * Register for notifications when a supplementary service attempt fails.
     * Message.obj will contain an AsyncResult.
     *
     * @param h Handler that receives the notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForSuppServiceFailed(Handler h, int what, Object obj){
        mSuppServiceFailedRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregister for notifications when a supplementary service attempt fails.
     * Extraneous calls are tolerated silently
     *
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForSuppServiceFailed(Handler h){
        mSuppServiceFailedRegistrants.remove(h);
    }

    /**
     * Register for supplementary service notifications.
     * Message.obj will contain an AsyncResult.
     *
     * @param h Handler that receives the notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForSuppServiceNotification(Handler h, int what, Object obj){
        mSuppServiceNotificationRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregister for supplementary service notifications.
     *
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForSuppServiceNotification(Handler h){
        mSuppServiceNotificationRegistrants.remove(h);
    }

    /**
     * Register for notifications when a sInCall VoicePrivacy is enabled
     *
     * @param h Handler that receives the notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForInCallVoicePrivacyOn(Handler h, int what, Object obj){
        mInCallVoicePrivacyOnRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregister for notifications when a sInCall VoicePrivacy is enabled
     *
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForInCallVoicePrivacyOn(Handler h){
        mInCallVoicePrivacyOnRegistrants.remove(h);
    }

    /**
     * Register for notifications when a sInCall VoicePrivacy is disabled
     *
     * @param h Handler that receives the notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForInCallVoicePrivacyOff(Handler h, int what, Object obj){
        mInCallVoicePrivacyOffRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregister for notifications when a sInCall VoicePrivacy is disabled
     *
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForInCallVoicePrivacyOff(Handler h){
        mInCallVoicePrivacyOffRegistrants.remove(h);
    }

    /**
     * Register for notifications when CDMA call waiting comes
     *
     * @param h Handler that receives the notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForCallWaiting(Handler h, int what, Object obj){
        mCallWaitingRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregister for notifications when CDMA Call waiting comes
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForCallWaiting(Handler h){
        mCallWaitingRegistrants.remove(h);
    }


    /**
     * Register for signal information notifications from the network.
     * Message.obj will contain an AsyncResult.
     * AsyncResult.result will be a SuppServiceNotification instance.
     *
     * @param h Handler that receives the notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */

    public void registerForSignalInfo(Handler h, int what, Object obj){
        mSignalInfoRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregisters for signal information notifications.
     * Extraneous calls are tolerated silently
     *
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForSignalInfo(Handler h){
        mSignalInfoRegistrants.remove(h);
    }

    /**
     * Register for display information notifications from the network.
     * Message.obj will contain an AsyncResult.
     * AsyncResult.result will be a SuppServiceNotification instance.
     *
     * @param h Handler that receives the notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForDisplayInfo(Handler h, int what, Object obj){
        mDisplayInfoRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregisters for display information notifications.
     * Extraneous calls are tolerated silently
     *
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForDisplayInfo(Handler h) {
        mDisplayInfoRegistrants.remove(h);
    }

    /**
     * Register for notifications when CDMA OTA Provision status change
     *
     * @param h Handler that receives the notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForCdmaOtaStatusChange(Handler h, int what, Object obj){
        mCdmaOtaStatusChangeRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregister for notifications when CDMA OTA Provision status change
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForCdmaOtaStatusChange(Handler h){
        mCdmaOtaStatusChangeRegistrants.remove(h);
    }

    /**
     * Registration point for subscription info ready
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForSubscriptionInfoReady(Handler h, int what, Object obj){
        mSubscriptionInfoReadyRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregister for notifications for subscription info
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForSubscriptionInfoReady(Handler h){
        mSubscriptionInfoReadyRegistrants.remove(h);
    }

    /**
     * Sets an event to be fired when the telephony system processes
     * a post-dial character on an outgoing call.<p>
     *
     * Messages of type <code>what</code> will be sent to <code>h</code>.
     * The <code>obj</code> field of these Message's will be instances of
     * <code>AsyncResult</code>. <code>Message.obj.result</code> will be
     * a Connection object.<p>
     *
     * Message.arg1 will be the post dial character being processed,
     * or 0 ('\0') if end of string.<p>
     *
     * If Connection.getPostDialState() == WAIT,
     * the application must call
     * {@link com.android.internal.telephony.Connection#proceedAfterWaitChar()
     * Connection.proceedAfterWaitChar()} or
     * {@link com.android.internal.telephony.Connection#cancelPostDial()
     * Connection.cancelPostDial()}
     * for the telephony system to continue playing the post-dial
     * DTMF sequence.<p>
     *
     * If Connection.getPostDialState() == WILD,
     * the application must call
     * {@link com.android.internal.telephony.Connection#proceedAfterWildChar
     * Connection.proceedAfterWildChar()}
     * or
     * {@link com.android.internal.telephony.Connection#cancelPostDial()
     * Connection.cancelPostDial()}
     * for the telephony system to continue playing the
     * post-dial DTMF sequence.<p>
     *
     */
    public void registerForPostDialCharacter(Handler h, int what, Object obj){
        mPostDialCharacterRegistrants.addUnique(h, what, obj);
    }

    public void unregisterForPostDialCharacter(Handler h){
        mPostDialCharacterRegistrants.remove(h);
    }

    /*
     * Registrants for CallModify
     */
    public void registerForCallModify(Handler h, int what, Object obj) {
        mCallModifyRegistrants.addUnique(h, what, obj);
    }

    public void unregisterForCallModify(Handler h) {
        mCallModifyRegistrants.remove(h);
    }

    /*
     * Registrants for CallModify Failed or Succeed
     */
    public void registerForCallModifyResponse(Handler h, int what, Object obj) {
        mModifyCallResponseRegistrants.addUnique(h, what, obj);
    }

    public void unregisterForCallModifyResponse(Handler h) {
        mModifyCallResponseRegistrants.remove(h);
    }

    /* APIs to access foregroudCalls, backgroudCalls, and ringingCalls
     * 1. APIs to access list of calls
     * 2. APIs to check if any active call, which has connection other than
     * disconnected ones, pleaser refer to Call.isIdle()
     * 3. APIs to return first active call
     * 4. APIs to return the connections of first active call
     * 5. APIs to return other property of first active call
     */

    /**
     * @return list of all ringing calls
     */
    public List<Call> getRingingCalls() {
        return Collections.unmodifiableList(mRingingCalls);
    }

    /**
     * @return list of all foreground calls
     */
    public List<Call> getForegroundCalls() {
        return Collections.unmodifiableList(mForegroundCalls);
    }

    /**
     * @return list of all background calls
     */
    public List<Call> getBackgroundCalls() {
        return Collections.unmodifiableList(mBackgroundCalls);
    }

    /**
     * Return true if there is at least one active foreground call
     */
    public boolean hasActiveFgCall() {
        return (getFirstActiveCall(mForegroundCalls) != null);
    }

    /**
     * Return true if there is at least one active background call
     */
    public boolean hasActiveBgCall() {
        // TODO since hasActiveBgCall may get called often
        // better to cache it to improve performance
        return (getFirstActiveCall(mBackgroundCalls) != null);
    }

    /**
     * Return true if there is at least one active ringing call
     *
     */
    public boolean hasActiveRingingCall() {
        return (getFirstActiveCall(mRingingCalls) != null);
    }

    /**
     * return the active foreground call from foreground calls
     *
     * Active call means the call is NOT in Call.State.IDLE
     *
     * 1. If there is active foreground call, return it
     * 2. If there is no active foreground call, return the
     *    foreground call associated with default phone, which state is IDLE.
     * 3. If there is no phone registered at all, return null.
     *
     */
    public Call getActiveFgCall() {
        Call call = getFirstNonIdleCall(mForegroundCalls);
        if (call == null) {
            call = (mDefaultPhone == null)
                    ? null
                    : mDefaultPhone.getForegroundCall();
        }
        return call;
    }

    // Returns the first call that is not in IDLE state. If both active calls
    // and disconnecting/disconnected calls exist, return the first active call.
    private Call getFirstNonIdleCall(List<Call> calls) {
        Call result = null;
        for (Call call : calls) {
            if (!call.isIdle()) {
                return call;
            } else if (call.getState() != Call.State.IDLE) {
                if (result == null) result = call;
            }
        }
        return result;
    }

    /**
     * return one active background call from background calls
     *
     * Active call means the call is NOT idle defined by Call.isIdle()
     *
     * 1. If there is only one active background call, return it
     * 2. If there is more than one active background call, return the first one
     * 3. If there is no active background call, return the background call
     *    associated with default phone, which state is IDLE.
     * 4. If there is no background call at all, return null.
     *
     * Complete background calls list can be get by getBackgroundCalls()
     */
    public Call getFirstActiveBgCall() {
        Call call = getFirstNonIdleCall(mBackgroundCalls);
        if (call == null) {
            call = (mDefaultPhone == null)
                    ? null
                    : mDefaultPhone.getBackgroundCall();
        }
        return call;
    }

    /**
     * return one active ringing call from ringing calls
     *
     * Active call means the call is NOT idle defined by Call.isIdle()
     *
     * 1. If there is only one active ringing call, return it
     * 2. If there is more than one active ringing call, return the first one
     * 3. If there is no active ringing call, return the ringing call
     *    associated with default phone, which state is IDLE.
     * 4. If there is no ringing call at all, return null.
     *
     * Complete ringing calls list can be get by getRingingCalls()
     */
    public Call getFirstActiveRingingCall() {
        Call call = getFirstNonIdleCall(mRingingCalls);
        if (call == null) {
            call = (mDefaultPhone == null)
                    ? null
                    : mDefaultPhone.getRingingCall();
        }
        return call;
    }

    /**
     * @return the state of active foreground call
     * return IDLE if there is no active foreground call
     */
    public Call.State getActiveFgCallState() {
        Call fgCall = getActiveFgCall();

        if (fgCall != null) {
            return fgCall.getState();
        }

        return Call.State.IDLE;
    }

    /**
     * @return the connections of active foreground call
     * return empty list if there is no active foreground call
     */
    public List<Connection> getFgCallConnections() {
        Call fgCall = getActiveFgCall();
        if ( fgCall != null) {
            return fgCall.getConnections();
        }
        return mEmptyConnections;
    }

    /**
     * @return the connections of active background call
     * return empty list if there is no active background call
     */
    public List<Connection> getBgCallConnections() {
        Call bgCall = getFirstActiveBgCall();
        if ( bgCall != null) {
            return bgCall.getConnections();
        }
        return mEmptyConnections;
    }

    /**
     * @return the latest connection of active foreground call
     * return null if there is no active foreground call
     */
    public Connection getFgCallLatestConnection() {
        Call fgCall = getActiveFgCall();
        if ( fgCall != null) {
            return fgCall.getLatestConnection();
        }
        return null;
    }

    /**
     * @return true if there is at least one Foreground call in disconnected state
     */
    public boolean hasDisconnectedFgCall() {
        return (getFirstCallOfState(mForegroundCalls, Call.State.DISCONNECTED) != null);
    }

    /**
     * @return true if there is at least one background call in disconnected state
     */
    public boolean hasDisconnectedBgCall() {
        return (getFirstCallOfState(mBackgroundCalls, Call.State.DISCONNECTED) != null);
    }

    /**
     * @return the first active call from a call list
     */
    private  Call getFirstActiveCall(ArrayList<Call> calls) {
        for (Call call : calls) {
            if (!call.isIdle()) {
                return call;
            }
        }
        return null;
    }

    /**
     * @return the first call in a the Call.state from a call list
     */
    private Call getFirstCallOfState(ArrayList<Call> calls, Call.State state) {
        for (Call call : calls) {
            if (call.getState() == state) {
                return call;
            }
        }
        return null;
    }


    private boolean hasMoreThanOneRingingCall() {
        int count = 0;
        for (Call call : mRingingCalls) {
            if (call.getState().isRinging()) {
                if (++count > 1) return true;
            }
        }
        return false;
    }

    /**
     * @return true if the IMS phone has any active calls. ie. there are active
     *         IMS calls at present
     */
    public boolean isImsPhoneActive() {
        for (Phone phone : mPhones) {
            if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_IMS
                    && phone.getState() != PhoneConstants.State.IDLE) {
                return true;
            }
        }
        return false;
    }

    private boolean isRingingDuplicateCall() {
        return ((mRingingCalls.size() > 1) && (mRingingCalls.get(0).getLatestConnection().
                getAddress().equals(mRingingCalls.get(1).getLatestConnection().getAddress())));
    }

    protected class CmHandler extends Handler {

        @Override
        public void handleMessage(Message msg) {

            switch (msg.what) {
                case EVENT_DISCONNECT:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_DISCONNECT)");
                    mDisconnectRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_PRECISE_CALL_STATE_CHANGED:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_PRECISE_CALL_STATE_CHANGED)");
                    mPreciseCallStateRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_NEW_RINGING_CONNECTION:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_NEW_RINGING_CONNECTION)");
                    if (getActiveFgCallState().isDialing()
                            || (hasMoreThanOneRingingCall() && !isRingingDuplicateCall())) {
                        Connection c = (Connection) ((AsyncResult) msg.obj).result;
                        try {
                            Rlog.d(LOG_TAG, "silently drop incoming call: " + c.getCall());
                            c.getCall().hangup();
                        } catch (CallStateException e) {
                            Rlog.w(LOG_TAG, "new ringing connection", e);
                        }
                    } else {
                        mNewRingingConnectionRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    }
                    break;
                case EVENT_UNKNOWN_CONNECTION:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_UNKNOWN_CONNECTION)");
                    mUnknownConnectionRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_INCOMING_RING:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_INCOMING_RING)");
                    // The event may come from RIL who's not aware of an ongoing fg call
                    if (!hasActiveFgCall()) {
                        mIncomingRingRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    }
                    break;
                case EVENT_RINGBACK_TONE:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_RINGBACK_TONE)");
                    mRingbackToneRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_IN_CALL_VOICE_PRIVACY_ON:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_IN_CALL_VOICE_PRIVACY_ON)");
                    mInCallVoicePrivacyOnRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_IN_CALL_VOICE_PRIVACY_OFF:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_IN_CALL_VOICE_PRIVACY_OFF)");
                    mInCallVoicePrivacyOffRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_CALL_WAITING:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_CALL_WAITING)");
                    mCallWaitingRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_DISPLAY_INFO:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_DISPLAY_INFO)");
                    mDisplayInfoRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_SIGNAL_INFO:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_SIGNAL_INFO)");
                    mSignalInfoRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_CDMA_OTA_STATUS_CHANGE:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_CDMA_OTA_STATUS_CHANGE)");
                    mCdmaOtaStatusChangeRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_RESEND_INCALL_MUTE:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_RESEND_INCALL_MUTE)");
                    mResendIncallMuteRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_MMI_INITIATE:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_MMI_INITIATE)");
                    mMmiInitiateRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_MMI_COMPLETE:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_MMI_COMPLETE)");
                    mMmiCompleteRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_ECM_TIMER_RESET:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_ECM_TIMER_RESET)");
                    mEcmTimerResetRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_SUBSCRIPTION_INFO_READY:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_SUBSCRIPTION_INFO_READY)");
                    mSubscriptionInfoReadyRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_SUPP_SERVICE_FAILED:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_SUPP_SERVICE_FAILED)");
                    mSuppServiceFailedRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_SUPP_SERVICE_NOTIFY:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_SUPP_SERVICE_NOTIFICATION)");
                    mSuppServiceNotificationRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_SERVICE_STATE_CHANGED:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_SERVICE_STATE_CHANGED)");
                    mServiceStateChangedRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_CALL_MODIFY:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_CALL_MODIFY)");
                    AsyncResult ar = (AsyncResult) msg.obj;
                    if (ar != null && ar.result != null && ar.exception == null) {
                        mCallModifyRegistrants.notifyRegistrants(new AsyncResult(null,
                                (Connection) ar.result, null));
                    } else {
                        Rlog.e(LOG_TAG, "Error EVENT_MODIFY_CALL AsyncResult ar= " + ar);
                    }
                    break;
                case EVENT_POST_DIAL_CHARACTER:
                    // we need send the character that is being processed in msg.arg1
                    // so can't use notifyRegistrants()
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_POST_DIAL_CHARACTER)");
                    for(int i=0; i < mPostDialCharacterRegistrants.size(); i++) {
                        Message notifyMsg;
                        notifyMsg = ((Registrant)mPostDialCharacterRegistrants.get(i)).messageForRegistrant();
                        notifyMsg.obj = msg.obj;
                        notifyMsg.arg1 = msg.arg1;
                        notifyMsg.sendToTarget();
                    }
                    break;
                 case EVENT_CALL_MODIFY_RESPONSE:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_CALL_MODIFY_RESPONSE)");
                    AsyncResult res = (AsyncResult) msg.obj;
                    if (res != null && res.result != null && res.exception == null) {
                        mModifyCallResponseRegistrants.notifyRegistrants(new AsyncResult(null,
                                res.result, null));
                    } else {
                        Rlog.e(LOG_TAG, "EVENT_MODIFY_CALL_RESPONSE AsyncResult res= " + res);
                    }
                    break;
            }
        }
    }

    @Override
    public String toString() {
        Call call;
        StringBuilder b = new StringBuilder();

        b.append("CallManager {");
        b.append("\nstate = " + getState());
        call = getActiveFgCall();
        b.append("\n- Foreground: " + getActiveFgCallState());
        b.append(" from " + call.getPhone());
        b.append("\n  Conn: ").append(getFgCallConnections());
        call = getFirstActiveBgCall();
        b.append("\n- Background: " + call.getState());
        b.append(" from " + call.getPhone());
        b.append("\n  Conn: ").append(getBgCallConnections());
        call = getFirstActiveRingingCall();
        b.append("\n- Ringing: " +call.getState());
        b.append(" from " + call.getPhone());

        for (Phone phone : getAllPhones()) {
            if (phone != null) {
                b.append("\nPhone: " + phone + ", name = " + phone.getPhoneName()
                        + ", state = " + phone.getState());
                call = phone.getForegroundCall();
                b.append("\n- Foreground: ").append(call);
                call = phone.getBackgroundCall();
                b.append(" Background: ").append(call);
                call = phone.getRingingCall();
                b.append(" Ringing: ").append(call);
            }
        }
        b.append("\n}");
        return b.toString();
    }

    public void registerForSubscriptionChange(Handler h, int what, Object obj) {
        Rlog.e(LOG_TAG, "registerForSubscriptionChange for subscription not supported");
    }

    public void unregisterForSubscriptionChange(Handler h) {
        Rlog.e(LOG_TAG, "unregisterForSubscriptionChange for subscription not supported");
    }

    public boolean hasActiveFgCallAnyPhone() {
        return hasActiveFgCall();
    }

    public int getServiceState(int subscription) {
        Rlog.e(LOG_TAG, " getServiceState for subscription not supported");
        return ServiceState.STATE_OUT_OF_SERVICE;
    }

    public PhoneConstants.State getState(int subscription) {
        Rlog.e(LOG_TAG, " getState for subscription not supported");
        return PhoneConstants.State.IDLE;
    }

    public Phone getFgPhone(int subscription) {
        Rlog.e(LOG_TAG, " getFgPhone for subscription not supported");
        return null;
    }

    public Phone getBgPhone(int subscription) {
        Rlog.e(LOG_TAG, " getBgPhone for subscription not supported");
        return null;
    }

    public Phone getRingingPhone(int subscription) {
        Rlog.e(LOG_TAG, " getRingingPhone for subscription not supported");
        return null;
    }

    public Phone getPhoneInCall(int subscription) {
        Rlog.e(LOG_TAG, " getPhoneInCall for subscription not supported");
        return null;
    }

    public Call getFirstActiveRingingCall(int subscription) {
        return getFirstActiveRingingCall();
    }

    public Call getFirstActiveBgCall(int subscription) {
        return getFirstActiveBgCall();
    }

    public Call getActiveFgCall(int subscription) {
        Rlog.e(LOG_TAG, " getActiveFgCall for subscription not supported");
        return null;
    }

    public Call.State getActiveFgCallState(int subscription) {
        Rlog.e(LOG_TAG, " getActiveFgCallState for subscription not supported");
        return Call.State.IDLE;
    }

    public boolean hasActiveRingingCall(int subscription) {
        Rlog.e(LOG_TAG, " hasActiveRingingCall for subscription not supported");
        return false;
    }

    public boolean hasActiveFgCall(int subscription) {
        Rlog.e(LOG_TAG, " hasActiveFgCall for subscription not supported");
        return false;
    }

    public boolean hasActiveBgCall(int subscription) {
        Rlog.e(LOG_TAG, " hasActiveBgCall for subscription not supported");
        return false;
    }

    public boolean hasDisconnectedFgCall(int subscription) {
        Rlog.e(LOG_TAG, " hasDisconnectedFgCall for subscription not supported");
        return false;
    }

    public boolean hasDisconnectedBgCall(int subscription) {
        Rlog.e(LOG_TAG, " hasDisconnectedBgCall for subscription not supported");
        return false;
    }

    public void clearDisconnected(int subscription) {
        Rlog.e(LOG_TAG, " clearDisconnected for subscription not supported");
    }

    public List<Connection> getFgCallConnections(int subscription) {
        Rlog.e(LOG_TAG, " getFgCallConnections for subscription not supported");
        return null;
    }

    public Connection getFgCallLatestConnection(int subscription) {
        Rlog.e(LOG_TAG, " getFgCallLatestConnection for subscription not supported");
        return null;
    }

    public void setActiveSubscription(int subscription) {
        Rlog.e(LOG_TAG, " setActiveSubscription for subscription not supported");
    }

    public int getActiveSubscription() {
        Rlog.e(LOG_TAG, " getActiveSubscription for subscription not supported");
        return 0;
    }

    public void switchToLocalHold(int subscription, boolean switchTo) {
        Rlog.e(LOG_TAG, " switchToLocalHold for subscription not supported");
    }

    public boolean getLocalCallHoldStatus(int subscription) {
        Rlog.e(LOG_TAG, " getLocalCallHoldStatus for subscription not supported");
        return false;
    }

    public boolean canConference(Call heldCall, int subscription) {
        Rlog.e(LOG_TAG, " canConference for subscription not supported");
        return false;
    }

    public void deactivateLchState(int sub) {
        Rlog.e(LOG_TAG, " deactivateLchState not supported");
    }
}
