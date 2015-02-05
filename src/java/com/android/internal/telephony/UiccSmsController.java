/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (c) 2011-2013, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 * Copyright (c) 2015 The CyanogenMod Project
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

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Telephony.Sms.Intents;
import android.telephony.Rlog;
import android.telephony.SmsMessage;
import android.util.Log;
import android.telephony.SubscriptionManager;

import com.android.internal.telephony.ISms;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.SmsRawData;

import java.util.ArrayList;
import java.util.List;

/**
 * UiccSmsController to provide an inter-process communication to
 * access Sms in Icc.
 */
public class UiccSmsController extends ISms.Stub {

    static final String LOG_TAG = "RIL_UiccSmsController";

    protected Phone[] mPhone;

    protected UiccSmsController(Phone[] phone, Context context){
        mPhone = phone;
        mContext = context;
        if (ServiceManager.getService("isms") == null) {
            ServiceManager.addService("isms", this);
        }

        createWakeLock();
    }

    private void createWakelock() {
        PowerManager pm = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IccSmsInterfaceManager");
        mWakeLock.setReferenceCounted(true);
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            // check if the message was aborted
            if (getResultCode() != Activity.RESULT_OK) {
                return;
            }
            String destAddr = getResultData();
            String scAddr = intent.getStringExtra("scAddr");
            String callingPackage = intent.getStringExtra("callingPackage");
            ArrayList<String> parts = intent.getStringArrayListExtra("parts");
            ArrayList<PendingIntent> sentIntents = intent.getParcelableArrayListExtra("sentIntents");
            ArrayList<PendingIntent> deliveryIntents = intent.getParcelableArrayListExtra("deliveryIntents");

            if (intent.getIntExtra("callingUid", 0) != 0) {
                callingPackage = callingPackage + "\\" + intent.getIntExtra("callingUid", 0);
            }

            if (intent.getBooleanExtra("multipart", false)) {
                if (Rlog.isLoggable("SMS", Log.VERBOSE)) {
                    log("ProxiedMultiPartSms destAddr: " + destAddr +
                            "\n scAddr= " + scAddr +
                            "\n subId= " + getDefaultSmsSubId() +
                            "\n callingPackage= " + callingPackage +
                            "\n partsSize= " + parts.size());
                }
                getIccSmsInterfaceManager(getDefaultSmsSubId())
                        .sendMultipartText(callingPackage, destAddr, scAddr, parts,
                                sentIntents, deliveryIntents);
                return;
            }

            PendingIntent sentIntent = null;
            if (sentIntents != null && sentIntents.size() > 0) {
                sentIntent = sentIntents.get(0);
            }
            PendingIntent deliveryIntent = null;
            if (deliveryIntents != null && deliveryIntents.size() > 0) {
                deliveryIntent = deliveryIntents.get(0);
            }
            String text = null;
            if (parts != null && parts.size() > 0) {
                text = parts.get(0);
            }
            if (Rlog.isLoggable("SMS", Log.VERBOSE)) {
                log("ProxiedSms destAddr: " + destAddr +
                        "\n scAddr=" + scAddr +
                        "\n callingPackage=" + callingPackage);
            }
            getIccSmsInterfaceManager(getDefaultSmsSubId()).sendText(callingPackage, destAddr,
                    scAddr, text, sentIntent, deliveryIntent);
        }
    };

    private Context mContext;
    private PowerManager.WakeLock mWakeLock;
    private static final int WAKE_LOCK_TIMEOUT = 5000;
    private final Handler mHandler = new Handler();
    private void dispatchPdus(byte[][] pdus) {
        Intent intent = new Intent(Intents.SMS_DELIVER_ACTION);
        // Direct the intent to only the default SMS app. If we can't find a default SMS app
        // then send it to all broadcast receivers.
        ComponentName componentName = SmsApplication.getDefaultSmsApplication(mContext, true);
        if (componentName == null)
            return;

        if (Rlog.isLoggable("SMS", Log.VERBOSE)) {
            log("dispatchPdu pdus: " + pdus +
                    "\n componentName=" + componentName +
                    "\n format=" + SmsMessage.FORMAT_SYNTHETIC);
        }

        // Deliver SMS message only to this receiver
        intent.setComponent(componentName);
        intent.putExtra("pdus", pdus);
        intent.putExtra("format", SmsMessage.FORMAT_SYNTHETIC);
        dispatch(intent, Manifest.permission.RECEIVE_SMS);

        intent.setAction(Intents.SMS_RECEIVED_ACTION);
        intent.setComponent(null);
        dispatch(intent, Manifest.permission.RECEIVE_SMS);
    }

    private void dispatch(Intent intent, String permission) {
        // Hold a wake lock for WAKE_LOCK_TIMEOUT seconds, enough to give any
        // receivers time to take their own wake locks.
        mWakeLock.acquire(WAKE_LOCK_TIMEOUT);
        intent.addFlags(Intent.FLAG_RECEIVER_NO_ABORT);
        mContext.sendOrderedBroadcast(intent, permission, AppOpsManager.OP_RECEIVE_SMS, null,
                mHandler, Activity.RESULT_OK, null, null);
    }

    private void broadcastOutgoingSms(
            String callingPackage, String destAddr, String scAddr, boolean multipart,
            ArrayList<String> parts, ArrayList<PendingIntent> sentIntents,
            ArrayList<PendingIntent> deliveryIntents, int priority, boolean isExpectMore,
            int validityPeriod) {
        Intent broadcast = new Intent(Intent.ACTION_NEW_OUTGOING_SMS);
        broadcast.putExtra("destAddr", destAddr);
        broadcast.putExtra("scAddr", scAddr);
        broadcast.putExtra("multipart", multipart);
        broadcast.putExtra("callingPackage", callingPackage);
        broadcast.putExtra("callingUid", android.os.Binder.getCallingUid());
        broadcast.putStringArrayListExtra("parts", parts);
        broadcast.putParcelableArrayListExtra("sentIntents", sentIntents);
        broadcast.putParcelableArrayListExtra("deliveryIntents", deliveryIntents);
        broadcast.putExtra("priority", priority);
        broadcast.putExtra("isExpectMore", isExpectMore);
        broadcast.putExtra("validityPeriod", validityPeriod);

        if (Rlog.isLoggable("SMS", Log.VERBOSE)) {
            log("Broadcasting sms destAddr: " + destAddr +
                    "\n scAddr= " + scAddr +
                    "\n multipart= " + multipart +
                    "\n callingPackager= " + callingPackage +
                    "\n callingUid= " + android.os.Binder.getCallingUid() +
                    "\n parts= " + parts.size() +
                    "\n sentIntents= " + sentIntents.size() +
                    "\n deliveryIntents= " + deliveryIntents.size() +
                    "\n priority= " + priority +
                    "\n isExpectMore= " + isExpectMore +
                    "\n validityPeriod= " + validityPeriod);
        }
        mContext.sendOrderedBroadcastAsUser(broadcast, UserHandle.OWNER,
                android.Manifest.permission.INTERCEPT_SMS,
                mReceiver, null, Activity.RESULT_OK, destAddr, null);
    }

    public boolean
    updateMessageOnIccEf(String callingPackage, int index, int status, byte[] pdu)
            throws android.os.RemoteException {
        return  updateMessageOnIccEfForSubscriber(getDefaultSmsSubId(), callingPackage,
                index, status, pdu);
    }

    public boolean
    updateMessageOnIccEfForSubscriber(long subId, String callingPackage, int index, int status,
                byte[] pdu) throws android.os.RemoteException {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            return iccSmsIntMgr.updateMessageOnIccEf(callingPackage, index, status, pdu);
        } else {
            Rlog.e(LOG_TAG,"updateMessageOnIccEf iccSmsIntMgr is null" +
                          " for Subscription: " + subId);
            return false;
        }
    }

    public boolean copyMessageToIccEf(String callingPackage, int status, byte[] pdu, byte[] smsc)
            throws android.os.RemoteException {
        return copyMessageToIccEfForSubscriber(getDefaultSmsSubId(), callingPackage, status,
                pdu, smsc);
    }

    public boolean copyMessageToIccEfForSubscriber(long subId, String callingPackage, int status,
            byte[] pdu, byte[] smsc) throws android.os.RemoteException {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            return iccSmsIntMgr.copyMessageToIccEf(callingPackage, status, pdu, smsc);
        } else {
            Rlog.e(LOG_TAG,"copyMessageToIccEf iccSmsIntMgr is null" +
                          " for Subscription: " + subId);
            return false;
        }
    }

    public List<SmsRawData> getAllMessagesFromIccEf(String callingPackage)
            throws android.os.RemoteException {
        return getAllMessagesFromIccEfForSubscriber(getDefaultSmsSubId(), callingPackage);
    }

    public List<SmsRawData> getAllMessagesFromIccEfForSubscriber(long subId, String callingPackage)
                throws android.os.RemoteException {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            return iccSmsIntMgr.getAllMessagesFromIccEf(callingPackage);
        } else {
            Rlog.e(LOG_TAG,"getAllMessagesFromIccEf iccSmsIntMgr is" +
                          " null for Subscription: " + subId);
            return null;
        }
    }

    public void sendData(String callingPackage, String destAddr, String scAddr, int destPort,
            byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent) {
         sendDataForSubscriber(getDefaultSmsSubId(), callingPackage, destAddr, scAddr,
                 destPort, data, sentIntent, deliveryIntent);
    }

    public void sendDataForSubscriber(long subId, String callingPackage, String destAddr,
            String scAddr, int destPort, byte[] data, PendingIntent sentIntent,
            PendingIntent deliveryIntent) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            iccSmsIntMgr.sendData(callingPackage, destAddr, scAddr, destPort, data,
                    sentIntent, deliveryIntent);
        } else {
            Rlog.e(LOG_TAG,"sendText iccSmsIntMgr is null for" +
                          " Subscription: " + subId);
        }
    }

    public void sendDataWithOrigPort(String callingPackage, String destAddr, String scAddr,
            int destPort, int origPort, byte[] data, PendingIntent sentIntent,
            PendingIntent deliveryIntent) {
         sendDataWithOrigPortUsingSubscriber(getDefaultSmsSubId(), callingPackage, destAddr,
                 scAddr, destPort, origPort, data, sentIntent, deliveryIntent);
    }

    public void sendDataWithOrigPortUsingSubscriber(long subId, String callingPackage,
            String destAddr, String scAddr, int destPort, int origPort, byte[] data,
            PendingIntent sentIntent, PendingIntent deliveryIntent) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            iccSmsIntMgr.sendDataWithOrigPort(callingPackage, destAddr, scAddr, destPort,
                    origPort, data, sentIntent, deliveryIntent);
        } else {
            Rlog.e(LOG_TAG,"sendTextWithOrigPort iccSmsIntMgr is null for" +
                          " Subscription: " + subId);
        }
    }

    public void sendText(String callingPackage, String destAddr, String scAddr,
            String text, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        sendTextForSubscriber(getDefaultSmsSubId(), callingPackage, destAddr, scAddr,
            text, sentIntent, deliveryIntent);
    }

    public void sendTextForSubscriber(long subId, String callingPackage, String destAddr,
            String scAddr, String text, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            iccSmsIntMgr.sendText(callingPackage, destAddr, scAddr, text, sentIntent,
                    deliveryIntent);
        } else {
            Rlog.e(LOG_TAG,"sendText iccSmsIntMgr is null for" +
                          " Subscription: " + subId);
        }
    }

    public void sendTextWithOptionsUsingSubscriber(long subId, String callingPackage,
            String destAddr, String scAddr, String text, PendingIntent sentIntent,
            PendingIntent deliveryIntent, int priority, boolean isExpectMore,
            int validityPeriod) {
        mContext.enforceCallingPermission(
                android.Manifest.permission.SEND_SMS,
                "Sending SMS message");
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr.isShortSMSCode(destAddr)) {
            iccSmsIntMgr.sendTextWithOptions(callingPackage, destAddr, scAddr, text,
                    sentIntent, deliveryIntent, priority, isExpectMore, validityPeriod);
            return;
        }
        ArrayList<String> parts = new ArrayList<String>();
        parts.add(text);
        ArrayList<PendingIntent> sentIntents = new ArrayList<PendingIntent>();
        sentIntents.add(sentIntent);
        ArrayList<PendingIntent> deliveryIntents = new ArrayList<PendingIntent>();
        deliveryIntents.add(deliveryIntent);
        broadcastOutgoingSms(callingPackage, destAddr, scAddr, false, parts, sentIntents,
                deliveryIntents, priority, isExpectMore, validityPeriod);
    }

    public void sendMultipartText(String callingPackage, String destAddr, String scAddr,
            List<String> parts, List<PendingIntent> sentIntents,
            List<PendingIntent> deliveryIntents) throws android.os.RemoteException {
         sendMultipartTextForSubscriber(getDefaultSmsSubId(), callingPackage, destAddr,
                 scAddr, parts, sentIntents, deliveryIntents);
    }

    public void sendMultipartTextForSubscriber(long subId, String callingPackage, String destAddr,
            String scAddr, List<String> parts, List<PendingIntent> sentIntents,
            List<PendingIntent> deliveryIntents)
            throws android.os.RemoteException {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null ) {
            iccSmsIntMgr.sendMultipartText(callingPackage, destAddr, scAddr, parts, sentIntents,
                    deliveryIntents);
        } else {
            Rlog.e(LOG_TAG,"sendMultipartText iccSmsIntMgr is null for" +
                          " Subscription: " + subId);
        }
    }

    public void sendMultipartTextWithOptionsUsingSubscriber(long subId, String callingPackage,
            String destAddr, String scAddr, List<String> parts, List<PendingIntent> sentIntents,
            List<PendingIntent> deliveryIntents, int priority, boolean isExpectMore,
            int validityPeriod) {
        mContext.enforceCallingPermission(
                android.Manifest.permission.SEND_SMS,
                "Sending SMS message");
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr.isShortSMSCode(destAddr)) {
            iccSmsIntMgr.sendMultipartTextWithOptions(callingPackage, destAddr,
                    scAddr, parts, sentIntents, deliveryIntents, -1, false, -1);
            return;
        }
        broadcastOutgoingSms(callingPackage, destAddr, scAddr, true,
                parts != null ? new ArrayList<String>(parts) : null,
                sentIntents != null ? new ArrayList<PendingIntent>(sentIntents) : null,
                deliveryIntents != null ? new ArrayList<PendingIntent>(deliveryIntents) : null,
                -1, false, -1);
    }

    public boolean enableCellBroadcast(int messageIdentifier) throws android.os.RemoteException {
        return enableCellBroadcastForSubscriber(getDefaultSmsSubId(), messageIdentifier);
    }

    public boolean enableCellBroadcastForSubscriber(long subId, int messageIdentifier)
                throws android.os.RemoteException {
        return enableCellBroadcastRangeForSubscriber(subId, messageIdentifier, messageIdentifier);
    }

    public boolean enableCellBroadcastRange(int startMessageId, int endMessageId)
            throws android.os.RemoteException {
        return enableCellBroadcastRangeForSubscriber(getDefaultSmsSubId(), startMessageId,
                endMessageId);
    }

    public boolean enableCellBroadcastRangeForSubscriber(long subId, int startMessageId,
            int endMessageId) throws android.os.RemoteException {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null ) {
            return iccSmsIntMgr.enableCellBroadcastRange(startMessageId, endMessageId);
        } else {
            Rlog.e(LOG_TAG,"enableCellBroadcast iccSmsIntMgr is null for" +
                          " Subscription: " + subId);
        }
        return false;
    }

    public boolean disableCellBroadcast(int messageIdentifier) throws android.os.RemoteException {
        return disableCellBroadcastForSubscriber(getDefaultSmsSubId(), messageIdentifier);
    }

    public boolean disableCellBroadcastForSubscriber(long subId, int messageIdentifier)
                throws android.os.RemoteException {
        return disableCellBroadcastRangeForSubscriber(subId, messageIdentifier, messageIdentifier);
    }

    public boolean disableCellBroadcastRange(int startMessageId, int endMessageId)
            throws android.os.RemoteException {
        return disableCellBroadcastRangeForSubscriber(getDefaultSmsSubId(), startMessageId,
                endMessageId);
    }

    public boolean disableCellBroadcastRangeForSubscriber(long subId, int startMessageId,
            int endMessageId) throws android.os.RemoteException {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null ) {
            return iccSmsIntMgr.disableCellBroadcastRange(startMessageId, endMessageId);
        } else {
            Rlog.e(LOG_TAG,"disableCellBroadcast iccSmsIntMgr is null for" +
                          " Subscription:"+subId);
        }
       return false;
    }

    public int getPremiumSmsPermission(String packageName) {
        return getPremiumSmsPermissionForSubscriber(getDefaultSmsSubId(), packageName);
    }

    @Override
    public int getPremiumSmsPermissionForSubscriber(long subId, String packageName) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null ) {
            return iccSmsIntMgr.getPremiumSmsPermission(packageName);
        } else {
            Rlog.e(LOG_TAG, "getPremiumSmsPermission iccSmsIntMgr is null");
        }
        //TODO Rakesh
        return 0;
    }

    public void setPremiumSmsPermission(String packageName, int permission) {
         setPremiumSmsPermissionForSubscriber(getDefaultSmsSubId(), packageName, permission);
    }

    @Override
    public void setPremiumSmsPermissionForSubscriber(long subId, String packageName, int permission) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null ) {
            iccSmsIntMgr.setPremiumSmsPermission(packageName, permission);
        } else {
            Rlog.e(LOG_TAG, "setPremiumSmsPermission iccSmsIntMgr is null");
        }
    }

    public boolean isImsSmsSupported() {
        return isImsSmsSupportedForSubscriber(getDefaultSmsSubId());
    }

    @Override
    public boolean isImsSmsSupportedForSubscriber(long subId) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null ) {
            return iccSmsIntMgr.isImsSmsSupported();
        } else {
            Rlog.e(LOG_TAG, "isImsSmsSupported iccSmsIntMgr is null");
        }
        return false;
    }

    public String getImsSmsFormat() {
        return getImsSmsFormatForSubscriber(getDefaultSmsSubId());
    }

    @Override
    public String getImsSmsFormatForSubscriber(long subId) {
       IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null ) {
            return iccSmsIntMgr.getImsSmsFormat();
        } else {
            Rlog.e(LOG_TAG, "getImsSmsFormat iccSmsIntMgr is null");
        }
        return null;
    }

    @Override
    public void updateSmsSendStatus(int messageRef, boolean success) {
        getIccSmsInterfaceManager(getDefaultSmsSubId())
            .updateSmsSendStatus(messageRef, success);
    }

    @Override
    public void injectSmsPdu(byte[] pdu, String format, PendingIntent receivedIntent) {
        injectSmsPduForSubscriber(getDefaultSmsSubId(), pdu, format, receivedIntent);
    }

    // FIXME: Add injectSmsPduForSubscriber to ISms.aidl
    public void injectSmsPduForSubscriber(long subId, byte[] pdu, String format,
            PendingIntent receivedIntent) {
        getIccSmsInterfaceManager(subId).injectSmsPdu(pdu, format, receivedIntent);
    }

    @Override
    public void synthesizeMessages(String originatingAddress,
            String scAddress, List<String> messages, long timestampMillis) throws RemoteException {
        mContext.enforceCallingPermission(
                android.Manifest.permission.BROADCAST_SMS, "");
        byte[][] pdus = new byte[messages.size()][];
        for (int i = 0; i < messages.size(); i++) {
            SyntheticSmsMessage message = new SyntheticSmsMessage(originatingAddress,
                    scAddress, messages.get(i), timestampMillis);
            pdus[i] = message.getPdu();
        }
        dispatchPdus(pdus);
    }

    /**
     * get sms interface manager object based on subscription.
     **/
    private IccSmsInterfaceManager getIccSmsInterfaceManager(long subId) {
        int phoneId = SubscriptionController.getInstance().getPhoneId(subId) ;
        //Fixme: for multi-subscription case
        if (!SubscriptionManager.isValidPhoneId(phoneId)
                || phoneId == SubscriptionManager.DEFAULT_PHONE_ID) {
            phoneId = 0;
        }

        try {
            return (IccSmsInterfaceManager)
                ((PhoneProxy)mPhone[phoneId]).getIccSmsInterfaceManager();
        } catch (NullPointerException e) {
            Rlog.e(LOG_TAG, "Exception is :"+e.toString()+" For subscription :"+subId );
            e.printStackTrace(); //This will print stact trace
            return null;
        } catch (ArrayIndexOutOfBoundsException e) {
            Rlog.e(LOG_TAG, "Exception is :"+e.toString()+" For subscription :"+subId );
            e.printStackTrace(); //This will print stack trace
            return null;
        }
    }

    private long getDefaultSmsSubId() {
        return SubscriptionController.getInstance().getDefaultSmsSubId();
    }

    @Override
    public void sendStoredText(long subId, String callingPkg, Uri messageUri, String scAddress,
            PendingIntent sentIntent, PendingIntent deliveryIntent) throws RemoteException {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            iccSmsIntMgr.sendStoredText(callingPkg, messageUri, scAddress, sentIntent,
                    deliveryIntent);
        } else {
            Rlog.e(LOG_TAG,"sendStoredText iccSmsIntMgr is null for subscription: " + subId);
        }
    }

    @Override
    public void sendStoredMultipartText(long subId, String callingPkg, Uri messageUri,
            String scAddress, List<PendingIntent> sentIntents, List<PendingIntent> deliveryIntents)
            throws RemoteException {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null ) {
            iccSmsIntMgr.sendStoredMultipartText(callingPkg, messageUri, scAddress, sentIntents,
                    deliveryIntents);
        } else {
            Rlog.e(LOG_TAG,"sendStoredMultipartText iccSmsIntMgr is null for subscription: "
                    + subId);
        }
    }

    /**
     * Get the capacity count of sms on Icc card.
     **/
    public int getSmsCapacityOnIccForSubscriber(long subId)
            throws android.os.RemoteException {
       IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);

        if (iccSmsIntMgr != null ) {
            return iccSmsIntMgr.getSmsCapacityOnIcc();
        } else {
            Rlog.e(LOG_TAG, "iccSmsIntMgr is null for " + " subId: " + subId);
            return -1;
        }
    }

    protected void log(String msg) {
        Log.d(LOG_TAG, "[UiccSmsController] " + msg);
    }
}
