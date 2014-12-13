/*
 * Copyright (C) 2011-2012 The Android Open Source Project
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

package com.android.internal.telephony.uicc;

import android.content.Context;
import android.content.Intent;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.telephony.TelephonyManager;
import android.telephony.Rlog;

import android.telephony.ServiceState;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppState;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * This class is responsible for keeping all knowledge about
 * Universal Integrated Circuit Card (UICC), also know as SIM's,
 * in the system. It is also used as API to get appropriate
 * applications to pass them to phone and service trackers.
 *
 * UiccController is created with the call to make() function.
 * UiccController is a singleton and make() must only be called once
 * and throws an exception if called multiple times.
 *
 * Once created UiccController registers with RIL for "on" and "unsol_sim_status_changed"
 * notifications. When such notification arrives UiccController will call
 * getIccCardStatus (GET_SIM_STATUS). Based on the response of GET_SIM_STATUS
 * request appropriate tree of uicc objects will be created.
 *
 * Following is class diagram for uicc classes:
 *
 *                       UiccController
 *                            #
 *                            |
 *                        UiccCard
 *                          #   #
 *                          |   ------------------
 *                    UiccCardApplication    CatService
 *                      #            #
 *                      |            |
 *                 IccRecords    IccFileHandler
 *                 ^ ^ ^           ^ ^ ^ ^ ^
 *    SIMRecords---- | |           | | | | ---SIMFileHandler
 *    RuimRecords----- |           | | | ----RuimFileHandler
 *    IsimUiccRecords---           | | -----UsimFileHandler
 *                                 | ------CsimFileHandler
 *                                 ----IsimFileHandler
 *
 * Legend: # stands for Composition
 *         ^ stands for Generalization
 *
 * See also {@link com.android.internal.telephony.IccCard}
 * and {@link com.android.internal.telephony.uicc.IccCardProxy}
 */
public class UiccController extends Handler {
    private static final boolean DBG = true;
    private static final String LOG_TAG = "UiccController";

    public static final int APP_FAM_UNKNOWN =  -1;
    public static final int APP_FAM_3GPP =  1;
    public static final int APP_FAM_3GPP2 = 2;
    public static final int APP_FAM_IMS   = 3;

    private static final int EVENT_ICC_STATUS_CHANGED = 1;
    private static final int EVENT_GET_ICC_STATUS_DONE = 2;
    private static final int EVENT_RADIO_UNAVAILABLE = 3;
    private static final int EVENT_REFRESH = 4;
    private static final int EVENT_REFRESH_OEM = 5;

    private CommandsInterface[] mCis;
    private UiccCard[] mUiccCards = new UiccCard[TelephonyManager.getDefault().getPhoneCount()];

    private static final Object mLock = new Object();
    private static UiccController mInstance;

    private Context mContext;
/*
    private CommandsInterface mCi;
    private UiccCard mUiccCard;
*/

    protected RegistrantList mIccChangedRegistrants = new RegistrantList();

    private boolean mOEMHookSimRefresh = false;

/*
    public static UiccController make(Context c, CommandsInterface ci) {
        synchronized (mLock) {
            if (mInstance != null) {
                throw new RuntimeException("UiccController.make() should only be called once");
            }
            mInstance = new UiccController(c, ci);
            return mInstance;
        }
    }
*/

    public static UiccController make(Context c, CommandsInterface[] ci) {
        synchronized (mLock) {
            if (mInstance != null) {
                throw new RuntimeException("MSimUiccController.make() should only be called once");
            }
            mInstance = new UiccController(c, ci);
            return (UiccController)mInstance;
        }
    }

    private UiccController(Context c, CommandsInterface []ci) {
        if (DBG) log("Creating UiccController");
        mContext = c;
        mCis = ci;
        mOEMHookSimRefresh = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_sim_refresh_for_dual_mode_card);
        for (int i = 0; i < mCis.length; i++) {
            Integer index = new Integer(i);
            mCis[i].registerForIccStatusChanged(this, EVENT_ICC_STATUS_CHANGED, index);
            // TODO remove this once modem correctly notifies the unsols
            mCis[i].registerForAvailable(this, EVENT_ICC_STATUS_CHANGED, index);
            mCis[i].registerForNotAvailable(this, EVENT_RADIO_UNAVAILABLE, index);
            if (mOEMHookSimRefresh) {
                mCis[i].registerForSimRefreshEvent(this, EVENT_REFRESH_OEM, index);
            } else {
                mCis[i].registerForIccRefresh(this, EVENT_REFRESH, index);
            }
        }
    }

    public static UiccController getInstance() {
        synchronized (mLock) {
            if (mInstance == null) {
                throw new RuntimeException(
                        "UiccController.getInstance can't be called before make()");
            }
            return mInstance;
        }
    }

    public UiccCard getUiccCard() {
        return getUiccCard(SubscriptionController.getInstance().getPhoneId(SubscriptionController.getInstance().getDefaultSubId()));
    }

    public UiccCard getUiccCard(int slotId) {
        synchronized (mLock) {
            if (isValidCardIndex(slotId)) {
                return mUiccCards[slotId];
            }
            return null;
        }
    }

    public UiccCard[] getUiccCards() {
        // Return cloned array since we don't want to give out reference
        // to internal data structure.
        synchronized (mLock) {
            return mUiccCards.clone();
        }
    }

    // Easy to use API
    public UiccCardApplication getUiccCardApplication(int family) {
        return getUiccCardApplication(SubscriptionController.getInstance().getPhoneId(
                SubscriptionController.getInstance().getDefaultSubId()), family);
    }

