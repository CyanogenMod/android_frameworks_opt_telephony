/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (c) 2012-2013, The Linux Foundation. All rights reserved.
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

package com.android.internal.telephony.cdma;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.Intent;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.Telephony.Sms;
import android.telephony.Rlog;
import android.telephony.SmsManager;

import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.SmsConstants;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.SMSDispatcher;
import com.android.internal.telephony.ImsSMSDispatcher;
import com.android.internal.telephony.SmsHeader;
import com.android.internal.telephony.SmsStorageMonitor;
import com.android.internal.telephony.SmsUsageMonitor;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.cdma.sms.UserData;

import java.util.HashMap;

public class CdmaSMSDispatcher extends SMSDispatcher {
    private static final String TAG = "CdmaSMSDispatcher";
    private static final boolean VDBG = false;
    private ImsSMSDispatcher mImsSMSDispatcher;

    public CdmaSMSDispatcher(PhoneBase phone, SmsStorageMonitor storageMonitor,
            SmsUsageMonitor usageMonitor, ImsSMSDispatcher imsSMSDispatcher) {
        super(phone, usageMonitor);
        mImsSMSDispatcher = imsSMSDispatcher;
        Rlog.d(TAG, "CdmaSMSDispatcher created");
    }

    /**
     * Send the SMS status report to the dispatcher thread to process.
     * @param sms the CDMA SMS message containing the status report
     */
    void sendStatusReportMessage(SmsMessage sms) {
        if (VDBG) Rlog.d(TAG, "sending EVENT_HANDLE_STATUS_REPORT message");
        sendMessage(obtainMessage(EVENT_HANDLE_STATUS_REPORT, sms));
    }

    @Override
    protected void handleStatusReport(Object o) {
        if (o instanceof SmsMessage) {
            if (VDBG) Rlog.d(TAG, "calling handleCdmaStatusReport()");
            handleCdmaStatusReport((SmsMessage) o);
        } else {
            Rlog.e(TAG, "handleStatusReport() called for object type " + o.getClass().getName());
        }
    }

    protected String getFormat() {
        return SmsConstants.FORMAT_3GPP2;
    }

