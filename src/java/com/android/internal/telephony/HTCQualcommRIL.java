/*
 * Copyright (C) 2012 The CyanogenMod Project
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

import static com.android.internal.telephony.RILConstants.*;

import android.content.Context;
import android.content.res.Resources;
import android.os.AsyncResult;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.telephony.CellInfo;
import android.telephony.Rlog;

import com.android.internal.telephony.cdma.CdmaERIInfo;
import com.android.internal.telephony.cdma.CdmaInformationRecords;
import com.android.internal.telephony.dataconnection.DataProfile;
import com.android.internal.telephony.dataconnection.DataProfileOmh;
import com.android.internal.telephony.dataconnection.DcFailCause;
import com.android.internal.telephony.dataconnection.DataCallResponse;
import com.android.internal.telephony.uicc.IccCardApplicationStatus;
import com.android.internal.telephony.uicc.IccCardStatus;

import java.util.ArrayList;

public class HTCQualcommRIL extends RIL implements CommandsInterface {

    static final int RIL_UNSOL_ENTER_LPM = 3023;
    static final int RIL_UNSOL_ENTER_LPM_S4 = 1523;
    static final int RIL_UNSOL_CDMA_3G_INDICATOR = 4259;
    static final int RIL_UNSOL_CDMA_3G_INDICATOR_S4 = 3009;
    static final int RIL_UNSOL_CDMA_ENHANCE_ROAMING_INDICATOR = 4262;
    static final int RIL_UNSOL_CDMA_ENHANCE_ROAMING_INDICATOR_S4 = 3012;
    static final int RIL_UNSOL_CDMA_NETWORK_BASE_PLUSCODE_DIAL = 4270;
    static final int RIL_UNSOL_CDMA_NETWORK_BASE_PLUSCODE_DIAL_S4 = 3020;
    static final int RIL_UNSOL_RESPONSE_PHONE_MODE_CHANGE = 4802;
    static final int RIL_UNSOL_RESPONSE_PHONE_MODE_CHANGE_S4 = 6002;
    static final int RIL_UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED_STUB = 5755;
    static final int RIL_UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED_STUB_S4 = 21005;
    static final int RIL_UNSOL_RESPONSE_DATA_NETWORK_STATE_CHANGED = 5757;
    static final int RIL_UNSOL_RESPONSE_DATA_NETWORK_STATE_CHANGED_S4 = 21007;
    
    private String homeOperator= SystemProperties.get("ro.cdma.home.operator.numeric");
    private String operator= SystemProperties.get("ro.cdma.home.operator.alpha");
    private String[] lastKnownOfGood = {null, null, null};

    public HTCQualcommRIL(Context context, int networkMode, int cdmaSubscription) {
        super(context, networkMode, cdmaSubscription);
    }

    @Override
    protected Object
    responseIccCardStatus(Parcel p) {
        // get type from Settings.Global default to CDMA + LTE network mode
        boolean forceCdmaLte = needsOldRilFeature("forceCdmaLteNetworkType");
        if (forceCdmaLte) {
            int phoneType = android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                            android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                            NETWORK_MODE_LTE_CDMA_EVDO);
            setPreferredNetworkType(phoneType, null);
        }

        return super.responseIccCardStatus(p);
    }

    @Override
    protected DataCallResponse getDataCallResponse(Parcel p, int version) {
        boolean oldDataCall = needsOldRilFeature("oldDataCall");
        if (oldDataCall) {
        DataCallResponse dataCall = new DataCallResponse();

        dataCall.version = version;
        dataCall.status = p.readInt();
        dataCall.suggestedRetryTime = p.readInt();
        dataCall.cid = p.readInt();
        dataCall.active = p.readInt();
        dataCall.type = p.readString();
        dataCall.ifname = p.readString();
        /* Check dataCall.active != 0 so address, dns, gateways are provided
         * when switching LTE<->3G<->2G */
            if ((dataCall.status == DcFailCause.NONE.getErrorCode()) &&
                    TextUtils.isEmpty(dataCall.ifname) && dataCall.active != 0) {
                throw new RuntimeException("getDataCallResponse, no ifname");
            }
            String addresses = p.readString();
            if (!TextUtils.isEmpty(addresses)) {
                dataCall.addresses = addresses.split(" ");
            }
            String dnses = p.readString();
            if (!TextUtils.isEmpty(dnses)) {
                dataCall.dnses = dnses.split(" ");
            }
            String gateways = p.readString();
            if (!TextUtils.isEmpty(gateways)) {
                dataCall.gateways = gateways.split(" ");
            }
            return dataCall;
        } else {
        return super.getDataCallResponse(p,version);
        }
    }

    @Override
    protected void
    processSolicited (Parcel p) {
        int serial, error;
        boolean found = false;

        serial = p.readInt();
        error = p.readInt();

        RILRequest rr;

        rr = findAndRemoveRequestFromList(serial);

        if (rr == null) {
            Rlog.w(RILJ_LOG_TAG, "Unexpected solicited response! sn: "
                            + serial + " error: " + error);
            return;
        }

        Object ret = null;

        if (error == 0 || p.dataAvail() > 0) {
            // either command succeeds or command fails but with data payload
            try {switch (rr.mRequest) {
            /*
 cat libs/telephony/ril_commands.h \
 | egrep "^ *{RIL_" \
 | sed -re 's/\{([^,]+),[^,]+,([^}]+).+/case \1: ret = \2(p); break;/'
             */
            case RIL_REQUEST_GET_SIM_STATUS: ret =  responseIccCardStatus(p); break;
            case RIL_REQUEST_ENTER_SIM_PIN: ret =  responseInts(p); break;
            case RIL_REQUEST_ENTER_SIM_PUK: ret =  responseInts(p); break;
            case RIL_REQUEST_ENTER_SIM_PIN2: ret =  responseInts(p); break;
            case RIL_REQUEST_ENTER_SIM_PUK2: ret =  responseInts(p); break;
            case RIL_REQUEST_CHANGE_SIM_PIN: ret =  responseInts(p); break;
            case RIL_REQUEST_CHANGE_SIM_PIN2: ret =  responseInts(p); break;
            case RIL_REQUEST_ENTER_DEPERSONALIZATION_CODE: ret =  responseInts(p); break;
            case RIL_REQUEST_GET_CURRENT_CALLS: ret =  responseCallList(p); break;
            case RIL_REQUEST_DIAL: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_IMSI: ret =  responseString(p); break;
            case RIL_REQUEST_HANGUP: ret =  responseVoid(p); break;
            case RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND: ret =  responseVoid(p); break;
            case RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND: {
                if (mTestingEmergencyCall.getAndSet(false)) {
                    if (mEmergencyCallbackModeRegistrant != null) {
                        riljLog("testing emergency call, notify ECM Registrants");
                        mEmergencyCallbackModeRegistrant.notifyRegistrant();
                    }
                }
                ret =  responseVoid(p);
                break;
            }
            case RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE: ret =  responseVoid(p); break;
            case RIL_REQUEST_CONFERENCE: ret =  responseVoid(p); break;
            case RIL_REQUEST_UDUB: ret =  responseVoid(p); break;
            case RIL_REQUEST_LAST_CALL_FAIL_CAUSE: ret =  responseInts(p); break;
            case RIL_REQUEST_SIGNAL_STRENGTH: ret =  responseSignalStrength(p); break;
            case RIL_REQUEST_VOICE_REGISTRATION_STATE: ret =  responseVoiceDataRegistrationState(p); break;
            case RIL_REQUEST_DATA_REGISTRATION_STATE: ret =  responseVoiceDataRegistrationState(p); break;
            case RIL_REQUEST_OPERATOR: ret =  operatorCheck(p); break;
            case RIL_REQUEST_RADIO_POWER: ret =  responseVoid(p); break;
            case RIL_REQUEST_DTMF: ret =  responseVoid(p); break;
            case RIL_REQUEST_SEND_SMS: ret =  responseSMS(p); break;
            case RIL_REQUEST_SEND_SMS_EXPECT_MORE: ret =  responseSMS(p); break;
            case RIL_REQUEST_SETUP_DATA_CALL: ret =  responseSetupDataCall(p); break;
            case RIL_REQUEST_SIM_IO: ret =  responseICC_IO(p); break;
            case RIL_REQUEST_SEND_USSD: ret =  responseVoid(p); break;
            case RIL_REQUEST_CANCEL_USSD: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_CLIR: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_CLIR: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_CALL_FORWARD_STATUS: ret =  responseCallForward(p); break;
            case RIL_REQUEST_SET_CALL_FORWARD: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_CALL_WAITING: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_CALL_WAITING: ret =  responseVoid(p); break;
            case RIL_REQUEST_SMS_ACKNOWLEDGE: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_IMEI: ret =  responseString(p); break;
            case RIL_REQUEST_GET_IMEISV: ret =  responseString(p); break;
            case RIL_REQUEST_ANSWER: ret =  responseVoid(p); break;
            case RIL_REQUEST_DEACTIVATE_DATA_CALL: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_FACILITY_LOCK: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_FACILITY_LOCK: ret =  responseInts(p); break;
            case RIL_REQUEST_CHANGE_BARRING_PASSWORD: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_NETWORK_SELECTION_AUTOMATIC: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_AVAILABLE_NETWORKS : ret =  responseOperatorInfos(p); break;
            case RIL_REQUEST_DTMF_START: ret =  responseVoid(p); break;
            case RIL_REQUEST_DTMF_STOP: ret =  responseVoid(p); break;
            case RIL_REQUEST_BASEBAND_VERSION: ret =  responseString(p); break;
            case RIL_REQUEST_SEPARATE_CONNECTION: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_MUTE: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_MUTE: ret =  responseInts(p); break;
            case RIL_REQUEST_QUERY_CLIP: ret =  responseInts(p); break;
            case RIL_REQUEST_LAST_DATA_CALL_FAIL_CAUSE: ret =  responseInts(p); break;
            case RIL_REQUEST_DATA_CALL_LIST: ret =  responseDataCallList(p); break;
            case RIL_REQUEST_RESET_RADIO: ret =  responseVoid(p); break;
            case RIL_REQUEST_OEM_HOOK_RAW: ret =  responseRaw(p); break;
            case RIL_REQUEST_OEM_HOOK_STRINGS: ret =  responseStrings(p); break;
            case RIL_REQUEST_SCREEN_STATE: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_SUPP_SVC_NOTIFICATION: ret =  responseVoid(p); break;
            case RIL_REQUEST_WRITE_SMS_TO_SIM: ret =  responseInts(p); break;
            case RIL_REQUEST_DELETE_SMS_ON_SIM: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_BAND_MODE: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_AVAILABLE_BAND_MODE: ret =  responseInts(p); break;
            case RIL_REQUEST_STK_GET_PROFILE: ret =  responseString(p); break;
            case RIL_REQUEST_STK_SET_PROFILE: ret =  responseVoid(p); break;
            case RIL_REQUEST_STK_SEND_ENVELOPE_COMMAND: ret =  responseString(p); break;
            case RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE: ret =  responseVoid(p); break;
            case RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM: ret =  responseInts(p); break;
            case RIL_REQUEST_EXPLICIT_CALL_TRANSFER: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE: ret =  responseGetPreferredNetworkType(p); break;
            case RIL_REQUEST_GET_NEIGHBORING_CELL_IDS: ret = responseCellList(p); break;
            case RIL_REQUEST_SET_LOCATION_UPDATES: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_TTY_MODE: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_TTY_MODE: ret =  responseInts(p); break;
            case RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE: ret =  responseInts(p); break;
            case RIL_REQUEST_CDMA_FLASH: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_BURST_DTMF: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_SEND_SMS: ret =  responseSMS(p); break;
            case RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE: ret =  responseVoid(p); break;
            case RIL_REQUEST_GSM_GET_BROADCAST_CONFIG: ret =  responseGmsBroadcastConfig(p); break;
            case RIL_REQUEST_GSM_SET_BROADCAST_CONFIG: ret =  responseVoid(p); break;
            case RIL_REQUEST_GSM_BROADCAST_ACTIVATION: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG: ret =  responseCdmaBroadcastConfig(p); break;
            case RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_BROADCAST_ACTIVATION: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_SUBSCRIPTION: ret =  responseStrings(p); break;
            case RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM: ret =  responseInts(p); break;
            case RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM: ret =  responseVoid(p); break;
            case RIL_REQUEST_DEVICE_IDENTITY: ret =  responseStrings(p); break;
            case RIL_REQUEST_GET_SMSC_ADDRESS: ret = responseString(p); break;
            case RIL_REQUEST_SET_SMSC_ADDRESS: ret = responseVoid(p); break;
            case RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE: ret = responseVoid(p); break;
            case RIL_REQUEST_REPORT_SMS_MEMORY_STATUS: ret = responseVoid(p); break;
            case RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING: ret = responseVoid(p); break;
            case RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE: ret =  responseInts(p); break;
            case RIL_REQUEST_GET_DATA_CALL_PROFILE: ret =  responseGetDataCallProfile(p); break;
            case RIL_REQUEST_ISIM_AUTHENTICATION: ret =  responseString(p); break;
            case RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU: ret = responseVoid(p); break;
            case RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS: ret = responseICC_IO(p); break;
            case RIL_REQUEST_VOICE_RADIO_TECH: ret = responseInts(p); break;
            case RIL_REQUEST_GET_CELL_INFO_LIST: ret = responseCellInfoList(p); break;
            case RIL_REQUEST_SET_UNSOL_CELL_INFO_LIST_RATE: ret = responseVoid(p); break;
            case RIL_REQUEST_IMS_REGISTRATION_STATE: ret = responseInts(p); break;
            case RIL_REQUEST_IMS_SEND_SMS: ret =  responseSMS(p); break;
            case RIL_REQUEST_SET_UICC_SUBSCRIPTION: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_DATA_SUBSCRIPTION: ret = responseVoid(p); break;
            case RIL_REQUEST_GET_UICC_SUBSCRIPTION: ret = responseUiccSubscription(p); break;
            case RIL_REQUEST_GET_DATA_SUBSCRIPTION: ret = responseInts(p); break;
            default:
                throw new RuntimeException("Unrecognized solicited response: " + rr.mRequest);
            //break;
            }} catch (Throwable tr) {
                // Exceptions here usually mean invalid RIL responses

                Rlog.w(RILJ_LOG_TAG, rr.serialString() + "< "
                        + requestToString(rr.mRequest)
                        + " exception, possible invalid RIL response", tr);

                if (rr.mResult != null) {
                    AsyncResult.forMessage(rr.mResult, null, tr);
                    rr.mResult.sendToTarget();
                }
                rr.release();
                return;
            }
        }

        // Here and below fake RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED, see b/7255789.
        // This is needed otherwise we don't automatically transition to the main lock
        // screen when the pin or puk is entered incorrectly.
        switch (rr.mRequest) {
            case RIL_REQUEST_ENTER_SIM_PUK:
            case RIL_REQUEST_ENTER_SIM_PUK2:
                if (mIccStatusChangedRegistrants != null) {
                    if (RILJ_LOGD) {
                        riljLog("ON enter sim puk fakeSimStatusChanged: reg count="
                                + mIccStatusChangedRegistrants.size());
                    }
                    mIccStatusChangedRegistrants.notifyRegistrants();
                }
                break;
        }

        if (error != 0) {
            switch (rr.mRequest) {
                case RIL_REQUEST_ENTER_SIM_PIN:
                case RIL_REQUEST_ENTER_SIM_PIN2:
                case RIL_REQUEST_CHANGE_SIM_PIN:
                case RIL_REQUEST_CHANGE_SIM_PIN2:
                case RIL_REQUEST_SET_FACILITY_LOCK:
                    if (mIccStatusChangedRegistrants != null) {
                        if (RILJ_LOGD) {
                            riljLog("ON some errors fakeSimStatusChanged: reg count="
                                    + mIccStatusChangedRegistrants.size());
                        }
                        mIccStatusChangedRegistrants.notifyRegistrants();
                    }
                    break;
            }

            rr.onError(error, ret);
            rr.release();
            return;
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "< " + requestToString(rr.mRequest)
            + " " + retToString(rr.mRequest, ret));

        if (rr.mResult != null) {
            AsyncResult.forMessage(rr.mResult, ret, null);
            rr.mResult.sendToTarget();
        }

        rr.release();
    }

    // CDMA FIXES, this fixes  bogus values in nv/sim on cdma family or bogus information from sim card
    private Object
    operatorCheck(Parcel p) {
        String response[] = (String[])responseStrings(p);
        for(int i=0; i<response.length; i++){
            if (response[i]!= null){
                if (i<2){
                    if (response[i].equals("       Empty") || (response[i].equals(""))) {
                        response[i]=operator;
                    } else if (!response[i].equals(""))  {
                        try {
                            Integer.parseInt(response[i]);
                            response[i]=Operators.operatorReplace(response[i]);
                            //optimize
                            if(i==0)
                                response[i+1]=response[i];
                        }  catch(NumberFormatException E){
                            // do nothing
                        }
                    }
                } else if (response[i].equals("31000")|| response[i].equals("11111") || response[i].equals("123456") || response[i].equals("31099") || (response[i].equals(""))){
                        response[i]=homeOperator;
                }
                lastKnownOfGood[i]=response[i];
            }else{
                if(lastKnownOfGood[i]!=null)
                    response[i]=lastKnownOfGood[i];
            }
        }
        return response;
    }
    // handle exceptions
    private Object
    responseVoiceDataRegistrationState(Parcel p) {
        String response[] = (String[])responseStrings(p);
        if ( response.length>=10){
            for(int i=6; i<=9; i++){
                if (response[i]== null){
                    response[i]=Integer.toString(Integer.MAX_VALUE);
                } else {
                    try {
                        Integer.parseInt(response[i]);
                    } catch(NumberFormatException e) {
                        response[i]=Integer.toString(Integer.parseInt(response[i],16));
                    }
                }
            }
        }
        return response;
    }

    private ArrayList<DataProfile> responseGetDataCallProfile(Parcel p) {
        int nProfiles = p.readInt();
        if (RILJ_LOGD) riljLog("# data call profiles:" + nProfiles);

        ArrayList<DataProfile> response = new ArrayList<DataProfile>(nProfiles);

        int profileId = 0;
        int priority = 0;
        for (int i = 0; i < nProfiles; i++) {
            profileId = p.readInt();
            priority = p.readInt();
            DataProfileOmh profile = new DataProfileOmh(profileId, priority);
            if (RILJ_LOGD) riljLog("responseGetDataCallProfile()" +
                    profile.getProfileId() + ":" + profile.getPriority());
            response.add(profile);
        }

        return response;
    }

    private Object
    responseUiccSubscription(Parcel p) {
        //TODO MultiSIM functionality
        //currently get uicc subscripton is not queried from RIL.
        return null;
    }

    private Object
    responseCdmaERIInfo(Parcel p) {
        CdmaERIInfo mCdmaERIInfo = new CdmaERIInfo();
        mCdmaERIInfo.carrier_id = p.readInt();
        mCdmaERIInfo.eri_id = p.readInt();
        mCdmaERIInfo.icon_img_id = p.readInt();
        mCdmaERIInfo.param1 = p.readInt();
        mCdmaERIInfo.param2 = p.readInt();
        mCdmaERIInfo.param3 = p.readInt();
        mCdmaERIInfo.param4 = p.readInt();
        mCdmaERIInfo.text = p.readString();
        mCdmaERIInfo.data_support = p.readInt();

        if (p.dataAvail() > 0)
            mCdmaERIInfo.roaming_type = p.readInt();
        return mCdmaERIInfo;
    }

    @Override
    protected void
    processUnsolicited (Parcel p) {
        Object ret;
        int dataPosition = p.dataPosition(); // save off position within the Parcel
        int response = p.readInt();

        switch(response) {
            case RIL_UNSOL_ENTER_LPM:
                ret = responseVoid(p);
                if (mEnterLowPowerModeRegistrants != null) {
                    mEnterLowPowerModeRegistrants.notifyResult(ret);
                }
                break;
            case RIL_UNSOL_ENTER_LPM_S4:
                ret = responseVoid(p);
                if (mEnterLowPowerModeRegistrants != null) {
                    mEnterLowPowerModeRegistrants.notifyResult(ret);
                }
                break;
            case RIL_UNSOL_CDMA_3G_INDICATOR:
                ret = responseInts(p);
                if (m3GIndicatorRegistrants != null) {
                    m3GIndicatorRegistrants.notifyResult(ret);
                }
                break;
            case RIL_UNSOL_CDMA_3G_INDICATOR_S4:
                ret = responseInts(p);
                if (m3GIndicatorRegistrants != null) {
                    m3GIndicatorRegistrants.notifyResult(ret);
                }
                break;
            case RIL_UNSOL_CDMA_ENHANCE_ROAMING_INDICATOR:
                ret = responseCdmaERIInfo(p);
                if (mERIRegistrants != null) {
                    mERIRegistrants.notifyResult(ret);
                }
                break;
            case RIL_UNSOL_CDMA_ENHANCE_ROAMING_INDICATOR_S4:
                ret = responseCdmaERIInfo(p);
                if (mERIRegistrants != null) {
                    mERIRegistrants.notifyResult(ret);
                }
                break;
            case RIL_UNSOL_CDMA_NETWORK_BASE_PLUSCODE_DIAL:
                ret = responseStrings(p);
                if (mNBPCDRegistrants != null) {
                    mNBPCDRegistrants.notifyResult(ret);
                }
                break;
            case RIL_UNSOL_CDMA_NETWORK_BASE_PLUSCODE_DIAL_S4:
                ret = responseStrings(p);
                if (mNBPCDRegistrants != null) {
                    mNBPCDRegistrants.notifyResult(ret);
                }
                break;
            case RIL_UNSOL_RESPONSE_PHONE_MODE_CHANGE:
                ret = responseInts(p);
                break;
            case RIL_UNSOL_RESPONSE_PHONE_MODE_CHANGE_S4:
                ret = responseInts(p);
                break;
            case RIL_UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED_STUB:
                ret = responseVoid(p);
                if (mIccAppRefreshRegistrant != null) {
                    mIccAppRefreshRegistrant.notifyRegistrant(
                                        new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED_STUB_S4:
                ret = responseVoid(p);
                if (mIccAppRefreshRegistrant != null) {
                    mIccAppRefreshRegistrant.notifyRegistrant(
                                        new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_RESPONSE_DATA_NETWORK_STATE_CHANGED:
                ret = responseInts(p);
                if (mExitEmergencyCallbackModeRegistrants != null) {
                    mExitEmergencyCallbackModeRegistrants.notifyRegistrants(
                                        new AsyncResult (null, null, null));
                }
                break;
            case RIL_UNSOL_RESPONSE_DATA_NETWORK_STATE_CHANGED_S4:
                ret = responseInts(p);
                if (mExitEmergencyCallbackModeRegistrants != null) {
                    mExitEmergencyCallbackModeRegistrants.notifyRegistrants(
                                        new AsyncResult (null, null, null));
                }
                break;
            case RIL_UNSOL_RIL_CONNECTED:
                ret = responseInts(p);
                setRadioPower(false, null);
                setPreferredNetworkType(mPreferredNetworkType, null);
                int cdmaSubscription = Settings.Global.getInt(mContext.getContentResolver(), Settings.Global.CDMA_SUBSCRIPTION_MODE, -1);
                if(cdmaSubscription != -1) {
                    setCdmaSubscriptionSource(mCdmaSubscription, null);
                }
                setCellInfoListRate(Integer.MAX_VALUE, null);
                notifyRegistrantsRilConnectionChanged(((int[])ret)[0]);
                break;
            default:
            // Rewind the Parcel
            p.setDataPosition(dataPosition);

            // Forward responses that we are not overriding to the super class
            super.processUnsolicited(p);
            return;
        }
    }
}