/*
    // Easy to use API
    public IccRecords getIccRecords(int family) {
        synchronized (mLock) {
            if (mUiccCard != null) {
                UiccCardApplication app = mUiccCard.getApplication(family);
                if (app != null) {
                    return app.getIccRecords();
                }
            }
            return null;
        }
    }
*/

    // Easy to use API
    public IccRecords getIccRecords(int slotId, int family) {
        synchronized (mLock) {
            UiccCardApplication app = getUiccCardApplication(slotId, family);
            if (app != null) {
                return app.getIccRecords();
            }
            return null;
        }
    }

/*
    // Easy to use API
    public IccFileHandler getIccFileHandler(int family) {
        synchronized (mLock) {
            if (mUiccCard != null) {
                UiccCardApplication app = mUiccCard.getApplication(family);
                if (app != null) {
                    return app.getIccFileHandler();
                }
            }
            return null;
        }
    }
*/

    // Easy to use API
    public IccFileHandler getIccFileHandler(int slotId, int family) {
        synchronized (mLock) {
            UiccCardApplication app = getUiccCardApplication(slotId, family);
            if (app != null) {
                return app.getIccFileHandler();
            }
            return null;
        }
    }


    public static int getFamilyFromRadioTechnology(int radioTechnology) {
        if (ServiceState.isGsm(radioTechnology) ||
                radioTechnology == ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD) {
            return  UiccController.APP_FAM_3GPP;
        } else if (ServiceState.isCdma(radioTechnology)) {
            return  UiccController.APP_FAM_3GPP2;
        } else {
            // If it is UNKNOWN rat
            return UiccController.APP_FAM_UNKNOWN;
        }
    }

    //Notifies when card status changes
    public void registerForIccChanged(Handler h, int what, Object obj) {
        synchronized (mLock) {
            Registrant r = new Registrant (h, what, obj);
            mIccChangedRegistrants.add(r);
            //Notify registrant right after registering, so that it will get the latest ICC status,
            //otherwise which may not happen until there is an actual change in ICC status.
            r.notifyRegistrant();
        }
    }

    public void unregisterForIccChanged(Handler h) {
        synchronized (mLock) {
            mIccChangedRegistrants.remove(h);
        }
    }

    @Override
    public void handleMessage (Message msg) {
        synchronized (mLock) {
            Integer index = getCiIndex(msg);

            if (index < 0 || index >= mCis.length) {
                Rlog.e(LOG_TAG, "Invalid index : " + index + " received with event " + msg.what);
                return;
            }

            switch (msg.what) {
                case EVENT_ICC_STATUS_CHANGED:
                    if (DBG) log("Received EVENT_ICC_STATUS_CHANGED, calling getIccCardStatus");
                    mCis[index].getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE, index));
                    break;
                case EVENT_GET_ICC_STATUS_DONE:
                    if (DBG) log("Received EVENT_GET_ICC_STATUS_DONE");
                    AsyncResult ar = (AsyncResult)msg.obj;
                    onGetIccCardStatusDone(ar, index);
                    break;
                case EVENT_RADIO_UNAVAILABLE:
                    if (DBG) log("EVENT_RADIO_UNAVAILABLE, dispose card");
                    if (mUiccCards[index] != null) {
                        mUiccCards[index].dispose();
                    }
                    mUiccCards[index] = null;
                    mIccChangedRegistrants.notifyRegistrants(new AsyncResult(null, index, null));
                    break;
                case EVENT_REFRESH:
                    ar = (AsyncResult)msg.obj;
                    if (DBG) log("Sim REFRESH received");
                    if (ar.exception == null) {
                        handleRefresh((IccRefreshResponse)ar.result, index);
                    } else {
                        log ("Exception on refresh " + ar.exception);
                    }
                    break;
                case EVENT_REFRESH_OEM:
                    ar = (AsyncResult)msg.obj;
                    if (DBG) log("Sim REFRESH OEM received");
                    if (ar.exception == null) {
                        ByteBuffer payload = ByteBuffer.wrap((byte[])ar.result);
                        handleRefresh(parseOemSimRefresh(payload), index);
                    } else {
                        log ("Exception on refresh " + ar.exception);
                    }
                    break;
                default:
                    Rlog.e(LOG_TAG, " Unknown Event " + msg.what);
            }
        }
    }

    private Integer getCiIndex(Message msg) {
        AsyncResult ar;
        Integer index = new Integer(PhoneConstants.DEFAULT_CARD_INDEX);

        /*
         * The events can be come in two ways. By explicitly sending it using
         * sendMessage, in this case the user object passed is msg.obj and from
         * the CommandsInterface, in this case the user object is msg.obj.userObj
         */
        if (msg != null) {
            if (msg.obj != null && msg.obj instanceof Integer) {
                index = (Integer)msg.obj;
            } else if(msg.obj != null && msg.obj instanceof AsyncResult) {
                ar = (AsyncResult)msg.obj;
                if (ar.userObj != null && ar.userObj instanceof Integer) {
                    index = (Integer)ar.userObj;
                }
            }
        }
        return index;
    }

    private void handleRefresh(IccRefreshResponse refreshResponse, int index){
        if (refreshResponse == null) {
            log("handleRefresh received without input");
            return;
        }

        // Let the card know right away that a refresh has occurred
        if (mUiccCards[index] != null) {
            mUiccCards[index].onRefresh(refreshResponse);
        }
        // The card status could have changed. Get the latest state
        mCis[index].getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE));
    }

    public static IccRefreshResponse
    parseOemSimRefresh(ByteBuffer payload) {
        IccRefreshResponse response = new IccRefreshResponse();
        /* AID maximum size */
        final int QHOOK_MAX_AID_SIZE = 20*2+1+3;

        /* parse from payload */
        payload.order(ByteOrder.nativeOrder());

        response.refreshResult = payload.getInt();
        response.efId  = payload.getInt();
        int aidLen = payload.getInt();
        byte[] aid = new byte[QHOOK_MAX_AID_SIZE];
        payload.get(aid, 0, QHOOK_MAX_AID_SIZE);
        //Read the aid string with QHOOK_MAX_AID_SIZE from payload at first because need
        //corresponding to the aid array length sent from qcril and need parse the payload
        //to get app type and sub id in IccRecords.java after called this method.
        response.aid = (aidLen == 0) ? null : (new String(aid)).substring(0, aidLen);

        if (DBG){
            Rlog.d(LOG_TAG, "refresh SIM card " + ", refresh result:" + response.refreshResult
                    + ", ef Id:" + response.efId + ", aid:" + response.aid);
        }
        return response;
    }