    /**
     * Called from parent class to handle status report from {@code CdmaInboundSmsHandler}.
     * @param sms the CDMA SMS message to process
     */
    void handleCdmaStatusReport(SmsMessage sms) {
        for (int i = 0, count = deliveryPendingList.size(); i < count; i++) {
            SmsTracker tracker = deliveryPendingList.get(i);
            if (tracker.mMessageRef == sms.mMessageRef) {
                // Found it.  Remove from list and broadcast.
                deliveryPendingList.remove(i);
                // Update the message status (COMPLETE)
                tracker.updateSentMessageStatus(mContext, Sms.STATUS_COMPLETE);

                PendingIntent intent = tracker.mDeliveryIntent;
                Intent fillIn = new Intent();
                fillIn.putExtra("pdu", sms.getPdu());
                fillIn.putExtra("format", getFormat());
                try {
                    intent.send(mContext, Activity.RESULT_OK, fillIn);
                } catch (CanceledException ex) {}
                break;  // Only expect to see one tracker matching this message.
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void sendData(String callingPackage, String destAddr, String scAddr, int destPort,
            byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        SmsMessage.SubmitPdu pdu = SmsMessage.getSubmitPdu(
                scAddr, destAddr, destPort, data, (deliveryIntent != null));
        HashMap map =  SmsTrackerMapFactory(destAddr, scAddr, destPort, data, pdu);
        SmsTracker tracker = SmsTrackerFactory(callingPackage, map, sentIntent, deliveryIntent,
                getFormat());
        sendSubmitPdu(tracker);
    }

    /** {@inheritDoc} */
    @Override
    protected void sendText(String callingPackage, String destAddr, String scAddr, String text,
            PendingIntent sentIntent, PendingIntent deliveryIntent) {
        SmsMessage.SubmitPdu pdu = SmsMessage.getSubmitPdu(
                scAddr, destAddr, text, (deliveryIntent != null), null);
        HashMap map =  SmsTrackerMapFactory(destAddr, scAddr, text, pdu);
        SmsTracker tracker = SmsTrackerFactory(callingPackage, map, sentIntent,
                deliveryIntent, getFormat());
        sendSubmitPdu(tracker);
    }

    /** {@inheritDoc} */
    @Override
    protected GsmAlphabet.TextEncodingDetails calculateLength(CharSequence messageBody,
            boolean use7bitOnly) {
        return SmsMessage.calculateLength(messageBody, use7bitOnly);
    }

    /** {@inheritDoc} */
    @Override
    protected void sendNewSubmitPdu(String callingPackage, String destinationAddress, String scAddress,
            String message, SmsHeader smsHeader, int encoding,
            PendingIntent sentIntent, PendingIntent deliveryIntent, boolean lastPart) {
        UserData uData = new UserData();
        uData.payloadStr = message;
        uData.userDataHeader = smsHeader;
        if (encoding == SmsConstants.ENCODING_7BIT) {
            uData.msgEncoding = UserData.ENCODING_GSM_7BIT_ALPHABET;
        } else { // assume UTF-16
            uData.msgEncoding = UserData.ENCODING_UNICODE_16;
        }
        uData.msgEncodingSet = true;

        /* By setting the statusReportRequested bit only for the
         * last message fragment, this will result in only one
         * callback to the sender when that last fragment delivery
         * has been acknowledged. */
        SmsMessage.SubmitPdu submitPdu = SmsMessage.getSubmitPdu(destinationAddress,
                uData, (deliveryIntent != null) && lastPart);

        HashMap map =  SmsTrackerMapFactory(destinationAddress, scAddress,
                message, submitPdu);
        SmsTracker tracker = SmsTrackerFactory(callingPackage, map, sentIntent,
                deliveryIntent, getFormat());
        sendSubmitPdu(tracker);
    }

    protected void sendSubmitPdu(SmsTracker tracker) {
        if (SystemProperties.getBoolean(TelephonyProperties.PROPERTY_INECM_MODE, false)) {
            if (tracker.mSentIntent != null) {
                try {
                    tracker.mSentIntent.send(SmsManager.RESULT_ERROR_NO_SERVICE);
                } catch (CanceledException ex) {}
            }
            if (VDBG) {
                Rlog.d(TAG, "Block SMS in Emergency Callback mode");
            }
            return;
        }
        sendRawPdu(tracker);
    }

    /** {@inheritDoc} */
    @Override
    protected void sendSms(SmsTracker tracker) {
        HashMap<String, Object> map = tracker.mData;

        // byte[] smsc = (byte[]) map.get("smsc");  // unused for CDMA
        byte[] pdu = (byte[]) map.get("pdu");

        Message reply = obtainMessage(EVENT_SEND_SMS_COMPLETE, tracker);

        Rlog.d(TAG, "sendSms: "
                +" isIms()="+isIms()
                +" mRetryCount="+tracker.mRetryCount
                +" mImsRetry="+tracker.mImsRetry
                +" mMessageRef="+tracker.mMessageRef
                +" SS=" +mPhone.getServiceState().getState());

        // sms over cdma is used:
        //   if sms over IMS is not supported AND
        //   this is not a retry case after sms over IMS failed
        //     indicated by mImsRetry > 0
        if (0 == tracker.mImsRetry && !isIms()) {
            mCi.sendCdmaSms(pdu, reply);
        } else {
            mCi.sendImsCdmaSms(pdu, tracker.mImsRetry, tracker.mMessageRef, reply);
            // increment it here, so in case of SMS_FAIL_RETRY over IMS
            // next retry will be sent using IMS request again.
            tracker.mImsRetry++;
        }
    }

    @Override
    public void sendRetrySms(SmsTracker tracker) {
        //re-routing to ImsSMSDispatcher
        mImsSMSDispatcher.sendRetrySms(tracker);
    }

    @Override
    public boolean isIms() {
        return mImsSMSDispatcher.isIms();
    }

    @Override
    public String getImsSmsFormat() {
        return mImsSMSDispatcher.getImsSmsFormat();
    }
}
