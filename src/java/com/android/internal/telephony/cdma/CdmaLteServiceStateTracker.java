/*
 * Copyright (C) 2012 The Android Open Source Project
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

import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.uicc.RuimRecords;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppState;

import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellIdentityLte;
import android.telephony.SignalStrength;
import android.telephony.ServiceState;
import android.telephony.cdma.CdmaCellLocation;
import android.text.TextUtils;
import android.os.AsyncResult;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;

import android.telephony.Rlog;
import android.util.EventLog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class CdmaLteServiceStateTracker extends CdmaServiceStateTracker {
    private CDMALTEPhone mCdmaLtePhone;
    private final CellInfoLte mCellInfoLte;

    private CellIdentityLte mNewCellIdentityLte = new CellIdentityLte();
    private CellIdentityLte mLasteCellIdentityLte = new CellIdentityLte();

    public CdmaLteServiceStateTracker(CDMALTEPhone phone) {
        super(phone, new CellInfoLte());
        mCdmaLtePhone = phone;
        mCellInfoLte = (CellInfoLte) mCellInfo;

        ((CellInfoLte)mCellInfo).setCellSignalStrength(new CellSignalStrengthLte());
        ((CellInfoLte)mCellInfo).setCellIdentity(new CellIdentityLte());

        if (DBG) log("CdmaLteServiceStateTracker Constructors");
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;
        int[] ints;
        String[] strings;

        if (!mPhone.mIsTheCurrentActivePhone) {
            loge("Received message " + msg + "[" + msg.what + "]" +
                    " while being destroyed. Ignoring.");
            return;
        }

        switch (msg.what) {
        case EVENT_POLL_STATE_GPRS:
            if (DBG) log("handleMessage EVENT_POLL_STATE_GPRS");
            ar = (AsyncResult)msg.obj;
            handlePollStateResult(msg.what, ar);
            break;
        case EVENT_RUIM_RECORDS_LOADED:
            updatePhoneObject();
            RuimRecords ruim = (RuimRecords)mIccRecords;
            if ((ruim != null) && ruim.isProvisioned()) {
                mMdn = ruim.getMdn();
                mMin = ruim.getMin();
                parseSidNid(ruim.getSid(), ruim.getNid());
                mPrlVersion = ruim.getPrlVersion();
                mIsMinInfoReady = true;
                updateOtaspState();
            }
            // SID/NID/PRL is loaded. Poll service state
            // again to update to the roaming state with
            // the latest variables.
            pollState();
            break;
        default:
            super.handleMessage(msg);
        }
    }

    /**
     * Handle the result of one of the pollState()-related requests
     */
    @Override
    protected void handlePollStateResultMessage(int what, AsyncResult ar) {
        if (what == EVENT_POLL_STATE_GPRS) {
            String states[] = (String[])ar.result;
            if (DBG) {
                log("handlePollStateResultMessage: EVENT_POLL_STATE_GPRS states.length=" +
                        states.length + " states=" + states);
            }

            int type = 0;
            int regState = -1;
            if (states.length > 0) {
                try {
                    regState = Integer.parseInt(states[0]);

                    // states[3] (if present) is the current radio technology
                    if (states.length >= 4 && states[3] != null) {
                        type = Integer.parseInt(states[3]);
                    }
                } catch (NumberFormatException ex) {
                    loge("handlePollStateResultMessage: error parsing GprsRegistrationState: "
                                    + ex);
                }
                if (states.length >= 10) {
                    int mcc;
                    int mnc;
                    int tac;
                    int pci;
                    int eci;
                    int csgid;
                    String operatorNumeric = null;

                    try {
                        operatorNumeric = mNewSS.getOperatorNumeric();
                        mcc = Integer.parseInt(operatorNumeric.substring(0,3));
                    } catch (Exception e) {
                        try {
                            operatorNumeric = mSS.getOperatorNumeric();
                            mcc = Integer.parseInt(operatorNumeric.substring(0,3));
                        } catch (Exception ex) {
                            loge("handlePollStateResultMessage: bad mcc operatorNumeric=" +
                                    operatorNumeric + " ex=" + ex);
                            operatorNumeric = "";
                            mcc = Integer.MAX_VALUE;
                        }
                    }
                    try {
                        mnc = Integer.parseInt(operatorNumeric.substring(3));
                    } catch (Exception e) {
                        loge("handlePollStateResultMessage: bad mnc operatorNumeric=" +
                                operatorNumeric + " e=" + e);
                        mnc = Integer.MAX_VALUE;
                    }

                    // Use Integer#decode to be generous in what we receive and allow
                    // decimal, hex or octal values.
                    try {
                        tac = Integer.decode(states[6]);
                    } catch (Exception e) {
                        loge("handlePollStateResultMessage: bad tac states[6]=" +
                                states[6] + " e=" + e);
                        tac = Integer.MAX_VALUE;
                    }
                    try {
                        pci = Integer.decode(states[7]);
                    } catch (Exception e) {
                        loge("handlePollStateResultMessage: bad pci states[7]=" +
                                states[7] + " e=" + e);
                        pci = Integer.MAX_VALUE;
                    }
                    try {
                        eci = Integer.decode(states[8]);
                    } catch (Exception e) {
                        loge("handlePollStateResultMessage: bad eci states[8]=" +
                                states[8] + " e=" + e);
                        eci = Integer.MAX_VALUE;
                    }
                    try {
                        csgid = Integer.decode(states[9]);
                    } catch (Exception e) {
                        // FIX: Always bad so don't pollute the logs
                        // loge("handlePollStateResultMessage: bad csgid states[9]=" +
                        //        states[9] + " e=" + e);
                        csgid = Integer.MAX_VALUE;
                    }
                    mNewCellIdentityLte = new CellIdentityLte(mcc, mnc, eci, pci, tac);
                    if (DBG) {
                        log("handlePollStateResultMessage: mNewLteCellIdentity=" +
                                mNewCellIdentityLte);
                    }
                }
            }

            mNewSS.setRilDataRadioTechnology(type);
            int dataRegState = regCodeToServiceState(regState);
            mNewSS.setDataRegState(dataRegState);
            if (DBG) {
                log("handlPollStateResultMessage: CdmaLteSST setDataRegState=" + dataRegState
                        + " regState=" + regState
                        + " dataRadioTechnology=" + type);
            }
        } else {
            super.handlePollStateResultMessage(what, ar);
        }
    }

    @Override
    protected void pollState() {
        mPollingContext = new int[1];
        mPollingContext[0] = 0;

        switch (mCi.getRadioState()) {
            case RADIO_UNAVAILABLE:
                mNewSS.setStateOutOfService();
                mNewCellLoc.setStateInvalid();
                setSignalStrengthDefaultValues();
                mGotCountryCode = false;

                pollStateDone();
                break;

            case RADIO_OFF:
                mNewSS.setStateOff();
                mNewCellLoc.setStateInvalid();
                setSignalStrengthDefaultValues();
                mGotCountryCode = false;

                pollStateDone();
                break;

            default:
                // Issue all poll-related commands at once, then count
                // down the responses which are allowed to arrive
                // out-of-order.

                mPollingContext[0]++;
                // RIL_REQUEST_OPERATOR is necessary for CDMA
                mCi.getOperator(obtainMessage(EVENT_POLL_STATE_OPERATOR_CDMA, mPollingContext));

                mPollingContext[0]++;
                // RIL_REQUEST_VOICE_REGISTRATION_STATE is necessary for CDMA
                mCi.getVoiceRegistrationState(obtainMessage(EVENT_POLL_STATE_REGISTRATION_CDMA,
                        mPollingContext));

                mPollingContext[0]++;
                // RIL_REQUEST_DATA_REGISTRATION_STATE
                mCi.getDataRegistrationState(obtainMessage(EVENT_POLL_STATE_GPRS,
                                            mPollingContext));
                break;
        }
    }

    @Override
    protected void pollStateDone() {
        log("pollStateDone: lte 1 ss=[" + mSS + "] newSS=[" + mNewSS + "]");

        useDataRegStateForDataOnlyDevices();

        boolean hasRegistered = mSS.getVoiceRegState() != ServiceState.STATE_IN_SERVICE
                && mNewSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE;

        boolean hasDeregistered = mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE
                && mNewSS.getVoiceRegState() != ServiceState.STATE_IN_SERVICE;

        boolean hasCdmaDataConnectionAttached =
            mSS.getDataRegState() != ServiceState.STATE_IN_SERVICE
                && mNewSS.getDataRegState() == ServiceState.STATE_IN_SERVICE;

        boolean hasCdmaDataConnectionDetached =
                mSS.getDataRegState() == ServiceState.STATE_IN_SERVICE
                && mNewSS.getDataRegState() != ServiceState.STATE_IN_SERVICE;

        boolean hasCdmaDataConnectionChanged =
            mSS.getDataRegState() != mNewSS.getDataRegState();

        boolean hasVoiceRadioTechnologyChanged = mSS.getRilVoiceRadioTechnology()
                != mNewSS.getRilVoiceRadioTechnology();

        boolean hasDataRadioTechnologyChanged = mSS.getRilDataRadioTechnology()
                != mNewSS.getRilDataRadioTechnology();

        boolean hasChanged = !mNewSS.equals(mSS);

        boolean hasRoamingOn = !mSS.getRoaming() && mNewSS.getRoaming();

        boolean hasRoamingOff = mSS.getRoaming() && !mNewSS.getRoaming();

        boolean hasLocationChanged = !mNewCellLoc.equals(mCellLoc);

        boolean has4gHandoff =
                mNewSS.getDataRegState() == ServiceState.STATE_IN_SERVICE &&
                (((mSS.getRilDataRadioTechnology() == ServiceState.RIL_RADIO_TECHNOLOGY_LTE) &&
                  (mNewSS.getRilDataRadioTechnology() == ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD)) ||
                 ((mSS.getRilDataRadioTechnology() == ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD) &&
                  (mNewSS.getRilDataRadioTechnology() == ServiceState.RIL_RADIO_TECHNOLOGY_LTE)));

        boolean hasMultiApnSupport =
                (((mNewSS.getRilDataRadioTechnology() == ServiceState.RIL_RADIO_TECHNOLOGY_LTE) ||
                  (mNewSS.getRilDataRadioTechnology() == ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD)) &&
                 ((mSS.getRilDataRadioTechnology() != ServiceState.RIL_RADIO_TECHNOLOGY_LTE) &&
                  (mSS.getRilDataRadioTechnology() != ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD)));

        boolean hasLostMultiApnSupport =
            ((mNewSS.getRilDataRadioTechnology() >= ServiceState.RIL_RADIO_TECHNOLOGY_IS95A) &&
             (mNewSS.getRilDataRadioTechnology() <= ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A));

        if (DBG) {
            log("pollStateDone:"
                + " hasRegistered=" + hasRegistered
                + " hasDeegistered=" + hasDeregistered
                + " hasCdmaDataConnectionAttached=" + hasCdmaDataConnectionAttached
                + " hasCdmaDataConnectionDetached=" + hasCdmaDataConnectionDetached
                + " hasCdmaDataConnectionChanged=" + hasCdmaDataConnectionChanged
                + " hasVoiceRadioTechnologyChanged= " + hasVoiceRadioTechnologyChanged
                + " hasDataRadioTechnologyChanged=" + hasDataRadioTechnologyChanged
                + " hasChanged=" + hasChanged
                + " hasRoamingOn=" + hasRoamingOn
                + " hasRoamingOff=" + hasRoamingOff
                + " hasLocationChanged=" + hasLocationChanged
                + " has4gHandoff = " + has4gHandoff
                + " hasMultiApnSupport=" + hasMultiApnSupport
                + " hasLostMultiApnSupport=" + hasLostMultiApnSupport);
        }
        // Add an event log when connection state changes
        if (mSS.getVoiceRegState() != mNewSS.getVoiceRegState()
                || mSS.getDataRegState() != mNewSS.getDataRegState()) {
            EventLog.writeEvent(EventLogTags.CDMA_SERVICE_STATE_CHANGE, mSS.getVoiceRegState(),
                    mSS.getDataRegState(), mNewSS.getVoiceRegState(), mNewSS.getDataRegState());
        }

        ServiceState tss;
        tss = mSS;
        mSS = mNewSS;
        mNewSS = tss;
        // clean slate for next time
        mNewSS.setStateOutOfService();

        CdmaCellLocation tcl = mCellLoc;
        mCellLoc = mNewCellLoc;
        mNewCellLoc = tcl;

        mNewSS.setStateOutOfService(); // clean slate for next time

        if (hasVoiceRadioTechnologyChanged) {
            updatePhoneObject();
        }

        if (hasDataRadioTechnologyChanged) {
            mPhone.setSystemProperty(TelephonyProperties.PROPERTY_DATA_NETWORK_TYPE,
                    ServiceState.rilRadioTechnologyToString(mSS.getRilDataRadioTechnology()));

            // Query Signalstrength when there is a change in PS RAT.
            sendMessage(obtainMessage(EVENT_POLL_SIGNAL_STRENGTH));
        }

        if (hasRegistered) {
            mNetworkAttachedRegistrants.notifyRegistrants();
        }

        if (hasChanged) {
            if (mPhone.isEriFileLoaded()) {
                String eriText;
                // Now the CDMAPhone sees the new ServiceState so it can get the
                // new ERI text
                if (mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE) {
                    eriText = mPhone.getCdmaEriText();
                } else if (mSS.getVoiceRegState() == ServiceState.STATE_POWER_OFF) {
                    eriText = (mIccRecords != null) ? mIccRecords.getServiceProviderName() : null;
                    if (TextUtils.isEmpty(eriText)) {
                        // Sets operator alpha property by retrieving from
                        // build-time system property
                        eriText = SystemProperties.get("ro.cdma.home.operator.alpha");
                    }
                } else {
                    // Note that ServiceState.STATE_OUT_OF_SERVICE is valid used
                    // for mRegistrationState 0,2,3 and 4
                    eriText = mPhone.getContext()
                            .getText(com.android.internal.R.string.roamingTextSearching).toString();
                }
                mSS.setOperatorAlphaLong(eriText);
            }

            if (mUiccApplcation != null && mUiccApplcation.getState() == AppState.APPSTATE_READY &&
                    mIccRecords != null) {
                // SIM is found on the device. If ERI roaming is OFF, and SID/NID matches
                // one configured in SIM, use operator name  from CSIM record.
                boolean showSpn =
                    ((RuimRecords)mIccRecords).getCsimSpnDisplayCondition();
                int iconIndex = mSS.getCdmaEriIconIndex();

                if (showSpn && (iconIndex == EriInfo.ROAMING_INDICATOR_OFF) &&
                    isInHomeSidNid(mSS.getSystemId(), mSS.getNetworkId()) &&
                    mIccRecords != null) {
                    mSS.setOperatorAlphaLong(mIccRecords.getServiceProviderName());
                }
            }

            String operatorNumeric;

            mPhone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ALPHA,
                    mSS.getOperatorAlphaLong());

            String prevOperatorNumeric =
                    SystemProperties.get(TelephonyProperties.PROPERTY_OPERATOR_NUMERIC, "");
            operatorNumeric = mSS.getOperatorNumeric();
            mPhone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_NUMERIC, operatorNumeric);

            if (operatorNumeric == null) {
                if (DBG) log("operatorNumeric is null");
                mPhone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY, "");
                mGotCountryCode = false;
            } else {
                String isoCountryCode = "";
                String mcc = operatorNumeric.substring(0, 3);
                try {
                    isoCountryCode = MccTable.countryCodeForMcc(Integer.parseInt(operatorNumeric
                            .substring(0, 3)));
                } catch (NumberFormatException ex) {
                    loge("countryCodeForMcc error" + ex);
                } catch (StringIndexOutOfBoundsException ex) {
                    loge("countryCodeForMcc error" + ex);
                }

                mPhone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY,
                        isoCountryCode);
                mGotCountryCode = true;

                if (shouldFixTimeZoneNow(mPhone, operatorNumeric, prevOperatorNumeric,
                        mNeedFixZone)) {
                    fixTimeZone(isoCountryCode);
                }
            }

            mPhone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ISROAMING,
                    mSS.getRoaming() ? "true" : "false");

            updateSpnDisplay();
            mPhone.notifyServiceStateChanged(mSS);
        }

        if (hasCdmaDataConnectionAttached || has4gHandoff) {
            mAttachedRegistrants.notifyRegistrants();
        }

        if (hasCdmaDataConnectionDetached) {
            mDetachedRegistrants.notifyRegistrants();
        }

        if ((hasCdmaDataConnectionChanged || hasDataRadioTechnologyChanged)) {
            notifyDataRegStateRilRadioTechnologyChanged();
            mPhone.notifyDataConnection(null);
        }

        if (hasRoamingOn) {
            mRoamingOnRegistrants.notifyRegistrants();
        }

        if (hasRoamingOff) {
            mRoamingOffRegistrants.notifyRegistrants();
        }

        if (hasLocationChanged) {
            mPhone.notifyLocationChanged();
        }

        ArrayList<CellInfo> arrayCi = new ArrayList<CellInfo>();
        synchronized(mCellInfo) {
            CellInfoLte cil = (CellInfoLte)mCellInfo;

            boolean cidChanged = ! mNewCellIdentityLte.equals(mLasteCellIdentityLte);
            if (hasRegistered || hasDeregistered || cidChanged) {
                // TODO: Handle the absence of LteCellIdentity
                long timeStamp = SystemClock.elapsedRealtime() * 1000;
                boolean registered = mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE;
                mLasteCellIdentityLte = mNewCellIdentityLte;

                cil.setRegisterd(registered);
                cil.setCellIdentity(mLasteCellIdentityLte);
                if (DBG) {
                    log("pollStateDone: hasRegistered=" + hasRegistered +
                            " hasDeregistered=" + hasDeregistered +
                            " cidChanged=" + cidChanged +
                            " mCellInfo=" + mCellInfo);
                }
                arrayCi.add(mCellInfo);
            }
            mPhoneBase.notifyCellInfo(arrayCi);
        }
    }

    @Override
    protected boolean onSignalStrengthResult(AsyncResult ar, boolean isGsm) {
        if (mSS.getRilDataRadioTechnology() == ServiceState.RIL_RADIO_TECHNOLOGY_LTE) {
            isGsm = true;
        }
        boolean ssChanged = super.onSignalStrengthResult(ar, isGsm);

        synchronized (mCellInfo) {
            if (mSS.getRilDataRadioTechnology() == ServiceState.RIL_RADIO_TECHNOLOGY_LTE) {
                mCellInfoLte.setTimeStamp(SystemClock.elapsedRealtime() * 1000);
                mCellInfoLte.setTimeStampType(CellInfo.TIMESTAMP_TYPE_JAVA_RIL);
                mCellInfoLte.getCellSignalStrength()
                                .initialize(mSignalStrength,SignalStrength.INVALID);
            }
            if (mCellInfoLte.getCellIdentity() != null) {
                ArrayList<CellInfo> arrayCi = new ArrayList<CellInfo>();
                arrayCi.add(mCellInfoLte);
                mPhoneBase.notifyCellInfo(arrayCi);
            }
        }
        return ssChanged;
    }

    @Override
    public boolean isConcurrentVoiceAndDataAllowed() {
        // For LTE set true, else if the SVDO property is set, else use the CSS indicator to
        // check for concurrent voice and data capability
        if (mSS.getRilDataRadioTechnology() == ServiceState.RIL_RADIO_TECHNOLOGY_LTE) {
            return true;
        } else if (SystemProperties.getBoolean(TelephonyProperties.PROPERTY_SVDO, false)) {
            return true;
        } else {
            return mSS.getCssIndicator() == 1;
        }
    }

    /**
     * Check whether the specified SID and NID pair appears in the HOME SID/NID list
     * read from NV or SIM.
     *
     * @return true if provided sid/nid pair belongs to operator's home network.
     */
    private boolean isInHomeSidNid(int sid, int nid) {
        // if SID/NID is not available, assume this is home network.
        if (isSidsAllZeros()) return true;

        // length of SID/NID shold be same
        if (mHomeSystemId.length != mHomeNetworkId.length) return true;

        if (sid == 0) return true;

        for (int i = 0; i < mHomeSystemId.length; i++) {
            // Use SID only if NID is a reserved value.
            // SID 0 and NID 0 and 65535 are reserved. (C.0005 2.6.5.2)
            if ((mHomeSystemId[i] == sid) &&
                ((mHomeNetworkId[i] == 0) || (mHomeNetworkId[i] == 65535) ||
                 (nid == 0) || (nid == 65535) || (mHomeNetworkId[i] == nid))) {
                return true;
            }
        }
        // SID/NID are not in the list. So device is not in home network
        return false;
    }

    /**
     * TODO: Remove when we get new ril/modem for Galaxy Nexus.
     *
     * @return all available cell information, the returned List maybe empty but never null.
     */
    @Override
    public List<CellInfo> getAllCellInfo() {
        if (mCi.getRilVersion() >= 8) {
            return super.getAllCellInfo();
        } else {
            ArrayList<CellInfo> arrayList = new ArrayList<CellInfo>();
            CellInfo ci;
            synchronized(mCellInfo) {
                arrayList.add(mCellInfoLte);
            }
            if (DBG) log ("getAllCellInfo: arrayList=" + arrayList);
            return arrayList;
        }
    }

    @Override
    protected void log(String s) {
        Rlog.d(LOG_TAG, "[CdmaLteSST] " + s);
    }

    @Override
    protected void loge(String s) {
        Rlog.e(LOG_TAG, "[CdmaLteSST] " + s);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("CdmaLteServiceStateTracker extends:");
        super.dump(fd, pw, args);
        pw.println(" mCdmaLtePhone=" + mCdmaLtePhone);
    }
}