/*
    private UiccController(Context c, CommandsInterface ci) {
        if (DBG) log("Creating UiccController");
        mContext = c;
        mCi = ci;
        mCi.registerForIccStatusChanged(this, EVENT_ICC_STATUS_CHANGED, null);
        // This is needed so that we query for sim status in the case when we boot in APM
        mCi.registerForAvailable(this, EVENT_ICC_STATUS_CHANGED, null);
    }
*/
    // Easy to use API
    public UiccCardApplication getUiccCardApplication(int slotId, int family) {
        synchronized (mLock) {
            if (isValidCardIndex(slotId)) {
                UiccCard c = mUiccCards[slotId];
                if (c != null) {
                    return mUiccCards[slotId].getApplication(family);
                }
            }
            return null;
        }
    }

    private synchronized void onGetIccCardStatusDone(AsyncResult ar, Integer index) {
        if (ar.exception != null) {
            Rlog.e(LOG_TAG,"Error getting ICC status. "
                    + "RIL_REQUEST_GET_ICC_STATUS should "
                    + "never return an error", ar.exception);
            return;
        }
        if (!isValidCardIndex(index)) {
            Rlog.e(LOG_TAG,"onGetIccCardStatusDone: invalid index : " + index);
            return;
        }

        IccCardStatus status = (IccCardStatus)ar.result;

        if (mUiccCards[index] == null) {
            //Create new card
            mUiccCards[index] = new UiccCard(mContext, mCis[index], status, index);

/*
            // Update the UiccCard in base class, so that if someone calls
            // UiccManager.getUiccCard(), it will return the default card.
            if (index == PhoneConstants.DEFAULT_CARD_INDEX) {
                mUiccCard = mUiccCards[index];
            }
*/
        } else {
            //Update already existing card
            mUiccCards[index].update(mContext, mCis[index] , status);
        }

        if (DBG) log("Notifying IccChangedRegistrants");
        mIccChangedRegistrants.notifyRegistrants(new AsyncResult(null, index, null));

    }

    private boolean isValidCardIndex(int index) {
        return (index >= 0 && index < mUiccCards.length);
    }

    private void log(String string) {
        Rlog.d(LOG_TAG, string);
    }


    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("UiccController: " + this);
        pw.println(" mContext=" + mContext);
        pw.println(" mInstance=" + mInstance);
//        pw.println(" mCi=" + mCi);
//        pw.println(" mUiccCard=" + mUiccCard);
        pw.println(" mIccChangedRegistrants: size=" + mIccChangedRegistrants.size());
        for (int i = 0; i < mIccChangedRegistrants.size(); i++) {
            pw.println("  mIccChangedRegistrants[" + i + "]="
                    + ((Registrant)mIccChangedRegistrants.get(i)).getHandler());
        }
        pw.println();
        pw.flush();
//        for (int i = 0; i < mUiccCards.length; i++) {
//            mUiccCards[i].dump(fd, pw, args);
//        }
    }
}
